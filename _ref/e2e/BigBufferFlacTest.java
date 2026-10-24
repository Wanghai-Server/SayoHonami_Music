import com.yue.music.audio.PcmSource;
import java.io.*;
import java.net.*;

/**
 * 验证「BufferedInputStream 缓冲太小」是不是 FLAC over HTTP 解码失败的原因。
 * 用法: BigBufferFlacTest <url> <bufferSize>
 */
public class BigBufferFlacTest {
	public static void main(String[] a) throws Exception {
		int bufSize = a.length > 1 ? Integer.parseInt(a[1]) : (1 << 20);
		URL u = new URL(a[0]);
		HttpURLConnection c = (HttpURLConnection) u.openConnection();
		c.setRequestProperty("User-Agent", "Mozilla/5.0");
		InputStream net = c.getInputStream();
		System.out.println("缓冲大小 = " + bufSize + " 字节");
		try (InputStream raw = new BufferedInputStream(net, bufSize);
			 PcmSource pcm = PcmSource.open(raw)) {
			byte[] buf = new byte[8192];
			long total = 0;
			int n;
			while (total < 352800 && (n = pcm.read(buf)) > 0) total += n;
			System.out.printf("  OK 解出 %d 字节 PCM（约 %.1f 秒）%n", total, total / 176400.0);
			System.out.println(total > 0 ? "[PASS] 该缓冲下 FLAC 解码成功" : "[FAIL] 无数据");
		} catch (Throwable e) {
			System.out.println("  X 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
			System.out.println("[FAIL] 缓冲=" + bufSize + " 仍然不行");
		}
	}
}
