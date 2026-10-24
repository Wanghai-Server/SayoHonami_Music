import com.yue.music.api.*;
public class PlaylistPayProbe {
  public static void main(String[] a) throws Exception {
    String body = Http.getString(
      "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0"
      + "&disstid=7257580854&song_begin=0&song_num=5&format=json&notice=0&platform=yqq.json&needNewCode=0",
      java.util.Map.of("Referer","https://y.qq.com/","User-Agent",Http.DEFAULT_UA));
    var root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
    var cd = root.getAsJsonArray("cdlist").get(0).getAsJsonObject();
    var songs = cd.getAsJsonArray("songlist");
    System.out.println("=== 歌单接口前 3 首的 pay 字段 ===");
    for (int i=0;i<Math.min(3,songs.size());i++){
      var s = songs.get(i).getAsJsonObject();
      String name = s.has("songname")? s.get("songname").getAsString() : "?";
      String pay = s.has("pay") ? s.get("pay").toString() : "【字段不存在】";
      System.out.println("  " + name);
      System.out.println("      pay = " + pay);
      String keys = String.join(",", s.keySet());
      System.out.println("      可用字段: " + (keys.length()>180? keys.substring(0,180)+"..." : keys));
    }
  }
}