import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 验证 1.5.0 的几个修复与新增功能（纯逻辑复刻，不依赖 Fabric）。
 *
 * <ol>
 *   <li><b>点击越界</b>：队列页的点击必须限制在列表可视区内</li>
 *   <li><b>三种播放模式</b>：顺序 / 单曲循环 / 随机（一轮不重复）</li>
 *   <li><b>队列搜索</b>：过滤后点击要映射回**原始下标**</li>
 * </ol>
 */
public class PlayModeTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	// ---------------------------------------------------------- 播放模式

	enum Mode {
		SEQUENTIAL, SINGLE, SHUFFLE;

		Mode next() {
			return switch (this) {
				case SEQUENTIAL -> SINGLE;
				case SINGLE -> SHUFFLE;
				case SHUFFLE -> SEQUENTIAL;
			};
		}

		String cn() {
			return switch (this) {
				case SEQUENTIAL -> "顺序播放";
				case SINGLE -> "单曲循环";
				case SHUFFLE -> "随机播放";
			};
		}
	}

	/** 复刻 ServerQueue 的播放推进逻辑。 */
	static class Queue {
		int size;
		int index = 0;
		Mode mode = Mode.SEQUENTIAL;
		final Set<Integer> played = new LinkedHashSet<>();

		Queue(int size) { this.size = size; }

		/** 一首播完之后走哪里。 */
		void onFinished() {
			if (mode == Mode.SINGLE) return;   // 原地重播
			advance();
		}

		void advance() {
			if (mode == Mode.SHUFFLE) {
				shuffleNext();
				return;
			}
			if (index + 1 >= size) return;     // 顺序：最后一首就停住
			index++;
		}

		void shuffleNext() {
			played.add(index);
			if (played.size() >= size) {
				int cur = index;
				played.clear();
				played.add(cur);
			}
			List<Integer> cand = new ArrayList<>();
			for (int i = 0; i < size; i++) if (!played.contains(i)) cand.add(i);
			if (cand.isEmpty()) return;
			index = cand.get(ThreadLocalRandom.current().nextInt(cand.size()));
			played.add(index);
		}
	}

	// ---------------------------------------------------------- 点击越界

	/** 复刻修复后的点击判定：返回被点中的队列下标，-1 表示没点中。 */
	static int hitTest(int mouseY, int listTop, int listBottom, int scroll, int rows) {
		int y = listTop - scroll;
		for (int i = 0; i < rows; i++) {
			int rowTop = Math.max(y, listTop);
			int rowBottom = Math.min(y + 20, listBottom);
			if (rowTop < rowBottom && mouseY >= rowTop && mouseY < rowBottom) return i;
			y += 20;
		}
		return -1;
	}

	// ---------------------------------------------------------- 搜索过滤

	/** 复刻 visibleQueueIndexes：返回匹配的**原始下标**。 */
	static int[] visible(String[] titles, String query) {
		if (query.isEmpty()) {
			int[] all = new int[titles.length];
			for (int i = 0; i < all.length; i++) all[i] = i;
			return all;
		}
		List<Integer> hit = new ArrayList<>();
		for (int i = 0; i < titles.length; i++) {
			if (titles[i].toLowerCase().contains(query)) hit.add(i);
		}
		int[] out = new int[hit.size()];
		for (int i = 0; i < out.length; i++) out[i] = hit.get(i);
		return out;
	}

	public static void main(String[] args) {
		// ================= 1) 点击越界（bug 3）=================
		System.out.println("=== 1) 队列页点击不能越界到下方按钮 ===");
		int listTop = 100;
		int listBottom = 200;   // 列表只有 100px 高
		int scroll = 0;
		int rows = 20;          // 20 行内容（远超可视区）

		check("点在列表第 1 行 → 命中第 0 首", hitTest(105, listTop, listBottom, scroll, rows) == 0);
		check("点在列表最后一行 → 命中第 4 首", hitTest(195, listTop, listBottom, scroll, rows) == 4);
		check("【点在列表下方（按钮区 y=210）→ 不命中任何歌】",
				hitTest(210, listTop, listBottom, scroll, rows) == -1);
		check("【点在列表下方更远（y=260）→ 不命中】",
				hitTest(260, listTop, listBottom, scroll, rows) == -1);
		check("点在列表上方 → 不命中", hitTest(90, listTop, listBottom, scroll, rows) == -1);

		// 对照：旧的实现（只判断 mouseY >= y）会误命中
		boolean oldBug = false;
		{
			int y = listTop - scroll;
			for (int i = 0; i < rows; i++) {
				if (210 >= y && 210 < y + 20) { oldBug = true; break; }
				y += 20;
			}
		}
		check("旧实现确实会误命中（复现「点空位置切歌」）", oldBug);

		// ================= 2) 三种播放模式（功能 6）=================
		System.out.println("\n=== 2) 播放模式轮换 ===");
		check("顺序 → 单曲", Mode.SEQUENTIAL.next() == Mode.SINGLE);
		check("单曲 → 随机", Mode.SINGLE.next() == Mode.SHUFFLE);
		check("随机 → 顺序", Mode.SHUFFLE.next() == Mode.SEQUENTIAL);
		check("三选一轮换一圈回到原点",
				Mode.SEQUENTIAL.next().next().next() == Mode.SEQUENTIAL);
		check("中文名正确", Mode.SEQUENTIAL.cn().equals("顺序播放")
				&& Mode.SINGLE.cn().equals("单曲循环") && Mode.SHUFFLE.cn().equals("随机播放"));

		System.out.println("\n=== 3) 顺序播放：放完最后一首停住 ===");
		Queue sq = new Queue(3);
		sq.index = 2;
		sq.onFinished();
		check("停在最后一首（下标仍是 2）", sq.index == 2);

		System.out.println("\n=== 4) 单曲循环：一直原地重播 ===");
		Queue sg = new Queue(3);
		sg.mode = Mode.SINGLE;
		sg.index = 1;
		for (int i = 0; i < 5; i++) sg.onFinished();
		check("【放 5 次之后还在第 1 首】", sg.index == 1);

		System.out.println("\n=== 5) 随机播放：一轮内不重复 ===");
		Queue sh = new Queue(5);
		sh.mode = Mode.SHUFFLE;
		sh.index = 0;
		List<Integer> order = new ArrayList<>();
		order.add(0);
		for (int i = 0; i < 4; i++) {
			sh.onFinished();
			order.add(sh.index);
		}
		System.out.println("  一轮播放顺序: " + order);
		check("【一轮 5 首全部不重复】", new LinkedHashSet<>(order).size() == 5);
		check("数量正确 = 5", order.size() == 5);

		System.out.println("\n=== 6) 随机播放：一轮放完会重新开始 ===");
		sh.onFinished();
		System.out.println("  第 6 次之后 index = " + sh.index + "（应该重新抽，且不是刚放的那首）");
		check("第 6 次仍在合法范围内", sh.index >= 0 && sh.index < 5);

		// 多次运行验证稳定性
		boolean allValid = true;
		for (int round = 0; round < 200; round++) {
			Queue q2 = new Queue(6);
			q2.mode = Mode.SHUFFLE;
			for (int i = 0; i < 30; i++) {
				q2.onFinished();
				if (q2.index < 0 || q2.index >= 6) { allValid = false; break; }
			}
		}
		check("【随机播放 200 轮 × 30 次，下标始终合法】", allValid);

		// ================= 7) 队列搜索（功能 5）=================
		System.out.println("\n=== 7) 队列搜索：过滤后点击映射回原始下标 ===");
		String[] titles = {
				"花骨朵 - 亞細亞曠世奇才",
				"晴天 - 周杰伦",
				"稻香 - 周杰伦",
				"夜曲 - 周杰伦",
				"Lava Chicken - 某歌手",
		};
		int[] all = visible(titles, "");
		check("空搜索词 → 全部 5 首", all.length == 5 && all[0] == 0 && all[4] == 4);

		int[] zjl = visible(titles, "周杰伦");
		System.out.println("  搜「周杰伦」→ 原始下标 " + java.util.Arrays.toString(zjl));
		check("搜到 3 首", zjl.length == 3);
		check("【下标是原始下标 1,2,3（不是 0,1,2）】",
				zjl[0] == 1 && zjl[1] == 2 && zjl[2] == 3);

		int[] huagu = visible(titles, "花骨朵");
		check("搜「花骨朵」→ 原始下标 0", huagu.length == 1 && huagu[0] == 0);

		int[] lava = visible(titles, "lava");
		check("大小写不敏感（lava → Lava Chicken，下标 4）", lava.length == 1 && lava[0] == 4);

		int[] none = visible(titles, "不存在的歌");
		check("搜不到 → 空列表", none.length == 0);

		System.out.println("\n=== 8) 过滤后点第 2 行，插播的应该是原始下标 2 那首 ===");
		int clickedRow = 1;                    // 点第 2 行
		int originalIndex = zjl[clickedRow];   // 映射回原始下标
		System.out.println("  点第 " + (clickedRow + 1) + " 行 → 原始下标 " + originalIndex
				+ " → 歌：" + titles[originalIndex]);
		check("【映射正确（1 → 稻香）】", originalIndex == 2 && titles[originalIndex].contains("稻香"));

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 点击/播放模式/搜索 全部正确" : "[FAIL] 有不符合预期的用例");
	}
}
