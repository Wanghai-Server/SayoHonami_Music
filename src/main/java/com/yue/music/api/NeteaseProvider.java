package com.yue.music.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yue.music.YueMusic;
import com.yue.music.model.MusicSource;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐 Provider。
 *
 * <p>走的是网页版公开接口，<b>不登录也能用</b>（搜 + 标准音质播放）：
 * <ul>
 *   <li>搜索：{@code GET https://music.163.com/api/search/get/web}</li>
 *   <li>播放（未登录）：{@code GET https://music.163.com/song/media/outer/url?id=<id>.mp3}
 *       —— 302 跳到 {@code *.music.126.net} 的真实 mp3；被版权限制时会跳到 {@code /404}</li>
 *   <li>播放（已登录）：{@code GET https://music.163.com/api/song/enhance/player/url}
 *       —— 能拿到<b>更高音质</b>，而且部分「未登录返回 404」的歌登录后能正常放</li>
 * </ul>
 * 实测：普通歌曲大部分可放；部分大牌版权歌会 404，此时由上层跨平台兜底。
 */
public class NeteaseProvider implements MusicProvider {

	private static final String SEARCH_URL =
			"https://music.163.com/api/search/get/web?csrf_token=&type=1&offset=0&total=true&limit=%d&s=%s";
	private static final String OUTER_URL = "https://music.163.com/song/media/outer/url?id=%s.mp3";
	/** 带登录 Cookie 时用的高质量直链接口。 */
	private static final String ENHANCE_URL =
			"https://music.163.com/api/song/enhance/player/url?ids=[%s]&id=%s&br=%d";

	private static final Map<String, String> BASE_HEADERS = Map.of(
			"Referer", "https://music.163.com/",
			"Cookie", "appver=2.0.2; os=pc; channel=netease;"
	);

	/** 网易云登录对象（可能为 null：老调用路径 / 测试）。 */
	private final NeteaseLogin login;

	public NeteaseProvider() {
		this(null);
	}

	public NeteaseProvider(NeteaseLogin login) {
		this.login = login;
	}

	/**
	 * 当前请求该用的 Cookie。
	 *
	 * <p>已登录时带上 {@code MUSIC_U}（接口据此给高音质/会员歌）；
	 * 未登录时用默认的匿名 Cookie。
	 */
	private Map<String, String> headers() {
		if (login != null && login.loggedIn() && !login.musicU().isEmpty()) {
			return Map.of(
					"Referer", "https://music.163.com/",
					"Cookie", "appver=2.0.2; os=pc; channel=netease; MUSIC_U=" + login.musicU()
			);
		}
		return BASE_HEADERS;
	}

	@Override
	public String id() {
		return MusicSource.NETEASE.id();
	}

	@Override
	public String displayName() {
		return MusicSource.NETEASE.displayName();
	}

	@Override
	public List<MusicTrack> search(String keyword, int limit) throws Exception {
		String url = String.format(SEARCH_URL, Math.max(1, Math.min(limit, 50)),
				java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8));
		JsonObject root = Http.getJson(url, headers()).getAsJsonObject();

		List<MusicTrack> out = new ArrayList<>();
		JsonObject result = root.has("result") && root.get("result").isJsonObject()
				? root.getAsJsonObject("result") : null;
		if (result == null || !result.has("songs") || !result.get("songs").isJsonArray()) {
			return out;
		}
		for (JsonElement el : result.getAsJsonArray("songs")) {
			if (!el.isJsonObject()) continue;
			JsonObject s = el.getAsJsonObject();

			String id = s.has("id") ? s.get("id").getAsString() : null;
			String name = s.has("name") ? s.get("name").getAsString() : null;
			if (id == null || name == null) continue;

			StringBuilder artists = new StringBuilder();
			if (s.has("artists") && s.get("artists").isJsonArray()) {
				for (JsonElement a : s.getAsJsonArray("artists")) {
					if (!a.isJsonObject()) continue;
					JsonObject ao = a.getAsJsonObject();
					String an = ao.has("name") ? ao.get("name").getAsString() : "";
					if (an.isEmpty()) continue;
					if (artists.length() > 0) artists.append('/');
					artists.append(an);
				}
			} else if (s.has("artist") && s.get("artist").isJsonObject()) {
				JsonObject ao = s.getAsJsonObject("artist");
				artists.append(ao.has("name") ? ao.get("name").getAsString() : "");
			}

			String album = "";
			String albumMid = "";
			if (s.has("album") && s.get("album").isJsonObject()) {
				JsonObject al = s.getAsJsonObject("album");
				album = al.has("name") ? al.get("name").getAsString() : "";
				// 网易云封面用 picUrl，这里先塞进 albumMid 字段方便以后取图
				albumMid = al.has("picId") && !al.get("picId").isJsonNull() ? "" : "";
			}

			// duration 单位是毫秒
			int duration = 0;
			if (s.has("duration") && !s.get("duration").isJsonNull()) {
				try {
					duration = s.get("duration").getAsInt() / 1000;
				} catch (Exception ignored) {
				}
			}

			// fee: 0 免费 / 1 需要VIP / 4 付费专辑 / 8 低音质免费
			boolean vip = s.has("fee") && !s.get("fee").isJsonNull() && s.get("fee").getAsInt() == 1;

			out.add(new MusicTrack(id, id(), name, artists.toString(), album, duration, albumMid, vip));
		}
		return out;
	}

	@Override
	public PlayUrl resolvePlayUrl(MusicTrack track) throws Exception {
		// 登录了先试「高质量直链接口」：能拿 320kbps，而且部分歌只有登录后才给地址
		if (login != null && login.loggedIn() && !login.musicU().isEmpty()) {
			PlayUrl hq = tryEnhance(track);
			if (hq != null) return hq;
		}

		// 未登录 / 高质量接口没给地址 → 退回公开的外层地址
		String link = String.format(OUTER_URL, track.id());
		String finalUrl = Http.resolveRedirect(link, headers());
		if (finalUrl == null || finalUrl.contains("music.163.com/404") || finalUrl.contains("/404")) {
			return PlayUrl.fail(login != null && login.loggedIn()
					? "网易云版权受限（这个账号也拿不到地址）"
					: "网易云版权受限，无法获取播放地址（登录网易云后可能可以放）");
		}
		if (!finalUrl.startsWith("http")) {
			return PlayUrl.fail("网易云返回了无效地址");
		}
		// 实测：直接 GET 原始 outer 地址（302）也能正常跟随到 mp3，这里直接用它，
		// 播放器客户端按 NORMAL 策略跟随重定向即可，避免 CDN 地址过期
		return PlayUrl.of(link, "mp3", "标准音质", 0);
	}

	/**
	 * 用登录态调「高质量直链接口」。
	 *
	 * <p>按 <b>无损 → 320k → 192k</b> 依次尝试，第一个给得出地址的就用它。
	 *
	 * @return 拿不到地址时返回 {@code null}（调用方再退回外层地址）
	 */
	private PlayUrl tryEnhance(MusicTrack track) {
		int[][] qualities = {{999000, 0}, {320000, 0}, {192000, 0}};
		String[] labels = {"无损", "320kbps", "192kbps"};
		for (int i = 0; i < qualities.length; i++) {
			int br = qualities[i][0];
			try {
				String url = String.format(ENHANCE_URL, track.id(), track.id(), br);
				JsonObject root = Http.getJson(url, headers()).getAsJsonObject();
				if (!root.has("data") || !root.get("data").isJsonArray()) continue;
				var arr = root.getAsJsonArray("data");
				if (arr.isEmpty()) continue;
				JsonElement el = arr.get(0);
				if (el == null || !el.isJsonObject()) continue;
				JsonObject d = el.getAsJsonObject();
				if (!d.has("url") || d.get("url").isJsonNull()) continue;
				String playUrl = d.get("url").getAsString();
				if (playUrl.isEmpty() || !playUrl.startsWith("http")) continue;
				// 接口返回的码：200 = 有地址
				int code = d.has("code") && !d.get("code").isJsonNull() ? d.get("code").getAsInt() : 200;
				if (code != 200) continue;
				YueMusic.LOGGER.info("[{}] 网易云登录态解析到 {} 直链：《{}》",
						YueMusic.MOD_NAME, labels[i], track.title());
				return PlayUrl.of(playUrl, "mp3", labels[i], 0);
			} catch (Exception e) {
				YueMusic.LOGGER.debug("[{}] 网易云高质量解析失败（{}）：{}",
						YueMusic.MOD_NAME, labels[i], e.toString());
			}
		}
		return null;
	}

	@Override
	public String loginHint() {
		if (login != null && login.loggedIn()) {
			String nick = login.nickname();
			return "网易云已登录" + (nick.isEmpty() ? "" : "：" + nick) + "（可用高音质）";
		}
		return "公开接口（免登录，只能标准音质）";
	}

	/**
	 * 歌词：{@code GET https://music.163.com/api/song/lyric?id=..&lv=1&kv=1&tv=-1}
	 * <p>返回 {@code lrc.lyric} 原文与 {@code tlyric.lyric} 翻译，都是 LRC 格式。
	 */
	@Override
	public com.yue.music.model.Lyrics lyrics(MusicTrack track) {
		String key = track.source() + ":" + track.id();
		try {
			JsonObject root = Http.getJson(
					"https://music.163.com/api/song/lyric?id=" + track.id() + "&lv=1&kv=1&tv=-1",
					headers()).getAsJsonObject();
			String lrc = null;
			String tlyric = null;
			if (root.has("lrc") && root.get("lrc").isJsonObject()) {
				JsonObject l = root.getAsJsonObject("lrc");
				if (l.has("lyric") && !l.get("lyric").isJsonNull()) lrc = l.get("lyric").getAsString();
			}
			if (root.has("tlyric") && root.get("tlyric").isJsonObject()) {
				JsonObject t = root.getAsJsonObject("tlyric");
				if (t.has("lyric") && !t.get("lyric").isJsonNull()) tlyric = t.get("lyric").getAsString();
			}
			if (lrc == null || lrc.isBlank()) {
				return com.yue.music.model.Lyrics.empty(key, null); // 纯音乐
			}
			var lines = LrcParser.parse(lrc, tlyric);
			return new com.yue.music.model.Lyrics(key, lines, tlyric != null && !tlyric.isBlank(), null);
		} catch (Exception e) {
			return com.yue.music.model.Lyrics.empty(key,
					e.getMessage() == null ? "歌词获取失败" : "歌词获取失败：" + e.getMessage());
		}
	}
}
