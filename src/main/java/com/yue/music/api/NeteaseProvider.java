package com.yue.music.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yue.music.model.MusicSource;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐 Provider。
 *
 * <p>走的是网页版公开接口，<b>不需要登录、不需要加密签名</b>：
 * <ul>
 *   <li>搜索：{@code GET https://music.163.com/api/search/get/web}</li>
 *   <li>播放：{@code GET https://music.163.com/song/media/outer/url?id=<id>.mp3}
 *       —— 302 跳到 {@code *.music.126.net} 的真实 mp3；被版权限制时会跳到 {@code /404}</li>
 * </ul>
 * 实测：普通歌曲大部分可放；部分大牌版权歌会 404，此时由上层跨平台兜底。
 */
public class NeteaseProvider implements MusicProvider {

	private static final String SEARCH_URL =
			"https://music.163.com/api/search/get/web?csrf_token=&type=1&offset=0&total=true&limit=%d&s=%s";
	private static final String OUTER_URL = "https://music.163.com/song/media/outer/url?id=%s.mp3";

	private static final Map<String, String> HEADERS = Map.of(
			"Referer", "https://music.163.com/",
			"Cookie", "appver=2.0.2; os=pc; channel=netease;"
	);

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
		JsonObject root = Http.getJson(url, HEADERS).getAsJsonObject();

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
		String link = String.format(OUTER_URL, track.id());
		String finalUrl = Http.resolveRedirect(link, HEADERS);
		if (finalUrl == null || finalUrl.contains("music.163.com/404") || finalUrl.contains("/404")) {
			return PlayUrl.fail("网易云版权受限，无法获取播放地址");
		}
		if (!finalUrl.startsWith("http")) {
			return PlayUrl.fail("网易云返回了无效地址");
		}
		// 实测：直接 GET 原始 outer 地址（302）也能正常跟随到 mp3，这里直接用它，
		// 播放器客户端按 NORMAL 策略跟随重定向即可，避免 CDN 地址过期
		return PlayUrl.of(link, "mp3", "标准音质", 0);
	}

	@Override
	public String loginHint() {
		return "公开接口（免登录）";
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
					HEADERS).getAsJsonObject();
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
