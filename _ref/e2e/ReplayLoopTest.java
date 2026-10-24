import com.yue.music.audio.AudioPlayer;
import com.yue.music.audio.PcmSource;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 验证「一首歌播完后会不会被无限重播」。
 *
 * <h3>怀疑的 bug</h3>
 * <pre>
 *   runLoop() {
 *     while (true) {
 *       req = request;
 *       if (req != null &amp;&amp; req.token() == generation) playOne(req);   // 播完回来又播
 *     }
 *   }
 * </pre>
 * {@code playOne()} 自然播完后 request 没变、generation 也没变 →
 * 会立刻再播一遍 → 无限重播。玩家听到的就是「响两句就没声了」（反复重启）。
 *
 * <h3>判据</h3>
 * 播放一段时间后，{@code playedBytes} 不应该反复归零（归零 = 又重新播了一遍）。
 */
public class ReplayLoopTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	static long playedBytesOf(AudioPlayer p) {
		try {
			Field f = AudioPlayer.class.getDeclaredField("playedBytes");
			f.setAccessible(true);
			return f.getLong(p);
		} catch (Exception e) {
			return -1;
		}
	}

	public static void main(String[] args) throws Exception {
		String url = args.length > 0 ? args[0] : "http://127.0.0.1:18960/a.flac";
		System.out.println("音频地址: " + url);

		// 预检
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
		p.setAudioEnabled(true);
		p.setVolume(0f);                     // 静音测试，不吵
		p.play(url, 30, 0);

		System.out.println("\n=== 连续采样 playedBytes（播完不该归零）===");
		long prev = 0;
		int resets = 0;
		int samples = 0;
		for (int i = 0; i < 60; i++) {       // 采样 6 秒
			Thread.sleep(100);
			long now = playedBytesOf(p);
			samples++;
			if (now < prev && prev > 100000) {   // 明显倒退 = 又从头播了
				resets++;
				System.out.println("  ⚠ 第 " + (i + 1) + " 次采样：playedBytes 从 "
						+ prev + " 掉回 " + now + "  → 重新播了一遍！");
			}
			prev = now;
			if (i % 10 == 9) {
				System.out.println("  " + ((i + 1) / 10) + " 秒：playedBytes=" + now
						+ "  进度=" + p.positionSeconds() + "s  状态=" + p.state());
			}
		}

		System.out.println("\n=== 断言 ===");
		System.out.println("  采样 " + samples + " 次，检测到 playedBytes 归零 " + resets + " 次");
		check("【播放期间不该反复从头开始（resets == 0）】", resets == 0);
		check("确实写出过音频数据", prev > 0);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 不会无限重播"
				: "[FAIL] 确认存在无限重播（runLoop 反复调用 playOne）");
		System.exit(fail == 0 ? 0 : 1);
	}
}
