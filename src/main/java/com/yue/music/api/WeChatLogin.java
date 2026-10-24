package com.yue.music.api;

import com.yue.music.YueMusic;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信扫码登录（微信开放平台「网站应用」扫码，QQ音乐 用的就是它）。
 *
 * <h2>链路（已实测各接口可用）</h2>
 * <ol>
 *   <li>{@code GET open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801...}
 *       → 返回一个 <b>HTML 页面</b>（不是图片！），里面含二维码 uuid 与图片地址</li>
 *   <li>从 HTML 提取 uuid，再 {@code GET open.weixin.qq.com/connect/qrcode/{uuid}} → 真正的二维码 <b>JPEG</b></li>
 *   <li>轮询 {@code GET long.open.weixin.qq.com/connect/l/qrconnect?uuid=..}
 *       → {@code window.wx_errcode=404|403|405|408;window.wx_code='..';}</li>
 *   <li>405（已确认）时 {@code wx_code} 就是授权码 → 用 {@link ScanLogin#exchangeCode} 换登录态</li>
 * </ol>
 *
 * <p>状态码含义：<b>404</b>=等待扫码，<b>403</b>=已扫码待确认，<b>405</b>=已确认（带 code），
 * <b>408</b>=二维码已失效。
 */
public class WeChatLogin extends ScanLogin {

	/** QQ音乐 在微信开放平台的网站应用 appid。 */
	private static final String APPID = "wx48db31d50e334801";

	private static final String REDIRECT_URI =
			"https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/";

	private static final String QR_PAGE =
			"https://open.weixin.qq.com/connect/qrconnect"
					+ "?appid=" + APPID
					+ "&redirect_uri=%s"
					+ "&response_type=code&scope=snsapi_login&state=%s&lang=zh_CN"
					+ "&href=%s";

	private static final String QR_IMAGE = "https://open.weixin.qq.com/connect/qrcode/%s";
	private static final String QR_POLL =
			"https://long.open.weixin.qq.com/connect/l/qrconnect?uuid=%s&_=%d";

	/** 从 HTML 里抠 uuid（形如 {@code /connect/qrcode/091y8WZR1lB91w3i}）。 */
	private static final Pattern UUID_PAT =
			Pattern.compile("/connect/qrcode/([A-Za-z0-9_-]{8,64})");
	/** 轮询响应：window.wx_errcode=405;window.wx_code='xxx'; */
	private static final Pattern ERRCODE_PAT = Pattern.compile("wx_errcode\\s*=\\s*(\\d+)");
	private static final Pattern WXCODE_PAT = Pattern.compile("wx_code\\s*=\\s*'([^']*)'");

	private volatile String uuid = "";
	/** 是否已经把 wx_code 换过登录态（微信长轮询会重复返回同一个 code）。 */
	private volatile boolean alreadyExchanged;

	/** 微信登录：tmeLoginType = 1。 */
	public WeChatLogin() {
		super(1);
	}

	@Override
	public Method method() {
		return Method.WECHAT;
	}

	@Override
	protected String loginName() {
		return "微信";
	}

	// ------------------------------------------------------------ 步骤 1~2

	@Override
	public byte[] fetchQrCode() throws Exception {
		jar.clear();
		uuid = "";
		alreadyExchanged = false;
		state = State.WAITING;
		message = "请用微信扫描二维码";
		qrCreatedAt = System.currentTimeMillis();
		Map<String, String> headers = Map.of("Referer", "https://y.qq.com/");

		// 第 1 步：拿承载二维码的 HTML 页面
		String pageUrl = String.format(QR_PAGE,
				java.net.URLEncoder.encode(REDIRECT_URI, java.nio.charset.StandardCharsets.UTF_8),
				UUID.randomUUID(),
				java.net.URLEncoder.encode("https://y.qq.com/mediastyle/pc/css/wx_login.css",
						java.nio.charset.StandardCharsets.UTF_8));
		Http.Step page = Http.getManual(pageUrl, headers, Map.of(), true);
		jar.putAll(page.cookies());
		String html = page.binary() == null ? "" : new String(page.binary(),
				java.nio.charset.StandardCharsets.UTF_8);

		// 第 2 步：从 HTML 里找 uuid
		Matcher m = UUID_PAT.matcher(html);
		if (!m.find()) {
			state = State.FAILED;
			message = "没从微信返回的页面里找到二维码 uuid（接口可能变了）";
			YueMusic.LOGGER.warn("[{}] 微信二维码页面里没找到 uuid，页面长度={}",
					YueMusic.MOD_NAME, html.length());
			return null;
		}
		uuid = m.group(1);

		// 第 3 步：拿真正的二维码图片
		Http.Step img = Http.getManual(String.format(QR_IMAGE, uuid), headers, jar, true);
		byte[] bytes = img.binary();
		if (bytes == null || bytes.length < 200) {
			state = State.FAILED;
			message = "微信二维码图片下载失败";
			return null;
		}
		qrImage = bytes;
		YueMusic.LOGGER.info("[{}] 微信二维码就绪 uuid={} 图片={} 字节",
				YueMusic.MOD_NAME, uuid, bytes.length);
		return qrImage;
	}

	// ------------------------------------------------------------ 轮询

	@Override
	public boolean pollOnce() throws Exception {
		if (uuid.isEmpty()) {
			state = State.FAILED;
			message = "没有 uuid，请刷新二维码";
			return true;
		}
		String url = String.format(QR_POLL, uuid, System.currentTimeMillis());
		Http.Step step;
		try {
			// 这个是长轮询接口：没人扫码时会一直挂着，所以用短超时
			step = Http.getManual(url, Map.of("Referer", "https://open.weixin.qq.com/"),
					jar, false, java.time.Duration.ofSeconds(6));
		} catch (java.net.http.HttpTimeoutException e) {
			// 超时 = 还没人扫码，属于正常情况，不是错误
			return setState(State.WAITING, "等待微信扫码");
		}
		jar.putAll(step.cookies());

		String body = step.body() == null ? "" : step.body();
		Matcher em = ERRCODE_PAT.matcher(body);
		if (!em.find()) {
			message = "无法解析微信登录响应";
			return false;
		}
		int errcode;
		try {
			errcode = Integer.parseInt(em.group(1));
		} catch (NumberFormatException e) {
			message = "微信响应码异常";
			return false;
		}

		return switch (errcode) {
			case 404 -> setState(State.WAITING, "等待微信扫码");
			case 403 -> setState(State.SCANNED, "已扫码，请在手机上确认");
			case 408 -> setState(State.EXPIRED, "二维码已失效，请刷新");
			case 405 -> {
				Matcher cm = WXCODE_PAT.matcher(body);
				String code = cm.find() ? cm.group(1) : "";
				if (code.isEmpty()) {
					state = State.FAILED;
					message = "微信已确认但没拿到授权码";
					yield true;
				}
				// 关键：微信的长轮询在确认后会**反复返回同一个 wx_code**。
				// 如果每次都拿去换登录态，会变成重复请求（容易被判异常，也可能让 code 失效）。
				// 所以这里只换一次。
				if (alreadyExchanged) {
					yield false;
				}
				alreadyExchanged = true;
				state = State.SUCCESS;
				message = "已确认，正在换取登录态…";
				exchangeCode(code);
				yield true;
			}
			default -> setState(State.WAITING, "等待微信扫码（" + errcode + "）");
		};
	}
}
