import com.yue.music.api.NeteaseProvider;
import com.yue.music.audio.AudioPlayer;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 验证 stop() 不再阻塞（修「插播时静音 2.5 秒」）。
 *
 * <p>以前 stop() 会 worker.join(2500) 等旧线程退出，而旧线程可能正卡在
 * SourceDataLine.write() 上 —— 结果是切歌/插播时静音最长 2.5 秒。
 * 现在改成「打标记 + 立刻关线路」，必须实测它是否真的立刻返回。
 */
public class StopLatencyTest {

	public static void main(String[] args) throws Exception {
		NeteaseProvider ne = new NeteaseProvider();
		String url = null;
		for (MusicTrack t : ne.search("晴天", 6)) {
			PlayUrl p = ne.resolvePlayUrl(t);
			if (p.ok()) {
				url = p.url();
				break;
			}
		}
		if (url == null) {
			System.out.println("[FAIL] 没拿到可播地址");
			return;
		}

		// 公开 API 即可（不再反射调 run）

		int worst = 0;
		boolean allFast = true;

		// 连续切歌 5 次，每次测 stop() 的耗时
		for (int i = 1; i <= 5; i++) {
			AudioPlayer player = new AudioPlayer();
			// 直接用公开 API 起播（1.5.1_hotfix1 起播放线程是常驻的，不再反射调 run）
			player.play(url, 278, 0);

			// 让它播一会儿（正在 write 的时候去 stop，最容易撞到阻塞）
			Thread.sleep(i == 1 ? 2500 : 800);

			long t0 = System.nanoTime();
			player.stop();
			int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
			worst = Math.max(worst, ms);
			if (ms > 300) allFast = false;
			System.out.printf("  第 %d 次 stop() 耗时 = %3d ms  %s%n",
					i, ms, ms > 300 ? "✗ 还是阻塞" : "✓ 立刻返回");
			Thread.sleep(200);
		}

		System.out.println();
		System.out.printf("最大耗时 = %d ms%n", worst);
		if (allFast) {
			System.out.println("[PASS] stop() 不再阻塞（以前最坏 2500ms）");
		} else {
			System.out.println("[FAIL] stop() 仍然会卡住");
		}
	}
}
