package com.yue.music.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.YueMusic;

import java.util.HashMap;
import java.util.Map;

/**
 * 扫码登录基类。
 *
 * <p>QQ 和微信两条链路的<b>前后两段是一样的</b>：
 * <ol>
 *   <li>拿二维码（图片字节 + 会话标识）</li>
 *   <li>轮询扫码状态</li>
 *   <li>成功后拿到一个 <b>code</b></li>
 *   <li>用 code 调 QQ音乐 的 {@code QQConnectLogin.LoginServer/QQLogin} 换取
 *       {@code musicid} + {@code musickey}</li>
 *   <li>落成 Cookie：{@code uin} + {@code qm_keyst} + {@code qqmusic_key} + {@code tmeLoginType}</li>
 * </ol>
 * 只有第 1、2、3 步的平台细节不同（QQ 互联 vs 微信开放平台），所以放在子类里。
 */
public abstract class ScanLogin {

	public enum State {IDLE, WAITING, SCANNED, EXPIRED, SUCCESS, FAILED}

	/** 登录方式。 */
	public enum Method {
		QQ("QQ", "QQ音乐登录"),
		WECHAT("微信", "微信登录"),
		NETEASE("网易云", "网易云登录");

		private final String label;
		private final String buttonLabel;

		Method(String label, String buttonLabel) {
			this.label = label;
			this.buttonLabel = buttonLabel;
		}

		public String label() {
			return label;
		}

		public String buttonLabel() {
			return buttonLabel;
		}
	}

	// ---- 共享状态 ----
	protected final Map<String, String> jar = new HashMap<>();
	protected volatile State state = State.IDLE;
	protected volatile String message = "未开始";
	protected volatile byte[] qrImage;
	protected volatile String nickname = "";
	protected volatile String musicId = "";
	protected volatile String musicKey = "";
	protected volatile int loginType;
	protected volatile long qrCreatedAt;

	protected ScanLogin(int loginType) {
		this.loginType = loginType;
	}

	// ---------------------------------------------------------------- 对外查询

	public abstract Method method();

	public State state() {
		return state;
	}

	public String message() {
		return message;
	}

	public byte[] qrImage() {
		return qrImage;
	}

	public String nickname() {
		return nickname;
	}

	public boolean loggedIn() {
		return !musicKey.isEmpty() && !musicId.isEmpty();
	}

	public String uin() {
		return musicId.isEmpty() ? "0" : musicId;
	}

	public String cookieHeader() {
		return Http.cookieString(jar);
	}

	public Map<String, String> cookieMap() {
		return Map.copyOf(jar);
	}

	/** 从本地配置恢复登录态。 */
	public void loadCookies(Map<String, String> saved, String nick) {
		jar.clear();
		jar.putAll(saved);
		nickname = nick == null ? "" : nick;
		musicId = jar.getOrDefault("uin", "");
		if (musicId.isEmpty()) musicId = jar.getOrDefault("wxuin", "");
		musicKey = jar.getOrDefault("qm_keyst", jar.getOrDefault("qqmusic_key", ""));
		if (!musicKey.isEmpty()) {
			state = State.SUCCESS;
			message = nickname.isEmpty() ? "已登录" : "已登录：" + nickname;
			try {
				loginType = Integer.parseInt(jar.getOrDefault("tmeLoginType", String.valueOf(loginType)));
			} catch (NumberFormatException ignored) {
			}
		}
	}

	// ---------------------------------------------------------------- 手动导入 Cookie

	/**
	 * 手动导入 Cookie（绕开扫码流程的备用登录方式）。
	 *
	 * <p><b>为什么需要它</b>：QQ 有时会在 {@code check_sig} 阶段拒发 {@code p_skey}
	 * （返回 {@code p_skey_forbid}），导致扫码流程永远拿不到授权码——这属于 QQ 侧的安全策略，
	 * 客户端无法绕过。此时可以在浏览器里登录一次 y.qq.com，把 Cookie 复制进来：
	 * 浏览器拿到的 Cookie 里**直接包含 {@code qm_keyst}**，那就是真正的登录凭证，
	 * 完全不需要 p_skey。
	 *
	 * <p>支持直接粘贴浏览器 DevTools 里那串 {@code a=1; b=2; c=3}。
	 *
	 * @return 成功返回 null，失败返回中文原因
	 */
	public String importCookies(String raw) {
		if (raw == null || raw.isBlank()) return "Cookie 是空的";
		String text = raw.trim().replace("\r", "").replace("\n", " ");
		if (text.startsWith("Cookie:")) text = text.substring("Cookie:".length());

		Map<String, String> parsed = new java.util.HashMap<>();
		for (String part : text.split(";")) {
			String p = part.trim();
			if (p.isEmpty()) continue;
			int eq = p.indexOf('=');
			if (eq <= 0) continue;
			String k = p.substring(0, eq).trim();
			String v = p.substring(eq + 1).trim();
			if (!k.isEmpty() && !v.isEmpty()) parsed.put(k, v);
		}
		if (parsed.isEmpty()) return "没解析出任何 Cookie，请确认复制的是「请求标头里的 Cookie」那一整串";

		String key = parsed.getOrDefault("qm_keyst", parsed.getOrDefault("qqmusic_key", ""));
		if (key.isEmpty()) {
			return "这串 Cookie 里没有 qm_keyst（登录凭证）。"
					+ "请确认是在**已登录的 y.qq.com 页面**复制，并且复制的是 Domain 含 y.qq.com 的请求";
		}

		jar.clear();
		jar.putAll(parsed);
		musicId = parsed.getOrDefault("uin", parsed.getOrDefault("wxuin", ""));
		musicKey = key;
		if (!parsed.containsKey("tmeLoginType")) jar.put("tmeLoginType", String.valueOf(loginType));
		try {
			loginType = Integer.parseInt(jar.getOrDefault("tmeLoginType", String.valueOf(loginType)));
		} catch (NumberFormatException ignored) {
		}
		// 试着取一下昵称，失败也不影响登录
		fetchNickname();

		state = State.SUCCESS;
		message = "已通过 Cookie 登录" + (nickname.isEmpty() ? "" : "：" + nickname);
		YueMusic.LOGGER.info("[{}] 手动导入 Cookie 成功 uin={} cookies={}",
				YueMusic.MOD_NAME, musicId, jar.keySet());
		return null;
	}

	// ---------------------------------------------------------------- 子类实现

	/** 拉取二维码（图片字节），并把会话标识存进 {@link #jar} / 子类字段。 */
	public abstract byte[] fetchQrCode() throws Exception;

	/** 轮询一次；返回 true 表示状态有变化（UI 需要重绘）。 */
	public abstract boolean pollOnce() throws Exception;

	protected abstract String loginName();

	// ---------------------------------------------------------------- 共享：换 code → 换登录态

	/**
	 * 用 code 调 QQ音乐 的登录接口换 {@code musicid} + {@code musickey}。
	 *
	 * @param code 平台返回的授权码
	 */
	protected final boolean exchangeCode(String code) throws Exception {
		JsonObject param = new JsonObject();
		param.addProperty("code", code);
		JsonObject req = new JsonObject();
		req.addProperty("module", "QQConnectLogin.LoginServer");
		req.addProperty("method", "QQLogin");
		req.add("param", param);

		JsonObject comm = new JsonObject();
		comm.addProperty("ct", 24);
		comm.addProperty("cv", 0);
		comm.addProperty("v", "1003006");
		comm.addProperty("platform", "yqq");
		comm.addProperty("format", "json");

		JsonObject root = new JsonObject();
		root.add("comm", comm);
		root.add("req", req);

		String respBody = Http.getMusicuFcg("https://u.y.qq.com/cgi-bin/musicu.fcg",
				Map.of("Referer", "https://y.qq.com/", "Origin", "https://y.qq.com",
						"Cookie", Http.cookieString(jar)),
				root.toString());

		JsonObject resp = JsonParser.parseString(respBody).getAsJsonObject();
		JsonObject reqObj = resp.has("req") ? resp.getAsJsonObject("req") : null;
		JsonObject data = reqObj != null && reqObj.has("data")
				? reqObj.getAsJsonObject("data") : null;
		if (data == null) {
			state = State.FAILED;
			message = "登录接口返回异常（" + shortJson(respBody) + "）";
			YueMusic.LOGGER.warn("[{}] {}登录换取登录态失败，原始响应：{}",
					YueMusic.MOD_NAME, loginName(), shortJson(respBody));
			return false;
		}
		musicId = data.has("musicid") && !data.get("musicid").isJsonNull()
				? data.get("musicid").getAsString() : musicId;
		musicKey = data.has("musickey") && !data.get("musickey").isJsonNull()
				? data.get("musickey").getAsString() : "";
		if (data.has("loginType") && !data.get("loginType").isJsonNull()) {
			loginType = data.get("loginType").getAsInt();
		}
		if (data.has("nick") && !data.get("nick").isJsonNull()) {
			nickname = data.get("nick").getAsString();
		}
		if (musicKey.isEmpty()) {
			state = State.FAILED;
			// 把真正的错误码/原因挖出来，别只丢一句「换取登录态失败」
			String tip = pickStr(data, "errMsg", "errtip", "errTip2", "tip3", "msg");
			int errCode = pickInt(resp, reqObj, data);
			String reason = explain(errCode, tip);
			message = "换取登录态失败：" + reason;
			YueMusic.LOGGER.warn("[{}] {}登录换取登录态失败 errCode={} tip={} 响应={}",
					YueMusic.MOD_NAME, loginName(), errCode, tip, shortJson(respBody));
			return false;
		}

		// 落成 Cookie
		jar.put("uin", musicId);
		jar.put("qm_keyst", musicKey);
		jar.put("qqmusic_key", musicKey);
		jar.put("tmeLoginType", String.valueOf(loginType));
		jar.putIfAbsent("qqmusic_uin", musicId);
		if (loginType == 1) {
			// 微信登录时服务端更认 wxuin
			jar.put("wxuin", musicId);
		}

		if (nickname.isEmpty()) fetchNickname();

		state = State.SUCCESS;
		message = nickname.isEmpty() ? "登录成功" : "登录成功：" + nickname;
		YueMusic.LOGGER.info("[{}] {}登录成功 musicid={} 昵称={}",
				YueMusic.MOD_NAME, loginName(), musicId, nickname);
		return true;
	}

	protected final void fetchNickname() {
		try {
			String body = Http.getString(
					"https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=" + uin()
							+ "&format=json&inCharset=utf8&outCharset=utf-8",
					Map.of("Referer", "https://y.qq.com/", "Cookie", cookieHeader()));
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			if (o.has("data") && o.getAsJsonObject("data").has("creator")) {
				JsonObject c = o.getAsJsonObject("data").getAsJsonObject("creator");
				if (c.has("nick")) nickname = c.get("nick").getAsString();
			}
		} catch (Exception e) {
			YueMusic.LOGGER.debug("[{}] 取昵称失败: {}", YueMusic.MOD_NAME, e.toString());
		}
	}

	/** 状态有变化时返回 true。 */
	protected final boolean setState(State s, String msg) {
		boolean changed = state != s || !msg.equals(message);
		state = s;
		message = msg;
		return changed;
	}

	// ---------------------------------------------------------------- 错误诊断

	private static String pickStr(JsonObject o, String... keys) {
		if (o == null) return "";
		for (String k : keys) {
			if (o.has(k) && !o.get(k).isJsonNull()) {
				String v = o.get(k).getAsString();
				if (v != null && !v.isBlank()) return v;
			}
		}
		return "";
	}

	/** 依次从 data / req / 顶层里找错误码。 */
	private static int pickInt(JsonObject resp, JsonObject reqObj, JsonObject data) {
		for (JsonObject o : new JsonObject[]{data, reqObj, resp}) {
			if (o == null) continue;
			for (String k : new String[]{"code", "retcode", "subcode", "subCode"}) {
				if (o.has(k) && !o.get(k).isJsonNull()) {
					try {
						return o.get(k).getAsInt();
					} catch (Exception ignored) {
					}
				}
			}
		}
		return 0;
	}

	/**
	 * 把错误码翻译成人能看懂的话。
	 * <p>这些码来自 QQ音乐/QQ互联 的常见返回，命中不了就原样显示，并附上排查建议。
	 */
	private static String explain(int code, String tip) {
		String base = switch (code) {
			case 0 -> tip.isEmpty() ? "服务端没返回 musickey（可能是授权码已失效，请重新扫码）" : tip;
			case 1000 -> "授权码无效或已过期（同一二维码只能扫一次，请重新点登录再扫）";
			case 1001 -> "授权码已被使用过（请重新扫码）";
			case 20279 -> "登录设备数超限（QQ音乐最多同时登录若干设备，去手机App里删掉旧设备再试）";
			case 20450 -> "账号被限制登录（需要去 QQ音乐 官方渠道申诉解封）";
			case 104604 -> "操作过于频繁，触发了风控（等 10~30 分钟再试）";
			default -> tip.isEmpty() ? ("未知错误（code=" + code + "）") : tip;
		};
		// 风控类补充建议
		boolean risk = code == 104604 || code == 1000 || code == 1001
				|| base.contains("频繁") || base.contains("风控");
		if (risk) {
			base += "。建议：等 10~30 分钟别扫码，或换手机网络（流量）再试";
		}
		return base + (code != 0 ? "（code=" + code + "）" : "");
	}

	/** 截断 JSON 便于打日志。 */
	private static String shortJson(String s) {
		if (s == null) return "";
		String t = s.replaceAll("\\s+", " ");
		return t.length() > 200 ? t.substring(0, 200) + "..." : t;
	}

	/**
	 * QQ / 微信登录通用的 hash33：{@code h = (h << 5) + h + c}。
	 * <p>种子 0 用于 ptqrtoken，5381 用于 g_tk。Java 的 int 溢出行为正好符合要求。
	 */
	public static int hash33(String s, int seed) {
		int h = seed;
		if (s == null) return h & 0x7FFFFFFF;
		for (int i = 0; i < s.length(); i++) {
			h += (h << 5) + s.charAt(i);
		}
		return h & 0x7FFFFFFF;
	}
}
