/**
 * 验证房间名规范化逻辑（纯逻辑，不依赖 Fabric）。
 *
 * <p>这段逻辑必须先过，否则会出现「指令输入的名字和实际房间对不上」
 * 或者「奇怪字符导致显示/指令坏掉」的问题。
 */
public class RoomNameTest {

	/** 与 RoomManager.normalize 保持一致（那边依赖 FabricLoader，这里独立验证规则）。 */
	static String normalize(String room) {
		if (room == null) return "default";
		String r = room.trim().toLowerCase();
		if (r.isEmpty()) return "default";
		r = r.replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]", "");
		if (r.isEmpty()) return "default";
		return r.length() > 16 ? r.substring(0, 16) : r;
	}

	static int pass = 0;
	static int fail = 0;

	static void check(String input, String expect) {
		String got = normalize(input);
		boolean ok = got.equals(expect);
		if (ok) pass++; else fail++;
		System.out.printf("  %s normalize(%-22s) = %-14s (期望 %s)%n",
				ok ? "✓" : "✗",
				input == null ? "null" : "\"" + input + "\"",
				"\"" + got + "\"", "\"" + expect + "\"");
	}

	public static void main(String[] args) {
		System.out.println("=== 房间名规范化测试 ===");
		check(null, "default");
		check("", "default");
		check("   ", "default");
		check("Music", "music");
		check("  CHILL  ", "chill");
		check("room-1", "room1");            // 连字符被过滤
		check("a b c", "abc");               // 空格被过滤
		check("听歌房", "听歌房");            // 中文保留
		check("电音Room", "电音room");
		check("!!!", "default");             // 全是非法字符 → 默认房间
		check("abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnop");  // 截断到 16

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		if (fail == 0) System.out.println("[PASS] 房间名规范化正确");
		else System.out.println("[FAIL] 有不符合预期的用例");
	}
}
