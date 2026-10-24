package com.yue.music.client.audio;

import com.yue.music.YueMusic;
import com.yue.music.api.MusicApi;
import com.yue.music.audio.AudioPlayer;
import com.yue.music.config.MusicConfig;
import com.yue.music.model.MusicSource;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;
import com.yue.music.net.MusicNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 客户端点歌台状态机。
 *
 * <p>数据流：
 * <ol>
 *   <li>UI 搜索 → {@link MusicApi} 直接请求 QQ/网易云</li>
 *   <li>点歌 → 后台线程解析直链 → 发给服务端 {@code AddSongC2S}</li>
 *   <li>服务端广播 {@code QueueS2C} → 这里更新队列</li>
 *   <li>当前歌变化 → 本地下载并播放（所有客户端同步）</li>
 * </ol>
 *
 * <p>只有队列状态走服务端；音频永远是各自客户端下载，服务器不承担流量。
 */
public final class ClientAudio {

	private static final ClientAudio INSTANCE = new ClientAudio();

	public static ClientAudio get() {
		return INSTANCE;
	}

	private final AudioPlayer player = new AudioPlayer();
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	// 服务端同步过来的状态
	private volatile List<MusicTrack> queue = List.of();
	private volatile int currentIndex = -1;
	private volatile boolean serverPlaying;
	private volatile long startEpochMs;
	/** 服务端广播的「已播毫秒」（暂停期间不前进，用作进度基准）。 */
	private volatile long serverPositionMs;
	/**
	 * 收到这次服务端状态时的 {@link System#nanoTime()}。
	 *
	 * <p>只用来量「收到之后过了多久」—— <b>单调时钟</b>，
	 * 不会被 NTP 校时 / 手动改时间影响（以前用墙钟，算出过「已播 46 小时」）。
	 */
	private volatile long nanosAtState;
	/**
	 * 服务端广播过来的「本房间实际生效的队列上限」。
	 *
	 * <p>为什么要这份：队列上限是服务端判定的，而配置两端各读各的文件。
	 * UI 显示服务端的值才不会误导玩家；0 表示「没连服务端」（单人存档，
	 * 这时用本地配置的值）。
	 */
	private volatile int serverLimit;
	/** 服务端是否已登录（登录后客户端点歌无需自己解析直链）。 */
	private volatile boolean serverLoggedIn;
	/** 播放模式（服务端广播）：SEQUENTIAL / SINGLE / SHUFFLE。 */
	private volatile String playMode = "SEQUENTIAL";
	/** 本机音量（纯本地设置，不受服务端广播影响）。 */
	private volatile int localVolume = 70;
	/**
	 * 上一次「服务端广播过来的音量」。
	 *
	 * <p>用来判断服务端音量有没有**变化过** —— 变了（例如服务器重启回到默认）
	 * 才采用；没变就保持本地设置，避免「一个人调音量全服跟着变」。
	 * -1 表示还没收到过。
	 */
	private volatile int lastServerVolume = -1;
	/**
	 * 正在等「服务端下发直链」。
	 *
	 * <p>切换房间 / 歌单导入后，服务端要 1~3 秒才把这一首的直链解析出来。
	 * 这期间本机也解析不了（没登录）—— 以前会直接 skipBroken 跳过，
	 * 于是整张歌单被挨个跳过去，表现是「切房间后没声音，要手动刷新」。
	 * 现在标记成「等直链」，由 tick() 稍后重试。
	 */
	private volatile boolean waitingServerUrl;
	private volatile long waitingServerSince;
	private volatile String lastNotice = "";
	private volatile long noticeAt;
	/** 本地刚改过音量的时间戳：短时间内不采纳服务端广播的旧值，避免按钮「回弹」。 */

	/** 记录每首歌解析出来的直链（避免重复解析）。 */
	private final Map<String, String> urlCache = new ConcurrentHashMap<>();
	/** 正在播放的是哪首歌（用 id+source 判断是否要换歌）。 */
	private volatile String playingKey = "";
	private volatile String loadingKey = "";
	/** 起播开始时间（用于检测起播卡死并自动重试）。 */
	private volatile long loadingStartedAt;
	/**
	 * 服务端随队列下发的直链（服主账号解析的）。
	 * <p>有它就**直接用**，本机不需要登录、也不需要再解析一次。
	 */
	private volatile String pushedUrl;
	/**
	 * {@link #pushedUrl} 属于哪首歌（{@code source:id}）。
	 * <p>必须校验：服务端解析新歌要 1~3 秒，这期间 {@code pushedUrl} 还停在上一首，
	 * 不校验就会「拿上一首的直链去播新歌」→ 插播后不播放。
	 */
	private volatile String pushedUrlKey;
	/** 当前所在房间（服务端广播过来）。 */
	private volatile String room = "default";
	private volatile String lastError = "";
	private volatile String status = "";

	private ClientAudio() {
		// 启动时用配置里的音量（服务端还没广播之前先按本地设置走）
		localVolume = Math.round(MusicConfig.get().volume * 100f);
	}

	public AudioPlayer player() {
		return player;
	}

	public List<MusicTrack> queue() {
		return queue;
	}

	public int currentIndex() {
		return currentIndex;
	}

	public boolean paused() {
		return !serverPlaying;
	}

	/** 播放模式中文名（播放页那个按钮显示用）。 */
	public String playModeCn() {
		return switch (playMode == null ? "" : playMode) {
			case "SINGLE" -> "单曲循环";
			case "SHUFFLE" -> "随机播放";
			default -> "顺序播放";
		};
	}

	public int volume() {
		return localVolume;
	}

	public String notice() {
		return lastNotice;
	}

	public long noticeAt() {
		return noticeAt;
	}

	public String error() {
		return lastError;
	}

	public String status() {
		return status;
	}

	public MusicTrack currentTrack() {
		List<MusicTrack> q = queue;
		int i = currentIndex;
		return (i >= 0 && i < q.size()) ? q.get(i) : null;
	}

	/** 当前播放位置（秒）：优先用音频引擎，其次按服务端起始时间推算。 */
	public int positionSeconds() {
		if (player.active()) {
			int p = player.positionSeconds();
			if (p > 0) return p;
		}
		if (startEpochMs > 0) {
			return (int) Math.max(0, (System.currentTimeMillis() - startEpochMs) / 1000);
		}
		return 0;
	}

	/** 服务端广播到达。**必须在客户端主线程调用**。 */
	public void onServerState(MusicNetworking.QueueS2C payload) {
		List<MusicTrack> newQueue = payload.tracks();
		int newIndex = payload.currentIndex();
		boolean newPlaying = payload.playing();
		boolean trackChanged = newIndex != currentIndex
				|| newQueue.size() != queue.size()
				|| (newIndex >= 0 && newIndex < newQueue.size() && !sameTrack(
				newQueue.get(newIndex), queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size()
						? null : queue.get(currentIndex)));

		queue = newQueue;
		currentIndex = newIndex;
		serverPlaying = newPlaying;
		startEpochMs = payload.startEpochMs();
		// 服务端的「已播毫秒」：暂停过也不会前进，用它当基准才不会算错进度
		serverPositionMs = payload.positionMs();
		nanosAtState = System.nanoTime();
		serverLimit = payload.serverLimit();
		playMode = payload.playMode();
		// 服务端是否已登录：决定点歌时由谁解析直链（服务端登录了客户端就不用管登录）
		serverLoggedIn = payload.serverLoggedIn();
		// 房间名（服务端广播过来）：UI 显示「当前房间」
		String newRoom = payload.room() == null || payload.room().isBlank() ? "default" : payload.room();
		if (!newRoom.equals(room)) {
			YueMusic.LOGGER.info("[{}] 切换房间：{} → {}", YueMusic.MOD_NAME, room, newRoom);
			// 换房间等于换队列：必须停掉正在播的歌，否则会继续放上一个房间的
			player.stop();
			playingKey = "";
			loadingKey = "";
			urlCache.clear();
			waitingServerUrl = false;   // 换房间：上一首的「等直链」状态作废
			room = newRoom;
			// 让打开着的界面立刻更新「房间：xxx」按钮文字
			try {
				com.yue.music.client.ui.MusicScreen.refreshRoomButton();
			} catch (Throwable ignored) {
			}
		}
		// 服务端解析好的直链（可能为空：服务端没登录，或这首歌还没解析）
		// 同时记住它属于哪首歌：pushedUrl 是全局字段，不绑定 key 的话，
		// 服务端还在解析下一首时（1~3 秒）会拿上一首的直链去播新歌 → 插播不播放。
		pushedUrl = MusicNetworking.streamUrlAt(payload.tracksJson(), newIndex);
		MusicTrack curTrack = currentTrack();
		pushedUrlKey = (pushedUrl != null && curTrack != null)
				? curTrack.source() + ":" + curTrack.id() : null;

		// 决定性诊断：把「这首歌是谁、服务端开播多久、有没有下发直链」全打出来。
		// 「B 进服从头听」「切下一首没声音」都要靠这几行定位。
		if (trackChanged || pushedUrl != null) {
			YueMusic.LOGGER.info("[{}] 收到状态: index={} playing={} 已播={}s 直链={} 曲目={}",
					YueMusic.MOD_NAME, newIndex, newPlaying,
					startEpochMs <= 0 ? "?" : (System.currentTimeMillis() - startEpochMs) / 1000,
					pushedUrl == null ? "无" : "有",
					curTrack == null ? "(空)" : curTrack.display());
		}
		if (pushedUrl != null && pushedUrlKey != null) {
			urlCache.put(pushedUrlKey, pushedUrl);
			// 服务端把直链发下来了 → 结束「等直链」状态，立刻重试起播。
			// 这就是「切换房间后不用手动刷新也能听到声音」的关键一步。
			if (waitingServerUrl) {
				waitingServerUrl = false;
				loadingKey = "";
				YueMusic.LOGGER.info("[{}] 服务端直链已到，重试起播《{}》",
						YueMusic.MOD_NAME, curTrack == null ? "?" : curTrack.title());
				startCurrent();
			}
		}

		// 音量：服务端的值只在它**变化过**的时候套用一次。
		//
		// 为什么不能每次都覆盖：那会变成「一个人调音量、全服跟着变」。
		// 为什么又不能完全不看：服务器重启后队列音量会回到默认值，
		// 而播放页显示的是本地音量 —— 两边不一致，玩家看到的就是
		// 「音量被设成默认了，但界面显示的还是错的」。
		//
		// 折中：记住「上一次服务端给的值」，只有它变了才采用为新的本地音量。
		// 这样重启（70 → 你的值 或 你的值 → 70）能同步，
		// 而你自己点音量按钮时（服务端值没变）不会被它抢回去。
		int sv = payload.volume();
		if (sv >= 0 && sv <= 100 && sv != lastServerVolume) {
			lastServerVolume = sv;
			if (sv != localVolume) {
				YueMusic.LOGGER.info("[{}] 采用服务端音量 {}%（本地原本 {}%）",
						YueMusic.MOD_NAME, sv, localVolume);
				setVolume(sv);
			}
		}

		if (payload.notice() != null && !payload.notice().isBlank()) {
			String n = payload.notice();
			// 服务端解析失败 → 它让本机自己解析一次再提交（Cookie 过期时的降级路径）
			if (n.startsWith(MusicNetworking.NEED_CLIENT_RESOLVE)) {
				String wantId = n.substring(MusicNetworking.NEED_CLIENT_RESOLVE.length());
				MusicTrack t = currentTrack();
				if (t != null && t.id().equals(wantId)) {
					YueMusic.LOGGER.info("[{}] 服务端解析失败，本机重试解析《{}》",
							YueMusic.MOD_NAME, t.title());
					resolveLocallyOnce(t, msg -> {
					});
				}
			} else {
				lastNotice = n;
				noticeAt = System.currentTimeMillis();
			}
		}

		if (trackChanged) {
			startCurrent();
		} else if (newPlaying && !player.playing() && player.active()) {
			player.resume();
		} else if (!newPlaying && player.playing()) {
			player.pause();
		}
	}

	private static boolean sameTrack(MusicTrack a, MusicTrack b) {
		if (a == null || b == null) return a == b;
		return a.source().equals(b.source()) && a.id().equals(b.id());
	}

	/**
	 * 给 UI 用的「当前进度秒数」。
	 *
	 * <p><b>为什么要单独一个方法</b>：本地播放器在「下载中 / 缓冲 / 起播失败」时
	 * {@code positionSeconds()} 会返回 0 或来回跳，进度条就会鬼畜。
	 * 这里做了兜底：只要本地还没真正开始出声，就按**服务端进度**显示——
	 * 时间轴始终跟着服务端走，视觉上永远平滑。
	 */
	public int displayPositionSeconds() {
		MusicTrack track = currentTrack();
		int dur = track == null ? 0 : track.duration();

		// 本地确实在播 → 用本地（更精确，能反映暂停）
		if (serverPlaying && player.playing() && player.state() == AudioPlayer.State.PLAYING) {
			int local = player.positionSeconds();
			if (dur <= 0) return local;
			return Math.min(local, dur);
		}
		// 暂停中：不回退，用本地位置
		if (serverPlaying && player.state() == AudioPlayer.State.PAUSED) {
			return player.positionSeconds();
		}
		// 还没出声（下载/缓冲/失败）→ 用服务端进度兜底，避免跳到 0
		if (serverPlaying && startEpochMs > 0) {
			int expected = (int) Math.max(0, (System.currentTimeMillis() - startEpochMs) / 1000);
			return dur > 0 ? Math.min(expected, dur) : expected;
		}
		return 0;
	}

	/** 播放当前这首歌：先拿直链，再丢给播放器。 */
	private void startCurrent() {
		MusicTrack track = currentTrack();
		if (track == null) {
			player.stop();
			playingKey = "";
			return;
		}
		String key = track.source() + ":" + track.id();
		if (key.equals(playingKey) || key.equals(loadingKey)) return;
		waitingServerUrl = false;   // 换歌了，上一首的「等直链」状态作废

		loadingKey = key;
		loadingStartedAt = System.currentTimeMillis();
		lastError = "";
		status = "正在获取播放地址…";

		workers.submit(() -> {
			try {
				String url = urlCache.get(key);
				if (url == null) {
					// 优先用**服务端广播过来的直链**：那是服主账号解析的，
					// 本机不需要登录就能直接下载（这才是「服务端登录」的意义）。
					//
					// ⚠ 必须校验 key：pushedUrl 是全局字段，里面可能装着**上一首**的直链
					//（服务端解析新歌要 1~3 秒，这期间广播里没有新直链）。
					// 不校验的话会拿着上一首的地址去播新歌 —— 表现就是「插播后不播放」。
					String pushed = pushedUrlKey != null && pushedUrlKey.equals(key) ? pushedUrl : null;
					if (pushed != null && !pushed.isBlank()) {
						url = pushed;
						urlCache.put(key, url);
						YueMusic.LOGGER.info("[{}] 使用服务端下发的直链播放《{}》",
								YueMusic.MOD_NAME, track.title());
					} else if (pushedUrl != null) {
						YueMusic.LOGGER.info("[{}] 服务端直链属于别的歌（{}），《{}》改为本机解析",
								YueMusic.MOD_NAME, pushedUrlKey, track.title());
					}
				}
				if (url == null) {
					PlayUrl resolved = MusicApi.resolve(track, true);
					if (!resolved.ok()) {
						lastError = resolved.error() == null ? "无法获取播放地址" : resolved.error();
						YueMusic.LOGGER.warn("[{}] 本机解析失败（服务端也没给直链）：{} → {}",
								YueMusic.MOD_NAME, track.display(), lastError);
						// ⚠ 关键：如果服务端**登录过**，那它正在后台解析这首歌
						// （歌单导入的歌 url 是空的，播到才解析，要 1~3 秒）。
						// 这时候直接 skipBroken 会把整张歌单挨个跳过去 ——
						// 表现就是「切换房间后听不到声音，要手动刷新才行」。
						// 所以这里**等**，让 tick() 稍后用服务端下发的直链重试。
						if (serverLoggedIn) {
							status = "等服务器解析播放地址…";
							loadingKey = "";
							waitingServerUrl = true;
							waitingServerSince = System.currentTimeMillis();
							YueMusic.LOGGER.info("[{}] 《{}》本机解析不了，等服务端下发直链（不跳过）",
									YueMusic.MOD_NAME, track.title());
							return;
						}
						// 服务端也没登录 → 这首歌确实放不了，告诉服务端跳过
						skipBroken(track);
						return;
					}
					url = resolved.url();
					urlCache.put(key, url);
				}
				final String finalUrl = url;
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					if (key.equals(loadingKey) || key.equals(playingKey)) {
						// 按服务端进度对齐：服务端已经播了 expectedSeconds 秒，
						// 客户端就从那里开始，否则每台机器都从 0 秒起 → 听着不同步
						int offset = expectedSeconds();
						// ⚠ 关键校验：偏移**绝不能超过这首歌的实际时长**。
						//
						// 服务端的进度是用时间戳算的，只要两边时钟有任何不一致
						// （NTP 校时 / 改时间 / 时钟漂移），就会算出荒唐的值。
						// 日志实证：客户端收到「服务端已播 167549 秒」（46.5 小时），
						// 于是从 167599 秒处起播 → AudioPlayer 会把整首歌的数据全丢光
						// → 玩家听到「响两句就没声了」，而且那个房间一直没声音。
						//
						// 这里夹一道：超出时长就当作时钟异常，从 0 秒开始播
						// （宁可不同步，也不要整个没声音）。
						int dur = track.duration();
						if (dur > 0 && offset > dur + 5) {
							YueMusic.LOGGER.warn("[{}] 服务端进度 {}s 超过《{}》时长 {}s，"
											+ "判定为时钟异常，改为从 0 秒起播",
									YueMusic.MOD_NAME, offset, track.title(), dur);
							offset = 0;
						} else if (offset < 0) {
							offset = 0;
						}
						YueMusic.LOGGER.info("[{}] 起播《{}》 offset={}s（服务端已播 {}s）url={}",
								YueMusic.MOD_NAME, track.title(), offset, offset,
								finalUrl.length() > 60 ? finalUrl.substring(0, 60) + "..." : finalUrl);
						player.play(finalUrl, track.duration(), offset);
						// 注意：这里**不**把 playingKey 设成新歌。
						// stop() 之后 state 立刻是 IDLE、进度是 0，直到播放线程真的
						// 开始写 PCM 才算「在播」。把 playingKey 提前设上的话，
						// tick() 会看到「playingKey 匹配 + IDLE + 有进度」的组合，
						// 误判成「播完了」从而跳下一首（就是「点播放跳到下一首」的 bug）。
						// 所以交给 tick() 在检测到真的出声后再确认。
						loadingKey = key;
						status = "";
					} else {
						YueMusic.LOGGER.info("[{}] 起播被取消（key 已变）: {}", YueMusic.MOD_NAME, track.title());
					}
				});
			} catch (Exception e) {
				YueMusic.LOGGER.warn("[YueMusic] 解析播放地址失败: {}", e.toString());
				lastError = "解析失败：" + e.getMessage();
				loadingKey = "";
				status = "";
			}
		});
	}

	/** 这首歌所有平台都放不了，请求服务端切下一首。 */
	private void skipBroken(MusicTrack track) {
		if (lastNotice.isBlank()) {
			lastNotice = "《" + track.title() + "》暂时放不了，已跳过";
			noticeAt = System.currentTimeMillis();
		}
		sendControl(MusicNetworking.Action.NEXT, 0);
	}

	/** 漂移检查的时间戳（每 5 秒一次）。 */
	private volatile long lastDriftCheck;

	/**
	 * 每首歌的起播失败次数。
	 * <p>用来防止「拿着同一根坏直链无限重试」：失败后先清 urlCache 重新解析，
	 * 连续失败 3 次就把这首跳过（否则会永远卡在插播不播放）。
	 */
	private final Map<String, Integer> startFailures = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 服务端视角的当前进度（秒）——起播位置以它为准。
	 *
	 * <p>以前这里会减 1 秒当「缓冲补偿」，结果是中途进服的玩家**比大家晚 1 秒**，
	 * 而且歌放了一会儿之后进来的人会明显对不上。现在直接取服务端进度，
	 * 起播慢的问题交给「漂移校正用 seek 追进度」解决。
	 */
	/**
	 * 服务端视角的当前进度（秒）——起播位置以它为准。
	 *
	 * <p>用服务端广播来的「已播毫秒」当基准：那是**暂停期间不会前进**的量，
	 * 所以暂停过一会儿的歌也能算对（以前直接用 startEpochMs 会把暂停时间算进去）。
	 */
	/**
	 * 服务端视角的当前进度（秒）——起播位置以它为准。
	 *
	 * <p>用服务端广播来的「已播毫秒」当基准：那是**暂停期间不会前进**的量，
	 * 所以暂停过一会儿的歌也能算对。
	 *
	 * <p><b>为什么用 {@code nanoTime} 而不是墙钟</b>：
	 * 以前是 {@code base + (System.currentTimeMillis() - startEpochMs)} ——
	 * 把「服务端的墙钟」和「本机的墙钟」拼在一起。两边只要有一点不一致
	 * （NTP 校时、改时间、时区、时钟漂移）就会算出荒唐的值：
	 * 日志实证收到过「已播 167549 秒」，客户端还又加了 50 秒 → 167599 秒，
	 * 然后从那个位置起播 → <b>整首歌被丢光 → 响两句就没声了</b>。
	 *
	 * <p>{@code System.nanoTime()} 是单调的，只用来量「收到状态之后过了多久」，
	 * 不参与任何跨机器的时间比较。
	 */
	private int expectedSeconds() {
		long base = serverPositionMs;
		if (base > 0) {
			long extraMs = nanosAtState > 0
					? (System.nanoTime() - nanosAtState) / 1_000_000L : 0;
			if (extraMs < 0) extraMs = 0;      // 理论上不会（nanoTime 单调）
			return (int) Math.max(0, (base + extraMs) / 1000);
		}
		if (startEpochMs <= 0) return 0;
		return (int) Math.max(0, (System.currentTimeMillis() - startEpochMs) / 1000);
	}

	/** 每 tick 调用：处理暂停/继续、歌曲结束、进度校正。 */
	public void tick() {
		// 把「游戏内播放」开关同步给播放器（音频层不直接依赖配置类，方便离线测试）
		player.setAudioEnabled(MusicConfig.get().audioEnabled);
		// 0) 音频被关掉时必须真的停下来——否则会一直放上一首
		if (!MusicConfig.get().audioEnabled) {
			if (player.active()) {
				player.stop();
			}
			playingKey = "";
			loadingKey = "";
			return;
		}

		MusicTrack track = currentTrack();
		if (track == null) return;

		if (!serverPlaying) {
			if (player.playing()) player.pause();
			return;
		}

		// 队列里换了歌但还没开始放
		String key = track.source() + ":" + track.id();
		if (!key.equals(playingKey) && !key.equals(loadingKey)) {
			startCurrent();
			return;
		}

		// 正在加载这首：等它真的出声。一旦有进度就确认「在播」。
		if (key.equals(loadingKey) && !key.equals(playingKey)) {
			if (player.hasProgress()) {
				playingKey = key;
				loadingKey = "";
				startFailures.remove(key);   // 这次成功了，清掉失败计数
				YueMusic.LOGGER.info("[{}] 《{}》已确认起播", YueMusic.MOD_NAME, track.title());
			} else if (waitingServerUrl) {
				// 正在等服务端下发直链（切房间 / 歌单导入后常见）。
				// 这里**不能**当成起播失败去跳过 —— 那会把整张歌单挨个跳过去。
				// 服务端通常 1~3 秒解析完；超过 20 秒就放弃等待，走正常失败流程。
				long waited = System.currentTimeMillis() - waitingServerSince;
				if (waited < 20_000) {
					// 每 3 秒重试一次（服务端直链到了就会立刻播）
					if (System.currentTimeMillis() - loadingStartedAt > 3_000) {
						loadingKey = "";
						startCurrent();
					}
					return;
				}
				YueMusic.LOGGER.warn("[{}] 等直链超过 20 秒仍未到，放弃等待《{}》",
						YueMusic.MOD_NAME, track.title());
				waitingServerUrl = false;
			}
			return;
		}

		// 本地播完了 → 请求服务端切歌（服务端会广播新的 currentIndex）
		//
		// 注意：必须有「本次播放真的出过声」这个条件。
		// stop() 会立刻把 state 设成 IDLE，所以从「点播放/切歌」到新歌真正起播之间
		// 存在一个空档（解析直链 + 下载缓冲）。在那段空档里 tick() 会看到
		// state == IDLE，如果直接当成「播完了」就会发 NEXT ——
		// 表现就是「点播放反而跳到下一首」。
		if (key.equals(playingKey) && player.state() == AudioPlayer.State.IDLE) {
			if (player.hasProgress()) {
				// ⚠ 再加一道「别切太早」的保护：
				// 元数据时长常比真实音频短，如果本地刚好放完就立刻上报 NEXT，
				// 而服务端（按元数据计时 + 12 秒容差）还没认为放完，
				// 两者节奏不一致也可能造成「提前切歌」的观感。
				// 所以本地放完后**再等一小段**，让服务端那边也到点。
				int dur = track.duration();
				if (dur > 0 && player.positionSeconds() + 3 < dur) {
					// 本地进度明显还没到元数据时长 → 是异常结束，不算「播完」
					YueMusic.LOGGER.warn("[{}] 《{}》本地提前结束（本地 {}s / 元数据 {}s），不触发切歌",
							YueMusic.MOD_NAME, track.title(), player.positionSeconds(), dur);
					playingKey = "";
					loadingKey = "";
					startCurrent();
					return;
				}
				playingKey = "";
				sendControl(MusicNetworking.Action.NEXT, 0);
			} else {
				// 还没出过声 → 不是播完，而是起播失败/被中断。
				//
				// ⚠ 关键：必须把 urlCache 里这条直链清掉再重试。
				// 否则会一直拿着同一根坏链（过期 vkey / CDN 抽风 / 下载失败）
				// 反复重试 —— 表现就是「插播了但一直不播放」，而且永远好不了。
				int tries = startFailures.merge(key, 1, Integer::sum);
				player.stop();
				urlCache.remove(key);
				playingKey = "";
				loadingKey = "";
				if (tries <= 3) {
					YueMusic.LOGGER.info("[{}] 《{}》起播失败（第 {} 次），丢弃直链重新解析",
							YueMusic.MOD_NAME, track.title(), tries);
					startCurrent();
				} else {
					YueMusic.LOGGER.warn("[{}] 《{}》连续 {} 次起播失败，跳过这首",
							YueMusic.MOD_NAME, track.title(), tries);
					startFailures.remove(key);
					skipBroken(track);
				}
			}
			return;
		}

		// 1) 播放器卡住（线程异常退出但状态没归位）→ 复位，让下面重新起播
		if (key.equals(playingKey) && player.state() == AudioPlayer.State.ERROR) {
			playingKey = "";
			return;
		}

		// 1.5) 起播卡死自动重试 —— 修「某人点歌时别人听不到」。
		//     网络慢 / 直链过期 / CDN 抽风都会让 play() 长时间停在 LOADING，
		//     而状态广播是按「歌曲没变」来的，不会重新调 startCurrent()，
		//     于是这个客户端就永远没声音了。这里给起播一个时限，超时就立刻重来。
		if (!loadingKey.isEmpty() && System.currentTimeMillis() - loadingStartedAt > 20000) {
			AudioPlayer.State st = player.state();
			if (st == AudioPlayer.State.LOADING || st == AudioPlayer.State.IDLE) {
				YueMusic.LOGGER.warn("[{}] 起播超时（{}），重新解析直链", YueMusic.MOD_NAME, st);
				player.stop();
				loadingKey = "";
				playingKey = "";
				urlCache.remove(key);          // 丢掉可能已过期的直链
				startCurrent();
				return;
			}
		}

		// 2) 进度校正：每 5 秒比一次「服务端期望位置」和「本地实际位置」。
		//
		//    这里**必须用 seek，不能用「停掉重播」**：
		//    重播要重新下载+缓冲，网络慢的客户端会被反复打断，
		//    永远停留在起播阶段 —— 表现就是「别人听得到，我听不到」，
		//    而且因为一直没在播，进度条会来回跳（鬼畜），也永远等不到「播完」
		//    去触发自动切歌。这三个 bug 是同一个根因。
		long now = System.currentTimeMillis();
		if (now - lastDriftCheck >= 4000 && startEpochMs > 0 && player.active()) {
			lastDriftCheck = now;
			int expected = (int) Math.max(0, (now - startEpochMs) / 1000);
			int local = player.positionSeconds();
			int diff = expected - local;   // >0 表示本地落后

			// 容差随时间放宽：刚起播时允许落后多一些（下载/缓冲要时间），
			// 但不允许无限落后，否则永远追不上。
			int tolerance = expected < 15 ? 6 : 4;

			// 本地落后 → seek 追上去（不中断下载）
			if (diff > tolerance) {
				YueMusic.LOGGER.info("[{}] 落后服务端 {}s（服务端 {}s / 本地 {}s），seek 追上",
						YueMusic.MOD_NAME, diff, expected, local);
				player.seekTo(expected);
			} else if (diff < -tolerance - 4) {
				// 本地超前很多：一般是本地时钟/缓冲异常，往回 seek 到服务端位置
				YueMusic.LOGGER.info("[{}] 超前服务端 {}s（服务端 {}s / 本地 {}s），seek 回退",
						YueMusic.MOD_NAME, -diff, expected, local);
				player.seekTo(expected);
			}
		}
	}

	/** 服务端是否已登录（UI 用来显示「免登录」提示）。 */
	public boolean serverLoggedIn() {
		return serverLoggedIn;
	}

	/** 当前房间名（UI 显示 + 切换用）。 */
	public String room() {
		return room;
	}

	/** 加入房间（不存在则服务端自动创建）。 */
	public void joinRoom(String roomName) {
		if (ClientPlayNetworking.canSend(MusicNetworking.RoomC2S.ID)) {
			ClientPlayNetworking.send(new MusicNetworking.RoomC2S(
					MusicNetworking.RoomAction.JOIN, roomName));
		}
	}

	/** 回到默认房间。 */
	public void leaveRoom() {
		if (ClientPlayNetworking.canSend(MusicNetworking.RoomC2S.ID)) {
			ClientPlayNetworking.send(new MusicNetworking.RoomC2S(
					MusicNetworking.RoomAction.LEAVE, ""));
		}
	}

	/** 请求房间列表（服务端会用通知形式回）。 */
	public void listRooms() {
		if (ClientPlayNetworking.canSend(MusicNetworking.RoomC2S.ID)) {
			ClientPlayNetworking.send(new MusicNetworking.RoomC2S(
					MusicNetworking.RoomAction.LIST, ""));
		}
	}

	public void onDisconnect() {
		player.stop();
		queue = List.of();
		currentIndex = -1;
		playingKey = "";
		loadingKey = "";
		urlCache.clear();
		serverLoggedIn = false;
	}

	// ---------------------------------------------------------------- 操作

	/** 搜索（后台线程执行，回调在主线程）。 */
	public void search(MusicSource source, String keyword, Consumer<MusicApi.SearchResult> callback) {
		workers.submit(() -> {
			MusicApi.SearchResult result = MusicApi.search(source, keyword, 25);
			net.minecraft.client.MinecraftClient.getInstance().execute(() -> callback.accept(result));
		});
	}

	/**
	 * 通过 QQ音乐歌单链接批量导入。
	 *
	 * <p>流程：后台拉歌单 → 把歌单里的歌一次性发给服务端 → 服务端直接加进本房间队列。
	 * <b>导入时不解析播放地址</b>（由服务端在播到那首时解析），
	 * 否则 1000 首要解析 1000 次，既慢又会被音乐平台风控。
	 *
	 * @param onStatus 状态回调（主线程）
	 */
	/**
	 * 当前生效的队列上限。
	 *
	 * <p>连了服务端就用服务端广播过来的值（那才是真正生效的），
	 * 单人存档（服务端就是自己）才用本地配置的值。
	 */
	public int effectiveQueueLimit() {
		int s = serverLimit;
		if (s > 0) return s;
		return MusicConfig.get().maxQueueSize;
	}

	/**
	 * 请求服务端把队列上限改成 {@code limit}。
	 *
	 * <p>客户端不能只改自己的配置 —— 队列上限是服务端判定的。
	 * 所以这里把新值发给服务端，由它写进服务端配置再广播回来。
	 *
	 * @return 是否发出去了（服务端没装模组时返回 false）
	 */
	public boolean requestQueueLimit(int limit) {
		if (!ClientPlayNetworking.canSend(MusicNetworking.SetLimitC2S.ID)) {
			// 单人存档：自己就是服务端，直接改本地配置即可
			MusicConfig cfg = MusicConfig.get();
			cfg.maxQueueSize = Math.max(1, Math.min(MusicConfig.MAX_QUEUE_LIMIT, limit));
			cfg.save();
			return false;
		}
		ClientPlayNetworking.send(new MusicNetworking.SetLimitC2S(limit));
		return true;
	}
	public void importPlaylist(String link, Consumer<String> onStatus) {
		if (link == null || link.isBlank()) {
			onStatus.accept("✗ 请先粘贴 QQ音乐歌单链接");
			return;
		}
		onStatus.accept("正在获取歌单…（上千首可能要十几秒）");
		workers.submit(() -> {
			try {
				com.yue.music.api.QQPlaylist.Playlist pl = MusicApi.fetchQQPlaylist(link);
				if (!pl.ok()) {
					String err = pl.error() == null ? "歌单获取失败" : pl.error();
					net.minecraft.client.MinecraftClient.getInstance()
							.execute(() -> onStatus.accept("✗ " + err));
					return;
				}
				String json = MusicNetworking.encodeTracksJson(pl.tracks());
				String pname = pl.name() == null || pl.name().isBlank() ? "歌单" : pl.name();
				int count = pl.tracks().size();
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					if (!ClientPlayNetworking.canSend(MusicNetworking.ImportPlaylistC2S.ID)) {
						onStatus.accept("✗ 服务端没有安装本模组，无法导入");
						return;
					}
					ClientPlayNetworking.send(new MusicNetworking.ImportPlaylistC2S(json, pname));
					onStatus.accept("✓ 已提交歌单《" + pname + "》共 " + count + " 首，正在入队…");
				});
			} catch (Exception e) {
				String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				net.minecraft.client.MinecraftClient.getInstance()
						.execute(() -> onStatus.accept("✗ 导入失败：" + msg));
			}
		});
	}

	/**
	 * 点歌。
	 *
	 * <p><b>两种模式</b>：
	 * <ul>
	 *   <li><b>服务端已登录</b>（{@code serverLoggedIn}）：客户端<b>不解析</b>，
	 *       只把歌名/ID 报给服务端，由服务端用服主账号解析 —— 所有玩家免登录</li>
	 *   <li><b>服务端未登录</b>：客户端自己解析直链再交给服务端（旧行为）</li>
	 * </ul>
	 *
	 * @param onStatus 状态回调（主线程）
	 */
	public void requestSong(MusicTrack track, Consumer<String> onStatus) {
		if (serverLoggedIn) {
			// 服务端负责解析，本客户端不需要登录，也不该浪费一次网络请求
			onStatus.accept("已提交《" + track.title() + "》，由服务端解析…");
			String json = MusicNetworking.encodeTrack(track);
			if (!ClientPlayNetworking.canSend(MusicNetworking.AddSongC2S.ID)) {
				onStatus.accept("✗ 服务端没有安装本模组，无法同步点歌");
				return;
			}
			ClientPlayNetworking.send(new MusicNetworking.AddSongC2S(json, "", track.duration()));
			return;
		}
		resolveLocallyOnce(track, onStatus);
	}

	/**
	 * 本机解析直链后提交给服务端（服务端未登录，或服务端解析失败回落时使用）。
	 */
	public void resolveLocallyOnce(MusicTrack track, Consumer<String> onStatus) {
		onStatus.accept("正在解析《" + track.title() + "》…");
		workers.submit(() -> {
			try {
				PlayUrl resolved = MusicApi.resolve(track, true);
				if (!resolved.ok()) {
					String err = resolved.error() == null ? "无法获取播放地址" : resolved.error();
					net.minecraft.client.MinecraftClient.getInstance().execute(() -> onStatus.accept("✗ " + err));
					return;
				}
				int duration = track.duration();
				String json = MusicNetworking.encodeTrack(track);
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					if (!ClientPlayNetworking.canSend(MusicNetworking.AddSongC2S.ID)) {
						onStatus.accept("✗ 服务端没有安装本模组，无法同步点歌");
						return;
					}
					ClientPlayNetworking.send(new MusicNetworking.AddSongC2S(json, resolved.url(), duration));
					onStatus.accept("✓ 已点歌：" + track.display());
				});
			} catch (Exception e) {
				String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> onStatus.accept("✗ 点歌失败：" + msg));
			}
		});
	}

	public void sendControl(MusicNetworking.Action action, int value) {
		if (ClientPlayNetworking.canSend(MusicNetworking.ControlC2S.ID)) {
			ClientPlayNetworking.send(new MusicNetworking.ControlC2S(action, value));
		} else {
			lastError = "服务端未安装本模组";
		}
	}

	/**
	 * 调整统一音量。
	 *
	/**
	 * 调整本机音量。**只影响自己**，不会改变别人的音量。
	 *
	 * <p>以前这里会把音量发给服务端，服务端再广播给所有人 ——
	 * 结果「一个人调音量，全服跟着变」。现在音量是**纯本地设置**：
	 * 存在各自的配置文件里，服务端状态广播也不会覆盖它。
	 *
	 * <p>（服务端仍然记一个音量值，只作为新玩家进服时的初始默认值，不参与实时同步。）
	 */
	public void setVolume(int volume) {
		localVolume = Math.max(0, Math.min(100, volume));
		player.setVolume(localVolume / 100f);
		MusicConfig cfg = MusicConfig.get();
		cfg.volume = localVolume / 100f;
		cfg.save();
	}

	/**
	 * 立刻停止播放并复位播放状态。
	 * <p>设置页把「游戏内播放」关掉时必须调用它——只改配置的话，
	 * 已经在播的那首歌会继续放下去（有人反馈过这个 bug）。
	 */
	public void stopPlayback() {
		player.stop();
		playingKey = "";
		loadingKey = "";
		status = "";
	}

	/** 按步长调整音量（UI 的 −/+ 按钮与拖拽都用它）。 */
	public void adjustVolume(int delta) {
		setVolume(localVolume + delta);
	}

	/** 从队列删除一首（服务端会广播新队列）。 */
	public void removeFromQueue(int index) {
		sendControl(MusicNetworking.Action.REMOVE, index + 1);
	}

}
