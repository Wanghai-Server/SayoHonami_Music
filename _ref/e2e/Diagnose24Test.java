import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 逐步拆解：为什么 24-bit FLAC 归一化没生效。
 * 直接打印每一步的实际对象与格式，不依赖日志框架。
 */
public class Diagnose24Test {

	public static void main(String[] args) throws Exception {
		String path = args[0];
		System.out.println("文件: " + path);

		try (InputStream raw = new BufferedInputStream(new FileInputStream(path), 64 * 1024)) {
			System.out.println("\n--- 步骤1: AudioSystem.getAudioInputStream(InputStream) ---");
			AudioInputStream decoded = AudioSystem.getAudioInputStream(raw);
			AudioFormat fmt = decoded.getFormat();
			System.out.println("  格式   : " + fmt);
			System.out.println("  encoding        = " + fmt.getEncoding());
			System.out.println("  sampleSizeBits  = " + fmt.getSampleSizeInBits());
			System.out.println("  sampleRate      = " + fmt.getSampleRate());
			System.out.println("  channels        = " + fmt.getChannels());
			System.out.println("  frameSize       = " + fmt.getFrameSize());
			System.out.println("  isBigEndian     = " + fmt.isBigEndian());

			System.out.println("\n--- 步骤2: 判断分支 ---");
			boolean isPcm = fmt.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
			System.out.println("  encoding == PCM_SIGNED ? " + isPcm);
			System.out.println("  bits != 16 ? " + (fmt.getSampleSizeInBits() != 16));
			boolean takeFix = isPcm && fmt.getSampleSizeInBits() != 16;
			System.out.println("  → 应该走 24→16 手工转换分支? " + takeFix);

			if (!takeFix) {
				System.out.println("\n  ⚠ 分支没进！原因是 encoding 不是 PCM_SIGNED");
				System.out.println("     这是 jflac 的自定义编码 FLAC，不是 PCM ——");
				System.out.println("     jflac 直接输出【已解码的 PCM 数据】但把编码标成 FLAC。");
				System.out.println("     所以正确判据应该是：FLAC 编码 + 24 bit 也要手工转。");
			}

			System.out.println("\n--- 步骤3: 试标准转换（会失败的那个）---");
			AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
					44100f, 16, 2, 4, 44100f, false);
			try (AudioInputStream conv = AudioSystem.getAudioInputStream(target, decoded)) {
				byte[] b = new byte[8192];
				int n = conv.read(b);
				System.out.println("  ✓ 标准转换成功，读到 " + n + " 字节");
			} catch (Throwable e) {
				System.out.println("  ✗ 标准转换失败: " + e.getMessage());
			}
		}
	}
}
