import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/**
 * 实测「队列 JSON 压缩」的开销，以及「每玩家压缩一次」的代价。
 *
 * <p>日志实证（994 首的队列）：
 * <pre>
 *   [10:39:02] 队列 JSON 289171 字符 → 压缩后 123396 字符
 *   [10:39:07] 队列 JSON 289171 字符 → 压缩后 123396 字符
 *   ...每 5 秒一次，而且同一秒内可能出现 2~3 次
 * </pre>
 */
public class CompressionCostTest {

	static String queueJson(int songs) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < songs; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"mid\":\"003Qui1q2u1Zho").append(i % 10)
					.append("\",\"source\":\"qq\",\"title\":\"歌曲名字大概这么长第").append(i).append("首\"")
					.append(",\"artists\":\"某歌手/另一个歌手/第三个\"")
					.append(",\"album\":\"某张专辑名称 Vol.").append(i % 30).append("\"")
					.append(",\"duration\":").append(180 + (i % 60))
					.append(",\"albumMid\":\"003Qui1q2u\",\"vip\":false}");
		}
		return sb.append(']').toString();
	}

	static int gzipBase64Len(String s) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length() / 4);
		try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			gz.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return Base64.getEncoder().encodeToString(bos.toByteArray()).length();
	}

	/** 复刻 MusicNetworking.encodeQueueField 的开销（含压缩）。 */
	static Object[] encodeQueueField(String json) throws Exception {
		if (json.length() <= 16_000) return new Object[]{json, false};
		ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length() / 4);
		try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return new Object[]{Base64.getEncoder().encodeToString(bos.toByteArray()), true};
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 1) 压缩一次要多久（994 首 = 日志里的规模）===");
		String json = queueJson(994);
		System.out.println("  JSON 长度 = " + json.length() + " 字符");

		// 预热
		for (int i = 0; i < 20; i++) encodeQueueField(json);

		long t0 = System.nanoTime();
		int n = 100;
		for (int i = 0; i < n; i++) encodeQueueField(json);
		long perOp = (System.nanoTime() - t0) / n;
		double ms = perOp / 1_000_000.0;
		System.out.printf("  压缩一次 = %.2f ms%n", ms);

		Object[] packed = encodeQueueField(json);
		System.out.println("  压缩后 = " + ((String) packed[0]).length() + " 字符");

		System.out.println("\n=== 2) 不同规模对比 ===");
		System.out.printf("  %6s | %10s | %10s | %10s%n", "首数", "JSON 字符", "压缩后", "单次耗时");
		for (int s : new int[]{100, 200, 500, 994, 2000}) {
			String j = queueJson(s);
			for (int i = 0; i < 10; i++) encodeQueueField(j);
			long st = System.nanoTime();
			for (int i = 0; i < 60; i++) encodeQueueField(j);
			double one = (System.nanoTime() - st) / 60.0 / 1_000_000.0;
			Object[] p = encodeQueueField(j);
			System.out.printf("  %6d | %10d | %10s | %8.2f ms%n",
					s, j.length(), String.valueOf(((String) p[0]).length()), one);
		}

		System.out.println("\n=== 3) 「每个玩家压缩一次」的代价 ===");
		double one994 = measure(json);
		for (int players : new int[]{1, 2, 5, 10}) {
			double perTick = one994 * players;
			double perSec = perTick / 5.0;      // 每 5 秒广播一次
			System.out.printf("  %2d 个玩家 → 每 5 秒 %.2f ms  → 平均每秒 %.3f ms%n",
					players, perTick, perSec);
		}

		System.out.println("\n=== 4) 结论 ===");
		System.out.printf("  单次压缩 %.2f ms，看着不多；%n", one994);
		System.out.println("  但它是【每个玩家】都做一遍，而且【每 5 秒】来一次；");
		System.out.println("  多房间 + 多人时是成倍叠加，全部压在【服务端主线程】上。");
		System.out.println("  同时每次还打一行日志 → 64 分钟刷了 807 行。");
	}

	static double measure(String json) throws Exception {
		for (int i = 0; i < 20; i++) encodeQueueField(json);
		long t0 = System.nanoTime();
		int n = 100;
		for (int i = 0; i < n; i++) encodeQueueField(json);
		return (System.nanoTime() - t0) / n / 1_000_000.0;
	}
}
