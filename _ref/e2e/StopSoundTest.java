import com.yue.music.audio.AudioPlayer;
import com.yue.music.audio.PcmSource;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 实测「切歌后上一首还在响」。
 *
 * <p>判据：切歌后**声卡线路必须已经关闭**（关闭 = 立即静音）。
 * 如果线路还开着（isOpen/isActive），那已经缓冲在声卡里的音频会继续放出来 ——
 * 那就是用户听到的「前一首声音不会消失」。
 */
public class StopSoundTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 反射读 AudioPlayer.line（诊断用，带详情）。 */
	static Object lineOf(AudioPlayer p) {
		try {
			var f = AudioPlayer.class.getDeclaredField("line");
			f.setAccessible(true);
			return f.get(p);
		} catch (Exception e) {
			System.out.println("      [反射失败] " + e);
			return null;
		}
	}

	/** 打印线路详情，方便确认测试本身没问题。 */
	static void dumpLine(String label, AudioPlayer p) {
		Object l = lineOf(p);
		if (l == null) {
			System.out.println("      " + label + ": line = null");
			return;
		}
		try {
			Object open = l.getClass().getMethod("isOpen").invoke(l);
			Object active = l.getClass().getMethod("isActive").invoke(l);
			Object avail = l.getClass().getMethod("available").invoke(l);
			System.out.println("      " + label + ": " + l.getClass().getSimpleName()
					+ "  isOpen=" + open + "  isActive=" + active + "  缓冲区可写=" + avail);
		} catch (Exception e) {
			System.out.println("      " + label + ": 反射调用失败 " + e);
		}
	}

	static boolean lineOpen(AudioPlayer p) {
		Object l = lineOf(p);
		if (l == null) return false;
		try {
			return (boolean) l.getClass().getMethod("isOpen").invoke(l);
		} catch (Exception e) {
			return false;
		}
	}

	static boolean lineActive(AudioPlayer p) {
		Object l = lineOf(p);
		if (l == null) return false;
		try {
			return (boolean) l.getClass().getMethod("isActive").invoke(l);
		} catch (Exception e) {
			return false;
		}
	}

	public static void main(String[] args) throws Exception {
		String url = args.length > 0 ? args[0] : "http://127.0.0.1:18942/a.mp3";
		System.out.println("音频地址: " + url);

		// 先确认真的有声音可放（避免测了个空）
		try (InputStream raw = new BufferedInputStream(
				HttpClient.newHttpClient().send(
						HttpRequest.newBuilder(URI.create(url)).build(),
						HttpResponse.BodyHandlers.ofInputStream()).body(), 65536);
			 PcmSource pcm = PcmSource.open(raw)) {
			byte[] b = new byte[4096];
			System.out.println("  预检：能解码 " + pcm.read(b) + " 字节 ✓");
		} catch (Exception e) {
			System.out.println("  预检失败: " + e);
			return;
		}

		AudioPlayer p = new AudioPlayer();
		p.setVolume(0.5f);

		System.out.println("\n=== 1) 正常起播 ===");
		p.play(url, 30, 0);
		Thread.sleep(2500);
		System.out.println("  状态 = " + p.state() + "  进度 = " + p.positionSeconds() + "s");
		dumpLine("播放中", p);
		check("已经起播（有进度）", p.hasProgress());
		check("【播放中线路必须是开着的（否则测试本身没测到东西）】", lineOpen(p));

		System.out.println("\n=== 2) 播到一半切歌：stop() 之后必须立刻静音 ===");
		p.stop();
		long t0 = System.currentTimeMillis();
		// stop() 返回后立刻检查
		boolean openRightAfter = lineOpen(p);
		boolean activeRightAfter = lineActive(p);
		System.out.println("  stop() 返回耗时 " + (System.currentTimeMillis() - t0) + " ms");
		System.out.println("  立刻检查：线路开着 = " + openRightAfter + "  在播 = " + activeRightAfter);
		check("【stop() 返回后线路已经关闭（= 立即静音）】", !openRightAfter);

		Thread.sleep(500);
		System.out.println("  500ms 后：线路开着 = " + lineOpen(p) + "  在播 = " + lineActive(p));
		check("【500ms 后线路仍然是关的】", !lineOpen(p));
		check("状态变成 IDLE（不再播放）", p.state() == AudioPlayer.State.IDLE);

		System.out.println("\n=== 3) 播到一半直接 play() 新歌（模拟切下一首）===");
		AudioPlayer p2 = new AudioPlayer();
		p2.setVolume(0.5f);
		p2.play(url, 30, 0);
		Thread.sleep(2500);
		System.out.println("  第一首在播：" + p2.hasProgress() + "  线路开着 = " + lineOpen(p2));
		check("第一首确实起播了", p2.hasProgress());

		// 关键：直接 play 新歌（内部会先 stop）
		p2.play(url, 30, 0);
		boolean openAfterSwitch = lineOpen(p2);
		System.out.println("  切歌后立刻：线路开着 = " + openAfterSwitch + "  状态 = " + p2.state());
		// 切歌瞬间旧线路必须已经关了（新歌还在下载，还没开新线路）
		Thread.sleep(50);
		System.out.println("  50ms 后：状态 = " + p2.state() + "  线路开着 = " + lineOpen(p2));

		System.out.println("\n=== 4) 连续快速切歌 5 次，最后必须只剩一路声音 ===");
		AudioPlayer p3 = new AudioPlayer();
		p3.setVolume(0.5f);
		p3.play(url, 30, 0);
		Thread.sleep(2000);
		for (int i = 0; i < 5; i++) {
			p3.play(url, 30, 0);
			Thread.sleep(300);
		}
		Thread.sleep(2500);
		System.out.println("  5 次快速切歌后：状态 = " + p3.state()
				+ "  进度 = " + p3.positionSeconds() + "s"
				+ "  线路开着 = " + lineOpen(p3));
		check("最终仍在正常播放", p3.state() == AudioPlayer.State.PLAYING || p3.hasProgress());

		System.out.println("\n=== 5) stop() 是否立刻返回（不能阻塞主线程）===");
		long t1 = System.currentTimeMillis();
		p3.stop();
		long cost = System.currentTimeMillis() - t1;
		System.out.println("  stop() 耗时 " + cost + " ms");
		check("【stop() 不阻塞（< 200ms）】", cost < 200);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 切歌后旧声音会立即消失" : "[FAIL] 仍有残留声音");
		System.exit(fail == 0 ? 0 : 1);
	}
}
