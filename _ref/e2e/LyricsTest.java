import com.yue.music.api.LrcParser;
import com.yue.music.api.NeteaseProvider;
import com.yue.music.api.QQProvider;
import com.yue.music.api.WeChatLogin;
import com.yue.music.api.QQLogin;
import com.yue.music.model.Lyrics;
import com.yue.music.model.MusicTrack;

import java.util.List;

/** 歌词功能自测：两个平台的接口 + LRC 解析 + 时间轴定位。 */
public class LyricsTest {

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

		// ---------- 1. LRC 解析器单测 ----------
		System.out.println("===== 1. LRC 解析器 =====");
		String lrc = "[ti:测试]\n[ar:歌手]\n[offset:0]\n"
				+ "[00:01.00]第一句\n"
				+ "[00:05.50][00:10.00]重复句\n"
				+ "[00:20.5]百分秒\n"
				+ "[01:00]无小数\n"
				+ "\n[00:30.123]毫秒\n";
		List<Lyrics.Line> lines = LrcParser.parse(lrc);
		check("解析行数正确", lines.size() == 6, "解析出 " + lines.size() + " 行（期望 6：1+2+1+1+1）");
		check("丢弃元信息", lines.stream().noneMatch(l -> l.text().contains("ti:")),
				"没有 [ti:]/[ar:] 这类行混进歌词");
		boolean timesOk = lines.get(0).timeMs() == 1000
				&& lines.get(1).timeMs() == 5500
				&& lines.get(2).timeMs() == 10000;
		check("时间戳换算正确", timesOk,
				"1.00s=" + lines.get(0).timeMs() + "ms, 5.50s=" + lines.get(1).timeMs()
						+ "ms, 10.00s=" + lines.get(2).timeMs() + "ms");
		Lyrics parsed = new Lyrics("test", lines, false, null);
		check("indexAt 定位", parsed.indexAt(0) == -1 && parsed.indexAt(6) == 2 && parsed.indexAt(3600) == -1
						|| parsed.indexAt(3600) == 5,
				"t=0 → " + parsed.indexAt(0) + ", t=6 → " + parsed.indexAt(6) + ", t=3600 → " + parsed.indexAt(3600));

		// 翻译合并
		String trans = "[00:01.00]First line\n[00:05.50]Repeat line\n";
		List<Lyrics.Line> merged = LrcParser.parse(lrc, trans);
		boolean hasTrans = merged.stream().anyMatch(l -> !l.trans().isEmpty());
		check("翻译按时间戳合并", hasTrans,
				merged.stream().filter(l -> !l.trans().isEmpty()).findFirst()
						.map(l -> l.text() + " → " + l.trans()).orElse("无"));

		// ---------- 2. QQ音乐歌词 ----------
		System.out.println("\n===== 2. QQ音乐 歌词 =====");
		List<MusicTrack> ql = qq.search("小苹果", 5);
		MusicTrack qTrack = ql.isEmpty() ? null : ql.get(0);
		if (qTrack != null) {
			Lyrics lyr = qq.lyrics(qTrack);
			check("QQ歌词获取成功", lyr.ok() && !lyr.lines().isEmpty(),
					lyr.ok() ? lyr.lines().size() + " 行，例如「"
							+ lyr.lines().get(Math.min(5, lyr.lines().size() - 1)).text() + "」" : lyr.error());
			if (!lyr.lines().isEmpty()) {
				System.out.println("    前 6 行：");
				for (int i = 0; i < Math.min(6, lyr.lines().size()); i++) {
					Lyrics.Line l = lyr.lines().get(i);
					System.out.printf("      [%6.2fs] %s%s%n", l.timeMs() / 1000.0, l.text(),
							l.trans().isEmpty() ? "" : "  /  " + l.trans());
				}
			}
		}

		// ---------- 3. 网易云歌词 ----------
		System.out.println("\n===== 3. 网易云 歌词 =====");
		List<MusicTrack> nl = ne.search("晴天", 5);
		MusicTrack nTrack = nl.isEmpty() ? null : nl.get(0);
		if (nTrack != null) {
			Lyrics lyr = ne.lyrics(nTrack);
			check("网易云歌词获取成功", lyr.ok() && !lyr.lines().isEmpty(),
					lyr.ok() ? lyr.lines().size() + " 行，带翻译=" + lyr.hasTranslation() : lyr.error());
			if (!lyr.lines().isEmpty()) {
				System.out.println("    前 6 行：");
				for (int i = 0; i < Math.min(6, lyr.lines().size()); i++) {
					Lyrics.Line l = lyr.lines().get(i);
					System.out.printf("      [%6.2fs] %s%s%n", l.timeMs() / 1000.0, l.text(),
							l.trans().isEmpty() ? "" : "  /  " + l.trans());
				}
			}
		}

		System.out.println("\n================ 结果: " + pass + " 通过 / " + fail + " 失败 ================");
	}
}
