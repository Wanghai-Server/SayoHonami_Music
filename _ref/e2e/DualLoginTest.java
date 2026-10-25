import com.yue.music.api.MusicApi;
import com.yue.music.api.NeteaseLogin;
import com.yue.music.api.QQLogin;
import com.yue.music.api.ScanLogin;
import com.yue.music.api.WeChatLogin;
import com.yue.music.model.MusicSource;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 验证 1.6.1：QQ 和网易云可以<b>同时登录并同时生效</b>。
 *
 * <h3>要守住的底线</h3>
 * <ol>
 *   <li>两个平台同时登录后，各自的 Cookie 都能取到</li>
 *   <li><b>当前选中方式不影响取 Cookie</b> —— 选中网易云时 QQ 的 Cookie 不能丢</li>
 *   <li>两家 Cookie 绝不串用（MUSIC_U 不进 QQ 请求，qm_keyst 不进网易云请求）</li>
 *   <li>任一平台登录，服务端就算「能解析」</li>
 * </ol>
 */
public class DualLoginTest {

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

	@SuppressWarnings("unchecked")
	static void setQqJar(ScanLogin login, String key, String uin) {
		try {
			Field jf = ScanLogin.class.getDeclaredField("jar");
			jf.setAccessible(true);
			Map<String, String> jar = (Map<String, String>) jf.get(login);
			jar.clear();
			jar.put("qm_keyst", key);
			jar.put("uin", uin);
			Field mk = ScanLogin.class.getDeclaredField("musicKey");
			mk.setAccessible(true);
			mk.set(login, key);
			Field mi = ScanLogin.class.getDeclaredField("musicId");
			mi.setAccessible(true);
			mi.set(login, uin);
			Field nick = ScanLogin.class.getDeclaredField("nickname");
			nick.setAccessible(true);
			nick.set(login, "QQ用户");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	static void clearJar(ScanLogin login) {
		try {
			Field jf = ScanLogin.class.getDeclaredField("jar");
			jf.setAccessible(true);
			((Map<String, String>) jf.get(login)).clear();
			Field mk = ScanLogin.class.getDeclaredField("musicKey");
			mk.setAccessible(true);
			mk.set(login, "");
			Field mi = ScanLogin.class.getDeclaredField("musicId");
			mi.setAccessible(true);
			mi.set(login, "");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String[] args) {
		Map<ScanLogin.Method, ScanLogin> logins = loginsMap();
		logins.clear();

		QQLogin qq = new QQLogin();
		WeChatLogin wx = new WeChatLogin();
		NeteaseLogin ne = new NeteaseLogin();
		logins.put(ScanLogin.Method.QQ, qq);
		logins.put(ScanLogin.Method.WECHAT, wx);
		logins.put(ScanLogin.Method.NETEASE, ne);

		// 两个平台都登录
		setQqJar(qq, "QQKEY_abc123", "10001");
		Map<String, String> nj = new HashMap<>();
		nj.put("MUSIC_U", "NETEASE_xyz789");
		nj.put("__uid", "8559979307");
		ne.loadCookies(nj, "CuteYue");

		System.out.println("=== 1) 两个平台都登录 ===");
		check("QQ 已登录", qq.loggedIn());
		check("网易云已登录", ne.loggedIn());
		check("loggedInFor(QQ) = true", MusicApi.loggedInFor(MusicSource.QQ));
		check("loggedInFor(NETEASE) = true", MusicApi.loggedInFor(MusicSource.NETEASE));

		System.out.println("\n=== 2) 【核心】选中网易云时，QQ 的 Cookie 不能丢 ===");
		setActive(ScanLogin.Method.NETEASE);
		String qqCookie = MusicApi.effectiveCookieHeader();
		String neCookie = MusicApi.neteaseCookieHeader();
		System.out.println("  QQ Cookie     = " + (qqCookie.isEmpty() ? "(空 ✗)" : qqCookie));
		System.out.println("  网易云 Cookie = " + (neCookie.isEmpty() ? "(空 ✗)" : neCookie));
		check("【QQ Cookie 非空（旧版这里会变空！）】", !qqCookie.isEmpty());
		check("【QQ Cookie 里是 qm_keyst】", qqCookie.contains("qm_keyst=QQKEY_abc123"));
		check("网易云 Cookie 非空", !neCookie.isEmpty());
		check("网易云 Cookie 里是 MUSIC_U", neCookie.contains("MUSIC_U=NETEASE_xyz789"));

		System.out.println("\n=== 3) 再切到 QQ，网易云也照样能取 ===");
		setActive(ScanLogin.Method.QQ);
		check("QQ Cookie 仍是 qm_keyst",
				MusicApi.effectiveCookieHeader().contains("qm_keyst=QQKEY_abc123"));
		check("【切到 QQ 后网易云 Cookie 没丢】",
				MusicApi.neteaseCookieHeader().contains("MUSIC_U=NETEASE_xyz789"));

		System.out.println("\n=== 4) 绝不串用 ===");
		setActive(ScanLogin.Method.NETEASE);
		check("【QQ 的 Cookie 里没有 MUSIC_U】",
				!MusicApi.effectiveCookieHeader().contains("MUSIC_U"));
		check("【网易云的 Cookie 里没有 qm_keyst】",
				!MusicApi.neteaseCookieHeader().contains("qm_keyst"));
		setActive(ScanLogin.Method.QQ);
		check("【选中 QQ 时两边也都不串】",
				!MusicApi.effectiveCookieHeader().contains("MUSIC_U")
						&& !MusicApi.neteaseCookieHeader().contains("qm_keyst"));

		System.out.println("\n=== 5) 任一平台登录 → 服务端算「能解析」 ===");
		clearJar(qq);
		check("QQ 退出后：QQ 未登录", !qq.loggedIn());
		check("【但网易云还登录着】", ne.loggedIn());
		check("【所以服务端仍能解析（网易云的歌）】", MusicApi.loggedInFor(MusicSource.NETEASE));
		check("QQ 的歌则不再走本机账号", !MusicApi.loggedInFor(MusicSource.QQ));

		System.out.println("\n=== 6) 恢复 QQ，回到双登录 ===");
		setQqJar(qq, "QQKEY_def456", "20002");
		check("两个平台再次都能用",
				MusicApi.loggedInFor(MusicSource.QQ) && MusicApi.loggedInFor(MusicSource.NETEASE));
		check("QQ Cookie 已换成新账号",
				MusicApi.effectiveCookieHeader().contains("QQKEY_def456"));
		check("网易云 Cookie 没被影响",
				MusicApi.neteaseCookieHeader().contains("NETEASE_xyz789"));

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] QQ 和网易云可以同时登录并同时使用，Cookie 互不干扰"
				: "[FAIL] 有不符合预期的用例");
		System.exit(fail == 0 ? 0 : 1);
	}
}
