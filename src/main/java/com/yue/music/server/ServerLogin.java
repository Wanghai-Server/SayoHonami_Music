package com.yue.music.server;

import com.yue.music.YueMusic;
import com.yue.music.api.MusicApi;
import com.yue.music.api.ScanLogin;
import com.yue.music.config.MusicConfig;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端登录态。
 *
 * <p><b>为什么需要它</b>：多人服务器上让每个玩家各自登录不现实。
 * 只要服主登录一次，服务端就用自己的登录态解析出播放直链，
 * 再把直链随队列广播给所有人——客户端只需要下载和播放，<b>完全不需要登录</b>。
 *
 * <p>凭证存在服务端自己的 {@code config/sayo-honami-music.json} 里，不会下发给任何客户端。
 */
public final class ServerLogin {

	private static final ServerLogin INSTANCE = new ServerLogin();

	public static ServerLogin get() {
		return INSTANCE;
	}

	private volatile boolean loggedIn;
	private volatile String nick = "";
	private volatile String uin = "";
	private volatile String cookie = "";
	private volatile String lastError = "";

	private ServerLogin() {
	}

	/**
	 * 服务端启动时从配置里恢复登录态。
	 *
	 * <p>QQ 和网易云<b>各存一份</b>，两个平台可以同时登录、同时生效：
	 * QQ 的歌用 QQ 账号解析，网易云的歌用网易云账号解析。
	 */
	public void load() {
		MusicConfig cfg = MusicConfig.get();
		Map<String, String> qq = cfg.qqCookies;
		Map<String, String> ne = cfg.neteaseCookies;

		boolean hasQq = qq != null && !qq.isEmpty()
				&& !qq.getOrDefault("qm_keyst", qq.getOrDefault("qqmusic_key", "")).isEmpty();
		boolean hasNe = ne != null && !ne.isEmpty()
				&& !ne.getOrDefault("MUSIC_U", "").isEmpty();

		if (hasQq) {
			apply(qq, cfg.qqNickname);
		}
		if (hasNe) {
			// 网易云的账号信息由 NeteaseLogin 自己维护（MusicApi 里已经加载好了），
			// 这里只需要保证「服务端算已登录」这个标记是对的，否则不会去解析直链。
			if (!hasQq) {
				loggedIn = true;
				nick = cfg.neteaseNickname == null ? "" : cfg.neteaseNickname;
				uin = "netease";
				cookie = "";
				lastError = "";
			}
			YueMusic.LOGGER.info("[{}] 服务端已恢复网易云登录（昵称={}）",
					YueMusic.MOD_NAME,
					cfg.neteaseNickname == null || cfg.neteaseNickname.isEmpty()
							? "(未知)" : cfg.neteaseNickname);
		}
		if (hasQq && hasNe) {
			YueMusic.LOGGER.info("[{}] 服务端两个平台都已登录（QQ + 网易云）", YueMusic.MOD_NAME);
		}
		if (!hasQq && !hasNe) {
			loggedIn = false;
			YueMusic.LOGGER.info("[{}] 服务端未登录（配置里没有 qqCookies / neteaseCookies）。"
					+ "如需让所有玩家共享账号，请用 /symus login <cookie>", YueMusic.MOD_NAME);
		}
	}

	private void apply(Map<String, String> cookies, String nickname) {
		String key = cookies.getOrDefault("qm_keyst", cookies.getOrDefault("qqmusic_key", ""));
		if (key.isEmpty()) {
			loggedIn = false;
			YueMusic.LOGGER.warn("[{}] 服务端配置里的 Cookie 缺少 qm_keyst，视为未登录", YueMusic.MOD_NAME);
			return;
		}
		StringBuilder sb = new StringBuilder();
		cookies.forEach((k, v) -> {
			if (sb.length() > 0) sb.append("; ");
			sb.append(k).append('=').append(v);
		});
		this.cookie = sb.toString();
		this.uin = cookies.getOrDefault("uin", cookies.getOrDefault("wxuin", "0"));
		this.nick = nickname == null ? "" : nickname;
		this.loggedIn = true;
		this.lastError = "";
		YueMusic.LOGGER.info("[{}] 服务端已登录 uin={} 昵称={}（{} 个 cookie），"
						+ "所有玩家将共享此账号解析播放地址",
				YueMusic.MOD_NAME, uin, nick.isEmpty() ? "(未知)" : nick, cookies.size());
	}

	/**
	 * 服务端能不能解析直链 —— <b>任一平台登录即可</b>。
	 *
	 * <p>不再只看内部的 {@code loggedIn} 标记：网易云有单独的登录对象
	 * （{@code NeteaseLogin}），两个平台要能各自独立生效。
	 * 只看标记的话，「只登录了网易云」会被误判成未登录，服务端就不解析了。
	 */
	public boolean loggedIn() {
		if (loggedIn) return true;
		return MusicApi.neteaseLoggedIn() || MusicApi.effectiveQqLogin() != null;
	}

	public String nick() {
		return nick;
	}

	public String uin() {
		return uin;
	}

	public String lastError() {
		return lastError;
	}

	/**
	 * 用粘贴的 Cookie 字符串登录（浏览器 DevTools 里复制的那串）。
	 *
	 * @return 成功返回 null，失败返回中文原因
	 */
	public String loginWithCookie(String raw) {
		if (raw == null || raw.isBlank()) return "Cookie 是空的";
		String text = raw.trim().replace("\r", " ").replace("\n", " ");
		if (text.startsWith("Cookie:")) text = text.substring("Cookie:".length());

		Map<String, String> parsed = new HashMap<>();
		for (String part : text.split(";")) {
			String p = part.trim();
			if (p.isEmpty()) continue;
			int eq = p.indexOf('=');
			if (eq <= 0) continue;
			String k = p.substring(0, eq).trim();
			String v = p.substring(eq + 1).trim();
			if (!k.isEmpty() && !v.isEmpty()) parsed.put(k, v);
		}
		if (parsed.isEmpty()) return "没解析出任何 Cookie";

		// 先看是不是网易云的 MUSIC_U（两种平台的凭证完全不一样，不能混）
		if (!parsed.containsKey("qm_keyst") && parsed.containsKey("MUSIC_U")) {
			return loginWithNetease(raw, parsed);
		}

		String key = parsed.getOrDefault("qm_keyst", parsed.getOrDefault("qqmusic_key", ""));
		if (key.isEmpty()) {
			return "这串 Cookie 里没有 qm_keyst（登录凭证）。"
					+ "请在**已登录的 y.qq.com 页面**复制「请求标头 → Cookie」那一整行";
		}

		// 先用这个账号试解析一首歌，验证凭证真的能用
		String verifyError = verify(parsed);
		if (verifyError != null) return verifyError;

		apply(parsed, "");
		MusicConfig cfg = MusicConfig.get();
		cfg.qqCookies = new HashMap<>(parsed);
		cfg.qqNickname = nick;
		cfg.lastLoginMethod = "qq";
		cfg.save();
		// 让 Provider 立刻用上新 Cookie
		MusicApi.reloadLogin();
		fetchNick();
		return null;
	}

	/** 退出登录并清掉配置里的凭证。 */
	public void logout() {
		loggedIn = false;
		nick = "";
		uin = "";
		cookie = "";
		MusicConfig cfg = MusicConfig.get();
		cfg.qqCookies = new HashMap<>();
		cfg.qqNickname = "";
		cfg.neteaseCookies = new HashMap<>();
		cfg.neteaseNickname = "";
		cfg.save();
		MusicApi.reloadLogin();
		YueMusic.LOGGER.info("[{}] 服务端已退出登录", YueMusic.MOD_NAME);
	}

	/**
	 * 用网易云的 {@code MUSIC_U} Cookie 登录。
	 *
	 * <p>和 QQ 那条路径分开：两家的凭证格式完全不同，
	 * 校验方式也不一样（网易云查账号信息即可，不用去试解析歌曲）。
	 *
	 * @return 成功返回 null，失败返回中文原因
	 */
	private String loginWithNetease(String raw, Map<String, String> parsed) {
		ScanLogin ne = MusicApi.neteaseLogin();
		if (ne == null) return "网易云登录未初始化";

		MusicConfig cfg = MusicConfig.get();
		Map<String, String> backup = new HashMap<>(cfg.neteaseCookies == null ? Map.of() : cfg.neteaseCookies);

		String err = ne.importCookies(raw);
		if (err != null) {
			// 失败 → 恢复原状，别留下坏凭证
			ne.loadCookies(backup, cfg.neteaseNickname);
			return err;
		}

		MusicApi.persistLogin(ne);
		MusicApi.reloadLogin();

		this.cookie = ne.cookieHeader();
		this.nick = ne.nickname();
		this.uin = parsed.getOrDefault("__uid", "netease");
		this.loggedIn = true;
		this.lastError = "";
		YueMusic.LOGGER.info("[{}] 服务端已登录网易云 昵称={}",
				YueMusic.MOD_NAME, nick.isEmpty() ? "(未知)" : nick);
		return null;
	}

	/** 验证一组 Cookie 是否真能解析出播放地址（拿一首免费歌试探）。 */
	private String verify(Map<String, String> cookies) {
		MusicConfig cfg = MusicConfig.get();
		Map<String, String> backup = new HashMap<>(cfg.qqCookies);
		try {
			cfg.qqCookies = new HashMap<>(cookies);
			MusicApi.reloadLogin();
			var list = MusicApi.search(com.yue.music.model.MusicSource.QQ, "小苹果", 5);
			if (list.tracks().isEmpty()) {
				return "用这组 Cookie 搜索失败（" + list.error() + "），请确认 Cookie 有效";
			}
			for (MusicTrack t : list.tracks()) {
				if (t.vip()) continue;
				PlayUrl url = MusicApi.resolve(t, false);
				if (url.ok()) return null; // 能用
			}
			return "Cookie 能搜索但拿不到播放地址，可能已过期";
		} catch (Exception e) {
			return "验证失败：" + e.getMessage();
		} finally {
			if (!loggedIn) {
				// 验证失败 → 把配置和 Provider 恢复成调用前的样子，避免留下坏凭证
				cfg.qqCookies = backup;
				MusicApi.reloadLogin();
			}
		}
	}

	private void fetchNick() {
		try {
			String body = com.yue.music.api.Http.getString(
					"https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=" + uin
							+ "&format=json&inCharset=utf8&outCharset=utf-8",
					Map.of("Referer", "https://y.qq.com/", "Cookie", cookie));
			var o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
			if (o.has("data") && o.getAsJsonObject("data").has("creator")) {
				var c = o.getAsJsonObject("data").getAsJsonObject("creator");
				if (c.has("nick")) {
					nick = c.get("nick").getAsString();
					MusicConfig cfg = MusicConfig.get();
					cfg.qqNickname = nick;
					cfg.save();
				}
			}
		} catch (Exception e) {
			YueMusic.LOGGER.debug("[{}] 取昵称失败: {}", YueMusic.MOD_NAME, e.toString());
		}
	}

	/**
	 * 用服务端登录态解析播放地址（会走跨平台兜底）。
	 * <p><b>会联网</b>，必须在后台线程调用，绝不能在主线程/服务端 tick 里直接调。
	 */
	public PlayUrl resolve(MusicTrack track) {
		if (!loggedIn) return PlayUrl.fail("服务端未登录");
		return MusicApi.resolve(track, true);
	}
}
