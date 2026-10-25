import com.yue.music.api.MusicApi;
import com.yue.music.api.NeteaseLogin;
import com.yue.music.api.QQLogin;
import com.yue.music.api.ScanLogin;
import com.yue.music.model.MusicSource;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 验证 1.6.0_hotfix1 的两处修复。
 *
 * <h3>日志实证的问题</h3>
 * <pre>
 *   [14:45:50] 网易云登录成功 userId=8559979307 昵称=CuteYue   ← 登录了
 *   [14:46:04] 使用服务端下发的直链播放《1》                    ← 但没用上！
 *   （服务端只登录了 QQ，解析网易云只能拿到免登录外层地址 → 404 网页）
 * </pre>
 *
 * <h3>要验证的</h3>
 * <ol>
 *   <li>{@code loggedInFor(NETEASE)} 只反映网易云登录态</li>
 *   <li>{@code loggedInFor(QQ)} 不把网易云登录算成 QQ 登录</li>
 *   <li>两种平台登录互不干扰</li>
 * </ol>
 */
public class LoginPriorityTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	@SuppressWarnings("unchecked")
	static Map<ScanLogin.Method, ScanLogin> loginsMap() {
		try {
			Field f = MusicApi.class.getDeclaredField("LOGINS");
			f.setAccessible(true);
			return (Map<ScanLogin.Method, ScanLogin>) f.get(null);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static void setActive(ScanLogin.Method m) {
		try {
			Field f = MusicApi.class.getDeclaredField("activeLogin");
			f.setAccessible(true);
			f.set(null, m);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String[] args) {
		Map<ScanLogin.Method, ScanLogin> logins = loginsMap();
		logins.clear();
		setActive(ScanLogin.Method.QQ);

		NeteaseLogin ne = new NeteaseLogin();
		QQLogin qq = new QQLogin();
		logins.put(ScanLogin.Method.NETEASE, ne);
		logins.put(ScanLogin.Method.QQ, qq);

		System.out.println("=== 1) 谁都没登录 ===");
		check("loggedInFor(NETEASE) = false", !MusicApi.loggedInFor(MusicSource.NETEASE));
		check("loggedInFor(QQ)      = false", !MusicApi.loggedInFor(MusicSource.QQ));

		System.out.println("\n=== 2) 只登录网易云 ===");
		Map<String, String> nj = new HashMap<>();
		nj.put("MUSIC_U", "0123456789abcdef0123456789abcdef");
		ne.loadCookies(nj, "CuteYue");
		check("loggedInFor(NETEASE) = true", MusicApi.loggedInFor(MusicSource.NETEASE));
		check("【loggedInFor(QQ) 仍是 false（不能把网易云当成 QQ 登录）】",
				!MusicApi.loggedInFor(MusicSource.QQ));
		check("neteaseLoggedIn() = true", MusicApi.neteaseLoggedIn());

		System.out.println("\n=== 3) 只登录 QQ（网易云退出）===");
		ne.loadCookies(new HashMap<>(), "");
		setActive(ScanLogin.Method.QQ);
		// 手动给 QQ 塞一个假 musickey，模拟已登录
		try {
			Field f = ScanLogin.class.getDeclaredField("musicKey");
			f.setAccessible(true);
			f.set(qq, "FAKE_KEY");
			Field g = ScanLogin.class.getDeclaredField("musicId");
			g.setAccessible(true);
			g.set(qq, "10001");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		check("QQ 已登录", qq.loggedIn());
		check("loggedInFor(QQ)      = true", MusicApi.loggedInFor(MusicSource.QQ));
		check("【loggedInFor(NETEASE) 回到 false】",
				!MusicApi.loggedInFor(MusicSource.NETEASE));

		System.out.println("\n=== 4) 两个都登录 ===");
		ne.loadCookies(nj, "CuteYue");
		check("loggedInFor(NETEASE) = true", MusicApi.loggedInFor(MusicSource.NETEASE));
		check("loggedInFor(QQ)      = true", MusicApi.loggedInFor(MusicSource.QQ));
		check("两种 Cookie 各自独立",
				MusicApi.neteaseCookieHeader().contains("MUSIC_U")
						&& !MusicApi.effectiveCookieHeader().contains("MUSIC_U"));

		System.out.println("\n=== 5) 场景复现：服务端只登录 QQ，客户端登录网易云 ===");
		// 客户端此时应该走本机网易云账号解析（而不是用服务端下发的免登录直链）
		boolean shouldUseLocal = MusicApi.loggedInFor(MusicSource.NETEASE);
		System.out.println("  客户端登录网易云 → 网易云的歌应走本机解析: " + shouldUseLocal);
		check("【网易云的歌会走本机账号（修复点）】", shouldUseLocal);
		boolean qqSongUsesLocal = MusicApi.loggedInFor(MusicSource.QQ);
		System.out.println("  QQ 的歌也会走本机解析（本机 QQ 也登录了）: " + qqSongUsesLocal);
		check("QQ 的歌同样走本机", qqSongUsesLocal);

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 登录状态按平台隔离，客户端能正确判断要不要用本机账号"
				: "[FAIL] 有不符合预期的用例");
		System.exit(fail == 0 ? 0 : 1);
	}
}
