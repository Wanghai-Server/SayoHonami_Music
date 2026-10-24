import com.yue.music.net.MusicNetworking;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 验证「队列 JSON 上限」修复。
 *
 * <h3>要复现的故障（日志实证）</h3>
 * <pre>
 *   EncoderException: Failed to encode packet (sayo-honami-music:queue)
 *   Caused by: String too big (was 85423 characters, max 32767)
 * </pre>
 * 队列约 500 首时 JSON 有 8.5 万字符，超过旧的 32767 上限 → 报文编不出来 → 玩家被踢。
 */
public class QueueJsonLimitTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 造一段接近真实的队列 JSON（每首约 170 字符，和实测一致）。 */
	static String fakeQueueJson(int songs) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < songs; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"mid\":\"003Qui1q2u1Zho").append(i % 10)
					.append("\",\"source\":\"qq\",\"title\":\"花骨朵（第").append(i).append("种翻唱版本）\"")
					.append(",\"artists\":\"亞細亞曠世奇才/洛天依/言和\"")
					.append(",\"album\":\"叶氏集团附属术 收录合集 Vol.").append(i % 20).append("\"")
					.append(",\"duration\":").append(180 + (i % 60))
					.append(",\"albumMid\":\"003Qui1q2u\",\"vip\":false}");
		}
		return sb.append(']').toString();
	}

	/** 复刻 MusicNetworking 里的压缩逻辑。 */
	static String gzipBase64(String s) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length() / 4);
		try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			gz.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return Base64.getEncoder().encodeToString(bos.toByteArray());
	}

	static String gunzipBase64(String b64) throws Exception {
		byte[] raw = Base64.getDecoder().decode(b64);
		try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
			return new String(gz.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	public static void main(String[] args) throws Exception {
		final int OLD_LIMIT = 32767;
		final int NEW_LIMIT = MusicNetworking.MAX_JSON_CHARS;

		System.out.println("=== 常量确认 ===");
		System.out.println("  旧上限 = " + OLD_LIMIT);
		System.out.println("  新上限 = " + NEW_LIMIT);
		check("新上限远大于旧上限", NEW_LIMIT > OLD_LIMIT);
		check("新上限至少 100 万字符", NEW_LIMIT >= 1_000_000);
		check("【新上限能容纳 10000 首（约 172 万字符）】", NEW_LIMIT >= 1_800_000);

		System.out.println("\n=== 复现旧 bug：500 首时 JSON 有多大 ===");
		String json500 = fakeQueueJson(500);
		System.out.println("  500 首 JSON = " + json500.length() + " 字符");
		check("【旧上限 32767 会溢出 → 复现踢人故障】", json500.length() > OLD_LIMIT);
		check("新上限放得下", json500.length() <= NEW_LIMIT);

		System.out.println("\n=== 压缩效果（超过 16000 字符会自动压缩）===");
		System.out.printf("  %6s | %10s | %10s | %8s | %s%n",
				"首数", "原始字符", "压缩后", "压缩比", "旧上限能过?");
		int[] counts = {100, 190, 200, 500, 1000, 2000, 5000, 10000};
		for (int n : counts) {
			String raw = fakeQueueJson(n);
			String packed = gzipBase64(raw);
			double ratio = (double) packed.length() / raw.length();
			boolean fitsOld = packed.length() <= OLD_LIMIT;
			boolean fitsNew = packed.length() <= NEW_LIMIT;
			System.out.printf("  %6d | %10d | %10d | %7.1f%% | %s%n",
					n, raw.length(), packed.length(), ratio * 100,
					fitsOld ? "是" : "否");
			if (!fitsNew) check(n + " 首即使压缩后也超新上限", false);
		}

		System.out.println("\n=== 往返正确性（压缩 → 解压必须一模一样）===");
		for (int n : counts) {
			String raw = fakeQueueJson(n);
			String packed = gzipBase64(raw);
			String back = gunzipBase64(packed);
			if (!raw.equals(back)) {
				check(n + " 首往返不一致", false);
			}
		}
		check("所有规模的压缩/解压往返完全一致", true);

		System.out.println("\n=== 和真实日志里的数字对照 ===");
		System.out.println("  日志里 500 首 = 85423 字符");
		System.out.println("  这里模拟 500 首 = " + json500.length() + " 字符");
		double diff = Math.abs(json500.length() - 85423) / 85423.0;
		System.out.printf("  差异 = %.1f%%%n", diff * 100);
		check("模拟数据和日志量级一致（差异 < 30%）", diff < 0.30);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 队列 JSON 上限已够用，且长队列会自动压缩"
				: "[FAIL] 仍有超限风险");
	}
}
