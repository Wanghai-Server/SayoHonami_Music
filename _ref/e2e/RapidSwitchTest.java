import com.yue.music.api.NeteaseProvider;
import com.yue.music.audio.AudioPlayer;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证「快速切歌」时新歌不会被旧线程的收尾逻辑打断。
 *
 * <p>stop() 改成非阻塞后，旧播放线程还在后台跑。如果它的 finally 把
 * 声卡线路关掉，而那时 line 里已经是<b>新歌</b>的线路 —— 新歌就会没声音。
 * 所以加了 lineRef 判断：只关自己那条线路。这里实测它有没有效果。
 */
public class RapidSwitchTest {

	public static void main(String[] args) throws Exception {
		NeteaseProvider ne = new NeteaseProvider();
		List<String> urls = new ArrayList<>();
		for (MusicTrack t : ne.search("晴天", 8)) {
			PlayUrl p = ne.resolvePlayUrl(t);
			if (p.ok()) urls.add(p.url());
			if (urls.size() >= 3) break;
		}
		if (urls.size() < 2) {
			System.out.println("[SKIP] 可用地址不足，无法测试");
			return;
		}
		System.out.println("拿到 " + urls.size() + " 个可用地址");

		// 反复「快速切歌」：起播后很快停掉再起播，模拟插播
		int rounds = 5;
		int okCount = 0;
		for (int r = 1; r <= rounds; r++) {
			AudioPlayer player = new AudioPlayer();

			for (int i = 0; i < 3; i++) {
				// 每次只播 400ms 就切走（很像插播：上一首刚起就被打断）
				start(player, urls.get(i % urls.size()));
				Thread.sleep(400);
				player.stop();
			}

			// 最后起一首并让它稳定播 2.5 秒 → 必须真的有声音（进度在走）
			start(player, urls.get(0));
			Thread.sleep(2500);
			int pos = player.positionSeconds();
			boolean playing = player.state() == AudioPlayer.State.PLAYING;
			boolean advanced = pos >= 1;
			boolean ok = playing && advanced;
			if (ok) okCount++;

			// 诊断信息：看清为什么没出声
			Field lineF = AudioPlayer.class.getDeclaredField("line");
			lineF.setAccessible(true);
			Object line = lineF.get(player);
			Field pbF = AudioPlayer.class.getDeclaredField("playedBytes");
			pbF.setAccessible(true);
			long pb = pbF.getLong(player);
			Field errF = AudioPlayer.class.getDeclaredField("error");
			errF.setAccessible(true);
			String err = String.valueOf(errF.get(player));
			boolean lineOpen = false;
			long frames = -1;
			if (line instanceof javax.sound.sampled.SourceDataLine l) {
				lineOpen = l.isOpen();
				try {
					frames = l.getLongFramePosition();
				} catch (Exception ignored) {
				}
			}
			System.out.printf("  第 %d 轮：状态=%-8s 进度=%ds 已写=%d字节 line=%s open=%s frames=%d err=%s %s%n",
					r, player.state(), pos, pb,
					line == null ? "null" : "有", lineOpen, frames,
					err.isEmpty() ? "(无)" : err, ok ? "✓" : "✗");
			player.stop();
			Thread.sleep(200);
		}

		System.out.println();
		System.out.printf("结果: %d/%d 轮正常%n", okCount, rounds);
		if (okCount == rounds) {
			System.out.println("[PASS] 快速切歌后新歌能正常播放（旧线程不会关掉新线路）");
		} else {
			System.out.println("[FAIL] 有轮次新歌没出声");
		}
	}

	static void start(AudioPlayer player, String url) throws Exception {
		// 直接用公开 API（1.5.1_hotfix1 起播放线程常驻，play() 也不再依赖 Fabric 环境）
		player.play(url, 278, 0);
	}
}
