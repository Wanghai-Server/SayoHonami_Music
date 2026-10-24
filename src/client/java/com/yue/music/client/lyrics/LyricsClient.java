package com.yue.music.client.lyrics;

import com.yue.music.YueMusic;
import com.yue.music.api.MusicApi;
import com.yue.music.client.audio.ClientAudio;
import com.yue.music.model.Lyrics;
import com.yue.music.model.MusicTrack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端歌词管理：按当前播放的歌自动拉取歌词，并对外提供「此刻该显示哪一行」。
 *
 * <p>要点：
 * <ul>
 *   <li>歌词在后台线程拉取，绝不在渲染线程发网络请求</li>
 *   <li>按「平台:id」缓存，同一首歌只拉一次</li>
 *   <li>播放进度用 {@link ClientAudio#positionSeconds()}，与服务端广播对齐</li>
 * </ul>
 */
public final class LyricsClient {

	private static final LyricsClient INSTANCE = new LyricsClient();

	public static LyricsClient get() {
		return INSTANCE;
	}

	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<String, Lyrics> cache = new ConcurrentHashMap<>();

	/** 当前显示的歌词归属哪首歌。 */
	private volatile String currentKey = "";
	private volatile Lyrics current = null;
	private volatile boolean loading;
	private volatile int lastIndex = -2;

	/** 正在加载的 key，避免同时发起多次。 */
	private volatile String loadingKey = "";

	private LyricsClient() {
	}

	public Lyrics current() {
		return current;
	}

	public boolean loading() {
		return loading;
	}

	/** 手动清空缓存（设置页「重新加载歌词」用）。 */
	public void clearCache() {
		cache.clear();
		currentKey = "";
		current = null;
		lastIndex = -2;
	}

	/** 每 tick 调用：换歌就拉歌词。 */
	public void tick() {
		MusicTrack track = ClientAudio.get().currentTrack();
		if (track == null) {
			currentKey = "";
			current = null;
			loading = false;
			loadingKey = "";
			return;
		}
		String key = track.source() + ":" + track.id();
		if (key.equals(currentKey)) return;

		// 换歌
		currentKey = key;
		lastIndex = -2;
		Lyrics cached = cache.get(key);
		if (cached != null) {
			current = cached;
			loading = false;
			return;
		}
		current = null;
		loading = true;
		if (key.equals(loadingKey)) return;
		loadingKey = key;
		fetch(track, key);
	}

	private void fetch(MusicTrack track, String key) {
		workers.submit(() -> {
			try {
				Lyrics result = MusicApi.lyrics(track);
				cache.put(key, result);
				// 只有仍然是当前这首歌才显示
				if (key.equals(currentKey)) {
					current = result;
					loading = false;
				}
			} catch (Exception e) {
				YueMusic.LOGGER.debug("[YueMusic] 歌词获取异常: {}", e.toString());
				Lyrics err = Lyrics.empty(key, "歌词获取失败");
				cache.put(key, err);
				if (key.equals(currentKey)) {
					current = err;
					loading = false;
				}
			} finally {
				if (key.equals(loadingKey)) loadingKey = "";
			}
		});
	}

	/** 当前时间点对应的歌词行下标；-1 = 还没到第一句。 */
	public int currentLineIndex() {
		Lyrics lyr = current;
		if (lyr == null || lyr.lines().isEmpty()) return -1;
		int idx = lyr.indexAt(ClientAudio.get().displayPositionSeconds());
		lastIndex = idx;
		return idx;
	}

	/** 上一句（用于在 HUD 上多显示一行上下文）。 */
	public Lyrics.Line lineAt(int index) {
		Lyrics lyr = current;
		if (lyr == null || index < 0 || index >= lyr.lines().size()) return null;
		return lyr.lines().get(index);
	}
}
