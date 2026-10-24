import com.yue.music.api.QQLogin;
import com.yue.music.api.QQProvider;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;
import com.yue.music.audio.PcmSource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 验证「VIP 歌为什么没声音」的根因假设：
 *
 * <p>登录后 {@code QUALITIES_LOGGED_IN} 首选 <b>F000（无损FLAC）</b>，
 * 而小夜的解码链是 mp3spi/jlayer（只能解 MP3）。
 * 如果 QQ 返回的是真 FLAC，而 Java Sound 又不支持 FLAC 解码 →
 * 整首歌就放不出来（静音）。
 *
 * <p>本测试分两步：
 * <ol>
 *   <li>用服务端配置里的登录态解析一首 VIP 歌，看拿到的是什么音质</li>
 *   <li>探测 Java 运行时到底能解哪些格式（mp3 / flac / m4a）</li>
 * </ol>
 */
public class VipFormatTest {

	public static void main(String[] args) throws Exception {
		System.out.println("=== 1) Java 运行时支持哪些音频格式 ===");
		System.out.println("  AudioSystem 支持的格式：");
		for (var f : javax.sound.sampled.AudioSystem.getAudioFileTypes()) {
			System.out.println("      " + f.getExtension());
		}

		System.out.println();
		System.out.println("=== 2) 登录后解析 VIP 歌，看拿到什么音质 ===");
		QQLogin login = new QQLogin();
		var cfg = com.yue.music.config.MusicConfig.get();
		if (cfg.qqCookies != null && !cfg.qqCookies.isEmpty()) {
			login.loadCookies(cfg.qqCookies, cfg.qqNickname);
			System.out.println("  用配置里的 Cookie 登录，状态=" + login.loggedIn());
		} else {
			System.out.println("  配置里没有 Cookie，按未登录测试（对照用）");
		}
		QQProvider qq = new QQProvider(login, new com.yue.music.api.WeChatLogin());

		for (String kw : new String[]{"晴天 周杰伦", "My Way 张敬轩"}) {
			System.out.println("  --- 搜索 " + kw + " ---");
			try {
				var list = qq.search(kw, 4);
				for (MusicTrack t : list) {
					PlayUrl p = qq.resolvePlayUrl(t);
					System.out.printf("    %-42s vip=%-5s → %s%n",
							cut(t.display(), 42), t.vip(),
							p.ok() ? (p.quality() + "  url后缀=" + ext(p.url())) : ("失败: " + p.error()));
					if (p.ok()) {
						String e = ext(p.url());
						System.out.println("        能否解码：" + tryDecode(p.url(), e));
					}
				}
			} catch (Exception e) {
				System.out.println("    搜索异常: " + e);
			}
		}
	}

	static String cut(String s, int n) {
		return s.length() > n ? s.substring(0, n) : s;
	}

	static String ext(String url) {
		if (url == null) return "?";
		String u = url.toLowerCase();
		for (String e : new String[]{".flac", ".mp3", ".m4a", ".aac", ".wav", ".ogg"}) {
			if (u.contains(e)) return e;
		}
		return "(无扩展名)";
	}

	/** 试着用现有解码链打开这个直链。 */
	static String tryDecode(String url, String ext) {
		try {
			HttpClient c = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.ALWAYS)
					.version(HttpClient.Version.HTTP_1_1).build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", "Mozilla/5.0")
					.header("Referer", "https://music.163.com/")
					.build();
			HttpResponse<InputStream> r = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() / 100 != 2) return "HTTP " + r.statusCode();
			try (InputStream in = r.body()) {
				byte[] head = in.readNBytes(16);
				String magic = magicOf(head);
				try (InputStream in2 = c.send(req, HttpResponse.BodyHandlers.ofInputStream()).body();
					 PcmSource pcm = PcmSource.open(in2)) {
					int n = pcm.read(new byte[8192]);
					return "成功（magic=" + magic + "，解出 " + n + " 字节 PCM）";
				}
			}
		} catch (Throwable e) {
			String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			return "失败: " + (m.length() > 90 ? m.substring(0, 90) : m);
		}
	}

	static String magicOf(byte[] b) {
		if (b.length < 4) return "太短";
		if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "FLAC";
		if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "MP3(ID3)";
		if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "MP3";
		if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "MP4/M4A";
		if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g') return "OGG";
		if (b[0] == 'R' && b[1] == 'I') return "RIFF/WAV";
		return "未知(" + String.format("%02X %02X %02X %02X", b[0], b[1], b[2], b[3]) + ")";
	}
}
