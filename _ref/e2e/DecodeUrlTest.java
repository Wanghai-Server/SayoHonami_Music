import com.yue.music.audio.PcmSource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 端到端验证：模拟真实播放链
 *   HTTP 拉流 → PcmSource.open() → 读 PCM
 *
 * <p>用第 1 个参数当音频地址。用来验证「加了 jflac 之后 FLAC 直链能不能正常解码」。
 */
public class DecodeUrlTest {

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("用法: DecodeUrlTest <音频直链>");
			return;
		}
		String url = args[0];
		System.out.println("地址: " + (url.length() > 90 ? url.substring(0, 90) + "..." : url));

		HttpClient c = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.version(HttpClient.Version.HTTP_1_1)
				.build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "Mozilla/5.0")
				.header("Referer", "https://music.163.com/")
				.build();

		// 先看文件头（判断真实格式）
		HttpResponse<InputStream> r1 = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
		System.out.println("HTTP 状态: " + r1.statusCode());
		if (r1.statusCode() / 100 != 2) {
			System.out.println("[FAIL] HTTP 失败");
			return;
		}
		byte[] head;
		try (InputStream in = r1.body()) {
			head = in.readNBytes(8);
		}
		System.out.println("文件头: " + magic(head));

		// 完整走一遍 PcmSource
		long t0 = System.currentTimeMillis();
		HttpResponse<InputStream> r2 = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = r2.body(); PcmSource pcm = PcmSource.open(in)) {
			byte[] buf = new byte[8192];
			long total = 0;
			int n;
			// 读约 2 秒（44100*4 = 176400 字节/秒）
			while (total < 352800 && (n = pcm.read(buf)) > 0) total += n;
			long ms = System.currentTimeMillis() - t0;
			System.out.printf("✓ 解码成功：%d 字节 PCM（约 %.1f 秒），耗时 %d ms%n",
					total, total / 176400.0, ms);
			// 检查是不是静音（全 0）
			int nonZero = 0;
			for (byte b : buf) if (b != 0) nonZero++;
			System.out.println("  样本非零字节数（最后一个缓冲）: " + nonZero + " / " + buf.length);
			System.out.println(total > 0 ? "[PASS] 该地址可以正常播放" : "[FAIL] 没解出数据");
		} catch (Throwable e) {
			System.out.println("[FAIL] 解码失败: " + e.getClass().getSimpleName()
					+ " - " + e.getMessage());
		}
	}

	static String magic(byte[] b) {
		if (b.length < 4) return "太短";
		if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "FLAC";
		if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "MP3(ID3)";
		if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "MP3";
		if (b.length > 7 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "MP4/M4A";
		if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g') return "OGG";
		if (b[0] == 'R' && b[1] == 'I') return "RIFF/WAV";
		return String.format("未知(%02X %02X %02X %02X)", b[0], b[1], b[2], b[3]);
	}
}
