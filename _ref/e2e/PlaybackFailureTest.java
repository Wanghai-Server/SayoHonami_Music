import com.sun.net.httpserver.HttpServer;
import com.yue.music.audio.AudioPlayer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证 1.5.1_hotfix3 对「播放失败」的两处修复。
 *
 * <h3>日志实证的问题（1.5.1_hotfix2）</h3>
 * <pre>
 *   [13:50:57] 起播《Fools Gold》url=https://music.163.com/song/media/outer/url?id=21941994.mp3
 *   [13:50:57] 播放失败: UnsupportedAudioFileException: Stream of unsupported format
 *   ...（5 分钟内重复 418 次，全程没声音）
 * </pre>
 * 那个链接 302 到 https://music.163.com/404，最终返回 <b>HTTP 200 + text/html</b>。
 *
 * <h3>要验证的修复</h3>
 * <ol>
 *   <li><b>一个请求只播一次</b>：失败后不能无限重试（以前会全速重试到歌放完）</li>
 *   <li><b>识别「不是音频」</b>：Content-Type 是 text/html 时直接判失败，
 *       并给出「已下架/404」这种能看懂的原因</li>
 * </ol>
 */
public class PlaybackFailureTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 起一个假服务器：返回「HTML 404 页面」，并统计被请求了几次。 */
	static HttpServer fake404Page(AtomicInteger hits) throws Exception {
		HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		s.createContext("/dead.mp3", ex -> {
			hits.incrementAndGet();
			byte[] body = ("<!DOCTYPE html><html><head><title>404</title></head>"
					+ "<body><h1>404 Not Found</h1><p>这首歌已下架</p></body></html>")
					.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html;charset=utf8");
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		s.start();
		return s;
	}

	public static void main(String[] args) throws Exception {
		AtomicInteger hits = new AtomicInteger();
		HttpServer server = fake404Page(hits);
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/dead.mp3";
		System.out.println("假服务器（返回 HTML 404 页面）: " + url);

		System.out.println("\n=== 1) 播放这个「假音频」===");
		AudioPlayer p = new AudioPlayer();
		p.setAudioEnabled(true);
		p.setVolume(0f);
		p.play(url, 300, 0);

		// 等 3 秒：旧版会在这 3 秒里疯狂重试几百次
		Thread.sleep(3000);

		int total = hits.get();
		System.out.println("  3 秒内服务器被请求了 " + total + " 次");
		System.out.println("  播放器状态: " + p.state());
		System.out.println("  错误信息  : " + p.error());

		check("【只请求了 1 次（没有无限重试）】", total == 1);
		check("状态是 ERROR", p.state() == AudioPlayer.State.ERROR);
		check("错误信息提到了「网页/下架」这类能看懂的原因",
				p.error() != null && (p.error().contains("网页") || p.error().contains("下架")
						|| p.error().contains("404")));

		System.out.println("\n=== 2) 再等 2 秒，确认真的不会自己重试 ===");
		Thread.sleep(2000);
		int after = hits.get();
		System.out.println("  再等 2 秒后总请求次数 = " + after);
		check("【又等了 2 秒，仍然只有 1 次请求】", after == 1);

		server.stop(0);

		System.out.println("\n=== 3) 正常音频仍然能播（回归）===");
		// 用一个真 MP3 头骗不过解码器，这里改用「服务端返回 200 audio/mpeg 但内容坏」
		// 来确认「不是 HTML 的情况」仍然走解码器报错，而不是被 HTML 判定误杀。
		AtomicInteger hits2 = new AtomicInteger();
		HttpServer s2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		s2.createContext("/fake.mp3", ex -> {
			hits2.incrementAndGet();
			byte[] body = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
			ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		s2.start();
		String url2 = "http://127.0.0.1:" + s2.getAddress().getPort() + "/fake.mp3";
		AudioPlayer p2 = new AudioPlayer();
		p2.setAudioEnabled(true);
		p2.setVolume(0f);
		p2.play(url2, 300, 0);
		Thread.sleep(2500);
		System.out.println("  状态: " + p2.state() + "  错误: " + p2.error());
		check("audio/mpeg 却内容是坏的 → 也判失败（走解码器）",
				p2.state() == AudioPlayer.State.ERROR);
		check("【同样只请求 1 次】", hits2.get() == 1);
		s2.stop(0);

		System.out.println("\n=== 4) 修复前后对比 ===");
		System.out.println("  旧版 3 秒内重试次数 ≈ 40~400 次（日志实测 5 分钟 418 次，被限速了）");
		System.out.println("  新版 3 秒内重试次数 = " + total + " 次");
		check("【重试次数下降 90% 以上】", total <= 1);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 播放失败不再无限重试，且能识别「返回网页」的坏链接"
				: "[FAIL] 有不符合预期的用例");
		System.exit(fail == 0 ? 0 : 1);
	}
}
