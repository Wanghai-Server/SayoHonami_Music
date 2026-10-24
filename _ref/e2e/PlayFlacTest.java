import com.yue.music.audio.AudioPlayer;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 终极验证：用与 AudioPlayer 完全相同的路径（HTTP + BufferedInputStream + PcmSource + 声卡）
 * 播放 24-bit FLAC，确认「VIP 歌没声音」真的修好了。
 *
 * 用法: PlayFlacTest <url>
 */
public class PlayFlacTest {

	public static void main(String[] args) throws Exception {
		String url = args[0];
		System.out.println("地址: " + url);

		// 先确认能解码
		HttpClient c = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.version(HttpClient.Version.HTTP_1_1).build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).build();
		HttpResponse<InputStream> r = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
		System.out.println("HTTP " + r.statusCode());
		try (InputStream in = new BufferedInputStream(r.body(), 64 * 1024);
			 com.yue.music.audio.PcmSource pcm = com.yue.music.audio.PcmSource.open(in)) {
			byte[] b = new byte[8192];
			long total = 0;
			int n;
			while (total < 176400 && (n = pcm.read(b)) > 0) total += n;
			System.out.printf("  解码 OK：%d 字节（约 %.1f 秒）%n", total, total / 176400.0);
		}

		// 再用真实 AudioPlayer 播 3 秒，确认状态是 PLAYING 且进度在走
		System.out.println("\n--- 真实播放 3 秒 ---");
		AudioPlayer player = new AudioPlayer();
		Field dur = AudioPlayer.class.getDeclaredField("reportedDuration");
		dur.setAccessible(true);
		dur.setInt(player, 278);
		Method run = AudioPlayer.class.getDeclaredMethod("run", String.class);
		run.setAccessible(true);
		final String u = url;
		Thread t = new Thread(() -> {
			try {
				run.invoke(player, u);
			} catch (Exception ignored) {
			}
		}, "YueMusic-PlayFlac");
		t.setDaemon(true);
		t.start();

		Thread.sleep(3000);
		int pos = player.positionSeconds();
		AudioPlayer.State st = player.state();
		Field errF = AudioPlayer.class.getDeclaredField("error");
		errF.setAccessible(true);
		String err = String.valueOf(errF.get(player));
		System.out.println("  状态 = " + st + "  进度 = " + pos + "s");
		System.out.println("  错误 = " + (err.isEmpty() ? "(无)" : err));
		// 注意：必须在 stop() 之前取状态，否则 stop 会把 state 改成 IDLE
		player.stop();

		boolean ok = st == AudioPlayer.State.PLAYING && pos >= 1;
		System.out.println();
		System.out.println(ok ? "[PASS] 24-bit FLAC 可以正常播放出声"
				: "[FAIL] 播放失败");
	}
}
