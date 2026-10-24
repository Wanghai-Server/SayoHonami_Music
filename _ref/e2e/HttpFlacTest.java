import com.yue.music.audio.PcmSource;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 用与 AudioPlayer 完全相同的 java.net.http.HttpClient 路径测试 FLAC 解码，
 * 并对比不同 BufferedInputStream 缓冲大小。
 *
 * <p>背景：FLAC over HTTP 之前报
 * {@code InputStream must support mark(), but doesn't} —— 这说明 jflac 拿到的
 * 流的 mark 支持有问题。要确认是不是 BufferedInputStream 缓冲太小导致 reset() 失败。
 *
 * 用法: HttpFlacTest <url> [bufferSize]
 */
public class HttpFlacTest {

	public static void main(String[] args) throws Exception {
		String url = args[0];
		int buf = args.length > 1 ? Integer.parseInt(args[1]) : 64 * 1024;
		System.out.println("缓冲 = " + buf + " 字节");

		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.connectTimeout(java.time.Duration.ofSeconds(10))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(java.time.Duration.ofSeconds(20))
				.header("User-Agent", "Mozilla/5.0")
				.header("Referer", "https://music.163.com/")
				.header("Accept", "*/*")
				.build();

		HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
		System.out.println("HTTP " + resp.statusCode());
		InputStream body = resp.body();
		System.out.println("原始流类型: " + body.getClass().getName()
				+ "  markSupported=" + body.markSupported());

		try (InputStream raw = new BufferedInputStream(body, buf);
			 PcmSource pcm = PcmSource.open(raw)) {
			byte[] b = new byte[8192];
			long total = 0;
			int n;
			while (total < 352800 && (n = pcm.read(b)) > 0) total += n;
			System.out.printf("  OK %d 字节 PCM（%.1f 秒）%n", total, total / 176400.0);
			System.out.println(total > 0 ? "[PASS] 成功" : "[FAIL] 无数据");
		} catch (Throwable e) {
			System.out.println("  X " + e.getClass().getSimpleName() + " - " + e.getMessage());
			System.out.println("[FAIL] 缓冲=" + buf);
		}
	}
}
