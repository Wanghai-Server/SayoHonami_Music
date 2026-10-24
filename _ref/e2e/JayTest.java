import com.yue.music.api.*;
import com.yue.music.model.*;
public class JayTest {
  public static void main(String[] a) throws Exception {
    QQLogin login = new QQLogin();
    QQProvider qq = new QQProvider(login, new WeChatLogin());
    System.out.println("=== 周杰伦的歌：VIP 标记 + 能否播放 ===");
    int vip=0, free=0, vipPlay=0, freeFail=0;
    for (String kw : new String[]{"周杰伦 晴天","周杰伦 稻香","周杰伦 七里香","周杰伦 青花瓷","周杰伦 告白气球","周杰伦 夜曲"}) {
      var hits = qq.search(kw, 1);
      if (hits.isEmpty()) { System.out.println("  " + kw + " → 搜不到"); continue; }
      MusicTrack t = hits.get(0);
      PlayUrl u = qq.resolvePlayUrl(t);
      if (t.vip()) vip++; else free++;
      if (t.vip() && u.ok()) vipPlay++;
      if (!t.vip() && !u.ok()) freeFail++;
      System.out.printf("  %-14s VIP=%-5s → %s%n", t.title(), t.vip(),
          u.ok() ? ("可播 " + u.quality()) : ("放不了：" + u.error()));
    }
    System.out.println();
    System.out.println("  周杰伦搜索样本：VIP " + vip + " 首 / 非VIP " + free + " 首");
    System.out.println("  其中 VIP 但能放 = " + vipPlay);
    System.out.println("  非VIP 却放不了  = " + freeFail);
  }
}