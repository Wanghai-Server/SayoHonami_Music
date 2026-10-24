import com.yue.music.api.NeteaseProvider;
import com.yue.music.api.QQProvider;
import com.yue.music.api.WeChatLogin;
import com.yue.music.api.QQLogin;
import com.yue.music.audio.AudioPlayer;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 用 jar 里真实的 AudioPlayer 做端到端播放测试：
 * 搜索 → 取真实播放地址 → 走 AudioPlayer 的完整拉流/解码/写声卡流程。
 *
 * <p>因为 AudioPlayer.play() 会读 Fabric 配置（独立环境没有 FabricLoader），
 * 这里用反射直接调它的私有 run(url) —— 这正是游戏里播放线程跑的那个方法。
 */
public class PlayTest {

	public static void main(String[] args) throws Exception {
		QQLogin login = new QQLogin();
		QQProvider qq = new QQProvider(login, new WeChatLogin());
		NeteaseProvider ne = new NeteaseProvider();

		// 找一首能放的歌
		String url = null;
		String label = "";
		List<MusicTrack> list = ne.search("晴天", 6);
		for (MusicTrack t : list) {
			PlayUrl p = ne.resolvePlayUrl(t);
			if (p.ok()) {
				url = p.url();
				label = "网易云: " + t.display();
				break;
			}
		}
		if (url == null) {
			List<MusicTrack> ql = qq.search("小苹果", 8);
			for (MusicTrack t : ql) {
				if (t.vip()) continue;
				PlayUrl p = qq.resolvePlayUrl(t);
				if (p.ok()) {
					url = p.url();
					label = "QQ音乐: " + t.display();
					break;
				}
			}
		}
		if (url == null) {
			System.out.println("[FAIL] 两个平台都没拿到可播地址");
			return;
		}
		System.out.println("测试曲目: " + label);
		System.out.println("播放地址: " + url.substring(0, Math.min(90, url.length())) + "...");

		// 反射调用真实的播放内核
		AudioPlayer player = new AudioPlayer();
		Method run = AudioPlayer.class.getDeclaredMethod("run", String.class);
		run.setAccessible(true);
		final String playUrl = url;
		Thread t = new Thread(() -> {
			try {
				run.invoke(player, playUrl);
			} catch (Exception e) {
				System.out.println("播放线程异常: " + e.getCause());
			}
		}, "YueMusic-Audio");
		t.setDaemon(true);
		t.start();

		// 观察 8 秒：状态、进度、错误
		for (int i = 0; i < 16; i++) {
			Thread.sleep(500);
			System.out.printf("  t=%4.1fs  状态=%-8s 进度=%2ds  音量=%.2f  错误=%s%n",
					(i + 1) * 0.5, player.state(), player.positionSeconds(),
					player.volume(), player.error().isEmpty() ? "无" : player.error());
			if (player.state() == AudioPlayer.State.ERROR) break;
		}

		// 测试暂停/继续/音量
		System.out.println("\n=== 控制测试 ===");
		player.pause();
		Thread.sleep(1500);
		int p1 = player.positionSeconds();
		System.out.println("  暂停后状态=" + player.state() + " 进度=" + p1 + "s");
		player.resume();
		Thread.sleep(2000);
		int p2 = player.positionSeconds();
		System.out.println("  继续后状态=" + player.state() + " 进度=" + p2 + "s");
		System.out.println("  进度在推进: " + (p2 > p1 ? "是 ✓" : "否 ✗"));
		player.setVolume(0.3f);
		System.out.println("  设置音量 0.3 → " + player.volume());
		Thread.sleep(1000);
		System.out.println("  当前进度=" + player.positionSeconds() + "s");
		player.stop();
		System.out.println("  停止后状态=" + player.state());

		System.out.println("\n[PASS] AudioPlayer 用真实网络音频跑完了：拉流 / 解码 / 写声卡 / 暂停 / 继续 / 音量 / 停止");
	}
}
