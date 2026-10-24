package com.yue.music.audio;

import com.yue.music.YueMusic;
import com.yue.music.api.Http;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 音频流播放器：HTTP 拉流 → 解码成 PCM → <b>javax.sound.sampled 播放</b>。
 *
 * <h2>为什么不用 OpenAL</h2>
 * 曾经用 LWJGL 的 OpenAL 直接播放，结果在真实环境里必然触发
 * {@code EXCEPTION_ACCESS_VIOLATION in OpenAL.dll}（整个游戏闪退，抓不到 Java 异常）——
 * 因为 Minecraft 自己也用 OpenAL，两套上下文/函数表在同一个 DLL 里互相踩。
 *
 * <p>现在改用 JDK 自带的 Java Sound（{@link SourceDataLine}），它走的是独立的音频后端，
 * <b>和游戏的声音引擎不共享任何原生状态</b>，物理上不可能再互相破坏。
 * 解码仍然用内置的 mp3spi，体验（进度、暂停、音量）完全一样。
 */
public final class AudioPlayer {

	public enum State {IDLE, LOADING, PLAYING, PAUSED, ERROR}

	/** 一次读出的 PCM 大小（44100×4 字节/秒 → 64KB ≈ 0.37 秒）。 */
	private static final int CHUNK_BYTES = 64 * 1024;
	/** 行内缓冲区：约 0.5 秒，太小会卡顿，太大会有延迟。 */
	private static final int LINE_BUFFER_BYTES = 44100 * 4 / 2;

	private final Object lock = new Object();

	/**
	 * 音频下载专用的 HttpClient。
	 *
	 * <p>必须用 {@code Redirect.ALWAYS}：网易云的 outer 地址会 302 到
	 * {@code http://m7xx.music.126.net/...}，属于 https → http 的跨协议跳转，
	 * {@code Redirect.NORMAL} 会拒绝跟随，结果就是「音频下载失败 HTTP 302」。
	 */
	private static final java.net.http.HttpClient AUDIO_CLIENT = java.net.http.HttpClient.newBuilder()
			.followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
			.connectTimeout(java.time.Duration.ofSeconds(10))
			.version(java.net.http.HttpClient.Version.HTTP_1_1)
			.build();

	private volatile State state = State.IDLE;
	private volatile String error = "";
	private volatile float volume = 0.7f;
	private volatile int reportedDuration;
	private volatile long playedBytes;
	/** 本次播放要跳过的起始秒数（用于和服务端对齐进度）。 */
	private volatile int startOffsetSeconds;
	/**
	 * 请求跳到第 N 秒（播放线程会丢弃到目标位置的数据，不中断下载）。
	 * <p>用 seek 而不是「停掉重播」：重播要重新下载+缓冲，网络慢的客户端
	 * 会一直卡在起播阶段，表现就是「别人听得到我听不到」。
	 */
	private volatile int skipToSeconds = -1;
	/**
	 * 播放代次。每次 {@link #play} 与 {@link #stop} 都自增。
	 *
	 * <p><b>这是"同一时刻只能有一路声音"的关键</b>：
	 * 播放线程每读一块数据都会核对代次，代次变了就立刻收工。
	 */
	private volatile int generation;

	/**
	 * 当前的播放请求。
	 *
	 * <p>以前 {@code play()} 每次都 {@code new Thread(...)} —— 而 {@code stop()}
	 * 只是打了个标记就返回，如果旧线程正卡在「读网络」或「写声卡」，
	 * 它不会立刻退出，新线程却已经开跑了 → <b>两路声音叠在一起</b>。
	 * 日志实证：同一秒里出现 2~5 次「音频解码」。
	 *
	 * <p>现在改成「一条常驻播放线程 + 一个请求变量」：
	 * 永远只有一个播放线程，新请求只是换掉它手上的活，<b>不可能叠加</b>。
	 */
	private record PlayRequest(String url, int duration, int startOffset, int token) {
	}

	private volatile PlayRequest request;

	/** 唯一的播放线程（常驻，不随切歌重建）。 */
	private Thread playThread;
	private SourceDataLine line;

	/**
	 * 「游戏内播放」开关。
	 *
	 * <p>由客户端每 tick 从配置同步过来（{@code ClientAudio.tick()}）。
	 * 这样音频层**不直接依赖配置类** —— 离线测试可以直接构造 AudioPlayer 使用，
	 * 不必去凑一个 Fabric 环境。
	 */
	private volatile boolean audioEnabled = true;

	/** 同步「游戏内播放」开关（关掉后 {@link #play} 会直接拒绝）。 */
	public void setAudioEnabled(boolean enabled) {
		this.audioEnabled = enabled;
	}

	public State state() {
		return state;
	}

	public String error() {
		return error;
	}

	public boolean playing() {
		return state == State.PLAYING;
	}

	public boolean active() {
		return state == State.PLAYING || state == State.PAUSED || state == State.LOADING;
	}

	public void setVolume(float v) {
		volume = Math.max(0f, Math.min(1f, v));
		applyVolume();
	}

	public float volume() {
		return volume;
	}

	private void applyVolume() {
		synchronized (lock) {
			if (line == null || !line.isOpen()) return;
			try {
				if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
					FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
					// 音量 0 时给一个很小的值，避免 -Infinity
					float db = volume <= 0.001f ? gain.getMinimum()
							: (float) (20.0 * Math.log10(volume));
					gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
				}
			} catch (Exception e) {
				YueMusic.LOGGER.debug("[YueMusic] 设置音量失败: {}", e.toString());
			}
		}
	}

	/**
	 * 播放一个音频地址。
	 *
	 * @param url             直链
	 * @param durationSeconds 已知时长（用于进度条；不确定填 0）
	 */
	public void play(String url, int durationSeconds) {
		play(url, durationSeconds, 0);
	}

	/**
	 * 播放一个音频地址，并从指定秒数开始。
	 *
	 * @param startOffsetSeconds 跳过开头这么多秒（服务端已经在播时用它对齐进度）。
	 *                           实现上是「解码后丢弃」——下载量不变，但不会为了追进度而跳帧爆音。
	 */
	public void play(String url, int durationSeconds, int startOffsetSeconds) {
		// 先把当前这一路停下来（它会立刻看到代次变化而收工），再发新请求。
		// 注意：这里【不会】等旧线程退出 —— 但也不会出现两路声音，
		// 因为整个类只有一条播放线程（见 request / playThread 的注释）。
		stop();
		skipToSeconds = -1;
		if (!audioEnabled) {
			fail("音频播放已在设置里关闭（设置页可打开）");
			return;
		}
		this.startOffsetSeconds = Math.max(0, startOffsetSeconds);
		reportedDuration = durationSeconds;
		playedBytes = 0;
		error = "";
		state = State.LOADING;
		// 发请求：带上当前代次，播放线程据此判断"这一路是不是已经作废"
		request = new PlayRequest(url, durationSeconds,
				Math.max(0, startOffsetSeconds), generation);
		ensurePlayThread();
	}

	/**
	 * 确保播放线程存在（只创建一次，之后一直复用）。
	 *
	 * <p>这是"永不叠加"的保证：不管切歌多快，播放线程始终只有这一条。
	 */
	private synchronized void ensurePlayThread() {
		if (playThread != null && playThread.isAlive()) return;
		playThread = new Thread(this::runLoop, "YueMusic-Audio");
		playThread.setDaemon(true);
		playThread.start();
	}

	/**
	 * 播放线程的主循环。
	 *
	 * <p>它一直活着：有新请求就播，没有就歇着。
	 * 播放过程中每一步都核对代次，代次变了立刻收工去拿新请求。
	 */
	private void runLoop() {
		while (true) {
			PlayRequest req = request;
			if (req != null && req.token() == generation) {
				try {
					playOne(req);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (Throwable t) {
					YueMusic.LOGGER.warn("[YueMusic] 播放线程异常: {}", t.toString());
				}
			} else {
				try {
					Thread.sleep(20);   // 空闲：歇一下再看有没有新请求
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/**
	 * 停止播放。
	 *
	 * <p><b>绝对不能阻塞</b>：这个方法由客户端主线程调用（切歌/插播/关音频）。
	 * 以前这里会 {@code worker.join(2500)} 等旧线程退出，而旧线程可能正卡在
	 * {@code SourceDataLine.write()}（下载慢 / 声卡缓冲满）—— 结果就是
	 * 插播时**静音最长 2.5 秒**。
	 *
	 * <p>现在改为：打停止标记 + <b>立刻关掉声卡线路</b>（关掉会让阻塞中的
	 * {@code write()} 直接抛异常返回，旧线程自然退出），不等它。
	 */
	public void stop() {
		generation++;                 // 让播放线程手里的这一路立刻作废（它每块数据都会核对）
		closeLine();
		// 注意：这里**不**清空 playThread —— 播放线程是常驻的，只是手上没活了。
		// 以前把 worker 置空是"丢掉引用"的意思，但线程还在跑；
		// 现在改成常驻线程 + 代次判定，就彻底不会有两条线程同时出声。
		if (state != State.ERROR) state = State.IDLE;
	}

	/** 关掉当前声卡线路（解除 write() 阻塞）。 */
	private void closeLine() {
		SourceDataLine l;
		synchronized (lock) {
			l = line;
			line = null;
		}
		if (l != null) {
			try {
				l.stop();
			} catch (Exception ignored) {
			}
			try {
				l.flush();
			} catch (Exception ignored) {
			}
			try {
				l.close();
			} catch (Exception ignored) {
			}
		}
	}

	public void pause() {
		synchronized (lock) {
			if (line != null && line.isOpen()) line.stop();
		}
		if (state == State.PLAYING) state = State.PAUSED;
	}

	public void resume() {
		synchronized (lock) {
			if (line != null && line.isOpen()) {
				line.start();
				// 重新套用音量（有些驱动在 stop/start 后会重置）
				applyVolume();
			}
		}
		if (state == State.PAUSED) state = State.PLAYING;
	}

	/**
	 * 跳到指定秒数（与服务端进度对齐用）。
	 *
	 * <p><b>不会中断下载</b>：播放线程在下一块数据处丢弃到目标位置，之后继续正常出声。
	 * 这比「停掉重播」可靠得多——重播会重新下载和缓冲，网络慢的客户端
	 * 会被反复打断，最终表现为「其他人听得到，我听不到」。
	 *
	 * <p>如果还没出声（正在 LOADING），直接把目标位置合并进起播偏移，省掉一次丢弃。
	 */
	public void seekTo(int seconds) {
		if (seconds < 0) return;
		if (state == State.IDLE || state == State.ERROR) return;
		if (line == null) {
			// 还没出声：直接改起播偏移
			startOffsetSeconds = seconds;
			return;
		}
		int current = positionSeconds();
		if (Math.abs(current - seconds) < 2) return;   // 够近了，不折腾
		skipToSeconds = seconds;
	}

	/**
	 * 本次播放是否<b>真的出过声</b>（写出过 PCM 数据）。
	 *
	 * <p>用来区分「歌曲真的播完了」和「还没起播 / 刚被 stop() 掉」——
	 * 两者都表现为 {@code state == IDLE}，但只有前者该触发「切下一首」。
	 * 不加这个判断的话，点播放后到新歌起播之间的空档会被误判成「播完了」，
	 * 结果就是「点播放反而跳到下一首」。
	 */
	public boolean hasProgress() {
		if (playedBytes > 0) return true;
		SourceDataLine l = line;
		if (l != null && l.isOpen()) {
			try {
				return l.getLongFramePosition() > 0;
			} catch (Exception ignored) {
			}
		}
		return false;
	}

	/** 已播放秒数（含开头跳过的偏移，所以它就是「这首歌的当前进度」）。 */
	public int positionSeconds() {
		int offset = startOffsetSeconds;
		SourceDataLine l = line;
		if (l != null && l.isOpen()) {
			long frames = l.getLongFramePosition();
			if (frames > 0) {
				int pos = (int) (frames / 44100L) + offset;
				return reportedDuration > 0 ? Math.min(pos, reportedDuration) : pos;
			}
		}
		int pos = (int) (playedBytes / (44100L * 4L)) + offset;
		return reportedDuration > 0 ? Math.min(pos, reportedDuration) : pos;
	}

	public float progress() {
		int pos = positionSeconds();
		if (reportedDuration > 0) return Math.min(1f, pos / (float) reportedDuration);
		return 0f;
	}

	// ---------------------------------------------------------------- 播放线程

	/**
	 * 播放**一路**音频：拉流 → 解码 → 写声卡。
	 *
	 * <p>由常驻线程 {@link #runLoop()} 调用。整个类同一时刻只会执行这一个方法，
	 * 所以不可能出现两路声音同时写声卡。
	 *
	 * <p>播放过程中每一步都核对代次 {@code req.token() == generation}：
	 * 一旦切歌/停止（代次自增），这里立刻收工返回，
	 * 绝不继续往声卡里写数据。
	 *
	 * @param req 播放请求（url / 时长 / 起播偏移 / 代次）
	 */
	private void playOne(PlayRequest req) throws Exception {
		final int token = req.token();
		SourceDataLine lineRef = null;   // 本次播放持有的声卡（收尾时只关它）
		try {
			HttpRequest httpReq = HttpRequest.newBuilder(URI.create(req.url()))
					.timeout(java.time.Duration.ofSeconds(20))
					.header("User-Agent", Http.DEFAULT_UA)
					.header("Referer", "https://music.163.com/")
					.header("Accept", "*/*")
					.build();
			HttpResponse<InputStream> resp = AUDIO_CLIENT.send(httpReq,
					HttpResponse.BodyHandlers.ofInputStream());
			if (token != generation) return;          // 已经被换掉了
			if (resp.statusCode() / 100 != 2) {
				fail("音频下载失败 HTTP " + resp.statusCode());
				return;
			}

			try (InputStream raw = new BufferedInputStream(resp.body(), CHUNK_BYTES);
				 PcmSource pcm = PcmSource.open(raw)) {
				if (token != generation) return;

				AudioFormat format = PcmSource.TARGET;
				DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
				if (!AudioSystem.isLineSupported(info)) {
					fail("系统不支持该音频格式（44100Hz/16bit/立体声）");
					return;
				}
				SourceDataLine newLine = (SourceDataLine) AudioSystem.getLine(info);
				newLine.open(format, LINE_BUFFER_BYTES);
				lineRef = newLine;
				synchronized (lock) {
					line = newLine;
				}
				applyVolume();
				newLine.start();
				state = State.PLAYING;

				byte[] chunk = new byte[CHUNK_BYTES];
				int n;

				// 对齐进度：先解码并丢弃开头 N 秒，再开始出声。
				// 这样「服务端已经播了 2 秒」时客户端不会从 0 秒开始，多人听才是同步的。
				// 用 req.startOffset（本次请求的），不是字段——
				// 字段会被下一次 play() 改写，导致这一路用错偏移。
				int skipBytes = Math.max(0, req.startOffset()) * 44100 * 4;
				while (token == generation && skipBytes > 0 && (n = pcm.read(chunk)) > 0) {
					skipBytes -= n;
				}
				if (token != generation) return;

				while (token == generation && (n = pcm.read(chunk)) > 0) {
					// 追进度：漂移校正时把目标秒数写进 skipToSeconds，这里丢弃到达目标前的数据。
					// 用 seek 而不是「停掉重播」——重播会重新下载、重新缓冲，
					// 结果就是网络慢的客户端一直卡在起播阶段（表现为「听不到」）。
					int target = skipToSeconds;
					if (target > 0) {
						skipToSeconds = 0;
						int frame = Math.max(0, target - req.startOffset());
						long want = (long) frame * 44100L * 4L;
						long dropped = 0;
						while (token == generation && dropped < want && (n = pcm.read(chunk)) > 0) {
							dropped += n;
						}
						if (token != generation) return;
						startOffsetSeconds = target;   // 之后的进度从新位置算
						continue;
					}

					// 暂停时把数据先扣在手里不写出去（写会阻塞，正好当背压）
					while (state == State.PAUSED && token == generation) {
						Thread.sleep(120);
					}
					if (token != generation) break;
					int written = 0;
					while (written < n && token == generation) {
						written += newLine.write(chunk, written, n - written);
					}
					playedBytes += n;
				}

				// 等缓冲区播完
				if (token == generation) {
					try {
						newLine.drain();
					} catch (Exception ignored) {
					}
					if (state != State.ERROR) state = State.IDLE;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			// 切歌时关线路会让阻塞中的 write() 抛异常 —— 那不是"播放失败"，别报错
			if (token == generation) {
				YueMusic.LOGGER.warn("[YueMusic] 播放失败: {}", e.toString());
				fail("播放失败：" + friendly(e));
			}
		} finally {
			// 只关「属于本次播放」的线路：如果期间已经切了歌（line 已换成新的），
			// 绝不能在这里关掉，否则新歌会没声音。
			synchronized (lock) {
				if (line != null && line == lineRef) {
					try {
						line.stop();
					} catch (Exception ignored) {
					}
					try {
						line.close();
					} catch (Exception ignored) {
					}
					line = null;
				}
			}
		}
	}

	private void fail(String msg) {
		error = msg;
		state = State.ERROR;
	}

	private static String friendly(Exception e) {
		if (e instanceof javax.sound.sampled.UnsupportedAudioFileException) {
			return "音频格式不支持（可能是 m4a/flac 或加密流）";
		}
		if (e instanceof javax.sound.sampled.LineUnavailableException) {
			return "音频设备被占用或不可用（检查系统声音设置）";
		}
		String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		if (m.contains("timed out") || m.contains("Timeout")) return "音频下载超时";
		return m;
	}
}
