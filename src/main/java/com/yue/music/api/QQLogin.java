package com.yue.music.api;

import com.yue.music.YueMusic;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 扫码登录（网页版 QQ互联 OAuth2 链路，7 步）。
 *
 * <ol>
 *   <li>{@code GET ssl.ptlogin2.qq.com/ptqrshow} → 二维码 PNG + {@code qrsig} Cookie</li>
 *   <li>{@code ptqrtoken = hash33(qrsig, 0)}</li>
 *   <li>轮询 {@code GET ssl.ptlogin2.qq.com/ptqrlogin} → {@code ptuiCB('66'|'67'|'65'|'0',...)}</li>
 *   <li>成功时返回的 url（check_sig?uin=..&ptsigx=..）→ GET 它拿 {@code p_skey}</li>
 *   <li>{@code POST graph.qq.com/oauth2.0/authorize}（带 {@code g_tk=hash33(p_skey,5381)}）→ 取 {@code code}</li>
 *   <li>用 code 换 {@code musicid}+{@code musickey}（见 {@link ScanLogin#exchangeCode}）</li>
 *   <li>落成 Cookie</li>
 * </ol>
 */
public class QQLogin extends ScanLogin {

	private static final String QR_SHOW =
			"https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4"
					+ "&t=%s&daid=383&pt_3rd_aid=100497308";

	private static final String QR_LOGIN =
			"https://ssl.ptlogin2.qq.com/ptqrlogin?u1=%s&ptqrtoken=%s&ptredirect=1&h=1&t=1&g=1"
					+ "&from_ui=1&ptlang=2052&action=0-0-%s&js_ver=20102616&js_type=1"
					+ "&login_sig=%s&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308";

	private static final String U1 = "https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump";
	private static final String APPID_OAUTH = "100497308";
	/** QQ互联 3rd appid（网页扫码登录用的那个）。 */
	private static final String AID_3RD = "716027609";
	private static final String DAID = "383";
	/** 授权成功后要跳回的地址（check_sig 必须带上它，QQ 才知道给哪个应用发会话）。 */
	private static final String S_URL = "https://graph.qq.com/oauth2.0/login_jump";

	private static final Pattern PTUI_CB = Pattern.compile("ptuiCB\\((.*?)\\)");
	private static final Pattern UIN_PAT = Pattern.compile("[?&]uin=([^&]+)");
	private static final Pattern SIGX_PAT = Pattern.compile("[?&]ptsigx=([^&]+)");
	private static final Pattern CODE_PAT = Pattern.compile("[?&]code=([^&]+)");

	private volatile String qrsig = "";
	/** 是否已经用掉这次扫码（防止同一二维码被反复换取登录态）。 */
	private volatile boolean alreadyExchanged;
	private volatile String loginSig = "";

	/** QQ 登录：tmeLoginType = 2。 */
	public QQLogin() {
		super(2);
	}

	@Override
	public Method method() {
		return Method.QQ;
	}

	@Override
	protected String loginName() {
		return "QQ";
	}

	// ------------------------------------------------------------ 步骤 1~3

	@Override
	public byte[] fetchQrCode() throws Exception {
		jar.clear();
		alreadyExchanged = false;
		state = State.WAITING;
		message = "请用手机QQ扫描二维码";
		String url = String.format(QR_SHOW, System.currentTimeMillis());
		Map<String, String> headers = Map.of("Referer", "https://xui.ptlogin2.qq.com/");

		Http.Step step = Http.getManual(url, headers, Map.of(), true);
		jar.putAll(step.cookies());
		qrsig = jar.getOrDefault("qrsig", "");
		loginSig = jar.getOrDefault("pt_login_sig", "");
		qrImage = step.binary();
		qrCreatedAt = System.currentTimeMillis();

		if (qrsig.isEmpty()) {
			state = State.FAILED;
			message = "没拿到 qrsig，二维码请求可能被拦了";
		}
		return qrImage;
	}

	@Override
	public boolean pollOnce() throws Exception {
		if (qrsig.isEmpty()) {
			state = State.FAILED;
			message = "没有 qrsig，请点刷新二维码";
			return true;
		}
		String url = String.format(QR_LOGIN, U1,
				hash33(qrsig, 0), System.currentTimeMillis(), loginSig);
		Http.Step step = Http.getManual(url, Map.of("Referer", "https://xui.ptlogin2.qq.com/"),
				jar, false);
		jar.putAll(step.cookies());

		String body = step.body() == null ? "" : step.body();
		Matcher m = PTUI_CB.matcher(body);
		if (!m.find()) {
			if (step.status() == 403) {
				return setState(State.EXPIRED, "二维码已失效（403），请刷新");
			}
			message = "无法解析登录响应";
			return false;
		}
		String[] args = splitArgs(m.group(1));
		if (args.length < 5) {
			message = "登录响应格式异常";
			return false;
		}
		int code;
		try {
			code = Integer.parseInt(args[0].trim());
		} catch (NumberFormatException e) {
			message = "登录响应码异常：" + args[0];
			return false;
		}
		String tip = args[4];
		return switch (code) {
			case 66 -> setState(State.WAITING, tip.isEmpty() ? "等待扫码" : tip);
			case 67 -> setState(State.SCANNED, tip.isEmpty() ? "已扫码，请在手机上确认" : tip);
			case 65 -> setState(State.EXPIRED, tip.isEmpty() ? "二维码已失效" : tip);
			case 0 -> {
				// 关键：扫码成功后**只能换一次**登录态，绝不能在后续轮询里重复调用
				// check_sig / authorize —— 同一二维码反复换取会被 QQ 判定为异常请求，
				// 直接导致 p_skey 被拒发（返回 p_skey_forbid + 空 p_skey）。
				// 所以这里一次性完成，并把 alreadyExchanged 置位，杜绝重复。
				if (alreadyExchanged) {
					yield false;
				}
				alreadyExchanged = true;
				state = State.SUCCESS;
				message = "已确认，正在换取登录态…";
				finishLogin(args[2]);
				yield true;
			}
			default -> setState(State.WAITING, "等待扫码（" + code + "）");
		};
	}

	// ------------------------------------------------------------ 步骤 4~7

	/**
	 * 步骤 4：把扫码拿到的 {@code ptsigx} 换成 p_skey 等会话 cookie。
	 *
	 * <p><b>关键点</b>：必须自己重新拼一个带全参数的 check_sig URL。
	 * 直接访问 ptuiCB 返回的那个 URL 是**不够的**——它只有
	 * {@code pttype/uin/service/nodirect/ptsigx} 五个参数，缺少 {@code s_url} 与 {@code aId}，
	 * QQ 会因为"不知道该为哪个应用发会话"而**拒绝下发 p_skey**（返回 {@code p_skey_forbid} + 空 p_skey），
	 * 后续 authorize 就必然被弹回登录页。
	 */
	private void finishLogin(String cbUrl) throws Exception {
		if (cbUrl == null || cbUrl.isBlank()) {
			state = State.FAILED;
			message = "登录成功但没拿到回调地址";
			return;
		}

		// 从回调 URL 里抠出 uin 与 ptsigx
		Matcher um = UIN_PAT.matcher(cbUrl);
		Matcher sm = SIGX_PAT.matcher(cbUrl);
		String uin = um.find() ? um.group(1) : jar.getOrDefault("uin", "");
		String ptsigx = sm.find() ? sm.group(1) : "";
		if (uin.isEmpty() || ptsigx.isEmpty()) {
			state = State.FAILED;
			message = "回调地址里缺少 uin/ptsigx，无法继续";
			YueMusic.LOGGER.warn("[{}] 回调解析失败：{}", YueMusic.MOD_NAME, mask(cbUrl));
			return;
		}

		// 自己拼完整的 check_sig（补上 s_url 和 aId）
		String checkSigUrl = "https://ssl.ptlogin2.graph.qq.com/check_sig"
				+ "?uin=" + uin
				+ "&pttype=1"
				+ "&service=ptqrlogin"
				+ "&nodirect=0"
				+ "&ptsigx=" + enc(ptsigx)
				+ "&s_url=" + enc(S_URL)
				+ "&f_url="
				+ "&aid=" + AID_3RD
				+ "&daid=" + DAID
				+ "&pt_3rd_aid=" + APPID_OAUTH
				+ "&ptlang=2052";

		YueMusic.LOGGER.info("[{}] 步骤4 自建 check_sig（带 s_url/aId）：{}",
				YueMusic.MOD_NAME, mask(checkSigUrl));

		Http.Step sig = Http.getManual(checkSigUrl,
				Map.of("Referer", "https://xui.ptlogin2.qq.com/"), jar, false);
		jar.putAll(sig.cookies());
		Map<String, String> sigCookies = sig.cookies();

		// 关键：check_sig 返回的是 302。真实浏览器会**继续沿着跳转链走**，
		// p_skey 很可能是在后续跳转里才下发的；之前只取第一次响应的 Set-Cookie，
		// 所以永远拿到空的 p_skey（服务端标记 p_skey_forbid）。
		String next = sig.location();
		int hop = 0;
		while (next != null && !next.isBlank() && hop < 5
				&& jar.getOrDefault("p_skey", "").isEmpty()) {
			hop++;
			Http.Step s = Http.getManual(next,
					Map.of("Referer", "https://graph.qq.com/"), jar, false);
			jar.putAll(s.cookies());
			sigCookies = s.cookies();
			YueMusic.LOGGER.info("[{}] 步骤4 跟随跳转#{}: {} -> status={} 新cookie={}",
					YueMusic.MOD_NAME, hop, mask(next), s.status(), s.cookies().keySet());
			next = s.location();
		}

		String pSkey = jar.getOrDefault("p_skey", "");
		String skey = jar.getOrDefault("skey", "");
		String gtK = String.valueOf(hash33(pSkey.isEmpty() ? skey : pSkey, 5381));

		// 决定性诊断：把第4步到底拿到了什么、g_tk 怎么算的、实际发出去的 Cookie 全打出来
		YueMusic.LOGGER.info("[{}] 步骤4 check_sig 结果: status={} location={}",
				YueMusic.MOD_NAME, sig.status(),
				sig.location() == null || sig.location().isBlank() ? "(无)" : mask(sig.location()));
		YueMusic.LOGGER.info("[{}] 步骤4 新收到的 cookie={}",
				YueMusic.MOD_NAME, sigCookies.keySet());
		YueMusic.LOGGER.info("[{}] p_skey长度={} skey长度={} uin={} g_tk={} ptsigx={}",
				YueMusic.MOD_NAME, pSkey.length(), skey.length(),
				uin.isEmpty() ? "(空)" : uin, gtK, ptsigx.isEmpty() ? "(无)" : "有");

		// 步骤 5：换 code。先 POST；如果 QQ 返回登录页（which=Login）说明它不认这次授权，
		// 再改用 GET + Referer 重试一次（两种方式都实测可行，命中率更高）。
		String codeUrl = "https://graph.qq.com/oauth2.0/authorize";
		String query = "response_type=code"
				+ "&client_id=" + APPID_OAUTH
				+ "&redirect_uri=" + enc("https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
				+ "&scope=" + enc("get_user_info,get_app_friends")
				+ "&state=state&switch=&from_ptlogin=1&src=1&update_auth=1"
				+ "&openapi=1010_1030&g_tk=" + gtK
				+ "&auth_time=" + System.currentTimeMillis()
				+ "&ui=" + java.util.UUID.randomUUID()
				+ (ptsigx.isEmpty() ? "" : "&ptsigx=" + enc(ptsigx));

		String outCookie = Http.cookieString(jar);
		YueMusic.LOGGER.info("[{}] authorize 发出 Cookie({} 字符)：{}",
				YueMusic.MOD_NAME, outCookie.length(),
				outCookie.length() > 300 ? outCookie.substring(0, 300) + "..." : outCookie);
		YueMusic.LOGGER.info("[{}] authorize 是否含 p_skey={} skey={} supertoken={} pt_oauth_token={}",
				YueMusic.MOD_NAME, jar.containsKey("p_skey"), jar.containsKey("skey"),
				jar.containsKey("supertoken"), jar.containsKey("pt_oauth_token"));

		String code = null;
		String lastLoc = "";
		String lastBody = "";
		String lastNote = "";
		int lastStatus = 0;

		// 尝试 1：POST（原做法）
		Http.Step auth = Http.postManual(codeUrl,
				Map.of("Referer", "https://graph.qq.com/", "Origin", "https://graph.qq.com"),
				jar, query);
		jar.putAll(auth.cookies());
		lastLoc = auth.location() == null ? "" : auth.location();
		lastBody = auth.body() == null ? "" : auth.body();
		lastStatus = auth.status();
		code = extractCode(lastLoc);
		if (code == null) code = extractCode(lastBody);

		// 尝试 2：GET（QQ 的 authorize 两种都支持；POST 被当成登录页时 GET 常常能过）
		if (code == null) {
			Http.Step get = Http.getManual(codeUrl + "?" + query,
					Map.of("Referer", "https://graph.qq.com/",
							"Origin", "https://graph.qq.com",
							"Accept", "text/html,application/xhtml+xml"),
					jar, false);
			jar.putAll(get.cookies());
			lastLoc = get.location() == null ? "" : get.location();
			lastBody = get.body() == null ? "" : get.body();
			lastStatus = get.status();
			code = extractCode(lastLoc);
			if (code == null) code = extractCode(lastBody);
		}

		if (code == null) {
			state = State.FAILED;
			lastNote = describeFailure(lastLoc, lastBody);
			// 把 p_skey 的真实情况说清楚，别再像以前那样误报"缺失"
			if (pSkey.isEmpty()) {
				lastNote = "QQ 没有下发 p_skey（服务端返回了 p_skey_forbid），"
						+ "所以拿不到授权码。这通常是 QQ 侧对该次登录的安全校验未通过：\n"
						+ "1) 重新点「QQ音乐登录」生成**新二维码**再扫（旧二维码的会话已作废，重复用会一直被拒）\n"
						+ "2) 扫码后请在手机上**点确认**，不要只扫码就退出\n"
						+ "3) 隔 10 分钟后再试，期间别再点登录（避免连续换取触发限制）\n"
						+ "4) 若一直如此，先改用「微信登录」";
			}
			message = "没换到 code：" + lastNote;
			// 把关键排查信息一次性打全
			YueMusic.LOGGER.warn("[{}] QQ 登录第5步失败 status={} p_skey长度={} location={}",
					YueMusic.MOD_NAME, lastStatus, pSkey.length(),
					lastLoc.isEmpty() ? "(空)" : lastLoc);
			YueMusic.LOGGER.warn("[{}] 响应体片段：{}", YueMusic.MOD_NAME, shortHtml(lastBody));
			YueMusic.LOGGER.warn("[{}] 当前 cookie：{}", YueMusic.MOD_NAME, jar.keySet());
			return;
		}

		if (musicId.isEmpty()) musicId = uin;
		// 步骤 6~7：换登录态并落 Cookie
		exchangeCode(code);
	}

	/** 从 URL / 响应体里抠出 code。 */
	private static String extractCode(String s) {
		if (s == null || s.isBlank()) return null;
		Matcher m = CODE_PAT.matcher(s);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * 把「为什么没拿到 code」翻译成人能看懂的话。
	 *
	 * <p>关键判断依据来自服务端返回的 HTML（浏览器会渲染成"授权失败"之类的页面），
	 * 所以这里同时看 Location 和响应体，尽量挖出真正原因。
	 */
	private static String describeFailure(String location, String body) {
		String l = location == null ? "" : location.toLowerCase();
		String b = body == null ? "" : body;

		// 1) 服务端明确说了"授权失败"或给了错误码
		if (b.contains("授权失败") || b.contains("授权已过期") || b.contains("authorize failed")) {
			return "QQ 显示「授权失败」。常见原因：该账号没在网页端授权过这个应用，"
					+ "或授权被安全策略拦截。请先在浏览器登录一次 y.qq.com 再回来扫码";
		}
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("(?:error|errcode|retcode)[\"']?\\s*[:=]\\s*[\"']?(\\d{3,6})", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(b);
		if (m.find()) {
			return "QQ 授权接口返回错误码 " + m.group(1) + "（详细信息见日志）";
		}

		// 2) 返回登录页
		if (l.contains("which=login")) {
			return "QQ 要求重新登录（授权会话没建立）。"
					+ "先确认：手机QQ扫的是本模组的二维码、且扫码后点了「确认登录」；"
					+ "如果反复如此，请在浏览器里登录一次 y.qq.com 验证账号状态，再回来重试";
		}

		// 3) 授权确认页
		if (l.contains("show?which=auth") || l.contains("authorize")) {
			return "QQ 弹出了授权确认页（需要再点一次登录重试）";
		}

		// 4) 兜底
		if (l.isBlank()) {
			return "QQ 没有返回跳转地址（网络异常或请求被拦截）";
		}
		return "QQ 返回的地址里没有 code（" + location.substring(0, Math.min(80, location.length())) + "）";
	}

	/** 截断 HTML，去掉多余空白便于打日志。 */
	private static String shortHtml(String s) {
		if (s == null || s.isBlank()) return "(空)";
		String t = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		return t.length() > 240 ? t.substring(0, 240) + "..." : t;
	}

	/** URL 太长时截断（保留前面关键信息）。 */
	private static String mask(String s) {
		if (s == null) return "(空)";
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}

	// ------------------------------------------------------------ 工具

	private static String[] splitArgs(String argsPart) {
		String[] raw = argsPart.split(",");
		String[] out = new String[raw.length];
		for (int i = 0; i < raw.length; i++) {
			String v = raw[i].trim();
			if (v.length() >= 2 && (v.startsWith("'") || v.startsWith("\""))) v = v.substring(1);
			if (v.length() >= 1 && (v.endsWith("'") || v.endsWith("\""))) {
				v = v.substring(0, v.length() - 1);
			}
			out[i] = v;
		}
		return out;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** 兼容旧调用（ptqrtoken 用种子 0）。 */
	public static int hash33(String s) {
		return hash33(s, 0);
	}

	/** 便于测试：当前 Cookie 的副本。 */
	Map<String, String> jarView() {
		return new HashMap<>(jar);
	}
}
