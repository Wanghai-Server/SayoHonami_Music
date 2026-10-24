import com.yue.music.api.QQLogin;
import com.yue.music.api.QQPlaylist;
import com.yue.music.api.QQProvider;
import com.yue.music.api.WeChatLogin;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

/**
 * 按歌单名搜索，找到 id 后逐首试解析。
 *
 * <p>目的：复现「45 首只成功 10 首」到底卡在哪。
 */
public class FindPlaylistTest {

	public static void main(String[] args) throws Exception {
		String keyword = args.length > 0 ? args[0] : "叶氏集团附属术";
		QQLogin login = new QQLogin();
		QQProvider qq = new QQProvider(login, new WeChatLogin());

		System.out.println("=== 搜索歌单名: " + keyword + " ===");
		// 用搜索接口找歌（歌单本身不能按名字搜，但可以看同名歌曲/歌手）
		var found = qq.search(keyword, 10);
		System.out.println("  搜索到 " + found.size() + " 首同名相关歌曲：");
		for (MusicTrack t : found) {
			System.out.println("    " + t.display() + "  [" + t.duration() + "s]"
					+ (t.vip() ? "  VIP" : ""));
		}

		// 直接测那首歌（用户截图里的第一首）
		System.out.println("\n=== 直接解析用户截图里的第一首 ===");
		var hits = qq.search("花骨朵 亞細亞曠世奇才", 5);
		for (MusicTrack t : hits) {
			PlayUrl u;
			try {
				u = qq.resolvePlayUrl(t);
			} catch (Exception e) {
				u = PlayUrl.fail("异常 " + e.getMessage());
			}
			System.out.printf("    %-30s VIP=%-5s → %s%n",
					t.display(), t.vip(), u.ok() ? ("OK " + u.quality()) : ("失败: " + u.error()));
		}

		System.out.println("\n=== 大量连续解析（模拟导入 45 首后的连续点播）===");
		System.out.println("  连解析 60 次同一首，看会不会触发限流：");
		if (!hits.isEmpty()) {
			MusicTrack t = hits.get(0);
			int ok = 0;
			java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
			for (int i = 0; i < 60; i++) {
				PlayUrl u;
				try {
					u = qq.resolvePlayUrl(t);
				} catch (Exception e) {
					u = PlayUrl.fail("异常 " + e.getMessage());
				}
				if (u.ok()) ok++;
				else reasons.merge(u.error() == null ? "?" : u.error(), 1, Integer::sum);
			}
			System.out.println("    成功 " + ok + " / 60");
			reasons.forEach((k, v) -> System.out.println("    失败 " + v + " 次: " + k));
		}
	}
}
