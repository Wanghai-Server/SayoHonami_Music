import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证 /SyMus room cleanup 的行为：
 *
 * <ol>
 *   <li>只清理「没人的」房间，有人的房间不动</li>
 *   <li>默认房间永远不清理</li>
 *   <li>清理前必须先把队列写盘（否则歌就真丢了）</li>
 *   <li>清理后重新进入该房间，歌能载入回来</li>
 * </ol>
 */
public class RoomCleanupTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	static class Q {
		final String room;
		int size;
		boolean saved = false;

		Q(String room, int size) { this.room = room; this.size = size; }

		Path file(Path dir) {
			String n = "default".equals(room) ? "yue-music-queue.json"
					: "yue-music-queue-" + room + ".json";
			return dir.resolve(n);
		}

		void save(Path dir) throws IOException {
			Files.writeString(file(dir), "{\"entries\":" + size + "}");
			saved = true;
		}

		void load(Path dir) throws IOException {
			Path f = file(dir);
			if (!Files.exists(f)) return;
			size = Integer.parseInt(Files.readString(f).replaceAll("\\D", ""));
		}
	}

	/** 复刻 RoomManager.cleanupEmptyRooms() */
	static int cleanupEmptyRooms(Map<String, Q> rooms, Map<String, String> occupancy,
								 Path dir) throws IOException {
		int removed = 0;
		List<String> names = new ArrayList<>(rooms.keySet());
		for (String r : names) {
			if ("default".equals(r)) continue;
			int people = "0".equals(occupancy.getOrDefault(r, "0")) ? 0 : 1;
			if (people == 0) {
				Q q = rooms.remove(r);
				if (q != null) q.save(dir);   // 先落盘再释放
				removed++;
			}
		}
		return removed;
	}

	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("cleanup");
		Map<String, Q> rooms = new LinkedHashMap<>();
		Map<String, String> occ = new LinkedHashMap<>();

		// 场景：default 有人；room4 没人但有 45 首；room9 有人有 10 首
		rooms.put("default", new Q("default", 3));
		occ.put("default", "1");
		rooms.put("room4", new Q("room4", 45));
		occ.put("room4", "0");          // 没人
		rooms.put("room9", new Q("room9", 10));
		occ.put("room9", "1");          // 有人

		System.out.println("=== 清理前 ===");
		rooms.forEach((k, v) -> System.out.println("  " + k + "：" + v.size + " 首，"
				+ ("1".equals(occ.get(k)) ? "有人" : "没人")));

		System.out.println("\n=== 执行 cleanup ===");
		int removed = cleanupEmptyRooms(rooms, occ, dir);
		System.out.println("  清理了 " + removed + " 个房间");

		System.out.println("\n=== 断言 ===");
		check("清理数 = 1（只有 room4）", removed == 1);
		check("room4 已被释放", !rooms.containsKey("room4"));
		check("【default 没被清理】", rooms.containsKey("default"));
		check("【room9（有人）没被清理】", rooms.containsKey("room9"));
		check("room9 的歌还在（10 首）", rooms.get("room9").size == 10);
		check("清理前确实写了盘", new Q("room4", 0).file(dir).toFile().exists());

		System.out.println("\n=== 玩家重新进入 room4 ===");
		Q back = new Q("room4", 0);
		back.load(dir);
		System.out.println("  room4 载入回 " + back.size + " 首");
		check("【清理后回来仍能载入 45 首】", back.size == 45);

		System.out.println("\n=== 全都没人时 ===");
		occ.put("room9", "0");
		int removed2 = cleanupEmptyRooms(rooms, occ, dir);
		check("清理数 = 1（room9）", removed2 == 1);
		check("【default 无论如何都不清理】", rooms.containsKey("default"));

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] cleanup 指令行为正确（存档不丢）"
				: "[FAIL] 有不符合预期的用例");
	}
}
