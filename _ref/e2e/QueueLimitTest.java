import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 验证「按房间设置队列上限」的逻辑，并复现用户的场景。
 *
 * <p>用户日志实证：
 * <pre>
 *   导入「叶氏集团附属术」：+10（重复0，超限35）    ← 45 首只进 10 首
 *   导入「1410个人最爱…」  ：+10（重复0，超限990）  ← 1000 首只进 10 首
 * </pre>
 * 两次都是「恰好 10 首 + 剩下的全超限」→ 上限被设成了 10。
 */
public class QueueLimitTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** 复刻 MusicConfig.limitOf() */
	static int limitOf(int global, Map<String, Integer> roomLimits, String room) {
		if (room != null) {
			Integer v = roomLimits.get(room);
			if (v != null) return clamp(v, 1, 1000);
		}
		return clamp(global, 1, 1000);
	}

	/** 复刻 onImportPlaylist 的入队循环（含跳过统计） */
	static int[] importPlaylist(int limit, int alreadyInQueue, int playlistSize) {
		int added = 0;
		int skippedFull = 0;
		for (int i = 0; i < playlistSize; i++) {
			if (alreadyInQueue + added >= limit) {
				skippedFull++;
				continue;
			}
			added++;
		}
		return new int[]{added, skippedFull};
	}

	public static void main(String[] args) {
		Map<String, Integer> roomLimits = new HashMap<>();

		// ========== 1) 复现用户的 bug ==========
		System.out.println("=== 1) 复现用户的场景（全局上限 = 10）===");
		int[] r1 = importPlaylist(limitOf(10, roomLimits, "room1"), 0, 45);
		System.out.println("      45 首 → 成功 " + r1[0] + "，超限 " + r1[1]);
		check("复现出「+10，超限35」（和日志完全一致）", r1[0] == 10 && r1[1] == 35);

		int[] r2 = importPlaylist(limitOf(10, roomLimits, "room1"), 0, 1000);
		System.out.println("      1000 首 → 成功 " + r2[0] + "，超限 " + r2[1]);
		check("复现出「+10，超限990」（和日志完全一致）", r2[0] == 10 && r2[1] == 990);

		// ========== 2) 设了上限后 ==========
		System.out.println("\n=== 2) 用 /SyMus limit room1 1000 之后 ===");
		roomLimits.put("room1", 1000);
		int[] r3 = importPlaylist(limitOf(10, roomLimits, "room1"), 0, 45);
		System.out.println("      45 首 → 成功 " + r3[0] + "，超限 " + r3[1]);
		check("【45 首全部导入成功】", r3[0] == 45 && r3[1] == 0);

		int[] r4 = importPlaylist(limitOf(10, roomLimits, "room1"), 0, 1000);
		System.out.println("      1000 首 → 成功 " + r4[0] + "，超限 " + r4[1]);
		check("【1000 首全部导入成功】", r4[0] == 1000 && r4[1] == 0);

		// ========== 3) 别的房间不受影响 ==========
		System.out.println("\n=== 3) 只影响指定房间 ===");
		check("room1 = 1000（刚设的）", limitOf(10, roomLimits, "room1") == 1000);
		check("【room2 仍是全局值 10】", limitOf(10, roomLimits, "room2") == 10);
		int[] r5 = importPlaylist(limitOf(10, roomLimits, "room2"), 0, 45);
		check("room2 导入 45 首仍然只进 10 首（互不影响）", r5[0] == 10);

		// ========== 4) 边界 ==========
		System.out.println("\n=== 4) 边界与规范化 ===");
		check("上限 0 被夹到 1", clamp(0, 1, 1000) == 1);
		check("上限 -5 被夹到 1", clamp(-5, 1, 1000) == 1);
		check("上限 99999 被夹到 1000", clamp(99999, 1, 1000) == 1000);
		check("上限正好 1000 保持不变", clamp(1000, 1, 1000) == 1000);
		check("上限 500 保持不变", clamp(500, 1, 1000) == 500);

		roomLimits.put("room_x", 99999);
		check("存进配置的超大值读出来是 1000", limitOf(10, roomLimits, "room_x") == 1000);

		// ========== 5) 持久化 ==========
		System.out.println("\n=== 5) 持久化（重启后仍有效）===");
		JsonObject cfg = new JsonObject();
		cfg.add("roomLimits", new Gson().toJsonTree(roomLimits));
		String saved = cfg.toString();
		System.out.println("      存盘: " + saved);

		JsonObject back = JsonParser.parseString(saved).getAsJsonObject();
		@SuppressWarnings("unchecked")
		Map<String, Double> raw = new Gson().fromJson(back.get("roomLimits"), Map.class);
		Map<String, Integer> reloaded = new HashMap<>();
		raw.forEach((k, v) -> reloaded.put(k, v.intValue()));
		check("【room1 的上限 1000 被保存并读回】",
				limitOf(10, reloaded, "room1") == 1000);
		check("room_x 读回也是 1000", limitOf(10, reloaded, "room_x") == 1000);

		// 老配置没有 roomLimits 字段
		JsonObject old = JsonParser.parseString("{\"maxQueueSize\":500}").getAsJsonObject();
		check("老配置没有 roomLimits 时能安全降级",
				!old.has("roomLimits") && limitOf(500, new HashMap<>(), "room1") == 500);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 房间级队列上限工作正常" : "[FAIL] 有不符合预期的用例");
	}
}
