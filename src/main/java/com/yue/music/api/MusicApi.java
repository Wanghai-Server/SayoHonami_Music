package com.yue.music.api;

import com.yue.music.config.MusicConfig;
import com.yue.music.model.MusicSource;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 点歌门面：UI 只跟它打交道，不关心具体是哪个平台。
 *
 * <p>「跨平台自动降级」也在这里实现：在 QQ 点《晴天》拿不到地址时，
 * 自动去网易云搜同名歌曲并返回可播放的那个。
 */
public final class MusicApi {

	private static final Map<MusicSource, MusicProvider> PROVIDERS = new EnumMap<>(MusicSource.class);
	/** 两种扫码登录方式（QQ / 微信），都指向 QQ音乐 的账号体系。 */
	private static final Map<ScanLogin.Method, ScanLogin> LOGINS = new EnumMap<>(ScanLogin.Method.class);
	private static volatile ScanLogin.Method activeLogin = ScanLogin.Method.QQ;

	private MusicApi() {
	}

	/** 注册所有平台 + 初始化三种登录方式。服务端和客户端初始化时都会调用（幂等）。 */
	public static synchronized void init() {
		if (!PROVIDERS.isEmpty()) return;
		MusicConfig cfg = MusicConfig.get();

		QQLogin qq = new QQLogin();
		if (!cfg.qqCookies.isEmpty()) qq.loadCookies(cfg.qqCookies, cfg.qqNickname);
		LOGINS.put(ScanLogin.Method.QQ, qq);

		WeChatLogin wechat = new WeChatLogin();
		if (!cfg.wechatCookies.isEmpty()) wechat.loadCookies(cfg.wechatCookies, cfg.wechatNickname);
		LOGINS.put(ScanLogin.Method.WECHAT, wechat);

		NeteaseLogin netease = new NeteaseLogin();
		if (!cfg.neteaseCookies.isEmpty()) netease.loadCookies(cfg.neteaseCookies, cfg.neteaseNickname);
		LOGINS.put(ScanLogin.Method.NETEASE, netease);

		activeLogin = parseMethod(cfg.lastLoginMethod, ScanLogin.Method.QQ);

		register(new QQProvider(qq, wechat));
		register(new NeteaseProvider(netease));
	}

	/** 把配置里的字符串还原成登录方式（认不出就用默认值）。 */
	private static ScanLogin.Method parseMethod(String name, ScanLogin.Method fallback) {
		if (name != null) {
			for (ScanLogin.Method m : ScanLogin.Method.values()) {
				if (m.name().equalsIgnoreCase(name)) return m;
			}
		}
		return fallback;
	}

	/** 扩展点：实现 {@link MusicProvider} 后在这里注册即可增加平台。 */
	public static synchronized void register(MusicProvider provider) {
		MusicSource src = MusicSource.byId(provider.id());
		if (src == null) {
			throw new IllegalArgumentException("未知平台 id: " + provider.id());
		}
		PROVIDERS.put(src, provider);
	}

	// ---------------------------------------------------------------- 登录

	/** QQ 登录（兼容旧调用）。 */
	public static ScanLogin qqLogin() {
		return LOGINS.get(ScanLogin.Method.QQ);
	}

	public static ScanLogin weChatLogin() {
		return LOGINS.get(ScanLogin.Method.WECHAT);
	}

	public static ScanLogin login(ScanLogin.Method method) {
		return LOGINS.get(method);
	}

	/** 当前选中的登录方式（界面上那个「登录方式」按钮）。 */
	public static ScanLogin.Method activeLoginMethod() {
		return activeLogin;
	}

	/** 切换登录方式；如果另一种已经登录，会自动优先用已登录的那个。 */
	public static void setActiveLoginMethod(ScanLogin.Method method) {
		activeLogin = method;
		MusicConfig cfg = MusicConfig.get();
		cfg.lastLoginMethod = method.name().toLowerCase();
		cfg.save();
	}

	public static ScanLogin activeLogin() {
		ScanLogin l = LOGINS.get(activeLogin);
		return l != null ? l : qqLogin();
	}

	/** 是否任一方式已登录（三种方式都算）。 */
	public static boolean anyLoggedIn() {
		ScanLogin a = activeLogin();
		if (a != null && a.loggedIn()) return true;
		return anyLoggedInMethod() != null;
	}

	/** 任意一种登录方式已经登录（用于自动挑选）。 */
	public static ScanLogin.Method anyLoggedInMethod() {
		// 优先「当前选中」的方式，其次按 微信 → QQ → 网易云 找
		ScanLogin cur = LOGINS.get(activeLogin);
		if (cur != null && cur.loggedIn()) return activeLogin;
		ScanLogin wechat = LOGINS.get(ScanLogin.Method.WECHAT);
		if (wechat != null && wechat.loggedIn()) return ScanLogin.Method.WECHAT;
		ScanLogin qq = LOGINS.get(ScanLogin.Method.QQ);
		if (qq != null && qq.loggedIn()) return ScanLogin.Method.QQ;
		ScanLogin ne = LOGINS.get(ScanLogin.Method.NETEASE);
		if (ne != null && ne.loggedIn()) return ScanLogin.Method.NETEASE;
		return null;
	}

	/** 登录成功后把 Cookie 写进配置（每种方式各存一份）。 */
	public static void persistLogin(ScanLogin login) {
		if (login == null || !login.loggedIn()) return;
		MusicConfig cfg = MusicConfig.get();
		switch (login.method()) {
			case WECHAT -> {
				cfg.wechatCookies = new java.util.HashMap<>(login.cookieMap());
				cfg.wechatNickname = login.nickname();
			}
			case NETEASE -> {
				cfg.neteaseCookies = new java.util.HashMap<>(login.cookieMap());
				cfg.neteaseNickname = login.nickname();
			}
			default -> {
				cfg.qqCookies = new java.util.HashMap<>(login.cookieMap());
				cfg.qqNickname = login.nickname();
			}
		}
		cfg.lastLoginMethod = login.method().name().toLowerCase();
		cfg.save();
	}

	/**
	 * 重新从配置加载登录态。
	 *
	 * <p>服务端用 {@code /symus login} 换账号后必须调用它，否则 Provider 还在用旧的
	 * Cookie 请求（表现为"登录成功了但拿不到会员歌"）。
	 */
	public static synchronized void reloadLogin() {
		MusicConfig cfg = MusicConfig.get();
		ScanLogin qq = LOGINS.get(ScanLogin.Method.QQ);
		if (qq != null) {
			qq.loadCookies(cfg.qqCookies == null ? Map.of() : cfg.qqCookies, cfg.qqNickname);
		}
		ScanLogin wx = LOGINS.get(ScanLogin.Method.WECHAT);
		if (wx != null) {
			wx.loadCookies(cfg.wechatCookies == null ? Map.of() : cfg.wechatCookies, cfg.wechatNickname);
		}
		ScanLogin ne = LOGINS.get(ScanLogin.Method.NETEASE);
		if (ne != null) {
			ne.loadCookies(cfg.neteaseCookies == null ? Map.of() : cfg.neteaseCookies, cfg.neteaseNickname);
		}
		if (cfg.lastLoginMethod != null) {
			for (ScanLogin.Method m : ScanLogin.Method.values()) {
				if (m.name().equalsIgnoreCase(cfg.lastLoginMethod)) {
					setActiveLoginMethod(m);
					break;
				}
			}
		}
	}

	/**
	 * 生效的 <b>QQ音乐</b> Cookie：优先用当前选中且已登录的那个。
	 *
	 * <p>⚠ 只认 QQ / 微信。网易云的 {@code MUSIC_U} 不能拿去请求 QQ 的接口，
	 * 反过来也一样 —— 所以两种凭据必须分开取（见 {@link #neteaseCookieHeader()}）。
	 */
	public static String effectiveCookieHeader() {
		ScanLogin a = activeLogin();
		if (a != null && a.loggedIn() && a.method() != ScanLogin.Method.NETEASE) {
			return a.cookieHeader();
		}
		ScanLogin.Method m = anyLoggedInMethod();
		if (m != null && m != ScanLogin.Method.NETEASE) {
			ScanLogin other = LOGINS.get(m);
			if (other != null) return other.cookieHeader();
		}
		return "";
	}

	/** 生效的<b>网易云</b> Cookie（{@code MUSIC_U} 等）；没登录返回空串。 */
	public static String neteaseCookieHeader() {
		ScanLogin ne = LOGINS.get(ScanLogin.Method.NETEASE);
		if (ne != null && ne.loggedIn()) return ne.cookieHeader();
		return "";
	}

	/** 网易云是否已登录。 */
	public static boolean neteaseLoggedIn() {
		ScanLogin ne = LOGINS.get(ScanLogin.Method.NETEASE);
		return ne != null && ne.loggedIn();
	}

	/** 网易云登录对象（设置页用）。 */
	public static ScanLogin neteaseLogin() {
		return LOGINS.get(ScanLogin.Method.NETEASE);
	}

	/**
	 * 本机是否已登录某个平台。
	 *
	 * <p><b>为什么客户端需要它</b>：服务端会广播它解析好的直链，
	 * 但服务端可能<b>没登录那个平台</b>（比如服主只登了 QQ）。
	 * 这时服务端解析网易云的歌只能拿到「免登录外层地址」——
	 * 那玩意对 VIP/下架歌会返回 404 网页。
	 *
	 * <p>而玩家本机如果登录了网易云（有自己的会员），
	 * <b>用本机账号解析的质量一定不比服务端差</b>。
	 * 所以客户端遇到「本机已登录该平台」时，应该忽略服务端直链、自己解析。
	 */
	public static boolean loggedInFor(MusicSource source) {
		if (source == MusicSource.NETEASE) return neteaseLoggedIn();
		// QQ音乐：QQ 或微信任何一种登录都算
		ScanLogin.Method m = anyLoggedInMethod();
		return m == ScanLogin.Method.QQ || m == ScanLogin.Method.WECHAT;
	}

	/** 生效的登录名（设置页显示用）。 */
	public static String effectiveLoginLabel() {
		ScanLogin a = activeLogin();
		if (a != null && a.loggedIn()) return a.method().label() + "：" + a.nickname();
		ScanLogin.Method m = anyLoggedInMethod();
		if (m != null) {
			ScanLogin other = LOGINS.get(m);
			if (other != null) return other.method().label() + "：" + other.nickname();
		}
		return "未登录";
	}

	/** 搜索结果里标记一下是否可播放（VIP 歌给个标识，UI 会画出来）。 */
	public record SearchResult(MusicSource source, List<MusicTrack> tracks, String error) {
		public boolean hasError() {
			return error != null;
		}
	}

	public static SearchResult search(MusicSource source, String keyword, int limit) {
		MusicProvider p = PROVIDERS.get(source);
		if (p == null) return new SearchResult(source, List.of(), "平台未启用");
		try {
			return new SearchResult(source, p.search(keyword, limit), null);
		} catch (Exception e) {
			return new SearchResult(source, List.of(), friendlyError(e));
		}
	}

	/**
	 * 拉取 QQ音乐歌单（从链接或 ID）。
	 *
	 * <p><b>会联网</b>，必须在后台线程调用（歌单可能有上千首，要分页拉）。
	 *
	 * @param input 歌单链接 / 短链 / 纯数字 ID
	 */
	public static QQPlaylist.Playlist fetchQQPlaylist(String input) {
		MusicProvider p = PROVIDERS.get(MusicSource.QQ);
		QQProvider qq = p instanceof QQProvider q ? q : null;
		if (qq == null) {
			return new QQPlaylist.Playlist("", "", "", java.util.List.of(), "QQ音乐平台未启用");
		}
		return QQPlaylist.fetch(input, qq);
	}
	/**
	 * 解析播放地址（带跨平台兜底）。
	 *
	 * @param track   想放的歌
	 * @param verbose 是否输出日志
	 * @return 播放地址；失败时 {@link PlayUrl#error()} 有中文原因
	 */
	public static PlayUrl resolve(MusicTrack track, boolean verbose) {
		MusicProvider p = PROVIDERS.get(MusicSource.byId(track.source()));
		if (p == null) return PlayUrl.fail("平台未启用");

		PlayUrl direct;
		try {
			direct = p.resolvePlayUrl(track);
		} catch (Exception e) {
			direct = PlayUrl.fail(friendlyError(e));
		}
		if (direct.ok()) return direct;

		if (!MusicConfig.get().crossSourceFallback) return direct;

		// ---- 跨平台降级 ----
		PlayUrl alt = resolveFromOther(track, verbose);
		return alt.ok() ? alt : direct;
	}

	/**
	 * 只从「另一个平台」找同名歌并解析播放地址。
	 *
	 * <p><b>什么时候需要它</b>：原平台的直链确认已经失效时。
	 *
	 * <p>典型情况是网易云的下架歌：外层地址
	 * {@code song/media/outer/url?id=xxx.mp3} 是「永远 200」的，
	 * 歌没了它会 302 到 {@code music.163.com/404}，最终返回
	 * <b>HTTP 200 + text/html</b>。这时再解析原平台只会拿到<b>同一根坏链</b>，
	 * 只有直接去对面平台找同名版本才有意义。
	 *
	 * @return 另一个平台的播放地址；找不到时 {@link PlayUrl#error()} 有原因
	 */
	public static PlayUrl resolveFromOther(MusicTrack track, boolean verbose) {
		if (!MusicConfig.get().crossSourceFallback) {
			return PlayUrl.fail("跨平台兜底已关闭");
		}
		for (MusicSource other : MusicSource.all()) {
			if (other.id().equals(track.source())) continue;
			MusicProvider op = PROVIDERS.get(other);
			if (op == null) continue;
			try {
				MusicTrack alt = findBestMatch(op.search(track.title() + " " + firstArtist(track.artists()), 6),
						track);
				if (alt == null) continue;
				PlayUrl altUrl = op.resolvePlayUrl(alt);
				if (altUrl.ok()) {
					if (verbose) {
						com.yue.music.YueMusic.LOGGER.info("[YueMusic] {} 放不了，已自动切到 {}({}) 播放",
								track.display(), other.displayName(), alt.display());
					}
					return new PlayUrl(altUrl.url(), altUrl.codec(), altUrl.quality(), altUrl.size(),
							null);
				}
			} catch (Exception e) {
				if (verbose) {
					com.yue.music.YueMusic.LOGGER.debug("[YueMusic] {} 兜底失败: {}", other.id(), e.toString());
				}
			}
		}
		return PlayUrl.fail("另一个平台也没找到能放的版本");
	}

	/** 在候选列表里挑同名同歌手的歌。 */
	private static MusicTrack findBestMatch(List<MusicTrack> candidates, MusicTrack want) {
		String wantTitle = normalize(want.title());
		String wantArtist = normalize(firstArtist(want.artists()));
		for (MusicTrack c : candidates) {
			String ct = normalize(c.title());
			if (!(ct.equals(wantTitle) || ct.contains(wantTitle) || wantTitle.contains(ct))) continue;
			if (wantArtist.isEmpty()) return c;
			String ca = normalize(c.artists());
			if (ca.contains(wantArtist) || wantArtist.contains(ca)) return c;
		}
		// 退一步：只要歌名一致就行
		for (MusicTrack c : candidates) {
			if (normalize(c.title()).equals(wantTitle)) return c;
		}
		return null;
	}

	private static String firstArtist(String artists) {
		if (artists == null || artists.isEmpty()) return "";
		int i = artists.indexOf('/');
		return i > 0 ? artists.substring(0, i) : artists;
	}

	/** 去掉括号内容和标点，便于跨平台比歌名。 */
	private static String normalize(String s) {
		if (s == null) return "";
		String t = s.toLowerCase()
				.replaceAll("[（(\\[【].*?[)）\\]】]", "")
				.replaceAll("[\\s\\-_/、,，.。!！?？'\"“”‘’~·]", "");
		return t.trim();
	}

	private static String friendlyError(Exception e) {
		String m = e.getMessage();
		if (m == null) m = e.getClass().getSimpleName();
		if (m.contains("timed out") || m.contains("Timeout")) return "网络超时，检查一下网络或代理";
		if (m.contains("UnknownHost") || m.contains("Connect")) return "连不上音乐平台接口（可能被墙/需要代理）";
		return "请求失败: " + m;
	}

	/**
	 * 获取歌词。
	 *
	 * <p>如果这首歌当初是靠「跨平台兜底」才播起来的，歌词也从能播的那个平台取——
	 * 否则会出现「听的是网易云的版本，歌词却是 QQ 音乐的」。
	 * 这里用「谁给得出播放地址就用谁的歌词」的策略：先试原平台，拿不到再试另一个。
	 */
	public static com.yue.music.model.Lyrics lyrics(MusicTrack track) {
		MusicProvider p = PROVIDERS.get(MusicSource.byId(track.source()));
		if (p == null) {
			var alt = lyricsFromOther(track);
			return alt != null ? alt
					: com.yue.music.model.Lyrics.empty(track.source() + ":" + track.id(), "平台未启用");
		}
		var own = p.lyrics(track);
		if (!own.lines().isEmpty()) return own;
		// 没有歌词（纯音乐 / 抓不到）→ 去另一个平台按同名再试一次，拿到就用
		var alt = lyricsFromOther(track);
		return alt != null ? alt : own;
	}

	private static com.yue.music.model.Lyrics lyricsFromOther(MusicTrack track) {
		for (MusicSource other : MusicSource.all()) {
			if (other.id().equals(track.source())) continue;
			MusicProvider op = PROVIDERS.get(other);
			if (op == null) continue;
			try {
				MusicTrack alt = findBestMatch(op.search(track.title() + " " + firstArtist(track.artists()), 5), track);
				if (alt == null) continue;
				var lyr = op.lyrics(alt);
				if (!lyr.lines().isEmpty()) return lyr;
			} catch (Exception ignored) {
			}
		}
		return null;
	}
}
