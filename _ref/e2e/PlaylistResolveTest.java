import com.yue.music.api.QQLogin;
import com.yue.music.api.QQPlaylist;
import com.yue.music.api.QQProvider;
import com.yue.music.api.WeChatLogin;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.util.ArrayList;
import java.util.List;

/**
 * 用真实歌单逐首试解析，看 QQ音乐到底为什么拒绝。
 *
 * <p>统计每个 result 码出现的次数 —— 这能直接区分
 * 「会员独占（104003）」和「请求太频繁（104604）」等不同原因。
 */
public class PlaylistResolveTest {

	public static void main(String[] args) throws Exception {
		String link = args.length > 0 ? args[0] : "https://y.qq.com/n/ryqq/playlist/7257580854";
		QQLogin login = new QQLogin();
		QQProvider qq = new QQProvider(login, new WeChatLogin());

		System.out.println("=== 拉歌单 ===");
		System.out.println("  链接: " + link);
		QQPlaylist.Playlist pl = QQPlaylist.fetch(link, qq);
		if (!pl.ok()) {
			System.out.println("  歌单拉取失败: " + pl.error());
			return;
		}
		System.out.println("  歌单名 = " + pl.name() + "，共 " + pl.tracks().size() + " 首");
		System.out.println("  服务端登录状态 = " + login.loggedIn());

		System.out.println("\n=== 逐首试解析 ===");
		int ok = 0;
		int none = 0;
		java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
		List<String> failedTitles = new ArrayList<>();

		for (int i = 0; i < pl.tracks().size(); i++) {
			MusicTrack t = pl.tracks().get(i);
			PlayUrl u;
			try {
				u = qq.resolvePlayUrl(t);
			} catch (Exception e) {
				u = PlayUrl.fail("异常: " + e.getMessage());
			}
			if (u.ok()) {
				ok++;
				System.out.printf("  %2d. ✓ %-28s %s%n", i + 1, cut(t.title(), 28), u.quality());
			} else {
				none++;
				String err = u.error() == null ? "(无错误信息)" : u.error();
				reasons.merge(err, 1, Integer::sum);
				failedTitles.add(t.title());
				System.out.printf("  %2d. ✗ %-28s %s   [VIP标记=%s]%n",
						i + 1, cut(t.title(), 28), err, t.vip());
			}
		}

		System.out.println("\n=== 汇总 ===");
		System.out.println("  成功 " + ok + " 首 / 失败 " + none + " 首");
		System.out.println("\n  失败原因分布：");
		reasons.entrySet().stream()
				.sorted((a, b) -> b.getValue() - a.getValue())
				.forEach(e -> System.out.printf("    %3d 次  %s%n", e.getValue(), e.getKey()));

		int vipFlagged = 0;
		for (MusicTrack t : pl.tracks()) if (t.vip()) vipFlagged++;
		System.out.println("\n  歌单里被标记为 VIP 的: " + vipFlagged + " / " + pl.tracks().size());
	}

	static String cut(String s, int n) {
		if (s == null) return "";
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}
}
