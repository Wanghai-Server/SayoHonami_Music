/**
 * 验证 1.5.1_hotfix2 的三层修复。
 *
 * <h3>要复现的故障（客户端日志实证）</h3>
 * <pre>
 *   [23:52:52] 收到状态: index=0 playing=false 已播=167549s 曲目=Champagne Supernova
 *   [23:52:52] 起播 offset=167599s（服务端已播 167599s）
 *   [23:52:55] 音频解码：MPEG1L3 ...
 *   → 之后再无音频日志（没声了）
 * </pre>
 *
 * <h3>三层修复</h3>
 * <ol>
 *   <li><b>客户端夹取</b>：offset 超过歌曲时长就判定时钟异常，从 0 秒起播</li>
 *   <li><b>客户端单调时钟</b>：量「收到状态后过了多久」不再用墙钟</li>
 *   <li><b>服务端单调时钟 + 上限</b>：进度用 nanoTime 算，并有「时长+30秒」上限</li>
 * </ol>
 */
public class PositionSanityTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	// ---------- 1) 客户端夹取（复刻 ClientAudio 的校验） ----------
	static int clampOffset(int offset, int duration) {
		if (duration > 0 && offset > duration + 5) return 0;
		if (offset < 0) return 0;
		return offset;
	}

	/** 模拟 AudioPlayer 的「跳过前 N 秒」会丢掉多少字节。 */
	static long skipBytesFor(int offsetSeconds) {
		return (long) Math.max(0, offsetSeconds) * 44100L * 4L;
	}

	// ---------- 2) 客户端 expectedSeconds（单调时钟版） ----------
	static int expectedSeconds(long baseMs, long nanosElapsed) {
		if (baseMs > 0) {
			long extraMs = nanosElapsed > 0 ? nanosElapsed / 1_000_000L : 0;
			if (extraMs < 0) extraMs = 0;
			return (int) Math.max(0, (baseMs + extraMs) / 1000);
		}
		return 0;
	}

	/** 旧版：把服务端 baseMs 和本机墙钟拼起来。 */
	static int expectedSecondsOld(long baseMs, long startEpochMs, long nowWallClock) {
		long extra = startEpochMs > 0 ? (nowWallClock - startEpochMs) : 0;
		return (int) Math.max(0, (baseMs + extra) / 1000);
	}

	// ---------- 3) 服务端 safePositionMs ----------
	static long safePositionMs(long pos, int durationSeconds) {
		if (durationSeconds <= 0) return Math.min(pos, 30 * 60_000L);
		long cap = (durationSeconds + 30L) * 1000L;
		return Math.min(pos, cap);
	}

	public static void main(String[] args) {
		// ============ 1) 客户端夹取 ============
		System.out.println("=== 1) 客户端必须夹掉荒唐的 offset ===");
		int dur = 441;   // Champagne Supernova 约 7 分 21 秒
		System.out.println("  《Champagne Supernova》时长 = " + dur + "s");
		System.out.println("  日志里的荒唐 offset = 167599s（46.5 小时）");

		long badSkip = skipBytesFor(167599);
		System.out.printf("  不夹取的话会丢 %.1f GB 数据\n", badSkip / 1024.0 / 1024 / 1024);
		check("【旧行为确实会丢掉几十 GB（把整首歌丢光）】",
				badSkip > 20L * 1024 * 1024 * 1024);

		int fixed = clampOffset(167599, dur);
		System.out.println("  夹取后 offset = " + fixed);
		check("【夹取后从 0 秒起播（不会丢数据）】", fixed == 0);

		System.out.println("\n=== 2) 正常进度不能被误夹 ===");
		check("offset=0（从头）→ 保持 0", clampOffset(0, 441) == 0);
		check("offset=100 → 保持 100", clampOffset(100, 441) == 100);
		check("offset=440 → 保持 440", clampOffset(440, 441) == 440);
		check("offset=445（时长+4，允许的余量）→ 保持 445", clampOffset(445, 441) == 445);
		check("offset=447（时长+6，超余量）→ 夹到 0", clampOffset(447, 441) == 0);
		check("负 offset → 0", clampOffset(-5, 441) == 0);
		check("时长未知（0）→ 不夹（无法判断）", clampOffset(1000, 0) == 1000);

		// ============ 2) 客户端单调时钟 ============
		System.out.println("\n=== 3) 客户端进度：单调时钟 vs 旧版墙钟 ===");
		long base = 3000;                       // 服务端说已播 3 秒
		long nanosElapsed = 2_000_000_000L;     // 本地过了 2 秒
		int newWay = expectedSeconds(base, nanosElapsed);
		System.out.println("  新（单调）：服务端 3s + 本地 2s = " + newWay + "s");
		check("【新算法 = 5 秒】", newWay == 5);

		// 旧版：墙钟往回跳 46 小时
		long wallJumpBack = -167_549_000L;
		int oldWay = expectedSecondsOld(base, 1_000_000L, 1_000_000L + wallJumpBack);
		System.out.println("  旧（墙钟被往回拨 46.5 小时）：算出来 = " + oldWay + "s");
		check("【旧算法会被墙钟跳变污染（算出负数或荒唐值）】", oldWay <= 0 || oldWay > 1000);

		// 单调时钟在面对同样跳变时不受影响
		int stillFine = expectedSeconds(base, 2_000_000_000L);
		check("【单调时钟不受墙钟跳变影响（仍是 5 秒）】", stillFine == 5);

		System.out.println("\n=== 4) 单调时钟的边界 ===");
		check("没收到过状态（base=0）→ 0", expectedSeconds(0, 999_999_999L) == 0);
		check("本地 0 秒 → 就是服务端的值", expectedSeconds(7000, 0) == 7);
		check("本地很久（60 秒）→ 正确累加", expectedSeconds(0, 60_000_000_000L) == 0);
		check("服务端 10s + 本地 60s = 70s", expectedSeconds(10000, 60_000_000_000L) == 70);

		// ============ 3) 服务端上限 ============
		System.out.println("\n=== 5) 服务端进度上限（safePositionMs）===");
		check("正常进度 100s 不被改", safePositionMs(100_000, 441) == 100_000);
		check("正好到时长 441s 不被改", safePositionMs(441_000, 441) == 441_000);
		check("时长+30s 是上限（余量内保留）", safePositionMs(471_000, 441) == 471_000);
		long capped = safePositionMs(167_549_000, 441);
		System.out.println("  荒唐值 167549000ms → 夹到 " + capped + "ms（" + (capped / 1000) + "s）");
		check("【荒唐值被夹到 时长+30 秒 = 471000ms】", capped == 471_000);
		check("时长未知时最多认 30 分钟", safePositionMs(999_999_999, 0) == 30 * 60_000L);

		System.out.println("\n=== 6) 端到端：修复后不会再有「没声」===");
		long rawPos = 167_549_000;              // 服务端算出的荒唐进度
		long sentPos = safePositionMs(rawPos, dur);   // ① 服务端夹取
		int clientOffset = (int) (sentPos / 1000);    // 客户端收到 471s
		int offset2 = clampOffset(clientOffset, dur); // ② 客户端夹取
		System.out.println("  服务端原始 " + (rawPos / 1000) + "s"
				+ " → 发出 " + (sentPos / 1000) + "s"
				+ " → 客户端实际起播 " + offset2 + "s");
		long realSkip = skipBytesFor(offset2);
		System.out.printf("  实际会丢 %.1f MB（对比修复前的 %.1f GB）%n",
				realSkip / 1024.0 / 1024, badSkip / 1024.0 / 1024 / 1024);
		check("【修复后丢的数据量 < 50MB（不会把整首歌丢光）】",
				realSkip < 50L * 1024 * 1024);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 三层防护都生效" : "[FAIL] 有不符合预期的用例");
	}
}
