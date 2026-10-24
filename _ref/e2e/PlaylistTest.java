import com.yue.music.api.MusicApi;
import com.yue.music.api.QQPlaylist;
import com.yue.music.model.MusicTrack;

/**
 * 测试 QQ音乐歌单导入：链接解析 + 真实拉取。
 */
public class PlaylistTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	public static void main(String[] args) {
		// 注意：不能调 MusicApi.init()（它依赖 MusicConfig → Fabric 环境）。
		// 直接构造 QQProvider 来测歌单解析（和 E2E 测试同样的做法）。
		com.yue.music.api.QQProvider qq;

		System.out.println("=== 1) 歌单 ID 提取（各种链接形式）===");
		check("标准歌单页链接",
				"8732463559".equals(QQPlaylist.extractId("https://y.qq.com/n/ryqq/playlist/8732463559")));
		check("带 ?id= 的分享链接",
				"8732463559".equals(QQPlaylist.extractId("https://i.y.qq.com/n2/m/share/details/taoge.html?id=8732463559")));
		check("disstid= 形式",
				"8732463559".equals(QQPlaylist.extractId("https://c.y.qq.com/x?disstid=8732463559&x=1")));
		check("纯数字 ID", "8732463559".equals(QQPlaylist.extractId("8732463559")));
		check("前后有空格", "8732463559".equals(QQPlaylist.extractId("  8732463559  ")));
		check("无效输入返回 null", QQPlaylist.extractId("hello world") == null);
		check("空输入返回 null", QQPlaylist.extractId("") == null);

		System.out.println("\n=== 2) 真实拉取 QQ音乐歌单 ===");
		qq = new com.yue.music.api.QQProvider(
				new com.yue.music.api.QQLogin(), new com.yue.music.api.WeChatLogin());
		System.out.println("  QQProvider 已构造（未登录状态）");
		// 用一个公开的歌单 ID 试；拉不到也不算失败（歌单可能被删/私密）
		String[] ids = {
				"https://y.qq.com/n/ryqq/playlist/8732463559",
				"https://y.qq.com/n/ryqq/playlist/7257580854",
		};
		boolean anyOk = false;
		for (String id : ids) {
			System.out.println("  --- " + id);
			try {
				QQPlaylist.Playlist pl = QQPlaylist.fetch(id, qq);
				if (pl.ok()) {
					anyOk = true;
					System.out.println("      歌单名 = " + pl.name() + "   创建者 = " + pl.creator());
					System.out.println("      歌曲数 = " + pl.tracks().size());
					for (int i = 0; i < Math.min(5, pl.tracks().size()); i++) {
						MusicTrack t = pl.tracks().get(i);
						System.out.printf("        %d. %s  [%s]  %s%n",
								i + 1, t.display(), t.duration() + "s", t.vip() ? "VIP" : "免费");
					}
					check("歌单 ID 正确", pl.id() != null && !pl.id().isEmpty());
					check("拿到歌曲", !pl.tracks().isEmpty());
				} else {
					System.out.println("      失败: " + pl.error());
				}
			} catch (Exception e) {
				System.out.println("      异常: " + e);
			}
		}
		if (anyOk) check("至少一个歌单拉取成功", true);
		else System.out.println("  （两个歌单都没拉到，可能是 ID 失效或网络问题，不影响链接解析的结论）");

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0 ? "[PASS] 歌单解析逻辑正确" : "[FAIL] 有不符合预期的用例");
	}
}
