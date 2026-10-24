package com.yue.music.api;

import com.yue.music.YueMusic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.model.MusicSource;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ音乐 Provider（网页版公开接口，实测可用）。
 *
 * <ul>
 *   <li>搜索：{@code GET c.y.qq.com/soso/fcgi-bin/client_search_cp?new_json=1}
 *       —— 注意新版 {@code music.search.SearchCgiService} 接口需额外签名，会返回 500001，故用这个</li>
 *   <li>播放：{@code POST u.y.qq.com/cgi-bin/musicu.fcg} module=vkey.GetVkeyServer / CgiGetVkey
 *       —— 需要带 {@code filename} 指定音质，否则可能 404；purl 拼到 sip 后直接可下（无需 Cookie）</li>
 * </ul>
 *
 * <p>未登录时免费歌可放，VIP 歌返回 {@code result=104003}；扫码登录后（见 {@link QQLogin}）可拿会员歌。
 */
public class QQProvider implements MusicProvider {

	private static final String SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
	private static final String FCG = "https://u.y.qq.com/cgi-bin/musicu.fcg";

	/** HTTPS 播放域名（sip 里给的是 http，部分网络会拦）。 */
	private static final String[] STREAM_HOSTS = {
			"https://isure.stream.qqmusic.qq.com/",
			"https://sjy6.stream.qqmusic.qq.com/",
			"http://aqqmusic.tc.qq.com/"
	};

	/**
	 * 音质尝试顺序：<b>先标准音质</b>。
	 *
	 * <p>实测：同一首免费歌，M500(128k) 能拿到 purl，而 F000/M800（无损/320k）会返回
	 * {@code result=104003}（会员专享）。所以必须从低到高试，且遇到「需要会员」要继续试下一档，
	 * 不能直接放弃——否则免费歌会被误判成放不了。
	 */
	private static final String[][] QUALITIES = {
			{"M500", "mp3", "128kbps"},
			{"C400", "m4a", "AAC 96k"},
			{"M800", "mp3", "320kbps"},
			{"F000", "flac", "无损FLAC"},
	};

	/** 已登录时优先要更高音质。 */
	private static final String[][] QUALITIES_LOGGED_IN = {
			{"F000", "flac", "无损FLAC"},
			{"M800", "mp3", "320kbps"},
			{"M500", "mp3", "128kbps"},
			{"C400", "m4a", "AAC 96k"},
	};

	private final QQLogin qqLogin;
	private final WeChatLogin weChatLogin;

	public QQProvider(QQLogin qqLogin, WeChatLogin weChatLogin) {
		this.qqLogin = qqLogin;
		this.weChatLogin = weChatLogin;
	}

	/** 当前生效的登录（可 QQ 可微信）；都没登录时返回 QQ 那个用于提示文案。 */
	private ScanLogin login() {
		String cookie = MusicApi.effectiveCookieHeader();
		if (!cookie.isEmpty()) {
			ScanLogin.Method m = MusicApi.anyLoggedInMethod();
			if (m == ScanLogin.Method.WECHAT && weChatLogin != null) return weChatLogin;
			if (m == ScanLogin.Method.QQ && qqLogin != null) return qqLogin;
		}
		return qqLogin != null ? qqLogin : weChatLogin;
	}

	@Override
	public String id() {
		return MusicSource.QQ.id();
	}

	@Override
	public String displayName() {
		return MusicSource.QQ.displayName();
	}

	private Map<String, String> headers() {
		Map<String, String> h = new HashMap<>();
		h.put("Referer", "https://y.qq.com/portal/search.html");
		String cookie = login().cookieHeader();
		if (!cookie.isEmpty()) h.put("Cookie", cookie);
		return h;
	}

	// ------------------------------------------------------------ 搜索

	@Override
	public List<MusicTrack> search(String keyword, int limit) throws Exception {
		int n = Math.max(1, Math.min(limit, 50));
		String url = SEARCH + "?w=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
				+ "&format=json&n=" + n + "&p=1&new_json=1&cr=1&catZhida=1";
		JsonObject root = JsonParser.parseString(Http.getString(url, headers())).getAsJsonObject();

		List<MusicTrack> out = new ArrayList<>();
		if (!root.has("data") || !root.getAsJsonObject("data").has("song")) return out;
		JsonObject song = root.getAsJsonObject("data").getAsJsonObject("song");
		if (!song.has("list") || !song.get("list").isJsonArray()) return out;

		for (JsonElement el : song.getAsJsonArray("list")) {
			if (!el.isJsonObject()) continue;
			JsonObject s = el.getAsJsonObject();

			String mid = s.has("mid") ? s.get("mid").getAsString()
					: (s.has("songmid") ? s.get("songmid").getAsString() : null);
			String title = s.has("title") ? s.get("title").getAsString()
					: (s.has("songname") ? s.get("songname").getAsString() : null);
			if (mid == null || title == null) continue;

			StringBuilder artists = new StringBuilder();
			if (s.has("singer") && s.get("singer").isJsonArray()) {
				for (JsonElement a : s.getAsJsonArray("singer")) {
					if (!a.isJsonObject()) continue;
					JsonObject ao = a.getAsJsonObject();
					String an = ao.has("name") ? ao.get("name").getAsString() : "";
					if (an.isEmpty()) continue;
					if (artists.length() > 0) artists.append('/');
					artists.append(an);
				}
			}

			String album = "";
			String albumMid = "";
			if (s.has("album") && s.get("album").isJsonObject()) {
				JsonObject al = s.getAsJsonObject("album");
				album = al.has("name") ? al.get("name").getAsString() : "";
				albumMid = al.has("mid") ? al.get("mid").getAsString() : "";
			}

			int duration = 0;
			if (s.has("interval") && !s.get("interval").isJsonNull()) {
				try {
					duration = s.get("interval").getAsInt();
				} catch (Exception ignored) {
				}
			}

			// VIP 标记：搜索接口用 pay.pay_play；这里也兼容 payplay，避免接口变动
			boolean vip = false;
			if (s.has("pay") && s.get("pay").isJsonObject()) {
				JsonObject pay = s.getAsJsonObject("pay");
				for (String k : new String[]{"pay_play", "payplay"}) {
					if (pay.has(k) && !pay.get(k).isJsonNull()) {
						try {
							vip = pay.get(k).getAsInt() != 0;
						} catch (Exception ignored) {
						}
						break;
					}
				}
			}

			out.add(new MusicTrack(mid, id(), title, artists.toString(), album, duration, albumMid, vip));
		}
		return out;
	}

	// ------------------------------------------------------------ 歌单

	/** 最近一次解析歌单时拿到的歌单名。 */
	private volatile String lastPlaylistName = "";
	/** 最近一次解析歌单时拿到的创建者。 */
	private volatile String lastPlaylistCreator = "";

	public String lastPlaylistName() {
		return lastPlaylistName;
	}

	public String lastPlaylistCreator() {
		return lastPlaylistCreator;
	}

	/**
	 * 解析歌单详情接口的一页，把歌曲追加进 {@code out}。
	 *
	 * <p>接口结构：{@code {"cdlist":[{"dissname":"歌单名","nickname":"创建者","songlist":[...]}]}}
	 *
	 * @return 本页解析出的歌曲数（0 表示没有更多了）
	 */
	public int parsePlaylistPage(String body, java.util.List<MusicTrack> out,
								 String name, String creator) {
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			if (!root.has("cdlist") || !root.get("cdlist").isJsonArray()) return 0;
			JsonArray cds = root.getAsJsonArray("cdlist");
			if (cds.isEmpty()) return 0;
			JsonObject cd = cds.get(0).getAsJsonObject();

			if (name == null || name.isEmpty()) {
				if (cd.has("dissname") && !cd.get("dissname").isJsonNull()) {
					lastPlaylistName = cd.get("dissname").getAsString();
				}
			}
			if (creator == null || creator.isEmpty()) {
				if (cd.has("nickname") && !cd.get("nickname").isJsonNull()) {
					lastPlaylistCreator = cd.get("nickname").getAsString();
				}
			}

			if (!cd.has("songlist") || !cd.get("songlist").isJsonArray()) return 0;
			JsonArray songs = cd.getAsJsonArray("songlist");
			int before = out.size();
			for (JsonElement el : songs) {
				if (!el.isJsonObject()) continue;
				MusicTrack t = parsePlaylistSong(el.getAsJsonObject());
				if (t != null) out.add(t);
			}
			return out.size() - before;
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[{}] 歌单页面解析失败: {}", YueMusic.MOD_NAME, e.toString());
			return 0;
		}
	}

	/**
	 * 解析歌单里的一首歌。
	 *
	 * <p>歌单接口的字段名和搜索接口不同（{@code songmid/songname/singer/albumname/interval}），
	 * 所以单独写一个解析方法，不复用搜索那份。
	 */
	private MusicTrack parsePlaylistSong(JsonObject s) {
		try {
			String mid = "";
			for (String k : new String[]{"songmid", "mid"}) {
				if (s.has(k) && !s.get(k).isJsonNull()) {
					mid = s.get(k).getAsString();
					if (!mid.isEmpty()) break;
				}
			}
			// 有些情况给的是数字 songid，用 songmid 拿不到就跳过
			if (mid.isEmpty()) return null;

			String title = s.has("songname") && !s.get("songname").isJsonNull()
					? s.get("songname").getAsString()
					: (s.has("title") && !s.get("title").isJsonNull() ? s.get("title").getAsString() : "");
			if (title.isEmpty()) return null;

			StringBuilder artists = new StringBuilder();
			if (s.has("singer") && s.get("singer").isJsonArray()) {
				for (JsonElement ae : s.getAsJsonArray("singer")) {
					if (!ae.isJsonObject()) continue;
					JsonObject ao = ae.getAsJsonObject();
					String an = ao.has("name") && !ao.get("name").isJsonNull()
							? ao.get("name").getAsString() : "";
					if (an.isEmpty()) continue;
					if (artists.length() > 0) artists.append('/');
					artists.append(an);
				}
			}

			String album = "";
			String albumMid = "";
			for (String k : new String[]{"albumname", "album"}) {
				if (s.has(k) && !s.get(k).isJsonNull() && s.get(k).isJsonPrimitive()) {
					album = s.get(k).getAsString();
					break;
				}
			}
			if (s.has("albummid") && !s.get("albummid").isJsonNull()) {
				albumMid = s.get("albummid").getAsString();
			}

			int duration = 0;
			if (s.has("interval") && !s.get("interval").isJsonNull()) {
				try {
					duration = s.get("interval").getAsInt();
				} catch (Exception ignored) {
				}
			}

			// ⚠ VIP 标记的字段名在【两个接口里不一样】：
			//   搜索接口      → pay.pay_play   （带下划线）
			//   歌单详情接口  → pay.payplay    （不带下划线，实测确认）
			// 以前只读 pay_play，于是从歌单导入的歌 VIP 标记永远是 false。
			boolean vip = false;
			if (s.has("pay") && s.get("pay").isJsonObject()) {
				JsonObject pay = s.getAsJsonObject("pay");
				for (String k : new String[]{"payplay", "pay_play"}) {
					if (pay.has(k) && !pay.get(k).isJsonNull()) {
						try {
							vip = pay.get(k).getAsInt() != 0;
						} catch (Exception ignored) {
						}
						break;
					}
				}
			}
			return new MusicTrack(mid, id(), title, artists.toString(), album, duration, albumMid, vip);
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------ 播放地址

	@Override
	public PlayUrl resolvePlayUrl(MusicTrack track) throws Exception {
		String[][] order = login().loggedIn() ? QUALITIES_LOGGED_IN : QUALITIES;
		PlayUrl last = PlayUrl.fail("QQ音乐未返回可用地址");
		for (String[] q : order) {
			PlayUrl url = requestVkey(track.id(), q[0], q[1], q[2]);
			if (url.ok()) return url;
			last = url;
			// 「需要会员」不代表这首歌完全放不了——低音质可能仍然免费，继续试下一档
		}
		return last;
	}

	/**
	 * 向 vkey 接口要地址。
	 *
	 * @param mid       songmid
	 * @param prefix    音质前缀（F000/M800/M500）
	 * @param codec     扩展名提示
	 * @param quality   音质显示名
	 */
	public PlayUrl requestVkey(String mid, String prefix, String codec, String quality) throws Exception {
		String filename = prefix + mid + mid + "." + codec;

		JsonObject param = new JsonObject();
		param.addProperty("guid", "10000");
		JsonArray mids = new JsonArray();
		mids.add(mid);
		param.add("songmid", mids);
		JsonArray files = new JsonArray();
		files.add(filename);
		param.add("filename", files);
		JsonArray types = new JsonArray();
		types.add(1);
		param.add("songtype", types);
		param.addProperty("uin", login().uin());
		param.addProperty("loginflag", 1);
		param.addProperty("platform", "20");

		JsonObject req = new JsonObject();
		req.addProperty("module", "vkey.GetVkeyServer");
		req.addProperty("method", "CgiGetVkey");
		req.add("param", param);

		JsonObject comm = new JsonObject();
		try {
			comm.addProperty("uin", Long.parseLong(login().uin()));
		} catch (Exception e) {
			comm.addProperty("uin", 0);
		}
		comm.addProperty("format", "json");
		comm.addProperty("ct", 24);
		comm.addProperty("cv", 0);
		if (login().loggedIn()) {
			comm.addProperty("g_tk", ScanLogin.hash33(login().cookieMap()
					.getOrDefault("qm_keyst", ""), 5381));
		}

		JsonObject root = new JsonObject();
		root.add("req", req);
		root.add("comm", comm);

		Map<String, String> h = new HashMap<>();
		h.put("Referer", "https://y.qq.com/");
		h.put("Origin", "https://y.qq.com");
		h.put("Content-Type", "application/json;charset=UTF-8");
		String cookie = login().cookieHeader();
		if (!cookie.isEmpty()) h.put("Cookie", cookie);

		JsonObject resp = JsonParser.parseString(
				Http.getMusicuFcg(FCG, h, root.toString())).getAsJsonObject();

		if (!resp.has("req") || !resp.getAsJsonObject("req").has("data")) {
			return PlayUrl.fail("QQ音乐接口返回异常");
		}
		JsonObject data = resp.getAsJsonObject("req").getAsJsonObject("data");
		if (!data.has("midurlinfo") || !data.get("midurlinfo").isJsonArray()
				|| data.getAsJsonArray("midurlinfo").isEmpty()) {
			return PlayUrl.fail("QQ音乐未返回播放信息");
		}

		JsonObject info = data.getAsJsonArray("midurlinfo").get(0).getAsJsonObject();
		int result = info.has("result") ? info.get("result").getAsInt() : 0;
		String purl = info.has("purl") && !info.get("purl").isJsonNull()
				? info.get("purl").getAsString() : "";

		if (purl.isBlank()) {
			if (result == 104003) {
				return PlayUrl.fail(login().loggedIn()
						? "这首歌需要QQ音乐会员（当前账号无权限）"
						: "这首歌需要QQ音乐会员，可在设置里扫码登录");
			}
			if (result == 104604) {
				return PlayUrl.fail("请求太频繁，稍后再试");
			}
			return PlayUrl.fail("QQ音乐无可用地址（result=" + result + "）");
		}

		// purl 里可能出现 \u0026 转义，JSON 解析器已经还原成 &，这里再兜一次底
		purl = purl.replace("\\u0026", "&");

		String host = STREAM_HOSTS[0];
		if (data.has("sip") && data.get("sip").isJsonArray() && !data.getAsJsonArray("sip").isEmpty()) {
			String sip = data.getAsJsonArray("sip").get(0).getAsString();
			if (sip.startsWith("https://")) host = sip;
		}

		long size = 0;
		if (info.has("filesize") && !info.get("filesize").isJsonNull()) {
			try {
				size = Long.parseLong(info.get("filesize").getAsString());
			} catch (Exception ignored) {
			}
		}
		return PlayUrl.of(host + purl, codec, quality, size);
	}

	@Override
	public boolean loggedIn() {
		return login().loggedIn();
	}

	@Override
	public String loginHint() {
		return login().loggedIn() ? "已登录：" + login().nickname() : "未登录（会员歌放不了）";
	}

	/**
	 * 歌词：{@code GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg}
	 * <p>用 {@code nobase64=1} 直接拿明文 LRC；{@code trans} 是翻译。
	 * 注意这个接口<b>必须带 Referer: y.qq.com</b>，否则会被拒。
	 */
	@Override
	public com.yue.music.model.Lyrics lyrics(MusicTrack track) {
		String key = track.source() + ":" + track.id();
		try {
			String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
					+ "?songmid=" + track.id()
					+ "&format=json&nobase64=1&g_tk=5381&loginUin=0&hostUin=0"
					+ "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0";
			JsonObject root = JsonParser.parseString(Http.getString(url, headers())).getAsJsonObject();
			String lrc = root.has("lyric") && !root.get("lyric").isJsonNull()
					? root.get("lyric").getAsString() : "";
			String trans = root.has("trans") && !root.get("trans").isJsonNull()
					? root.get("trans").getAsString() : "";
			if (lrc.isBlank()) {
				return com.yue.music.model.Lyrics.empty(key, null);
			}
			var lines = LrcParser.parse(lrc, trans.isBlank() ? null : trans);
			return new com.yue.music.model.Lyrics(key, lines, !trans.isBlank(), null);
		} catch (Exception e) {
			return com.yue.music.model.Lyrics.empty(key,
					e.getMessage() == null ? "歌词获取失败" : "歌词获取失败：" + e.getMessage());
		}
	}
}
