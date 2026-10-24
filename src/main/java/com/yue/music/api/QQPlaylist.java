package com.yue.music.api;

import com.yue.music.YueMusic;
import com.yue.music.model.MusicTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ音乐歌单解析（从分享链接/ID 拉取整个歌单的歌曲列表）。
 *
 * <h3>支持的链接形式</h3>
 * <ul>
 *   <li>{@code https://y.qq.com/n/ryqq/playlist/8732463559}</li>
 *   <li>{@code https://i.y.qq.com/n2/m/share/details/taoge.html?id=8732463559}</li>
 *   <li>{@code https://c6.y.qq.com/base/fcgi-bin/u?__=xxxx}（短链，需重定向）</li>
 *   <li>纯数字 ID：{@code 8732463559}</li>
 * </ul>
 *
 * <h3>为什么要分页</h3>
 * QQ音乐歌单接口需要 {@code song_begin} 偏移分页，
 * 所以这里循环拉取直到拿满或拿不到为止（最多 {@link #MAX_SONGS} 首）。
 */
public final class QQPlaylist {

	/** 歌单详情接口（网页版用的那个）。 */
	private static final String DETAIL = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg";

	/** 一次拉多少首。 */
	private static final int PAGE = 500;

	/**
	 * 安全上限：防止歌单异常大时无限拉。
	 *
	 * <p>从 1000 提到 10000 —— 用户要支持上万首的大歌单，
	 * 而队列上限（{@code MusicConfig.MAX_QUEUE_LIMIT}）也是 10000，两边对齐。
	 */
	private static final int MAX_SONGS = 10_000;

	private QQPlaylist() {
	}

	/** 从任意形式的输入里抠出歌单 ID。 */
	public static String extractId(String input) {
		if (input == null) return null;
		String s = input.trim();
		if (s.isEmpty()) return null;
		// 纯数字
		if (s.matches("\\d{5,20}")) return s;
		// ?id=xxx 或 /playlist/xxx
		Matcher m = Pattern.compile("[?&]id=(\\d{5,20})").matcher(s);
		if (m.find()) return m.group(1);
		m = Pattern.compile("/playlist/(\\d{5,20})").matcher(s);
		if (m.find()) return m.group(1);
		m = Pattern.compile("disstid=(\\d{5,20})").matcher(s);
		if (m.find()) return m.group(1);
		// 任意位置的长数字（兜底）
		m = Pattern.compile("(\\d{8,20})").matcher(s);
		if (m.find()) return m.group(1);
		return null;
	}

	/** 歌单信息 + 歌曲列表。 */
	public record Playlist(String id, String name, String creator, List<MusicTrack> tracks, String error) {
		public boolean ok() {
			return error == null && tracks != null && !tracks.isEmpty();
		}
	}

	/**
	 * 拉取歌单（支持短链：会先跟随重定向拿到真实 URL 再抠 ID）。
	 *
	 * <p><b>会联网</b>，必须在后台线程调用。
	 */
	public static Playlist fetch(String input, QQProvider provider) {
		String id = extractId(input);
		if (id == null) {
			// 可能是短链 → 跟随重定向后再试
			try {
				String finalUrl = resolveRedirect(input);
				id = extractId(finalUrl);
			} catch (Exception ignored) {
			}
		}
		if (id == null) {
			return new Playlist("", "", "", List.of(),
					"没认出歌单 ID。请用「y.qq.com 歌单页」的链接，或直接填歌单数字 ID");
		}
		return fetchById(id, provider);
	}

	/** 跟随一次重定向，拿到最终 URL（QQ 分享短链用）。 */
	private static String resolveRedirect(String url) throws Exception {
		if (url == null || !url.startsWith("http")) return "";
		Http.Step step = Http.getManual(url, java.util.Map.of("User-Agent", Http.DEFAULT_UA),
				new java.util.HashMap<>(), false);
		return step.location() == null ? url : step.location();
	}

	/** 按 ID 拉取歌单（分页直到拿满）。 */
	public static Playlist fetchById(String id, QQProvider provider) {
		if (provider == null) {
			return new Playlist(id, "", "", List.of(), "QQ音乐平台未启用");
		}
		List<MusicTrack> all = new ArrayList<>();
		String name = "";
		String creator = "";
		String lastError = null;

		for (int begin = 0; begin < MAX_SONGS; begin += PAGE) {
			try {
				String body = Http.getString(DETAIL + "?type=1&json=1&utf8=1&onlysong=0"
								+ "&disstid=" + id
								+ "&song_begin=" + begin
								+ "&song_num=" + PAGE
								+ "&format=json&notice=0&platform=yqq.json&needNewCode=0",
						java.util.Map.of("Referer", "https://y.qq.com/",
								"User-Agent", Http.DEFAULT_UA));
				int got = provider.parsePlaylistPage(body, all, name, creator);
				if (got <= 0) break;               // 没有更多了
				if (name.isEmpty()) {
					// 从原始响应里补名字（第一次就能拿到）
					name = provider.lastPlaylistName();
					creator = provider.lastPlaylistCreator();
				}
				if (got < PAGE) break;             // 最后一页
			} catch (Exception e) {
				lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				break;
			}
		}

		if (all.isEmpty()) {
			return new Playlist(id, name, creator, List.of(),
					lastError == null ? "这个歌单是空的，或无法访问（可能不是公开歌单）" : lastError);
		}
		YueMusic.LOGGER.info("[{}] 歌单「{}」拉取完成：{} 首", YueMusic.MOD_NAME, name, all.size());
		return new Playlist(id, name, creator, all, null);
	}
}
