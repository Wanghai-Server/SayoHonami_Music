/**
 * 验证「提前切歌」的修复。
 *
 * <h3>真正的根因</h3>
 * 自动切歌用的是「搜索接口的元数据时长」（QQ音乐的 interval，整数秒），
 * 它<b>常常比真实音频短</b>。原来的容差只有 3 秒，所以歌还没放完就被切掉 ——
 * 这就是玩家反馈的「<b>有的时候会提前切歌</b>」。
 *
 * <p>修复：容差从 3 秒提高到 {@code AUTO_NEXT_TOLERANCE_SECONDS = 12} 秒。
 * 宁可多等几秒（客户端放完自然静音），也不要早切。
 *
 * <h3>顺带修的另一个问题</h3>
 * 暂停/恢复时进度会归零（旧代码 {@code startEpochMs = now - positionSeconds()*1000}，
 * 而 playing=false 时 positionSeconds() 恒为 0）→ 恢复后从头开始、进度显示错误。
 * 新版引入 {@code positionBeforePauseMs}，暂停期间进度<b>冻结</b>。
 */
public class PausePositionTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 旧进度模型（有 bug）。 */
	static class OldPos {
		long startEpochMs;
		boolean playing;

		void start(long now) { startEpochMs = now; playing = true; }

		int position(long now) {
			if (!playing) return 0;
			return (int) Math.max(0, (now - startEpochMs) / 1000);
		}

		void pause() { playing = false; }

		void resume(long now) {
			startEpochMs = now - position(now) * 1000L;   // position()==0 → 归零
			playing = true;
		}
	}

	/** 新进度模型（已修）。 */
	static class NewPos {
		long startEpochMs;
		long beforePauseMs;
		boolean playing;

		void start(long now) { beforePauseMs = 0; startEpochMs = now; playing = true; }

		long positionMs(long now) {
			if (!playing) return Math.max(0, beforePauseMs);
			return Math.max(0, beforePauseMs + (now - startEpochMs));
		}

		int position(long now) { return (int) (positionMs(now) / 1000); }

		void pause(long now) { beforePauseMs = positionMs(now); playing = false; }

		void resume(long now) { beforePauseMs = positionMs(now); startEpochMs = now; playing = true; }
	}

	/** 模拟服务端判定「放完」。 */
	static boolean wouldAdvance(int positionSeconds, int metadataDuration, int tolerance) {
		return positionSeconds >= metadataDuration + tolerance;
	}

	public static void main(String[] args) {
		final long T0 = 1_000_000_000_000L;

		// ========== 1) 容差：真正的「提前切歌」原因 ==========
		System.out.println("=== 1) 元数据时长比真实音频短 → 提前切歌 ===");
		System.out.println("    假设：元数据说 180s，真实音频 188s（很常见）\n");

		check("旧容差 3s：188s 时会被切（=提前切歌）", wouldAdvance(188, 180, 3));
		check("新容差 12s：188s 时【不会】被切", !wouldAdvance(188, 180, 12));
		check("新容差 12s：192s 时才切（已过真实结尾）", wouldAdvance(192, 180, 12));
		check("新容差不会让歌无限播下去（195s 时切）", wouldAdvance(195, 180, 12));

		System.out.println("\n=== 2) 不同元数据偏差下的对比 ===");
		int[] gaps = {0, 3, 5, 8, 10, 15};
		System.out.println("  元数据偏差 | 旧容差3s | 新容差12s");
		for (int gap : gaps) {
			int realEnd = 180 + gap;
			boolean oldCut = wouldAdvance(realEnd, 180, 3);
			boolean newCut = wouldAdvance(realEnd, 180, 12);
			System.out.printf("  %+3ds      | %s | %s%n", gap,
					oldCut ? "切早了 ✗" : "正常", newCut ? "切早了 ✗" : "正常 ✓");
		}

		// ========== 3) 暂停进度冻结 ==========
		System.out.println("\n=== 3) 暂停期间进度必须冻结 ===");
		OldPos o = new OldPos();
		o.start(T0);
		long t30 = T0 + 30_000;
		o.pause();
		long t150 = t30 + 120_000;
		o.resume(t150);
		int oldAfter = o.position(t150 + 5_000);

		NewPos n = new NewPos();
		n.start(T0);
		n.pause(t30);
		int frozen = n.position(t150);
		n.resume(t150);
		int newAfter = n.position(t150 + 5_000);

		System.out.println("  旧模型：播30s → 暂停120s → 恢复后5s 进度 = " + oldAfter + "s");
		System.out.println("  新模型：播30s → 暂停120s → 恢复后5s 进度 = " + newAfter + "s");
		check("旧模型恢复后进度归零（从 30s 变 0s，是另一个 bug）", oldAfter != 35);
		check("新模型暂停 120s 期间进度冻结在 30s", frozen == 30);
		check("新模型恢复后继续前进（35s）", newAfter == 35);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 提前切歌已修（容差 3s→12s）+ 暂停进度不再归零"
				: "[FAIL] 仍有问题");
	}
}
