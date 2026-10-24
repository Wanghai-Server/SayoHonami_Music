import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 验证「提示只发给同房间玩家」的路由逻辑（不依赖 Fabric）。
 *
 * <p>复刻 RoomManager.playersIn() 的语义：
 *   玩家 UUID → 房间名 的映射里，筛出指定房间的玩家。
 *   广播提示时就是遍历这个列表逐个 sendMessage。
 *
 * <p>要证明的核心：A 在 room1 点歌时，room3 的 B **不在**收件人列表里。
 */
public class RoomNoticeRouteTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 复刻 RoomManager.playersIn 的语义。 */
	static List<UUID> playersIn(String room, Map<UUID, String> playerRoom) {
		List<UUID> out = new ArrayList<>();
		for (Map.Entry<UUID, String> e : playerRoom.entrySet()) {
			if (room.equals(e.getValue())) out.add(e.getKey());
		}
		return out;
	}

	public static void main(String[] args) {
		// 场景：A 在 room1，B 在 room3，C 也在 room1，D 没进房间(default)
		UUID A = UUID.nameUUIDFromBytes("A".getBytes());
		UUID B = UUID.nameUUIDFromBytes("B".getBytes());
		UUID C = UUID.nameUUIDFromBytes("C".getBytes());
		UUID D = UUID.nameUUIDFromBytes("D".getBytes());

		Map<UUID, String> playerRoom = new LinkedHashMap<>();
		playerRoom.put(A, "room1");
		playerRoom.put(B, "room3");
		playerRoom.put(C, "room1");
		playerRoom.put(D, "default");

		System.out.println("=== 场景 ===");
		System.out.println("  A=room1  B=room3  C=room1  D=default");

		System.out.println("\n=== A 在 room1 点歌 → 提示应该发给谁 ===");
		List<UUID> receivers = playersIn("room1", playerRoom);
		System.out.println("  收件人数量 = " + receivers.size());
		check("A（点歌的人）在收件人里", receivers.contains(A));
		check("C（同房间）在收件人里", receivers.contains(C));
		check("【B（room3）不在收件人里】", !receivers.contains(B));
		check("【D（default）不在收件人里】", !receivers.contains(D));
		check("收件人只有 2 个（A 和 C）", receivers.size() == 2);

		System.out.println("\n=== 反向验证：B 在 room3 点歌 ===");
		List<UUID> r3 = playersIn("room3", playerRoom);
		check("收件人只有 B（1 个）", r3.size() == 1 && r3.contains(B));
		check("A 收不到 room3 的提示", !r3.contains(A));

		System.out.println("\n=== default 房间 ===");
		List<UUID> rd = playersIn("default", playerRoom);
		check("收件人只有 D", rd.size() == 1 && rd.contains(D));

		System.out.println("\n=== 全员在同一房间时（对照）===");
		Map<UUID, String> all = new HashMap<>();
		all.put(A, "room1"); all.put(B, "room1"); all.put(C, "room1");
		check("同房间时 3 人都收到", playersIn("room1", all).size() == 3);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 提示路由已正确按房间隔离"
				: "[FAIL] 提示路由有问题");
	}
}
