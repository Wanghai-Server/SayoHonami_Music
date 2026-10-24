import com.yue.music.audio.PcmSource;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 用**真实的 PcmSource.open()** 测试各种位深的 FLAC。
 *
 * <p>注意：不能直接调 AudioSystem（那会绕过 PcmSource 里的 24→16 手工转换，
 * 测出来的还是修复前的行为）。
 *
 * 用法: PcmSourceTest <音频文件> [是否走 HTTP]
 */
public class PcmSourceTest {

	public static void main(String[] args) throws Exception {
		String path = args[0];
		System.out.println("文件: " + path);
		try (InputStream raw = new BufferedInputStream(new FileInputStream(path), 64 * 1024);
			 PcmSource pcm = PcmSource.open(raw)) {
			byte[] buf = new byte[8192];
			long total = 0;
			int n;
			// 目标：读 2 秒（44100 * 4 = 176400 字节/秒）
			while (total < 352800 && (n = pcm.read(buf)) > 0) total += n;
			System.out.printf("  ✓ 解出 %d 字节 PCM（约 %.2f 秒）%n", total, total / 176400.0);
			// 顺带看看有没有声音（非零样本）
			int nonZero = 0;
			for (byte b : buf) if (b != 0) nonZero++;
			System.out.println("  最后一个缓冲的非零字节: " + nonZero + " / " + buf.length);
			System.out.println(total > 0 ? "[PASS] PcmSource 能正常解码这个文件"
					: "[FAIL] 没解出数据");
		} catch (Throwable e) {
			System.out.println("  ✗ 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
			System.out.println("[FAIL] PcmSource 解码失败");
		}
	}
}
