import com.yue.music.api.NeteaseProvider;
import com.yue.music.audio.AudioPlayer;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 验证「seek 追进度」不会中断播放。
 *
 * <p>这是修 bug「别人听得到我听不到」的核心：以前漂移校正是「停掉重播」，
 * 重播要重新下载缓冲，网络慢的客户端会被反复打断，永远停在起播阶段。
 * 现在改成播放中丢弃数据追进度（不中断下载），必须验证它真的能追上且不卡死。
 */
public class SeekTest {

	public static void main(String[] args) throws Exception {
		NeteaseProvider ne = new NeteaseProvider();
		String url = null;
		String label = "";
		for (MusicTrack t : ne.search("晴天", 6)) {
			PlayUrl p = ne.resolvePlayUrl(t);
			if (p.ok()) {
				url = p.url();
				label = t.display();
				break;
			}
		}
		if (url == null) {
			System.out.println("[FAIL] 没拿到可播地址");
			return;
		}
		System.out.println("测试曲目: " + label);

		AudioPlayer player = new AudioPlayer();
		Field d = AudioPlayer.class.getDeclaredField("reportedDuration");
		d.setAccessible(true);
		d.setInt(player, 278);

		Method run = AudioPlayer.class.getDeclaredMethod("run", String.class);
		run.setAccessible(true);
		final String playUrl = url;
		Thread t = new Thread(() -> {
			try {
				run.invoke(player, playUrl);
			} catch (Exception ignored) {
			}
		}, "YueMusic-SeekTest");
		t.setDaemon(true);
		t.start();

		Thread.sleep(3000);
		int before = player.positionSeconds();
		System.out.printf("起播 3 秒后进度 = %ds%n", before);

		// 模拟「落后服务端 20 秒」→ seek 追上
		int target = before + 20;
		player.seekTo(target);
		System.out.printf("调用 seekTo(%d) —— 模拟落后 20 秒%n", target);

		Thread.sleep(2500);
		int mid = player.positionSeconds();
		boolean caughtUp = mid >= target - 3;
		System.out.printf("seek 后 2.5 秒进度 = %ds  状态=%-8s  %s%n",
				mid, player.state(), caughtUp ? "✓ 已追上" : "✗ 没追上");

		// 关键：seek 之后必须还在正常播放（不能变成 IDLE/ERROR）
		boolean stillPlaying = player.state() == AudioPlayer.State.PLAYING;

		// 再验证一次：连续 seek 不应该把它弄挂
		player.seekTo(mid + 15);
		Thread.sleep(2000);
		boolean aliveAfterSecondSeek = player.state() == AudioPlayer.State.PLAYING;
		System.out.printf("连续第二次 seek 后状态 = %s  %s%n",
				player.state(), aliveAfterSecondSeek ? "✓ 仍正常播放" : "✗ 挂了");

		player.stop();
		System.out.println();
		if (caughtUp && stillPlaying && aliveAfterSecondSeek) {
			System.out.println("[PASS] seek 追进度正常：能追上、不中断、可连续使用");
		} else {
			System.out.println("[FAIL] seek 有问题：caughtUp=" + caughtUp
					+ " stillPlaying=" + stillPlaying
					+ " aliveAfterSecondSeek=" + aliveAfterSecondSeek);
		}
	}
}
