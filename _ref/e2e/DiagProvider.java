import com.yue.music.api.QQLogin;
import com.yue.music.api.QQProvider;
import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

/** 直接测 Provider 的解析路径（和 E2E 一样），看差异在哪。 */
public class DiagProvider {

	public static void main(String[] args) throws Exception {
		QQLogin login = new QQLogin();
		QQProvider qq = new QQProvider(login);
		String mid = args.length > 0 ? args[0] : "001zOWbJ3Q94ca";

		MusicTrack t = new MusicTrack(mid, "qq", "小苹果", "贝乐虎儿歌", "", 216, "", false);
		System.out.println("直接 requestVkey(M500):");
		PlayUrl p1 = qq.requestVkey(mid, "M500", "mp3", "128kbps");
		System.out.println("  ok=" + p1.ok() + " err=" + p1.error() + " q=" + p1.quality());
		if (p1.ok()) System.out.println("  url=" + p1.url().substring(0, Math.min(100, p1.url().length())));

		System.out.println("走 resolvePlayUrl(三档音质依次尝试):");
		PlayUrl p2 = qq.resolvePlayUrl(t);
		System.out.println("  ok=" + p2.ok() + " err=" + p2.error() + " q=" + p2.quality());
		if (p2.ok()) System.out.println("  url=" + p2.url().substring(0, Math.min(100, p2.url().length())));
	}
}
