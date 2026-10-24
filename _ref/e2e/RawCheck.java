import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 直接检查 jflac 到底吐的是【解码后的 PCM】还是【原始 FLAC 文件字节】。
 *
 * <p>触发原因：用户反馈 24-bit 的歌是「一片噪音」，
 * 而 hex dump 里看到了 "fLaC" 文件头 → 怀疑根本没解码。
 */
public class RawCheck {

	public static void main(String[] a) throws Exception {
		File f = new File(a[0]);
		System.out.println("文件: " + f.getName() + "  " + (f.length() / 1024) + "KB");

		System.out.println("\n--- 1) AudioSystem.getAudioInputStream(File) ---");
		AudioInputStream in = AudioSystem.getAudioInputStream(f);
		System.out.println("  格式: " + in.getFormat());
		dump(in, "  File方式");

		System.out.println("\n--- 2) AudioSystem.getAudioInputStream(InputStream) ---");
		try (InputStream raw = new BufferedInputStream(new FileInputStream(f), 64 * 1024)) {
			AudioInputStream in2 = AudioSystem.getAudioInputStream(raw);
			System.out.println("  格式: " + in2.getFormat());
			dump(in2, "  Stream方式");
		}
	}

	static void dump(AudioInputStream in, String tag) throws Exception {
		byte[] b = new byte[32];
		int n = in.read(b);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) sb.append(String.format("%02X ", b[i]));
		System.out.println(tag + " 前 " + n + " 字节: " + sb);
		boolean isFlacHeader = n >= 4 && b[0] == 0x66 && b[1] == 0x4C
				&& b[2] == 0x61 && b[3] == 0x43;
		System.out.println(tag + (isFlacHeader
				? " 是 \"fLaC\" 文件头 -> 【没有解码】！"
				: " 不是文件头 -> 应该是解码后的数据"));
	}
}
