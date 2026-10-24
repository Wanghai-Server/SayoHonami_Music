public class FabricProbe {
  public static void main(String[] a) {
    try {
      var cfg = com.yue.music.config.MusicConfig.get();
      System.out.println("MusicConfig OK: volume=" + cfg.volume + " maxQueueSize=" + cfg.maxQueueSize);
    } catch (Throwable t) {
      System.out.println("失败: " + t);
    }
  }
}