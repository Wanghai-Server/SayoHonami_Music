package com.yue.music.api;

import com.yue.music.YueMusic;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 轻量 HTTP 工具。
 * <p>所有联网请求都从这里走：统一超时、统一 UA、统一 Cookie 采集，避免每个 Provider 各写一套。
 * <p>请求都在后台线程执行（UI 用虚拟线程调用），绝不阻塞游戏主线程。
 */
public final class Http {

	private Http() {
	}

	public static final String DEFAULT_UA =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/141.0.0.0 Safari/537.36";

	public static final Duration TIMEOUT = Duration.ofSeconds(12);

	/** 需要手动处理重定向时用这个（登录换 code 必须拿到 Location，不能让客户端自动跳）。 */
	private static final HttpClient MANUAL = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	private static final HttpClient AUTO = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	/** 带连接池的默认客户端（搜索、拉音频流都用它）。 */
	public static HttpClient client() {
		return AUTO;
	}

	public static HttpRequest.Builder request(String url, Map<String, String> headers) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.header("User-Agent", DEFAULT_UA)
				.header("Accept", "*/*")
				.header("Accept-Language", "zh-CN,zh;q=0.9");
		headers.forEach(b::header);
		return b;
	}

	// ------------------------------------------------------------ 基础请求

	public static String getString(String url, Map<String, String> headers)
			throws IOException, InterruptedException {
		return readBody(AUTO.send(request(url, headers).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream()));
	}

	/**
	 * QQ音乐 {@code musicu.fcg} 专用：必须用 GET + {@code data=<urlencoded JSON>}。
	 * <p>实测同一个请求体用 POST 会返回 {@code {"code":500001}}，用 GET 才正常。
	 */
	public static String getMusicuFcg(String baseUrl, Map<String, String> headers, String jsonBody)
			throws IOException, InterruptedException {
		return getString(baseUrl + "?data=" + java.net.URLEncoder.encode(jsonBody, StandardCharsets.UTF_8),
				headers);
	}

	private static String readBody(HttpResponse<InputStream> resp) throws IOException {
		try (InputStream raw = resp.body()) {
			InputStream in = raw;
			if (resp.headers().firstValue("Content-Encoding").orElse("").toLowerCase().contains("gzip")) {
				in = new GZIPInputStream(raw);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	public static com.google.gson.JsonElement getJson(String url, Map<String, String> headers)
			throws IOException, InterruptedException {
		return com.google.gson.JsonParser.parseString(getString(url, headers));
	}

	/**
	 * HEAD 请求：返回重定向后的最终地址。
	 * <p>网易云用它判断歌曲是否被版权限制（跳到 {@code /404} 就是放不了）。
	 */
	public static String resolveRedirect(String url, Map<String, String> headers) {
		try {
			HttpResponse<Void> resp = AUTO.send(
					request(url, headers).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.discarding());
			return resp.uri().toString();
		} catch (Exception e) {
			YueMusic.LOGGER.debug("[YueMusic] HEAD {} 失败: {}", url, e.toString());
			return null;
		}
	}

	// ------------------------------------------------------------ 手动重定向（登录用）

	/** 一次不自动跳转的请求结果。 */
	public record Step(int status, String body, byte[] binary, Map<String, String> cookies,
					   String location) {
		public String cookieHeader() {
			return cookieString(cookies);
		}
	}

	/** GET，不跟随重定向；用于登录链路逐步采集 Cookie。 */
	public static Step getManual(String url, Map<String, String> headers, Map<String, String> jar,
			boolean wantBinary) throws IOException, InterruptedException {
		return getManual(url, headers, jar, wantBinary, TIMEOUT);
	}

	/**
	 * 同上，但可以指定超时。
	 *
	 * <p>微信的扫码状态接口 {@code long.open.weixin.qq.com/connect/l/qrconnect}
	 * 是<b>长轮询</b>：没人扫码时它会一直挂着不返回。所以那边必须用短超时，
	 * 并把 {@link java.net.http.HttpTimeoutException} 当成「还在等待」处理，而不是报错。
	 */
	public static Step getManual(String url, Map<String, String> headers, Map<String, String> jar,
			boolean wantBinary, Duration timeout) throws IOException, InterruptedException {
		Map<String, String> h = new HashMap<>(headers);
		String cookie = cookieString(jar);
		if (!cookie.isEmpty()) h.put("Cookie", cookie);

		HttpRequest.Builder b = request(url, h).timeout(timeout).GET();
		if (wantBinary) {
			HttpResponse<byte[]> resp = MANUAL.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
			return new Step(resp.statusCode(), null, resp.body(), collectCookies(resp), location(resp));
		}
		HttpResponse<String> resp = MANUAL.send(b.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Step(resp.statusCode(), resp.body(), null, collectCookies(resp), location(resp));
	}

	/** POST，不跟随重定向。 */
	public static Step postManual(String url, Map<String, String> headers, Map<String, String> jar,
			String body) throws IOException, InterruptedException {
		Map<String, String> h = new HashMap<>(headers);
		String cookie = cookieString(jar);
		if (!cookie.isEmpty()) h.put("Cookie", cookie);
		h.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");

		HttpRequest req = request(url, h)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> resp = MANUAL.send(req,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Step(resp.statusCode(), resp.body(), null, collectCookies(resp), location(resp));
	}

	private static String location(HttpResponse<?> resp) {
		return resp.headers().firstValue("Location").orElse("");
	}

	public static String cookieString(Map<String, String> jar) {
		if (jar == null || jar.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		jar.forEach((k, v) -> {
			if (sb.length() > 0) sb.append("; ");
			sb.append(k).append('=').append(v);
		});
		return sb.toString();
	}

	/** 从响应头收集所有 Set-Cookie 的键值。 */
	public static Map<String, String> collectCookies(HttpResponse<?> resp) {
		Map<String, String> out = new HashMap<>();
		List<String> all = new ArrayList<>(resp.headers().allValues("Set-Cookie"));
		for (String raw : all) {
			String first = raw.split(";", 2)[0];
			int eq = first.indexOf('=');
			if (eq > 0) {
				out.put(first.substring(0, eq).trim(), first.substring(eq + 1).trim());
			}
		}
		return out;
	}
}
