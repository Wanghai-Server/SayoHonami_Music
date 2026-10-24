import com.yue.music.api.Http;
import com.yue.music.api.NeteaseProvider;
import com.yue.music.api.QQLogin;
import com.yue.music.api.QQProvider;
import com.yue.music.api.WeChatLogin;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 端到端联网自测：搜索 / 取地址 / 下载 / 解码 / 兜底 / 扫码接口。 */
public class E2E {

	static int pass = 0, fail = 0;

	static void check(String name, boolean ok, String detail) {
		if (ok) {
			pass++;
			System.out.println("[PASS] " + name + "  " + detail);
		} else {
			fail++;
			System.out.println("[FAIL] " + name + "  " + detail);
		}
	}

	public static void main(String[] args) throws Exception {
		QQLogin login = new QQLogin();
		QQProvider qq = new QQProvider(login, new WeChatLogin());
		NeteaseProvider ne = new NeteaseProvider();

		System.out.println("===== 1. QQ音乐 搜索 =====");
		List<MusicTrack> qqList = qq.search("小苹果", 8);
		check("QQ搜索返回结果", !qqList.isEmpty(), "共 " + qqList.size() + " 首");
		for (MusicTrack t : qqList.subList(0, Math.min(4, qqList.size()))) {
			System.out.println("    " + t.id() + " | " + t.display() + " | " + t.durationText() + " | VIP=" + t.vip());
		}

		System.out.println("\n===== 2. QQ音乐 取播放地址 + 下载 + 解码 =====");
		MusicTrack freeQq = null;
		for (MusicTrack t : qqList) {
			if (!t.vip()) {
				freeQq = t;
				break;
			}
		}
		if (freeQq != null) {
			PlayUrl url = qq.resolvePlayUrl(freeQq);
			check("QQ免费歌拿到地址", url.ok(), url.ok() ? url.quality() + "  " + shortUrl(url.url()) : url.error());
			if (url.ok()) testDownloadDecode("QQ", url.url());
		} else {
			check("QQ免费歌拿到地址", false, "前8首都是VIP歌");
		}

		System.out.println("\n===== 3. 网易云 搜索 =====");
		List<MusicTrack> neList = ne.search("晴天", 8);
		check("网易云搜索返回结果", !neList.isEmpty(), "共 " + neList.size() + " 首");
		for (MusicTrack t : neList.subList(0, Math.min(4, neList.size()))) {
			System.out.println("    " + t.id() + " | " + t.display() + " | " + t.durationText() + " | VIP=" + t.vip());
		}

		System.out.println("\n===== 4. 网易云 取播放地址 + 下载 + 解码 =====");
		if (!neList.isEmpty()) {
			PlayUrl url = ne.resolvePlayUrl(neList.get(0));
			check("网易云拿到地址", url.ok(), url.ok() ? url.quality() : url.error());
			if (url.ok()) testDownloadDecode("网易云", url.url());
		}

		System.out.println("\n===== 5. 跨平台兜底（QQ VIP歌 → 网易云同名）=====");
		MusicTrack vip = null;
		for (MusicTrack t : qqList) {
			if (t.vip()) {
				vip = t;
				break;
			}
		}
		if (vip != null) {
			PlayUrl direct = qq.resolvePlayUrl(vip);
			System.out.println("    QQ直连: " + (direct.ok() ? "可播" : direct.error()));
			List<MusicTrack> alt = ne.search(vip.title() + " " + firstArtist(vip.artists()), 6);
			PlayUrl altUrl = null;
			MusicTrack hit = null;
			for (MusicTrack c : alt) {
				if (norm(c.title()).contains(norm(vip.title())) || norm(vip.title()).contains(norm(c.title()))) {
					PlayUrl u = ne.resolvePlayUrl(c);
					if (u.ok()) {
						altUrl = u;
						hit = c;
						break;
					}
				}
			}
			check("跨平台兜底能找到替代版本", altUrl != null,
					altUrl != null ? hit.display() + " 可播" : "网易云也没找到同名可播版本");
		}

		System.out.println("\n===== 6. QQ 扫码登录：二维码 + 轮询 =====");
		try {
			Map<String, String> jar = new HashMap<>();
			Map<String, String> hdr = Map.of("Referer", "https://xui.ptlogin2.qq.com/");
			String qrUrl = String.format(
					"https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4"
							+ "&t=%d&daid=383&pt_3rd_aid=100497308", System.currentTimeMillis());
			Http.Step qr = Http.getManual(qrUrl, hdr, jar, true);
			jar.putAll(qr.cookies());
			byte[] png = qr.binary();
			boolean isPng = png != null && png.length > 100
					&& (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
			check("拿到二维码 PNG", isPng,
					png == null ? "null" : png.length + " 字节, qrsig=" + (jar.containsKey("qrsig") ? "有" : "无"));
			if (isPng && jar.containsKey("qrsig")) {
				int token = QQLogin.hash33(jar.get("qrsig"), 0);
				String pollUrl = String.format(
						"https://ssl.ptlogin2.qq.com/ptqrlogin?u1=%s&ptqrtoken=%s&ptredirect=1&h=1&t=1&g=1"
								+ "&from_ui=1&ptlang=2052&action=0-0-%d&js_ver=20102616&js_type=1"
								+ "&login_sig=&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308",
						"https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump", token, System.currentTimeMillis());
				Http.Step poll = Http.getManual(pollUrl, hdr, jar, false);
				String body = poll.body() == null ? "" : poll.body();
				boolean gotCb = body.contains("ptuiCB");
				check("轮询返回 ptuiCB（验证 hash33 正确）", gotCb,
						gotCb ? body.substring(0, Math.min(80, body.length())) : "HTTP " + poll.status() + " " + body);
			}
		} catch (Exception e) {
			check("二维码接口", false, e.toString());
		}

		System.out.println("\n================ 结果: " + pass + " 通过 / " + fail + " 失败 ================");
	}

	static void testDownloadDecode(String tag, String url) {
		try {
			HttpClient client = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.ALWAYS).build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", "Mozilla/5.0")
					.header("Referer", "https://music.163.com/")
					.timeout(java.time.Duration.ofSeconds(20)).build();
			HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
			check(tag + " 音频 HTTP 状态", resp.statusCode() / 100 == 2, "HTTP " + resp.statusCode());
			try (InputStream raw = new BufferedInputStream(resp.body(), 65536);
				 AudioInputStream in = AudioSystem.getAudioInputStream(raw)) {
				AudioFormat src = in.getFormat();
				AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);
				try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
					byte[] buf = new byte[65536];
					long total = 0;
					int n, reads = 0;
					while ((n = pcm.read(buf)) > 0 && reads < 60) {
						total += n;
						reads++;
					}
					check(tag + " MP3→PCM 解码", total > 100000,
							"源=" + (int) src.getSampleRate() + "Hz/" + src.getChannels() + "ch, 解出 "
									+ total + " 字节 PCM（约 " + String.format("%.1f", total / (44100.0 * 4)) + " 秒）");
				}
			}
		} catch (Exception e) {
			check(tag + " 下载/解码", false, e.toString());
		}
	}

	static String norm(String s) {
		return s == null ? "" : s.replaceAll("[()（）\\[\\]【】]", "").replaceAll("\\s", "").toLowerCase();
	}

	static String firstArtist(String s) {
		if (s == null) return "";
		int i = s.indexOf('/');
		return i > 0 ? s.substring(0, i) : s;
	}

	static String shortUrl(String u) {
		if (u == null) return "null";
		return u.length() > 70 ? u.substring(0, 70) + "..." : u;
	}
}
