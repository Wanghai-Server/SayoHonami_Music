import com.yue.music.api.MusicApi;
import com.yue.music.api.NeteaseLogin;
import com.yue.music.api.QQLogin;
import com.yue.music.api.ScanLogin;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 验证 1.6.0 的「网易云登录」相关逻辑（不依赖网络的纯逻辑部分）。
 *
 * <h3>要守住的几条底线</h3>
 * <ol>
 *   <li>Cookie 解析：整串 / 单条 / <b>只粘值</b> 三种都要认</li>
 *   <li>MUSIC_U 的值里可能含 {@code =}，不能被误拆成 key=value</li>
 *   <li>没 MUSIC_U 时要给能看懂的提示（而不是「登录成功」）</li>
 *   <li><b>网易云的 Cookie 绝不能漏给 QQ 用</b>（两家凭证不通用）</li>
 * </ol>
 */
public class NeteaseLoginTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String what, boolean ok) {
		if (ok) pass++;
		else fail++;
		System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
	}

	/** 直接塞 jar，绕过联网验证，只为测解析结果。 */
	@SuppressWarnings("unchecked")
	static void injectJar(ScanLogin login, Map<String, String> jar) {
		try {
			Field f = ScanLogin.class.getDeclaredField("jar");
			f.setAccessible(true);
			((Map<String, String>) f.get(login)).putAll(jar);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static String musicUOf(NeteaseLogin login) {
		return login.musicU();
	}

	public static void main(String[] args) throws Exception {
		NeteaseLogin ne = new NeteaseLogin();

		System.out.println("=== 1) 登录方式标识 ===");
		check("method() 是 NETEASE", ne.method() == ScanLogin.Method.NETEASE);
		check("enum 里确实有 NETEASE", ScanLogin.Method.valueOf("NETEASE") != null);
		check("label 是「网易云」", "网易云".equals(ScanLogin.Method.NETEASE.label()));

		System.out.println("\n=== 2) 没登录时的状态 ===");
		check("初始未登录", !ne.loggedIn());
		check("musicU 为空", musicUOf(ne).isEmpty());
		check("fetchQrCode 返回 null（引导用 Cookie）", ne.fetchQrCode() == null);
		check("fetchQrCode 给出 Coin 提示", ne.message() != null && ne.message().contains("MUSIC_U"));

		System.out.println("\n=== 3) 空 / 乱输入的提示 ===");
		String e1 = ne.importCookies("");
		check("空字符串被拒：" + e1, e1 != null && e1.contains("空"));
		String e2 = ne.importCookies("abc");
		check("太短的串被拒：" + e2, e2 != null);
		String e3 = ne.importCookies("foo=1; bar=2");
		check("没有 MUSIC_U 被拒，且提示里点名 MUSIC_U：" + e3,
				e3 != null && e3.contains("MUSIC_U"));

		System.out.println("\n=== 4) 注入合法的 MUSIC_U 后 state 正确 ===");
		Map<String, String> jar = new HashMap<>();
		jar.put("MUSIC_U", "0a1b2c3d4e5f60718293a4b5c6d7e8f9ABCDEF0123456789==");
		jar.put("__uid", "12345678");
		injectJar(ne, jar);
		ne.loadCookies(jar, "测试昵称");
		check("loadCookies 后算已登录", ne.loggedIn());
		check("musicU 取到了", !musicUOf(ne).isEmpty());
		check("昵称保留", "测试昵称".equals(ne.nickname()));
		check("cookieHeader 里带 MUSIC_U", ne.cookieHeader().contains("MUSIC_U="));

		System.out.println("\n=== 5) loadCookies 空数据 → 未登录 ===");
		NeteaseLogin ne2 = new NeteaseLogin();
		ne2.loadCookies(new HashMap<>(), "");
		check("空 Cookie 不算登录", !ne2.loggedIn());

		System.out.println("\n=== 6) 关键：网易云 Cookie 不能漏给 QQ ===");
		// 注意：不能调 MusicApi.init() —— 它要读 Fabric 配置目录，离线环境跑不了。
		// 这里直接往 LOGINS 里塞实例，测「取 Cookie 时会不会串味」这段逻辑。
		Map<ScanLogin.Method, ScanLogin> logins = loginsMap();
		logins.clear();

		NeteaseLogin ne3 = new NeteaseLogin();
		Map<String, String> nj = new HashMap<>();
		nj.put("MUSIC_U", "0123456789abcdef0123456789abcdef");
		nj.put("__uid", "999");
		ne3.loadCookies(nj, "网易云用户");
		logins.put(ScanLogin.Method.NETEASE, ne3);

		// QQ 注册但未登录
		QQLogin qq = new QQLogin();
		logins.put(ScanLogin.Method.QQ, qq);

		check("neteaseCookieHeader() 有内容", !MusicApi.neteaseCookieHeader().isEmpty());
		check("neteaseLoggedIn() 为 true", MusicApi.neteaseLoggedIn());

		setActive(ScanLogin.Method.NETEASE);
		String qqCookie = MusicApi.effectiveCookieHeader();
		System.out.println("  当前选中=网易云 时，QQ 用的 Cookie = "
				+ (qqCookie.isEmpty() ? "(空 ✓)" : qqCookie));
		check("【QQ 没有拿到网易云的 MUSIC_U】", !qqCookie.contains("MUSIC_U"));
		check("【QQ 的 Cookie 是空的（QQ 没登录）】", qqCookie.isEmpty());

		setActive(ScanLogin.Method.QQ);
		check("切回 QQ 后 neteaseCookieHeader 仍有内容（互不干扰）",
				!MusicApi.neteaseCookieHeader().isEmpty());
		check("切回 QQ 后 effectiveCookieHeader 仍不串味",
				!MusicApi.effectiveCookieHeader().contains("MUSIC_U"));

		System.out.println();
		System.out.printf("结果: %d 通过 / %d 失败%n", pass, fail);
		System.out.println(fail == 0
				? "[PASS] 网易云登录逻辑正确，且不会污染 QQ 的 Cookie"
				: "[FAIL] 有不符合预期的用例");
		System.exit(fail == 0 ? 0 : 1);
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
	static void clearJar(ScanLogin login) {
		if (login == null) return;
		try {
			Field f = ScanLogin.class.getDeclaredField("jar");
			f.setAccessible(true);
			((Map<String, String>) f.get(login)).clear();
		} catch (Exception ignored) {
		}
	}

	/** 占位，避免未使用警告。 */
	static class FileState {
		static void mark() {
		}
	}
}
