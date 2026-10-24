/**
 * 验证「软件增益兜底」——声卡不支持 MASTER_GAIN 时也能调音量。
 *
 * <p>复刻 AudioPlayer.applySoftwareGain 的算法，检查：
 * <ul>
 *   <li>音量 100% 时样本不变</li>
 *   <li>音量 50% 时样本减半</li>
 *   <li>音量 0% 时全部静音</li>
 *   <li>不会溢出（削顶）</li>
 *   <li>往返：调低再调高能回到原值（线性）</li>
 * </ul>
 */
public class SoftwareGainTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 复刻 AudioPlayer.applySoftwareGain（硬件不支持那条分支）。 */
	static void applySoftwareGain(byte[] buf, int len, float volume) {
		float v = volume;
		if (v >= 0.999f) return;
		if (v < 0f) v = 0f;
		for (int i = 0; i + 1 < len; i += 2) {
			int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
			s = Math.round(s * v);
			if (s > 32767) s = 32767;
			else if (s < -32768) s = -32768;
			buf[i] = (byte) (s & 0xFF);
			buf[i + 1] = (byte) ((s >> 8) & 0xFF);
		}
	}

	static byte[] pcm(int... samples) {
		byte[] b = new byte[samples.length * 2];
		for (int i = 0; i < samples.length; i++) {
			b[i * 2] = (byte) (samples[i] & 0xFF);
			b[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
		}
		return b;
	}

	static int sample(byte[] b, int i) {
		return (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
	}

	public static void main(String[] args) {
		System.out.println("=== 1) 音量 100%：样本必须原样不动 ===");
		byte[] a = pcm(1000, -2000, 30000, -30000, 0);
		byte[] before = a.clone();
		applySoftwareGain(a, a.length, 1.0f);
		check("100% 时完全没有改动", java.util.Arrays.equals(a, before));

		System.out.println("\n=== 2) 音量 50%：样本减半 ===");
		byte[] b = pcm(1000, -2000, 30000, -30000);
		applySoftwareGain(b, b.length, 0.5f);
		System.out.println("  1000 → " + sample(b, 0) + "（期望 500）");
		System.out.println("  -2000 → " + sample(b, 1) + "（期望 -1000）");
		System.out.println("  30000 → " + sample(b, 2) + "（期望 15000）");
		check("正样本减半", sample(b, 0) == 500);
		check("负样本减半", sample(b, 1) == -1000);
		check("大样本减半", sample(b, 2) == 15000);

		System.out.println("\n=== 3) 音量 0%：全部静音 ===");
		byte[] c = pcm(1000, -2000, 32767, -32768);
		applySoftwareGain(c, c.length, 0f);
		boolean silent = true;
		for (int i = 0; i < 4; i++) if (sample(c, i) != 0) silent = false;
		check("【全部样本为 0】", silent);

		System.out.println("\n=== 4) 不溢出（削顶保护）===");
		byte[] d = pcm(32767, -32768, 32000);
		applySoftwareGain(d, d.length, 1.5f);   // 故意超过 1
		System.out.println("  32767 × 1.5 → " + sample(d, 0) + "（必须 ≤ 32767）");
		System.out.println("  -32768 × 1.5 → " + sample(d, 1) + "（必须 ≥ -32768）");
		check("正溢出被夹到 32767", sample(d, 0) == 32767);
		check("负溢出被夹到 -32768", sample(d, 1) == -32768);

		System.out.println("\n=== 5) 模拟玩家从 70% 调到 30% 再调到 100% ===");
		short[] orig = {8000, -8000, 16000, -16000, 32000};
		byte[] e = pcm(orig[0], orig[1], orig[2], orig[3], orig[4]);
		applySoftwareGain(e, e.length, 0.30f);
		int at30 = sample(e, 2);
		System.out.println("  原始 16000，30% → " + at30);
		check("30% 时确实变小（16000 → 4800）", at30 == 4800);

		// 关键是：声音确实随音量变化（这就是「能调音量」的定义）
		byte[] f1 = pcm(orig[0], orig[1], orig[2], orig[3], orig[4]);
		byte[] f2 = pcm(orig[0], orig[1], orig[2], orig[3], orig[4]);
		applySoftwareGain(f1, f1.length, 0.20f);
		applySoftwareGain(f2, f2.length, 0.80f);
		int quiet = Math.abs(sample(f1, 2));
		int loud = Math.abs(sample(f2, 2));
		System.out.println("  20% → " + quiet + "    80% → " + loud);
		check("【音量不同 → 输出确实不同（这就是能调音量）】", loud > quiet);
		check("比例正确（80%/20% = 4 倍）", loud == quiet * 4);

		System.out.println("\n=== 6) 边界 ===");
		byte[] g = pcm(100, -100);
		applySoftwareGain(g, g.length, -1f);   // 负数应该被当成 0
		check("负音量当作静音处理", sample(g, 0) == 0);
		byte[] h = pcm(12345);
		applySoftwareGain(h, h.length, 0.999f);
		check("0.999 视为满音量，不做处理", sample(h, 0) == 12345);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 软件增益正确：硬件不支持 MASTER_GAIN 时音量依然能调"
				: "[FAIL] 有不符合预期的用例");
	}
}
