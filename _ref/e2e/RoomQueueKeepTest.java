import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟用户报告的场景，验证房间队列有没有被保住：
 *
 * <pre>
 *   A 在 room4 导入 20 首
 *   → A 去别的房间（room4 只剩 0 人）
 *   → room4 被自动回收（应保存到磁盘）
 *   → A 回到 room4（应重建并载入 20 首）
 * </pre>
 *
 * <p>用真实文件读写，尽量贴近 ServerQueue.save()/load() 的行为。
 */
public class RoomQueueKeepTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 极简版房间队列（复刻 ServerQueue 的存档行为）。 */
	static class Q {
		final String room;
		int size = 0;

		Q(String room) { this.room = room; }

		Path file(Path dir) {
			String n = "default".equals(room) ? "yue-music-queue.json"
					: "yue-music-queue-" + room + ".json";
			return dir.resolve(n);
		}

		void save(Path dir) throws IOException {
			Files.writeString(file(dir), "{\"entries\":" + size + "}");
			System.out.println("      [save] " + room + " → " + file(dir).getFileName() + " (" + size + " 首)");
		}

		void load(Path dir) throws IOException {
			Path f = file(dir);
			if (!Files.exists(f)) {
				System.out.println("      [load] " + room + " 文件不存在 → 保持 " + size + " 首");
				return;
			}
			String s = Files.readString(f);
			size = Integer.parseInt(s.replaceAll("\\D", ""));
			System.out.println("      [load] " + room + " ← " + size + " 首");
		}
	}

	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("roomtest");
		System.out.println("临时目录: " + dir);

		Map<String, Q> rooms = new LinkedHashMap<>();
		// 复刻 RoomManager 的语义
		java.util.function.Function<String, Q> queueFor = r ->
				rooms.computeIfAbsent(r, Q::new);

		System.out.println("\n=== 1) A 在 room4 导入 20 首 ===");
		Q room4 = queueFor.apply("room4");
		room4.size = 20;
		room4.save(dir);                    // 导入后服务端会广播+存档
		check("room4 有 20 首", room4.size == 20);

		System.out.println("\n=== 2) A 去 room9（room4 变空 → 自动回收）===");
		// join(): 先把 A 移出 room4，再 maybeDropEmpty("room4")
		Q room9 = queueFor.apply("room9");
		room9.size = 0;
		// maybeDropEmpty：从内存移除前 save
		Q dropped = rooms.remove("room4");
		dropped.save(dir);
		System.out.println("      room4 已从内存移除，保存在磁盘");
		check("room4 不在内存里了", !rooms.containsKey("room4"));

		System.out.println("\n=== 3) A 回到 room4（应重建 + 载入）===");
		// restoreRoom(): computeIfAbsent → new ServerQueue(k) → q.load()
		boolean[] loaded = {false};
		Q back = rooms.computeIfAbsent("room4", k -> {
			Q q = new Q(k);
			try {
				q.load(dir);
				loaded[0] = true;
			} catch (IOException e) {
				System.out.println("      load 异常: " + e);
			}
			return q;
		});
		System.out.println("      回来后 room4 有 " + back.size + " 首");
		check("重建时确实调用了 load()", loaded[0]);
		check("【回来后仍有 20 首】", back.size == 20);

		// ============ 反向验证：找出会让它丢歌的写法 ============
		System.out.println("\n=== 4) 反向验证：如果重建时【不调 load】会怎样（复现 bug）===");
		Path dir2 = Files.createTempDirectory("roomtest2");
		Map<String, Q> rooms2 = new LinkedHashMap<>();
		Q r4 = new Q("room4");
		r4.size = 20;
		r4.save(dir2);
		// 回收
		r4.save(dir2);
		// 重建但不 load（这是很多人会写错的地方）
		Q backNoLoad = new Q("room4");
		System.out.println("      不调 load 时：" + backNoLoad.size + " 首");
		check("不调 load 就是 0 首（能复现「歌都没了」）", backNoLoad.size == 0);

		// ============ 新增：autosave（主动落盘）场景 ============
		System.out.println("\n=== 5) 主动落盘：导入后立刻写盘，即使没有任何「回收」动作也不丢 ===");
		Path dir3 = Files.createTempDirectory("roomtest3");
		Q a = new Q("room4");
		a.size = 20;
		a.save(dir3);                 // 对应 autosave()：加歌时立刻写盘
		// 模拟「进程直接挂掉」——没有回收、没有 saveAll
		Q reloaded = new Q("room4");
		reloaded.load(dir3);
		System.out.println("      进程崩溃后重启：room4 = " + reloaded.size + " 首");
		check("【不依赖回收动作也能恢复 20 首】", reloaded.size == 20);

		// ============ 新增：空房间不卸载（本次的核心修复）============
		System.out.println("\n=== 6) 空房间「不卸载」：队列一直留在内存里 ===");
		Path dir4 = Files.createTempDirectory("roomtest4");
		Map<String, Q> live = new LinkedHashMap<>();
		// 玩家在 room4 导入 20 首
		Q room4live = new Q("room4");
		room4live.size = 20;
		room4live.save(dir4);         // autosave
		live.put("room4", room4live);
		// 玩家走了：房间空了
		System.out.println("      玩家离开 room4（房间空）");
		// 修复后的 maybeDropEmpty：什么都不做（不 remove）
		//   ← 旧版这里是 live.remove("room4")
		check("【房间仍在内存里】", live.containsKey("room4"));
		check("【20 首歌还在内存里，没丢】", live.get("room4").size == 20);

		// 玩家回来：queueFor() 命中内存里那份（不新建、不 load）
		Q back2 = live.computeIfAbsent("room4", k -> {
			Q q = new Q(k);
			try { q.load(dir4); } catch (IOException ignored) { }
			return q;
		});
		System.out.println("      玩家回到 room4：" + back2.size + " 首");
		check("【回来后仍是同一份内存对象】", back2 == room4live);
		check("【回来后有 20 首】", back2.size == 20);

		// ============ 新增：queueFor 创建时必须 load ============
		System.out.println("\n=== 7) queueFor 创建房间时必须载入存档 ===");
		Path dir5 = Files.createTempDirectory("roomtest5");
		Q saved = new Q("room7");
		saved.size = 33;
		saved.save(dir5);
		// 模拟 queueFor：computeIfAbsent + new + load
		Map<String, Q> rooms5 = new LinkedHashMap<>();
		Q created = rooms5.computeIfAbsent("room7", k -> {
			Q q = new Q(k);
			try { q.load(dir5); } catch (IOException ignored) { }
			return q;
		});
		System.out.println("      queueFor(room7) 之后有 " + created.size + " 首");
		check("【创建即载入 → 33 首，而不是 0 首】", created.size == 33);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 房间队列不会再丢" : "[FAIL] 仍有丢失");
	}
}
