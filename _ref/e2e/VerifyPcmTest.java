import com.yue.music.audio.PcmSource;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 验证 FLAC 解码输出是【真正的 PCM】而不是噪音（立体声正确版）。
 *
 * <p>关键：立体声是**左右声道交替**存的，所以比较相邻样本必须**按声道分开**，
 * 否则 L 和 R 的差值会被误当成"不平滑"。
 *
 * <p>判据：同一声道内相邻样本的差值应该远小于满量程（正弦波本来就是渐变的）。
 */
public class VerifyPcmTest {

	public static void main(String[] args) throws Exception {
		String path = args[0];
		System.out.println("文件: " + path);

		try (InputStream raw = new BufferedInputStream(new FileInputStream(path), 64 * 1024);
			 PcmSource pcm = PcmSource.open(raw)) {
			byte[] buf = new byte[16384];
			int n = pcm.read(buf);
			System.out.println("读了 " + n + " 字节");

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(16, n); i++) sb.append(String.format("%02X ", buf[i]));
			System.out.println("前 16 字节: " + sb);

			boolean isFlacHeader = n >= 4 && buf[0] == 0x66 && buf[1] == 0x4C
					&& buf[2] == 0x61 && buf[3] == 0x43;
			System.out.println("是 fLaC 头? " + isFlacHeader
					+ (isFlacHeader ? "  -> 没解码！" : "  -> 已解码 ✓"));

			// 假设输出是 16-bit 立体声：L R L R ...
			int ch = 2;
			int frames = n / (2 * ch);
			int[][] prev = new int[ch][1];
			boolean[] first = new boolean[ch];
			long[] sum = new long[ch];
			int[] max = new int[ch];
			int[] count = new int[ch];
			int globalMin = Integer.MAX_VALUE, globalMax = Integer.MIN_VALUE;

			for (int f = 0; f < frames; f++) {
				for (int c = 0; c < ch; c++) {
					int p = (f * ch + c) * 2;
					short v = (short) ((buf[p] & 0xFF) | (buf[p + 1] << 8));
					if (v < globalMin) globalMin = v;
					if (v > globalMax) globalMax = v;
					if (!first[c]) {
						first[c] = true;
					} else {
						int d = Math.abs(v - prev[c][0]);
						sum[c] += d;
						if (d > max[c]) max[c] = d;
						count[c]++;
					}
					prev[c][0] = v;
				}
			}

			System.out.printf("%n样本范围: %d ~ %d（满量程 ±32767）%n", globalMin, globalMax);
			for (int c = 0; c < ch; c++) {
				double avg = count[c] > 0 ? (double) sum[c] / count[c] : 0;
				System.out.printf("  声道%d: 同声道相邻差值 最大=%d 平均=%.1f%n", c + 1, max[c], avg);
			}

			// 真正的噪音：相邻样本差接近满量程（平均 > 8000）
			double avg0 = count[0] > 0 ? (double) sum[0] / count[0] : 0;
			boolean notNoise = avg0 < 6000;
			System.out.println();
			System.out.println("看起来像正常音频? " + notNoise
					+ (notNoise ? "  ✓" : "  ✗ 像噪音"));

			System.out.println();
			if (!isFlacHeader && notNoise) {
				System.out.println("[PASS] FLAC 输出是正常 PCM");
			} else {
				System.out.println("[FAIL] 输出异常");
			}
		}
	}
}
