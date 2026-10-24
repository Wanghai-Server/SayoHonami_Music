import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 验证「房间记忆」的持久化逻辑（不依赖 Fabric）。
 *
 * <p>模拟 MusicConfig 的 playerRooms 字段：
 *   玩家进房间 → 写入 map → 存成 JSON → 重新读出来 → 还能查到 → 送回原房间。
 *
 * <p>这就是「在房间2退出，重进还在房间2」的核心：记忆必须能跨「重启」存活。
 */
public class RoomMemoryTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	public static void main(String[] args) {
		Gson gson = new Gson();
		System.out.println("=== 房间记忆持久化测试 ===");

		// 1) 模拟「玩家 SayoHonami 进了 room2」→ 写进配置
		Map<String, String> playerRooms = new HashMap<>();
		playerRooms.put("SayoHonami", "room2");
		playerRooms.put("nidie_1125", "room5");

		JsonObject cfg = new JsonObject();
		cfg.add("playerRooms", gson.toJsonTree(playerRooms));
		String json = cfg.toString();
		System.out.println("\n存盘 JSON: " + json);

		// 2) 模拟「服务器重启」→ 重新读配置
		JsonObject reloaded = JsonParser.parseString(json).getAsJsonObject();
		Map<String, String> restored = reloaded.has("playerRooms")
				? gson.fromJson(reloaded.get("playerRooms"), Map.class) : new HashMap<>();
		System.out.println("读回结果: " + restored);

		System.out.println("\n--- 断言 ---");
		check("SayoHonami 的记忆被保留 = room2", "room2".equals(restored.get("SayoHonami")));
		check("nidie_1125 的记忆被保留 = room5", "room5".equals(restored.get("nidie_1125")));
		check("记忆条数正确 = 2", restored.size() == 2);

		// 3) 老配置没有 playerRooms 字段 → 必须安全（不能 NPE）
		System.out.println("\n--- 兼容性：老配置文件没有 playerRooms ---");
		JsonObject old = JsonParser.parseString("{\"volume\":0.7}").getAsJsonObject();
		Map<String, String> fresh = old.has("playerRooms")
				? gson.fromJson(old.get("playerRooms"), Map.class) : new HashMap<>();
		check("老配置能安全降级为空记忆", fresh != null && fresh.isEmpty());

		// 4) 主动离开后记忆被清除 → 重进应该回默认房间
		System.out.println("\n--- 主动离开后清记忆 ---");
		playerRooms.remove("SayoHonami");
		check("离开后记忆已清除", !playerRooms.containsKey("SayoHonami"));
		check("其他人不受影响", "room5".equals(playerRooms.get("nidie_1125")));

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 房间记忆逻辑正确"
				: "[FAIL] 房间记忆有不符合预期的用例");
	}
}
