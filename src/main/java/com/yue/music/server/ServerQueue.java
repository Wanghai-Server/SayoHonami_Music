package com.yue.music.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.YueMusic;
import com.yue.music.config.MusicConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端点歌队列 + 播放状态。
 *
 * <p>服务端<b>不联网</b>：歌曲信息和直链都由点歌的客户端解析好之后报上来，
 * 服务端只负责排队、广播给所有人、保持进度一致。这样服务器不会被音乐平台风控，也不承担音频流量。
 */
public final class ServerQueue {

	/**
	 * 默认房间的队列（兼容旧调用）。
	 * <p>每个房间都有自己独立的 ServerQueue 实例，见 {@link RoomManager}。
	 */
	public static ServerQueue get() {
		return RoomManager.get().queueFor(RoomManager.DEFAULT_ROOM);
	}

	private final List<QueueEntry> entries = new ArrayList<>();
	private int currentIndex = -1;
	private boolean playing;

	/**
	 * 播放模式（三选一）。
	 *
	 * <p>{@link PlayMode#SEQUENTIAL} 是默认：一首接一首，放完最后一首就停。
	 */
	public enum PlayMode {
		/** 顺序播放：放完最后一首就停。 */
		SEQUENTIAL,
		/** 单曲循环：一直重复当前这首。 */
		SINGLE,
		/** 随机播放：随机跳，一轮之内不重复。 */
		SHUFFLE;

		/** 中文名（给界面/提示用）。 */
		public String cn() {
			return switch (this) {
				case SEQUENTIAL -> "顺序播放";
				case SINGLE -> "单曲循环";
				case SHUFFLE -> "随机播放";
			};
		}

		/** 轮换到下一个模式（点一下按钮就换一种）。 */
		public PlayMode next() {
			return switch (this) {
				case SEQUENTIAL -> SINGLE;
				case SINGLE -> SHUFFLE;
				case SHUFFLE -> SEQUENTIAL;
			};
		}

		public static PlayMode of(String s) {
			if (s == null) return SEQUENTIAL;
			try {
				return valueOf(s);
			} catch (Exception e) {
				return SEQUENTIAL;
			}
		}
	}

	private PlayMode playMode = PlayMode.SEQUENTIAL;
	/** 随机播放时，本轮已经放过的下标（放满一轮后清空重来）。 */
	private final java.util.Set<Integer> shufflePlayed = new java.util.LinkedHashSet<>();
	/**
	 * 当前这一段「连续播放」的开始时刻（**墙钟**，发给客户端做对齐用）。
	 *
	 * <p>注意它只表示**正在播的这一段**，暂停时间不计入。
	 */
	private long startEpochMs;

	/**
	 * 计算「这一段播了多久」用的**单调时钟**起点。
	 *
	 * <p><b>为什么不能直接用 {@link #startEpochMs} 算</b>：
	 * 那是墙钟（{@code System.currentTimeMillis()}），而墙钟会跳 ——
	 * NTP 校时、手动改时间、定时同步都会让它前后跳。
	 * 一旦往回跳，{@code now - startEpochMs} 就会算出一个**巨大的值**。
	 *
	 * <p>日志实证：客户端收到「服务端已播 <b>167549 秒</b>」（46.5 小时），
	 * 于是从 167599 秒处起播 → 把整首歌的数据全丢光 → <b>响两句就没声了</b>。
	 *
	 * <p>{@code System.nanoTime()} 是单调的，不受墙钟调整影响。
	 */
	private long playAnchorNanos;
	/**
	 * 本次连续播放开始前，这首歌已经播过的毫秒数。
	 *
	 * <p><b>为什么必须有这个字段</b>：进度曾经用
	 * {@code (now - startEpochMs)} 直接算，而暂停时不更新 {@code startEpochMs}，
	 * 于是「暂停 2 分钟再恢复」会让进度凭空多出 2 分钟 ——
	 * 服务端 tick 一看 {@code position >= 时长+3} 就以为放完了，
	 * 表现就是<b>暂停过一会儿再恢复会突然跳到下一首（提前切歌）</b>。
	 *
	 * <p>现在进度 = {@code positionBeforePauseMs} + （正在播 ? now - startEpochMs : 0），
	 * <b>暂停多久都不会让进度前进</b>。
	 */
	private long positionBeforePauseMs;
	private int volume = 70;

	/** 所属房间名（用于存档文件名与日志）。 */
	private final String room;

	/**
	 * 「某首歌没有直链时，去把它解析出来」的回调。
	 *
	 * <p>由 {@link MusicServer} 在启动时注册。歌单导入进来的歌 url 是空的，
	 * 播到那首时通过这个回调让服务端去解析（解析是联网操作，不能在这边直接做）。
	 *
	 * @param queue 哪个队列
	 * @param index 第几首
	 * @param track 歌
	 */
	public interface UrlResolver {
		void resolve(ServerQueue queue, int index, com.yue.music.model.MusicTrack track);
	}

	private static volatile UrlResolver urlResolver;

	/** 注册解析回调（服务端启动时调用一次）。 */
	public static void setUrlResolver(UrlResolver resolver) {
		urlResolver = resolver;
	}

	/**
	 * 把某一首的直链补上（解析完成后由服务端主线程调用）。
	 *
	 * @return 是否真的补上了（下标非法/已有直链返回 false）
	 */
	public synchronized boolean fillUrl(int index, String url) {
		if (index < 0 || index >= entries.size()) return false;
		if (url == null || url.isBlank()) return false;
		QueueEntry old = entries.get(index);
		if (old.url() != null && !old.url().isBlank()) return false;   // 已有直链，不覆盖
		entries.set(index, new QueueEntry(old.track(), url, old.duration(), old.addedBy()));
		return true;
	}

	/** 队列原本是空的、刚导入歌单后立刻开始播第一首。 */
	public synchronized void nextFromEmpty() {
		if (entries.isEmpty()) return;
		currentIndex = 0;
		startCurrent();
	}

	ServerQueue(String room) {
		this.room = room == null ? RoomManager.DEFAULT_ROOM : room;
	}
	public String room() {
		return room;
	}

	// ------------------------------------------------------------ 查询

	public synchronized List<QueueEntry> entries() {
		return List.copyOf(entries);
	}

	public synchronized int currentIndex() {
		return currentIndex;
	}

	public synchronized QueueEntry current() {
		if (currentIndex < 0 || currentIndex >= entries.size()) return null;
		return entries.get(currentIndex);
	}

	public synchronized boolean playing() {
		return playing;
	}

	public synchronized PlayMode playMode() {
		return playMode;
	}

	public synchronized long startEpochMs() {
		return startEpochMs;
	}

	public synchronized int volume() {
		return volume;
	}

	/** 当前播放位置的估算秒数（服务端视角）。 */
	public synchronized int positionSeconds() {
		if (currentIndex < 0) return 0;
		return (int) Math.max(0, positionMs() / 1000L);
	}

	/**
	 * 当前播放进度（毫秒）。**暂停期间不会前进。**
	 *
	 * <p>进度 = 暂停前已播时长 + （正在播 ? 本段已播时长 : 0）
	 */
	/** 本房间当前生效的队列上限。 */
	public synchronized int limit() {
		return MusicConfig.get().limitOf(room);
	}

	public synchronized long positionMs() {
		if (currentIndex < 0) return 0;
		if (!playing) return Math.max(0, positionBeforePauseMs);
		// 用**单调时钟**算这一段播了多久（墙钟会被校时/改时间弄跳，
		// 一旦往回跳就会算出「已播 40 多小时」这种荒唐值，客户端据此起播会整个没声）
		long elapsed = (System.nanoTime() - playAnchorNanos) / 1_000_000L;
		if (elapsed < 0) elapsed = 0;                       // 理论上不会，保险
		return Math.max(0, positionBeforePauseMs + elapsed);
	}

	/**
	 * 当前这首先播了多少（毫秒）——**带合理性上限**。
	 *
	 * <p>对外广播进度时用这个：万一有任何异常让进度算出荒唐值，
	 * 也会被夹在「这首歌的实际时长 + 一条容差」之内，
	 * 不会再出现「服务端已播 46 小时」把客户端搞哑。
	 */
	public synchronized long safePositionMs() {
		long pos = positionMs();
		QueueEntry cur = current();
		if (cur == null) return 0;
		int dur = cur.duration() > 0 ? cur.duration() : cur.track().duration();
		if (dur <= 0) return Math.min(pos, 30 * 60_000L);   // 时长未知：最多认 30 分钟
		long cap = (dur + 30L) * 1000L;                     // 时长 + 30 秒余量
		return Math.min(pos, cap);
	}

	// ------------------------------------------------------------ 修改

	/** 点歌。返回提示文本。 */
	public synchronized String add(QueueEntry entry) {
		// 用「本房间」自己的上限（可用 /SyMus limit <房间> <数字> 单独设置）
		int limit = MusicConfig.get().limitOf(room);
		if (entries.size() >= limit) {
			return "队列已满（上限 " + limit + " 首）";
		}
		entries.add(entry);
		if (currentIndex < 0) {
			startCurrent();
		}
		autosave();   // 队列变了立刻落盘（防止房间切换/卸载时丢歌）
		return entry.addedBy() + " 点了《" + entry.track().title() + "》";
	}

	private void startCurrent() {
		if (entries.isEmpty()) {
			currentIndex = -1;
			playing = false;
			return;
		}
		if (currentIndex < 0 || currentIndex >= entries.size()) currentIndex = 0;
		positionBeforePauseMs = 0;   // 换歌：进度从 0 开始
		startEpochMs = System.currentTimeMillis();   // 墙钟（给客户端做对齐）
		playAnchorNanos = System.nanoTime();         // 单调时钟（算本段播了多久）
		playing = true;
		ensureUrl(currentIndex);
	}

	/**
	 * 如果这一首还没有直链（歌单导入进来的），请求服务端去解析。
	 *
	 * <p>只对「当前正在播」的那首做，避免导入 1000 首时一次性解析 1000 次。
	 */
	private void ensureUrl(int index) {
		if (index < 0 || index >= entries.size()) return;
		QueueEntry e = entries.get(index);
		if (e.url() != null && !e.url().isBlank()) return;
		UrlResolver r = urlResolver;
		if (r == null) return;
		try {
			r.resolve(this, index, e.track());
		} catch (Exception ex) {
			YueMusic.LOGGER.warn("[YueMusic] 请求解析直链失败: {}", ex.toString());
		}
	}

	/** 下一首。返回是否还有歌。 */
	public synchronized boolean next() {
		if (entries.isEmpty()) {
			currentIndex = -1;
			playing = false;
			return false;
		}
		// 随机播放：交给专门的逻辑（一轮内不重复）
		if (playMode == PlayMode.SHUFFLE) {
			return nextShuffle();
		}
		if (currentIndex + 1 >= entries.size()) {
			// 顺序播放：放完最后一首就停在结尾，等新歌
			currentIndex = entries.size() - 1;
			playing = false;
			autosave();
			return false;
		}
		currentIndex++;
		startCurrent();
		autosave();   // 记住播到第几首（重连/重启后进度不丢）
		return true;
	}

	/**
	 * 随机播放的「下一首」：从**本轮没放过的**歌里随机挑一首。
	 *
	 * <p>放满一轮（所有歌都放过）就清空记录重新开始 ——
	 * 这样不会重复，也不会像纯随机那样老是撞到同一首。
	 */
	private boolean nextShuffle() {
		int n = entries.size();
		if (n == 0) return false;
		// 当前这首也算「放过」
		if (currentIndex >= 0) shufflePlayed.add(currentIndex);

		if (shufflePlayed.size() >= n) {
			// 一轮放完：除了当前这首，其它重新可挑（避免立刻又放同一首）
			int cur = currentIndex;
			shufflePlayed.clear();
			if (cur >= 0) shufflePlayed.add(cur);
		}
		java.util.List<Integer> candidates = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (!shufflePlayed.contains(i)) candidates.add(i);
		}
		if (candidates.isEmpty()) return false;
		int pick = candidates.get(java.util.concurrent.ThreadLocalRandom.current()
				.nextInt(candidates.size()));
		currentIndex = pick;
		shufflePlayed.add(pick);
		startCurrent();
		autosave();
		return true;
	}

	public synchronized boolean prev() {
		if (entries.isEmpty()) return false;
		currentIndex = Math.max(0, currentIndex - 1);
		startCurrent();
		autosave();
		return true;
	}

	/** 一首自然播完后的处理：单曲循环就重头，顺序/随机都交给 {@link #next()}。 */
	public synchronized void onTrackFinished() {
		// 单曲循环：把这首从头再放一遍
		if (playMode == PlayMode.SINGLE && currentIndex >= 0 && currentIndex < entries.size()) {
			positionBeforePauseMs = 0;   // 循环重播：进度归零
			startEpochMs = System.currentTimeMillis();
			playAnchorNanos = System.nanoTime();
			playing = true;
			return;
		}
		next();
	}

	public synchronized void setPlaying(boolean value) {
		if (value) {
			if (currentIndex < 0 && !entries.isEmpty()) {
				startCurrent();
			} else if (currentIndex >= 0) {
				// 从暂停恢复：把当前进度固定下来当作「已播时长」，
				// 再从此刻重新开始计时 —— 这样**暂停多久都不会让进度前进**。
				// （以前是 startEpochMs = now - positionSeconds()*1000，
				//   而 playing=false 时 positionSeconds() 恒为 0，
				//   暂停时间就被算进歌里了 → 提前切歌）
				long now = System.currentTimeMillis();
				positionBeforePauseMs = positionMs();
				startEpochMs = now;
				playAnchorNanos = System.nanoTime();
				playing = true;
			}
		} else {
			// 暂停：先固定进度，再置为暂停
			positionBeforePauseMs = positionMs();
			playing = false;
		}
	}

	public synchronized void seek(int seconds) {
		if (currentIndex < 0) return;
		int v = Math.max(0, seconds);
		positionBeforePauseMs = v * 1000L;
		startEpochMs = System.currentTimeMillis();
		playAnchorNanos = System.nanoTime();
	}

	/**
	 * 轮换播放模式：顺序 → 单曲 → 随机 → 顺序。
	 *
	 * @return 切换后的模式
	 */
	public synchronized PlayMode cyclePlayMode() {
		playMode = playMode.next();
		// 切到随机播放时，把当前这首标成「已放」，避免立刻又抽到它
		if (playMode == PlayMode.SHUFFLE) {
			shufflePlayed.clear();
			if (currentIndex >= 0) shufflePlayed.add(currentIndex);
		}
		autosave();
		return playMode;
	}

	public synchronized void clear() {
		entries.clear();
		currentIndex = -1;
		playing = false;
		autosave();   // 清空也要落盘，否则重启后"幽灵队列"又回来了
	}

	/**
	 * 插播：直接跳到某一首。
	 *
	 * @param wireIndex 客户端传来的下标（1 起，0 表示「继续播放」这个控制语义）
	 */
	public synchronized void playNow(int wireIndex) {
		int index = wireIndex - 1;
		if (index < 0 || index >= entries.size()) return;
		currentIndex = index;
		startCurrent();
		autosave();
	}

	/**
	 * 从队列里删掉某一首。
	 *
	 * @param wireIndex 客户端传来的下标（1 起）
	 * @return 被删掉的曲目名；下标非法时返回 null
	 */
	public synchronized String remove(int wireIndex) {
		int index = wireIndex - 1;
		if (index < 0 || index >= entries.size()) return null;
		QueueEntry removed = entries.remove(index);
		if (entries.isEmpty()) {
			currentIndex = -1;
			playing = false;
		} else if (index < currentIndex) {
			// 删的是当前曲目之前的歌 → 当前下标前移
			currentIndex--;
		} else if (index == currentIndex) {
			// 删的就是正在播的 → 原地接着播（后面的歌顶上来了），越界则退回最后一首
			if (currentIndex >= entries.size()) currentIndex = entries.size() - 1;
			startCurrent();
		}
		autosave();   // 队列变了立刻落盘
		return removed.track().title();
	}

	/** 设置统一音量，并写进配置（重启后仍在）。 */
	public synchronized void setVolume(int v) {
		volume = Math.max(0, Math.min(100, v));
		MusicConfig cfg = MusicConfig.get();
		cfg.volume = volume / 100f;
		cfg.save();
	}

	// ------------------------------------------------------------ 持久化

	private Path file() {
		// 默认房间仍用老文件名，保证升级后旧队列不丢；其它房间各自一份存档
		String name = RoomManager.DEFAULT_ROOM.equals(room)
				? "yue-music-queue.json"
				: "yue-music-queue-" + room + ".json";
		return FabricLoader.getInstance().getGameDir().resolve(name);
	}

	public synchronized void save() {
		try {
			JsonObject root = new JsonObject();
			JsonArray arr = new JsonArray();
			for (QueueEntry e : entries) arr.add(e.toJson());
			root.add("entries", arr);
			root.addProperty("currentIndex", currentIndex);
			root.addProperty("playMode", playMode.name());
			root.addProperty("volume", volume);
			Files.writeString(file(), root.toString());
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 队列保存失败: {}", e.toString());
		}
	}

	/**
	 * 队列内容变动时立刻写盘（主动防丢）。
	 *
	 * <p><b>为什么需要它</b>：以前只靠「房间空了回收时保存」+「服务器关闭时保存」，
	 * 于是「20 首导入的歌在房间切换后消失」这类问题就出现了 ——
	 * 队列的存活依赖一个很脆弱的时机假设。
	 *
	 * <p>现在每次「加歌 / 删歌 / 切歌 / 清空」都主动落盘，
	 * 任何时刻拔电、切房间、房间被卸载，内容都不会丢。
	 * 写入失败只记日志、不影响功能。
	 */
	public synchronized void autosave() {
		if (entries.isEmpty() && currentIndex < 0) return;   // 空队列不必写盘
		save();
	}

	public synchronized void load() {
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			entries.clear();
			if (root.has("entries") && root.get("entries").isJsonArray()) {
				for (var el : root.getAsJsonArray("entries")) {
					if (el.isJsonObject()) {
						QueueEntry e = QueueEntry.fromJson(el.getAsJsonObject());
						if (e.track() != null) entries.add(e);
					}
				}
			}
			currentIndex = root.has("currentIndex") ? root.get("currentIndex").getAsInt() : -1;
			// 兼容旧存档：以前只有一个 loop 布尔值，true 视为顺序循环（现在是顺序播放）
			playMode = root.has("playMode")
					? PlayMode.of(root.get("playMode").getAsString())
					: (root.has("loop") && root.get("loop").getAsBoolean()
							? PlayMode.SEQUENTIAL : PlayMode.SEQUENTIAL);
			volume = root.has("volume") ? root.get("volume").getAsInt() : 70;
			playing = false;
			YueMusic.LOGGER.info("[YueMusic] 已载入房间「{}」的队列：{} 首", room, entries.size());
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 队列读取失败: {}", e.toString());
		}
	}

	// ------------------------------------------------------------ 校验

	/**
	 * 只接受来自已知音乐平台/自建服务的音频地址。
	 * <p>防止有人乱塞一个 URL 让全服去下载。
	 */
	public static boolean isTrustedAudioUrl(String url) {
		if (url == null || url.isBlank()) return false;
		String u = url.toLowerCase();
		if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
		for (String host : new String[]{
				"music.126.net", "126.net", "music.163.com",
				"qqmusic.qq.com", "qq.com", "tc.qq.com", "stream.qqmusic",
				"kuwo.cn", "migu.cn"
		}) {
			if (u.contains(host)) return true;
		}
		// 自建 API 服务地址也放行
		String custom = MusicConfig.get().customApiBase;
		if (custom != null && !custom.isBlank() && u.startsWith(custom.toLowerCase())) return true;
		// 局域网自建（开发调试）
		return u.contains("127.0.0.1") || u.contains("localhost");
	}
}
