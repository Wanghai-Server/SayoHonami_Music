import com.yue.music.model.MusicTrack;
import com.yue.music.server.QueueEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 实测：把队列留在内存里到底占多少内存。
 *
 * <p>用真实的 MusicTrack / QueueEntry 对象（和运行期完全一样的类），
 * 用 System.gc() + 前后差值测量，避免瞎猜。
 */
public class MemoryFootprintTest {

	/** 造一首"典型长度"的歌（标题/歌手/专辑都取常见长度）。 */
	static MusicTrack typical(int i) {
		return new MusicTrack(
				"003Qui1q2u1Zho" + (i % 10),                 // songmid
				"qq",
				"花骨朵（第" + i + "种翻唱版本）",              // 标题 ~15 字
				"亞細亞曠世奇才/洛天依/言和",                    // 歌手 ~13 字
				"叶氏集团附属术 收录合集 Vol." + (i % 20),      // 专辑 ~18 字
				180 + (i % 60),                                // 时长
				"003Qui1q2u",                                  // albumMid
				i % 3 == 0                                     // VIP
		);
	}

	static long used() {
		Runtime r = Runtime.getRuntime();
		for (int i = 0; i < 4; i++) {
			System.gc();
			try {
				Thread.sleep(60);
			} catch (InterruptedException ignored) {
			}
		}
		return r.totalMemory() - r.freeMemory();
	}

	public static void main(String[] args) {
		System.out.println("=== 队列内存占用实测 ===");

		int[] counts = {100, 1000, 5000};
		for (int n : counts) {
			long before = used();
			List<QueueEntry> q = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				q.add(new QueueEntry(typical(i), "", 180, "SayoHonami"));
			}
			long after = used();
			// 防止被优化掉
			if (q.size() != n) System.out.println("?");

			long bytes = after - before;
			System.out.printf("  %5d 首：共 %.1f KB，平均每首 %.0f 字节%n",
					n, bytes / 1024.0, (double) bytes / n);
		}

		System.out.println();
		System.out.println("=== 换算成很多个房间会怎样 ===");
		// 单首按实测约 500~900 字节估
		double perSong = 700;
		System.out.println("  按每首约 " + (int) perSong + " 字节估算：");
		for (int[] c : new int[][]{{1, 1000}, {10, 1000}, {100, 1000}, {100, 100}}) {
			double mb = c[0] * c[1] * perSong / 1024.0 / 1024.0;
			System.out.printf("    %3d 个房间 × %4d 首 = %8.1f MB%n", c[0], c[1], mb);
		}
		System.out.println();
		System.out.println("  对比：Minecraft 单机客户端启动就要 1.5~3 GB，");
		System.out.println("        服务端典型也要 1~4 GB。");
	}
}
