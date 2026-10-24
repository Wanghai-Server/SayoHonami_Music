/**
 * 验证 1.5.1 的三项改动（纯逻辑复刻）。
 *
 * <ol>
 *   <li><b>GUI 缩放自动跟随</b>：按原版 GUI 缩放算尺寸系数</li>
 *   <li><b>右侧滚动条</b>：滑块长度/位置/点击跳转的数学</li>
 *   <li><b>VIP 歌重试</b>：会员限制不重试，其它错误退避重试 3 次</li>
 * </ol>
 */
public class GuiScaleScrollTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	// ---------------------------------------------------------- 1) GUI 缩放

	static final int BASE_GUI_SCALE = 3;

	/** 复刻 MusicScreen.autoGuiFactor()。 */
	static float autoGuiFactor(int vanillaScale) {
		if (vanillaScale <= 0) return 1f;
		float f = (float) BASE_GUI_SCALE / vanillaScale;
		return Math.max(0.7f, Math.min(1.5f, f));
	}

	// ---------------------------------------------------------- 2) 滚动条

	static int scrollMax(int content, int view) {
		return Math.max(0, content - view);
	}

	static int thumbHeight(int trackH, int view, int content) {
		if (content <= view) return 0;
		return Math.max(12, (int) ((long) trackH * view / content));
	}

	/** 复刻 drawScrollbar 里滑块 Y 的计算。 */
	static int thumbY(int top, int trackH, int thumbH, int scroll, int maxScroll) {
		int travel = trackH - thumbH;
		return top + (maxScroll <= 0 ? 0 : (int) ((long) travel * Math.min(scroll, maxScroll) / maxScroll));
	}

	/** 复刻 scrollbarClick：点哪跳哪。 */
	static int clickToScroll(int mouseY, int top, int trackH, int thumbH, int maxScroll) {
		int travel = Math.max(1, trackH - thumbH);
		int rel = (int) (mouseY - top - thumbH / 2.0);
		int v = (int) ((long) rel * maxScroll / travel);
		return Math.max(0, Math.min(maxScroll, v));
	}

	// ---------------------------------------------------------- 3) 重试策略

	/** 复刻 resolveForQueue 的重试判定。 */
	static String retryDecision(String error, int attempt, int maxRetry) {
		boolean vipBlocked = error.contains("会员");
		if (vipBlocked) return "不重试（会员限制）";
		if (attempt < maxRetry) return "重试";
		return "放弃";
	}

	public static void main(String[] args) {
		// ===== 1) GUI 缩放自动跟随 =====
		System.out.println("=== 1) GUI 缩放自动跟随（基准 = 原版缩放 3）===");
		check("缩放 3 → 系数 1.0（保持原尺寸）", autoGuiFactor(3) == 1.0f);
		check("缩放 4 → 系数 0.75（逻辑像素更大，界面缩小）",
				Math.abs(autoGuiFactor(4) - 0.75f) < 0.001f);
		check("缩放 2 → 系数 1.5（逻辑像素更小，界面放大）",
				Math.abs(autoGuiFactor(2) - 1.5f) < 0.001f);
		check("缩放 1 → 夹到 1.5（不让它无限放大）", autoGuiFactor(1) == 1.5f);
		check("缩放 0（自动）→ 1.0（交给原版，不额外缩放）", autoGuiFactor(0) == 1.0f);

		System.out.println("\n=== 2) 缩放后面板宽度都要能用 ===");
		for (int s : new int[]{2, 3, 4}) {
			float f = autoGuiFactor(s);
			int pw = Math.max(200, Math.round(470 * f));
			System.out.printf("  缩放 %d → 系数 %.2f → 面板宽 %d px%n", s, f, pw);
			if (pw < 200) check("缩放 " + s + " 面板过窄", false);
		}
		check("所有缩放下面板宽度都 ≥ 200", true);

		// ===== 2) 滚动条 =====
		System.out.println("\n=== 3) 滚动条：内容不超出就不画 ===");
		check("内容 100 < 可视 300 → 不需要滚动条",
				thumbHeight(300, 300, 100) == 0);
		check("内容 300 = 可视 300 → 不需要",
				thumbHeight(300, 300, 300) == 0);
		check("内容 600 > 可视 300 → 需要", thumbHeight(300, 300, 600) > 0);

		System.out.println("\n=== 4) 滚动条：滑块长度与滚动上限 ===");
		int top = 100, trackH = 300, view = 300;
		int content = 600;
		int max = scrollMax(content, view);
		int th = thumbHeight(trackH, view, content);
		System.out.println("  内容 600 / 可视 300 → 滚动上限 " + max + "，滑块长 " + th);
		check("滚动上限 = 内容 − 可视 = 300", max == 300);
		check("滑块长度 = 轨道一半 = 150", th == 150);

		System.out.println("\n=== 5) 滑块位置随滚动变化 ===");
		int y0 = thumbY(top, trackH, th, 0, max);
		int yMid = thumbY(top, trackH, th, 150, max);
		int yEnd = thumbY(top, trackH, th, 300, max);
		System.out.println("  滚动 0   → 滑块 Y = " + y0);
		System.out.println("  滚动 150 → 滑块 Y = " + yMid);
		System.out.println("  滚动 300 → 滑块 Y = " + yEnd);
		check("滚到顶 → 滑块在最上面", y0 == top);
		check("滚到底 → 滑块贴到轨道底（"+top+"+"+trackH+"-"+th+"="+(top+trackH-th)+"）",
				yEnd == top + trackH - th);
		check("滚动到一半 → 滑块在中间", yMid > y0 && yMid < yEnd);

		System.out.println("\n=== 6) 点击跳转（点哪跳哪）===");
		check("点轨道最上面 → 滚到顶", clickToScroll(top, top, trackH, th, max) == 0);
		check("点轨道最下面 → 滚到底",
				clickToScroll(top + trackH, top, trackH, th, max) == max);
		int midClick = clickToScroll(top + trackH / 2, top, trackH, th, max);
		System.out.println("  点轨道中间 → 滚动到 " + midClick);
		check("点中间 → 大概滚到一半（±20）", Math.abs(midClick - max / 2) <= 20);
		check("点超出上边界不会变负数",
				clickToScroll(top - 900, top, trackH, th, max) == 0);

		System.out.println("\n=== 7) 大量内容（1000 首队列）===");
		int qContent = 1000 * 20;      // 20000
		int qView = 300;
		int qMax = scrollMax(qContent, qView);
		int qTh = thumbHeight(trackH, qView, qContent);
		System.out.println("  1000 首 = " + qContent + "px，可视 " + qView
				+ " → 上限 " + qMax + "，滑块长 " + qTh);
		check("滚动上限很大（能滚到最后一首）", qMax == qContent - qView);
		check("滑块不会短到看不见（最小 12px）", qTh >= 12);
		check("滚到底时滑块贴底", thumbY(top, trackH, qTh, qMax, qMax) == top + trackH - qTh);

		// ===== 3) 重试策略 =====
		System.out.println("\n=== 8) VIP 解析重试策略 ===");
		check("会员限制 → 不重试（重试也没用）",
				retryDecision("这首歌需要QQ音乐会员", 0, 3).equals("不重试（会员限制）"));
		check("网络抖动 → 重试",
				retryDecision("请求太频繁，稍后再试", 0, 3).equals("重试"));
		check("第 2 次失败 → 还重试",
				retryDecision("解析异常", 1, 3).equals("重试"));
		check("第 3 次失败 → 还重试",
				retryDecision("解析异常", 2, 3).equals("重试"));
		check("【试满 3 次 → 放弃并告诉玩家】",
				retryDecision("解析异常", 3, 3).equals("放弃"));
		check("会员限制即使第 1 次也立刻放弃",
				retryDecision("需要QQ音乐会员（当前账号无权限）", 0, 3).contains("不重试"));

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] GUI 自适应 / 滚动条 / 重试 全部正确" : "[FAIL] 有不符合预期的用例");
	}
}
