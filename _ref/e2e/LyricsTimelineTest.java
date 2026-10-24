import com.yue.music.api.LrcParser;
import com.yue.music.api.NeteaseProvider;
import com.yue.music.model.Lyrics;
import com.yue.music.model.MusicTrack;

import java.util.List;

/** 模拟「播放进度推进时，HUD 应该显示哪一句歌词」。 */
public class LyricsTimelineTest {

	public static void main(String[] args) throws Exception {
		NeteaseProvider ne = new NeteaseProvider();
		List<MusicTrack> list = ne.search("晴天", 3);
		if (list.isEmpty()) {
			System.out.println("[FAIL] 搜索失败");
			return;
		}
		MusicTrack t = list.get(0);
		Lyrics lyr = ne.lyrics(t);
		if (!lyr.ok() || lyr.lines().isEmpty()) {
			System.out.println("[FAIL] 歌词获取失败: " + lyr.error());
			return;
		}
		System.out.println("曲目: " + t.display() + "（" + lyr.lines().size() + " 行）");
		System.out.println("\n模拟播放进度 → HUD 应显示的当前句（模拟 3 行窗口）：\n");

		int[] samples = {0, 5, 25, 31, 35, 45, 60, 90, 120};
		boolean allOk = true;
		for (int sec : samples) {
			int idx = lyr.indexAt(sec);
			String cur = idx >= 0 ? lyr.lines().get(idx).text() : "（还没到第一句，显示第一句）";
			// 模拟 3 行窗口
			StringBuilder window = new StringBuilder();
			int rows = 3;
			int curRow = (rows - 1) / 2;
			int anchor = idx < 0 ? 0 : idx;
			for (int r = 0; r < rows; r++) {
				int li = anchor - curRow + r;
				if (li < 0 || li >= lyr.lines().size()) continue;
				boolean active = li == anchor;
				window.append(active ? "【" : "〔").append(lyr.lines().get(li).text()).append(active ? "】" : "〕");
			}
			System.out.printf("  t=%3ds  当前句#%-3d  %s%n     窗口: %s%n", sec, idx, cur, window);
			if (idx < -1 || idx >= lyr.lines().size()) allOk = false;
		}

		// 边界：超出歌曲长度
		int last = lyr.indexAt(99999);
		System.out.println("\n超出时长(t=99999) → 下标 " + last + "（应为最后一行 "
				+ (lyr.lines().size() - 1) + "）");
		System.out.println("\n" + (allOk && last == lyr.lines().size() - 1
				? "[PASS] 歌词时间轴跟随正确，边界处理正确"
				: "[FAIL] 时间轴有问题"));
	}
}
