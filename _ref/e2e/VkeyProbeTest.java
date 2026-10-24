import com.google.gson.JsonObject;
import com.yue.music.api.Http;

import java.util.HashMap;
import java.util.Map;

/**
 * 用项目自己的 {@code Http.getMusicuFcg} 请求 vkey，看 VIP 歌到底返回什么。
 *
 * <p>重点看 {@code midurlinfo[0].result}：
 * <ul>
 *   <li>{@code 104003} → 需要 QQ音乐会员（账号问题，代码改不了）</li>
 *   <li>其它非 0 → 值得研究</li>
 * </ul>
 */
public class VkeyProbeTest {

	/** 直接用 {@link QQProvider#requestVkey} 的同款请求体。 */
	static String request(String mid, String filename, boolean withGuid) throws Exception {
		JsonObject param = new JsonObject();
		var mids = new com.google.gson.JsonArray();
		mids.add(mid);
		param.add("songmid", mids);
		var files = new com.google.gson.JsonArray();
		files.add(filename);
		param.add("filename", files);
		var types = new com.google.gson.JsonArray();
		types.add(1);
		param.add("songtype", types);
		param.addProperty("uin", "0");
		param.addProperty("loginflag", 1);
		param.addProperty("platform", "20");

		JsonObject req = new JsonObject();
		req.addProperty("module", "vkey.GetVkeyServer");
		req.addProperty("method", "CgiGetVkey");
		req.add("param", param);

		JsonObject comm = new JsonObject();
		comm.addProperty("uin", 0);
		comm.addProperty("format", "json");
		comm.addProperty("ct", 24);
		comm.addProperty("cv", 0);
		if (withGuid) comm.addProperty("guid", "10000");

		JsonObject root = new JsonObject();
		root.add("req", req);
		root.add("comm", comm);

		Map<String, String> h = new HashMap<>();
		h.put("Referer", "https://y.qq.com/");
		h.put("Origin", "https://y.qq.com");
		h.put("Content-Type", "application/json;charset=UTF-8");
		return Http.getMusicuFcg("https://u.y.qq.com/cgi-bin/musicu.fcg", h, root.toString());
	}

	static void probe(String label, String mid, String quality, boolean withGuid) {
		String ext = quality.startsWith("F") ? ".flac" : (quality.startsWith("C") ? ".m4a" : ".mp3");
		String filename = quality + mid + mid + ext;
		try {
			String body = request(mid, filename, withGuid);
			System.out.println("  --- " + label + (withGuid ? " [带guid]" : " [不带guid]")
					+ "  文件=" + filename);
			System.out.println("      原始: " + cut(body, 260));
			JsonObject root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
			if (!root.has("req")) { System.out.println("      ✗ 没有 req 字段"); return; }
			JsonObject req = root.getAsJsonObject("req");
			if (!req.has("data")) { System.out.println("      ✗ 没有 data 字段"); return; }
			JsonObject data = req.getAsJsonObject("data");
			System.out.println("      data 字段: " + data.keySet());
			if (data.has("midurlinfo") && !data.getAsJsonArray("midurlinfo").isEmpty()) {
				JsonObject info = data.getAsJsonArray("midurlinfo").get(0).getAsJsonObject();
				System.out.println("      midurlinfo[0]: " + info);
			} else {
				System.out.println("      midurlinfo 为空");
			}
		} catch (Exception e) {
			System.out.println("  --- " + label + "  异常: " + e);
		}
	}

	static String cut(String s, int n) {
		if (s == null) return "";
		return s.length() <= n ? s : s.substring(0, n) + "…";
	}

	public static void main(String[] args) {
		System.out.println("=== 免费歌（对照）===");
		probe("免费歌 M500", "000Gf4pO0WMcbF", "M500", true);
		System.out.println();
		System.out.println("=== VIP 歌（周杰伦 晴天）===");
		probe("晴天 M500", "0039MnYb0qxYhV", "M500", true);
		probe("晴天 F000", "0039MnYb0qxYhV", "F000", true);
		System.out.println();
		System.out.println("=== 不带 guid（看是不是必需）===");
		probe("免费歌 M500", "000Gf4pO0WMcbF", "M500", false);
	}
}
