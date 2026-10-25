package com.yue.music.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.YueMusic;

import java.util.HashMap;
import java.util.Map;

/**
 * 网易云音乐登录。
 *
 * <h3>为什么是「Cookie 登录」而不是扫码</h3>
 * QQ / 微信的二维码是<b>平台直接返回一张图片</b>，搬过来就能显示。
 * 网易云不是：它只给一个 {@code unikey} 字符串，二维码是网页里的 JS 现画的。
 * 本模组不带二维码绘制器，硬写一个编码器风险很高（画错了玩家扫不出来还没法测），
 * 所以这里走<b>最稳的那条路</b>：让玩家把浏览器里的 {@code MUSIC_U} 粘进来。
 *
 * <h3>登录之后有什么用</h3>
 * <ul>
 *   <li>能拿到<b>更高音质</b>的直链（320kbps）</li>
 *   <li>部分「未登录返回 404」的歌，登录后能正常播放</li>
 *   <li>会员歌是否可放也取决于账号权益</li>
 * </ul>
 *
 * <h3>怎么拿到 MUSIC_U</h3>
 * <ol>
 *   <li>浏览器登录 {@code https://music.163.com/}</li>
 *   <li>按 {@code F12} → <b>应用 / Application</b> → Cookie → {@code https://music.163.com}</li>
 *   <li>找到 {@code MUSIC_U}，复制它的<b>值</b>（很长的一串）</li>
 *   <li>也可以复制整行 {@code Cookie: xxx=1; MUSIC_U=...; yyy=2} 直接粘进来</li>
 * </ol>
 */
public class NeteaseLogin extends ScanLogin {

	/** 查当前账号信息（用来验证 Cookie 是否有效 + 取昵称）。 */
	private static final String ACCOUNT_API = "https://music.163.com/api/nuser/account/get";

	/** 请求网易云时通用的 Referer（少了它接口会拒绝）。 */
	private static final String REFERER = "https://music.163.com/";

	public NeteaseLogin() {
		super(3);
	}

	@Override
	public Method method() {
		return Method.NETEASE;
	}

	@Override
	protected String loginName() {
		return "网易云";
	}

	/** 网易云的登录凭证。 */
	public String musicU() {
		return jar.getOrDefault("MUSIC_U", "");
	}

	/**
	 * 网易云没有「拿一张二维码图片」的接口，这里明确引导到 Cookie 登录。
	 *
	 * <p>返回 null 时设置页会显示 {@link #message()} 里的提示。
	 */
	@Override
	public byte[] fetchQrCode() {
		state = State.FAILED;
		message = "网易云请用 Cookie 登录：把 MUSIC_U 粘贴到下面的输入框";
		return null;
	}

	@Override
	public boolean pollOnce() {
		return false;
	}

	/**
	 * 只要 MUSIC_U 在就算登录了。
	 *
	 * <p>基类那个判断要求 {@code musicKey} 和 {@code musicId} 都非空，
	 * 但网易云这边 {@code userId} 要联网才查得到，离线启动时不该因此判成「未登录」。
	 */
	@Override
	public boolean loggedIn() {
		return !musicU().isEmpty();
	}

	@Override
	public void loadCookies(Map<String, String> saved, String nick) {
		jar.clear();
		if (saved != null) jar.putAll(saved);
		nickname = nick == null ? "" : nick;
		String key = musicU();
		if (key.isEmpty()) {
			state = State.IDLE;
			message = "未登录";
			return;
		}
		musicKey = key;
		musicId = jar.getOrDefault("__uid", "");
		state = State.SUCCESS;
		message = nickname.isEmpty() ? "已登录" : "已登录：" + nickname;
	}

	/**
	 * 导入 Cookie。
	 *
	 * <p>接受三种粘贴方式：
	 * <ol>
	 *   <li>整串 {@code Cookie: a=1; MUSIC_U=xxx; b=2}</li>
	 *   <li>只有 {@code MUSIC_U=xxx}</li>
	 *   <li><b>只粘值</b>（那串很长的字符）—— 自动当成 MUSIC_U</li>
	 * </ol>
	 *
	 * @return 成功返回 null，失败返回中文原因
	 */
	@Override
	public String importCookies(String raw) {
		if (raw == null || raw.isBlank()) return "Cookie 是空的";

		String text = raw.trim().replace("\r", "").replace("\n", " ");
		if (text.regionMatches(true, 0, "Cookie:", 0, 7)) {
			text = text.substring(7).trim();
		}

		Map<String, String> parsed = new HashMap<>();
		// 判断是「整串 Cookie」还是「只粘了值」：
		// MUSIC_U 的值本身可能含 '=' 填充，所以不能只看有没有 '='，
		// 而是看有没有分号、或者有没有出现 MUSIC_U 这个名字。
		boolean looksLikeCookieString = text.contains(";")
				|| text.toLowerCase().contains("music_u");
		if (!looksLikeCookieString) {
			// 只粘了值
			String v = text.trim();
			if (v.length() < 16) return "这串字符太短了，不像是 MUSIC_U";
			parsed.put("MUSIC_U", v);
		} else {
			for (String part : text.split(";")) {
				String p = part.trim();
				if (p.isEmpty()) continue;
				int eq = p.indexOf('=');
				if (eq <= 0) continue;
				String k = p.substring(0, eq).trim();
				String v = p.substring(eq + 1).trim();
				if (!k.isEmpty() && !v.isEmpty()) parsed.put(k, v);
			}
		}

		String key = parsed.get("MUSIC_U");
		if (key == null || key.isEmpty()) {
			return "这串 Cookie 里没有 MUSIC_U（网易云的登录凭证）。"
					+ "请确认是在**已登录的 music.163.com** 页面复制，"
					+ "并且复制的是 Cookie 里那一项 MUSIC_U";
		}

		jar.clear();
		jar.putAll(parsed);
		musicKey = key;

		// 联网验证一次：能拿到账号信息才算真登录（避免「粘错了也显示已登录」）
		String err = verify();
		if (err != null) {
			jar.clear();
			musicKey = "";
			musicId = "";
			state = State.FAILED;
			message = err;
			YueMusic.LOGGER.warn("[{}] 网易云 Cookie 验证失败：{}", YueMusic.MOD_NAME, err);
			return err;
		}

		state = State.SUCCESS;
		message = "已通过 Cookie 登录" + (nickname.isEmpty() ? "" : "：" + nickname);
		YueMusic.LOGGER.info("[{}] 网易云登录成功 userId={} 昵称={}",
				YueMusic.MOD_NAME, musicId, nickname);
		return null;
	}

	/**
	 * 用当前 Cookie 调一次账号接口，成功则填好 {@link #nickname} / {@link #musicId}。
	 *
	 * @return 成功返回 null，失败返回中文原因
	 */
	private String verify() {
		try {
			String body = Http.getString(ACCOUNT_API, Map.of(
					"Cookie", cookieHeader(),
					"Referer", REFERER,
					"Accept", "application/json"));
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			int code = root.has("code") && !root.get("code").isJsonNull()
					? root.get("code").getAsInt() : -1;
			if (code != 200) {
				String tip = code == 301 ? "Cookie 已失效（请重新登录一次 music.163.com 再复制）"
						: "Cookie 无效（接口返回 code=" + code + "）";
				return tip;
			}
			if (root.has("profile") && root.get("profile").isJsonObject()) {
				JsonObject p = root.getAsJsonObject("profile");
				if (p.has("userId") && !p.get("userId").isJsonNull()) {
					musicId = p.get("userId").getAsString();
					jar.put("__uid", musicId);
				}
				if (p.has("nickname") && !p.get("nickname").isJsonNull()) {
					nickname = p.get("nickname").getAsString();
				}
			}
			return null;
		} catch (Exception e) {
			return "验证 Cookie 时出错：" + (e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}
}
