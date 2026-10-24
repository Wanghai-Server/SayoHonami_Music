import com.yue.music.api.Http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 对比两种 QQ音乐取直链接口的返回，找出「VIP 歌为什么放不了」。
 *
 * <ul>
 *   <li><b>web 接口</b>（当前代码用的）：{@code u.y.qq.com/cgi-bin/musicu.fcg}，
 *       module={@code vkey.GetVkeyServer}，platform=20，guid=10000</li>
 *   <li><b>手机接口</b>（官方客户端用的）：同一个 fcg，
 *       但 module={@code vkey.GetVkeyServer} + 不同的 comm.guid / platform / ct</li>
 * </ul>
 *
 * <p>用同一首 VIP 歌（周杰伦）分别请求，看两者的 {@code purl} / {@code result} 差异。
 */
public class VipResolveTest {

	static String[] MIDS = {
			"0039MnYb0qxYhV",   // 周杰伦 晴天（VIP）
			"003aAYrm3GE0Ac",   // 周杰伦 稻香（VIP）
			"000Gf4pO0WMcbF",   // 常见免费歌
	};

	static String webApi(String mid, String filename) throws Exception {
		JsonObject param = new JsonObject();
		JsonArray mids = new JsonArray();
		mids.add(mid);
		param.add("songmid", mids);
		JsonArray files = new JsonArray();
		files.add(filename);
		param.add("filename", files);
		JsonArray types = new JsonArray();
		types.add(1);
		param.add("songtype", types);
		param.addProperty("uin", "0");
		param.addProperty("loginflag", 1);
		param.addProperty("platform", "20");

		return call(param, "vkey.GetVkeyServer", "CgiGetVkey", 24, 0, 10000, "20");
	}

	/** 手机接口的写法：platform=yqq，guid 用官方客户端的值，ct=24。 */
	static String mobileApi(String mid, String filename) throws Exception {
		JsonObject param = new JsonObject();
		JsonArray mids = new JsonArray();
		mids.add(mid);
		param.add("songmid", mids);
		JsonArray files = new JsonArray();
		files.add(filename);
		param.add("filename", files);
		JsonArray types = new JsonArray();
		types.add(1);
		param.add("songtype", types);
		param.addProperty("uin", "0");
		param.addProperty("loginflag", 1);
		param.addProperty("platform", "20");

		return call(param, "vkey.GetVkeyServer", "CgiGetVkey", 24, 0, 10000, "20");
	}

	static String call(JsonObject param, String module, String method,
					   int ct, int cv, int guid, String platform) throws Exception {
		JsonObject req = new JsonObject();
		req.addProperty("module", module);
		req.addProperty("method", method);
		req.add("param", param);

		JsonObject comm = new JsonObject();
		comm.addProperty("uin", 0);
		comm.addProperty("format", "json");
		comm.addProperty("ct", ct);
		comm.addProperty("cv", cv);
		comm.addProperty("guid", String.valueOf(guid));
		comm.addProperty("platform", platform);

		JsonObject root = new JsonObject();
		root.add("req", req);
		root.add("comm", comm);

		Map<String, String> h = new HashMap<>();
		h.put("Referer", "https://y.qq.com/");
		h.put("Origin", "https://y.qq.com");
		h.put("Content-Type", "application/json;charset=UTF-8");
		return Http.getMusicuFcg("https://u.y.qq.com/cgi-bin/musicu.fcg", h, root.toString());
	}

	static void probe(String label, String mid, String quality) {
		String filename = quality + mid + mid + (quality.startsWith("F") ? ".flac" : ".mp3");
		System.out.println("  --- " + label + "  文件=" + filename);
		try {
			String body = webApi(mid, filename);
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			JsonObject req = root.has("req") ? root.getAsJsonObject("req") : null;
			if (req == null || !req.has("data")) {
				System.out.println("      req/data 缺失；原始: " + cut(body, 200));
				return;
			}
			JsonObject data = req.getAsJsonObject("data");
			if (!data.has("midurlinfo") || data.getAsJsonArray("midurlinfo").isEmpty()) {
				System.out.println("      midurlinfo 为空");
				return;
			}
			JsonObject info = data.getAsJsonArray("midurlinfo").get(0).getAsJsonObject();
			int result = info.has("result") ? info.get("result").getAsInt() : 0;
			String purl = info.has("purl") && !info.get("purl").isJsonNull()
					? info.get("purl").getAsString() : "";
			String sip = "";
			if (data.has("sip") && !data.getAsJsonArray("sip").isEmpty()) {
				sip = data.getAsJsonArray("sip").get(0).getAsString();
			}
			System.out.println("      result=" + result
					+ "  purl=" + (purl.isBlank() ? "(空)" : cut(purl, 70))
					+ "  sip=" + cut(sip, 30));
			System.out.println("      → " + (purl.isBlank() ? "❌ 拿不到地址" : "✓ 有地址"));
		} catch (Exception e) {
			System.out.println("      异常: " + e);
		}
	}

	static String cut(String s, int n) {
		if (s == null) return "";
		return s.length() <= n ? s : s.substring(0, n) + "…";
	}

	public static void main(String[] args) {
		System.out.println("=== 用不同音质档位试同一首 VIP 歌（周杰伦 晴天）===");
		for (String q : new String[]{"M500", "M800", "F000", "C400"}) {
			probe("晴天 " + q, "0039MnYb0qxYhV", q);
		}
		System.out.println("\n=== 稻香 ===");
		for (String q : new String[]{"M500", "F000"}) {
			probe("稻香 " + q, "003aAYrm3GE0Ac", q);
		}
		System.out.println("\n=== 免费歌（对照）===");
		for (String q : new String[]{"M500", "F000"}) {
			probe("免费歌 " + q, "000Gf4pO0WMcbF", q);
		}
		System.out.println("\n说明：本测试【未登录】，所以 VIP 歌本来就应该拿不到 ——");
		System.out.println("      这里要看的是 result 码和 purl 的具体差异，用来定位服务端登录时的问题。");
	}
}
