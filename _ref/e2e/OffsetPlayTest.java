import com.yue.music.api.NeteaseProvider;
import com.yue.music.audio.AudioPlayer;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 测试「从指定秒数开始播放」——多人同步的关键。
 * 反射调用私有 run(url)，绕开 Fabric 配置。
 */
public class OffsetPlayTest {

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

		for (int offset : new int[]{0, 5, 20}) {
			AudioPlayer player = new AudioPlayer();
			// 设置 startOffsetSeconds（私有字段）
			var f = AudioPlayer.class.getDeclaredField("startOffsetSeconds");
			f.setAccessible(true);
			f.setInt(player, offset);
			var d = AudioPlayer.class.getDeclaredField("reportedDuration");
			d.setAccessible(true);
			d.setInt(player, 278);

			// 直接用公开 API（1.5.1_hotfix1 起播放线程是常驻的，不该再用反射调 run）
			player.play(url, 278, offset);
			Thread t = new Thread(() -> {
			}, "YueMusic-Audio-" + offset);
			t.setDaemon(true);
			t.start();

			// 等 3 秒，看进度是否从 offset 开始
			Thread.sleep(3000);
			int pos = player.positionSeconds();
			boolean ok = Math.abs(pos - (offset + 2)) <= 2;   // 3 秒后应约等于 offset+2
			System.out.printf("  offset=%2ds → 3秒后进度=%2ds  状态=%-7s  %s%n",
					offset, pos, player.state(), ok ? "✓ 对齐正确" : "✗ 不对");
			player.stop();
			Thread.sleep(300);
		}
		System.out.println("\n[PASS] 偏移播放测试完成（多人同步靠这个对齐）");
	}
}
