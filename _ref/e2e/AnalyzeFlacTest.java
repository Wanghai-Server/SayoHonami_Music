import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 分析 jflac 解码 24-bit FLAC 时「实际吐出的字节布局」。
 *
 * <p>背景：用户反馈「24-bit 的歌播放是一片噪音」，说明解码出数据了但**字节解析错了**。
 * 需要确认 jflac 输出的到底是：
 *   A) 真 24-bit（每采样 3 字节）
 *   B) 其实是 16-bit（每采样 2 字节）但格式标成 24-bit
 *   C) 32-bit 容器（每采样 4 字节，低 24 位有效）
 */
public class AnalyzeFlacTest {

	public static void main(String[] args) throws Exception {
		String path = args[0];
		System.out.println("文件: " + path);

		try (InputStream raw = new BufferedInputStream(new FileInputStream(path), 64 * 1024)) {
			AudioInputStream in = AudioSystem.getAudioInputStream(raw);
			AudioFormat f = in.getFormat();
			System.out.println("格式: " + f);
			System.out.println("  bits=" + f.getSampleSizeInBits()
					+ " channels=" + f.getChannels()
					+ " frameSize=" + f.getFrameSize()
					+ " bigEndian=" + f.isBigEndian());

			byte[] buf = new byte[64];
			int n = in.read(buf);
			System.out.println("\n读了 " + n + " 字节，hex dump（前 64 字节）：");
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < Math.min(n, 48); i++) {
				hex.append(String.format("%02X ", buf[i]));
				if ((i + 1) % 12 == 0) hex.append("\n  ");
			}
			System.out.println("  " + hex);

			// 关键推断：这些字节更像哪种布局？
			System.out.println("\n--- 布局推断 ---");
			int ch = f.getChannels();
			// 假设 A: 24-bit → 每帧 ch*3 字节
			int fsz24 = ch * 3;
			// 假设 B: 16-bit → 每帧 ch*2 字节
			int fsz16 = ch * 2;
			// 假设 C: 32-bit → 每帧 ch*4 字节
			int fsz32 = ch * 4;

			System.out.println("  若 24-bit：帧长 " + fsz24 + " 字节");
			System.out.println("  若 16-bit：帧长 " + fsz16 + " 字节");
			System.out.println("  若 32-bit：帧长 " + fsz32 + " 字节");

			// 统计「每 N 字节里高字节是否像填充」——
			// 真 24-bit 小端时，每 3 字节的第 3 字节（最高字节）变化应该较慢/接近符号扩展
			report("24-bit 小端(每3字节)", buf, n, 3, 2);
			report("16-bit 小端(每2字节)", buf, n, 2, 1);
			report("32-bit 小端(每4字节)", buf, n, 4, 3);

			// 额外：把同样数据按 16-bit 直接播放会不会更像声音？
			System.out.println("\n--- 幅度对比（判断哪个布局是「合理音量」）---");
			System.out.println("  按 16-bit 小端解读样本值（前 8 个）：");
			for (int i = 0; i + 1 < Math.min(n, 16); i += 2) {
				short v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
				System.out.printf("    [%d,%d] → %d%n", i, i + 1, v);
			}
			System.out.println("  按 24-bit 小端解读样本值（前 8 个）：");
			for (int i = 0; i + 2 < Math.min(n, 24); i += 3) {
				int v = (buf[i + 2] << 16) | ((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF);
				if ((v & 0x800000) != 0) v |= 0xFF000000;
				System.out.printf("    [%d,%d,%d] → %d%n", i, i + 1, i + 2, v);
			}
		}
	}

	/** 统计某个「第 k 字节」位置上的值分布，判断它像不像高字节。 */
	static void report(String name, byte[] buf, int n, int stride, int k) {
		int zeros = 0, ff = 0, count = 0;
		for (int i = k; i < Math.min(n, 60); i += stride) {
			int v = buf[i] & 0xFF;
			if (v == 0) zeros++;
			if (v == 0xFF) ff++;
			count++;
		}
		System.out.printf("  %-22s 第%d字节: 0x00占%d/%d  0xFF占%d/%d%n",
				name, k, zeros, count, ff, count);
	}
}
