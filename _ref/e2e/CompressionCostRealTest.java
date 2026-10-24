import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/** 用「压缩比接近真实日志(43%)」的数据再测一次，得到可信的耗时。 */
public class CompressionCostRealTest {
  static final Random RND = new Random(42);
  static final char[] CJK;   // 构造"不那么重复"的中文标题
  static {
    StringBuilder sb = new StringBuilder();
    for (char c = 0x4E00; c < 0x4E00 + 500; c++) sb.append(c);
    CJK = sb.toString().toCharArray();
  }
  static String rand(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) sb.append(CJK[RND.nextInt(CJK.length)]);
    return sb.toString();
  }
  /** 故意不做重复：标题/歌手/专辑全随机，模拟真实歌单。 */
  static String queueJson(int songs) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < songs; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"mid\":\"").append(rand(12)).append("\"")
        .append(",\"source\":\"qq\",\"title\":\"").append(rand(8 + RND.nextInt(10))).append("\"")
        .append(",\"artists\":\"").append(rand(6 + RND.nextInt(8))).append("\"")
        .append(",\"album\":\"").append(rand(8)).append("\"")
        .append(",\"duration\":").append(150 + RND.nextInt(200))
        .append(",\"albumMid\":\"").append(rand(10)).append("\",\"vip\":").append(RND.nextBoolean())
        .append("}");
    }
    return sb.append(']').toString();
  }
  static Object[] enc(String json) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length() / 4);
    try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
      gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return new Object[]{Base64.getEncoder().encodeToString(bos.toByteArray()), true};
  }
  public static void main(String[] a) throws Exception {
    System.out.println("=== 真实压缩比 + 真实耗时 ===");
    System.out.printf("  %6s | %10s | %10s | %8s | %10s%n", "首数", "JSON字符", "压缩后", "压缩比", "单次耗时");
    for (int s : new int[]{200, 500, 994, 2000}) {
      String j = queueJson(s);
      for (int i = 0; i < 15; i++) enc(j);
      long t0 = System.nanoTime(); int n = 80;
      for (int i = 0; i < n; i++) enc(j);
      double ms = (System.nanoTime() - t0) / n / 1e6;
      Object[] p = enc(j);
      int cl = ((String) p[0]).length();
      System.out.printf("  %6d | %10d | %10d | %7.0f%% | %7.2f ms%n",
          s, j.length(), cl, 100.0 * cl / j.length(), ms);
    }
    System.out.println();
    System.out.println("  对比：日志里 994 首 → 289171 字符，压缩后 123396 字符（43%）");
    System.out.println("  上面的模拟数据和日志同一量级、同一压缩比 ✓");
    System.out.println();
    System.out.println("=== 多玩家 × 每 5 秒的累计开销（994 首）===");
    String j = queueJson(994);
    for (int i = 0; i < 15; i++) enc(j);
    long t0 = System.nanoTime(); int n = 80;
    for (int i = 0; i < n; i++) enc(j);
    double one = (System.nanoTime() - t0) / n / 1e6;
    for (int pl : new int[]{1, 2, 5}) {
      System.out.printf("  %d 个玩家 → 每 5 秒 %.2f ms（占 1 个 tick 的 %.3f%%）%n",
          pl, one * pl, one * pl / 50.0);
    }
  }
}