import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;

/**
 * 直接验证：现有解码链（PcmSource 用的 AudioSystem）能不能解 FLAC。
 *
 * <p>假设：QQ音乐 VIP 歌走 F000（无损FLAC），而 mp3spi/jlayer 只能解 MP3，
 * JDK 也没有 FLAC 解码器 → 拿到 FLAC 直链时整首歌放不出来（静音）。
 */
public class FlacDecodeTest {

	public static void main(String[] args) throws Exception {
		File f = new File(args.length > 0 ? args[0] : (System.getenv("TEMP") + "\\test.flac"));
		System.out.println("测试文件: " + f + "  存在=" + f.exists()
				+ "  大小=" + (f.length() / 1024) + "KB");

		byte[] head = new byte[4];
		try (var in = new java.io.FileInputStream(f)) {
			in.read(head);
		}
		System.out.printf("文件头: %c%c%c%c%n", head[0], head[1], head[2], head[3]);

		System.out.println();
		System.out.println("=== 步骤 1: AudioSystem.getAudioInputStream(File) ===");
		try (AudioInputStream decoded = AudioSystem.getAudioInputStream(f)) {
			AudioFormat fmt = decoded.getFormat();
			System.out.println("  ✓ 解码器找到了");
			System.out.println("    格式: " + fmt);
			System.out.println("    编码: " + fmt.getEncoding() + "  采样率=" + fmt.getSampleRate()
					+ "  位深=" + fmt.getSampleSizeInBits() + "  声道=" + fmt.getChannels());

			System.out.println();
			System.out.println("=== 步骤 2: 转成 PcmSource.TARGET（44100/16bit/立体声）===");
			AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
					44100f, 16, 2, 4, 44100f, false);
			try (AudioInputStream conv = AudioSystem.getAudioInputStream(target, decoded)) {
				byte[] buf = new byte[8192];
				int total = 0;
				int n;
				while (total < 65536 && (n = conv.read(buf)) > 0) total += n;
				System.out.println("  ✓ 转换成功，读到 " + total + " 字节 PCM");
				System.out.println();
				System.out.println("[PASS] FLAC 能被现有解码链解码");
				return;
			}
		} catch (Throwable e) {
			System.out.println("  ✗ 失败: " + e.getClass().getSimpleName()
					+ " - " + e.getMessage());
		}

		// 再单独试「有没有任何 FLAC 相关的 SPI」
		System.out.println();
		System.out.println("=== 诊断: 已安装的解码器提供商 ===");
		for (javax.sound.sampled.spi.AudioFileReader r :
				java.util.ServiceLoader.load(javax.sound.sampled.spi.AudioFileReader.class)) {
			System.out.println("  reader: " + r.getClass().getName());
		}
		System.out.println();
		System.out.println("[FAIL] FLAC 无法解码 —— 这就是 VIP 歌没声音的原因");
	}
}
