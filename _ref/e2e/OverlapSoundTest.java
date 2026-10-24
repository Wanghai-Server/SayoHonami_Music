import com.yue.music.audio.AudioPlayer;
import com.yue.music.audio.PcmSource;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证「同一时刻只有一路声音」。
 *
 * <h3>要复现的 bug（日志实证）</h3>
 * <pre>
 *   [14:30:45] 起播《Lifeline》 offset=0s
 *   [14:30:45] 起播《Lifeline》 offset=0s      ← 同一秒又来一次
 *   [14:30:45] 音频解码：MPEG1L3 ...           ← 线程 1
 *   [14:30:45] 音频解码：MPEG1L3 ...           ← 线程 2（同时！）
 *   ...同一首歌被解码 5 次 → 声音叠在一起
 * </pre>
 *
 * <h3>判据</h3>
 * 快速连续 play() 之后，<b>存活时间重叠的播放线程数必须始终 ≤ 1</b>。
 */
public class OverlapSoundTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 数一下当前存活的播放线程数量。 */
	static int countAudioThreads() {
		int c = 0;
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.isAlive() && "YueMusic-Audio".equals(t.getName())) c++;
		}
		return c;
	}

	static Thread playThreadOf(AudioPlayer p) {
		try {
			Field f = AudioPlayer.class.getDeclaredField("playThread");
			f.setAccessible(true);
			return (Thread) f.get(p);
		} catch (Exception e) {
			return null;
		}
	}

	static int generationOf(AudioPlayer p) {
		try {
			Field f = AudioPlayer.class.getDeclaredField("generation");
			f.setAccessible(true);
			return f.getInt(p);
		} catch (Exception e) {
			return -1;
		}
	}

	/** 采样：每隔一小段记录一次当前存活的 YueMusic-Audio 线程。 */
	static class Sampler extends Thread {
		final List<Integer> samples = new ArrayList<>();
		volatile boolean stop;

		Sampler() {
			setDaemon(true);
		}

		public void run() {
			while (!stop) {
				int c = 0;
				for (Thread t : Thread.getAllStackTraces().keySet()) {
					if (t.isAlive() && "YueMusic-Audio".equals(t.getName())) c++;
				}
				synchronized (samples) {
					samples.add(c);
				}
				try {
					Thread.sleep(15);
				} catch (InterruptedException e) {
					return;
				}
			}
		}

		int max() {
			synchronized (samples) {
				return samples.stream().mapToInt(Integer::intValue).max().orElse(0);
			}
		}
	}

	public static void main(String[] args) throws Exception {
		String url = args.length > 0 ? args[0] : "http://127.0.0.1:18943/a.flac";
		System.out.println("音频地址: " + url);

		// 预检：确认地址真的能解码
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

		System.out.println("\n=== 1) 只有一条播放线程（常驻）===");
		AudioPlayer p = new AudioPlayer();
		p.setVolume(0f);                    // 静音测试，不吵
		p.play(url, 30, 0);
		Thread.sleep(800);
		Thread t1 = playThreadOf(p);
		System.out.println("  播放线程 = " + (t1 == null ? "null" : t1.getName() + " id=" + t1.getId()));
		check("play() 之后有播放线程", t1 != null && t1.isAlive());

		p.play(url, 30, 0);
		Thread.sleep(200);
		Thread t2 = playThreadOf(p);
		System.out.println("  再 play() 之后 = " + (t2 == null ? "null" : "id=" + t2.getId()));
		check("【线程没有被重建（同一条件次复用）】", t1 == t2);

		System.out.println("\n=== 2) 快速连点 10 次：同一个播放器最多只有 1 条播放线程 ===");
		// 先把前面那个播放器停掉，免得它的常驻线程被采样器数进来
		p.stop();
		Thread.sleep(300);
		int before = countAudioThreads();
		System.out.println("  采样前已存在的播放线程数 = " + before);

		AudioPlayer p2 = new AudioPlayer();
		p2.setVolume(0f);
		Sampler s = new Sampler();
		s.start();
		for (int i = 0; i < 10; i++) {
			p2.play(url, 30, 0);
			Thread.sleep(80);               // 模拟手速：80ms 点一次
		}
		Thread.sleep(1500);
		s.stop = true;
		int maxThreads = s.max();
		System.out.println("  采样 " + s.samples.size() + " 次，最多同时存活播放线程 = " + maxThreads);
		// 关键是：相对采样前【只多了一条】（p2 自己的那条），没有累积
		check("【快速连点 10 次只多出 1 条播放线程（没有累积）】",
				maxThreads <= before + 1);
		check("【绝对数量不会爆炸（≤ 3）】", maxThreads <= 3);

		int alive = countAudioThreads();
		System.out.println("  测试结束时 YueMusic-Audio 线程数 = " + alive + "（每个活着的 AudioPlayer 各一条）");
		check("线程数不超过实例数（没有残留线程累积）", alive <= 3);
		p2.stop();

		System.out.println("\n=== 3) 切歌后代次变化，旧的一路立刻作废 ===");
		AudioPlayer p3 = new AudioPlayer();
		p3.setVolume(0f);
		p3.play(url, 30, 0);
		Thread.sleep(600);
		int genBefore = generationOf(p3);
		p3.play(url, 30, 1);
		int genAfter = generationOf(p3);
		System.out.println("  切歌前代次 = " + genBefore + "，切歌后 = " + genAfter);
		check("【切歌会让代次自增（旧的一路据此收工）】", genAfter > genBefore);

		System.out.println("\n=== 4) stop() 立刻返回 ===");
		long t0 = System.currentTimeMillis();
		p3.stop();
		long cost = System.currentTimeMillis() - t0;
		System.out.println("  stop() 耗时 " + cost + " ms");
		check("【stop() 不阻塞（< 200ms）】", cost < 200);
		check("状态变成 IDLE", p3.state() == AudioPlayer.State.IDLE);

		System.out.println("\n=== 5) 切歌后进度按新偏移走（不会用错偏移）===");
		AudioPlayer p4 = new AudioPlayer();
		p4.setVolume(0f);
		p4.play(url, 30, 0);
		Thread.sleep(300);
		p4.play(url, 30, 1);
		Thread.sleep(1200);
		System.out.println("  切到 offset=1s 后，进度 = " + p4.positionSeconds() + "s");
		check("进度从 1s 附近开始（1~5s）", p4.positionSeconds() >= 1 && p4.positionSeconds() <= 5);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 同一时刻只有一路声音，切歌不会叠加"
				: "[FAIL] 仍有叠加风险");
		System.exit(fail == 0 ? 0 : 1);
	}
}
