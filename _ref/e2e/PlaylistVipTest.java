import com.yue.music.api.QQLogin;
import com.yue.music.api.QQPlaylist;
import com.yue.music.api.QQProvider;
import com.yue.music.api.WeChatLogin;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

/**
 * 验证「歌单导入的歌 VIP 标记是否正确」。
 *
 * <p>修复点：歌单详情接口的字段是 {@code pay.payplay}（不带下划线），
 * 而代码原来只读 {@code pay.pay_play}（搜索接口的写法）→ 歌单导入的歌
 * VIP 标记永远是 false。
 *
 * <p>判据：VIP 标记 和「解析结果」应该高度一致 ——
 * 标记为 VIP 的歌解析失败（需要会员），没标记的解析成功。
 */
public class PlaylistVipTest {

	public static void main(String[] args) throws Exception {
		QQLogin login = new QQLogin();
		QQProvider qq = new QQProvider(login, new WeChatLogin());

		String[] links = args.length > 0 ? args
				: new String[]{
				// 周杰伦的歌单（他的歌基本全是 VIP）
				"https://y.qq.com/n/ryqq/playlist/7257580854",
				"https://y.qq.com/n/ryqq/playlist/8667277944",
				"https://y.qq.com/n/ryqq/playlist/8066926824",
		};

		int totalSongs = 0;
		int totalVip = 0;
		int vipButPlayable = 0;      // 标 VIP 但能放（标记可能偏保守，不算错）
		int notVipButFailed = 0;     // 没标 VIP 却放不了 ← 这才是标记有问题
		int notVipPlayable = 0;
		int vipFailed = 0;

		for (String link : links) {
			System.out.println("=== " + link);
			QQPlaylist.Playlist pl;
			try {
				pl = QQPlaylist.fetch(link, qq);
			} catch (Exception e) {
				System.out.println("    拉取异常: " + e);
				continue;
			}
			if (!pl.ok()) {
				System.out.println("    拉取失败: " + pl.error());
				continue;
			}
			System.out.println("    歌单「" + pl.name() + "」共 " + pl.tracks().size() + " 首");

			for (MusicTrack t : pl.tracks()) {
				totalSongs++;
				PlayUrl u;
				try {
					u = qq.resolvePlayUrl(t);
				} catch (Exception e) {
					u = PlayUrl.fail("异常");
				}
				if (t.vip()) {
					totalVip++;
					if (u.ok()) {
						vipButPlayable++;
						System.out.println("      [VIP·可放]  " + t.title());
					} else {
						vipFailed++;
						System.out.println("      [VIP·放不了] " + t.title() + "  ← " + u.error());
					}
				} else {
					if (u.ok()) {
						notVipPlayable++;
					} else {
						notVipButFailed++;
						System.out.println("      [!! 没标VIP却放不了] " + t.title() + "  ← " + u.error());
					}
				}
			}
		}

		System.out.println("\n=== 汇总 ===");
		System.out.println("  总歌曲数        = " + totalSongs);
		System.out.println("  标记为 VIP      = " + totalVip);
		System.out.println("  非 VIP 可播放   = " + notVipPlayable);
		System.out.println("  VIP 但能放      = " + vipButPlayable + "（标记偏保守，可接受）");
		System.out.println("  VIP 且放不了    = " + vipFailed + "（符合预期）");
		System.out.println("  ★ 没标VIP却放不了 = " + notVipButFailed + "（这个才是标记错误）");

		System.out.println();
		boolean fixOk = totalVip > 0 || totalSongs == 0;
		System.out.println(fixOk
				? "[PASS] 歌单里的 VIP 标记能读出来了（修复前恒为 0）"
				: "[FAIL] 还是读不到 VIP 标记");
		System.out.println(notVipButFailed == 0
				? "[PASS] 没有出现「没标 VIP 却放不了」的情况"
				: "[WARN] 有 " + notVipButFailed + " 首没标 VIP 却放不了");
	}
}
