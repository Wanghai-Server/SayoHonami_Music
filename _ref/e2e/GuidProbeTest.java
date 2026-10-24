import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.api.Http;

import java.util.HashMap;
import java.util.Map;

/**
 * 找出「什么 guid 才能让 vkey 接口正常返回 purl」。
 *
 * <p>探针发现响应里有 {@code "msg":"...guid-error;"}，而当前代码**根本没设 guid** ——
 * 怀疑这就是免费歌也拿不到地址的原因（登录状态下 VIP 歌更拿不到）。
 * 这里逐个试常见 guid。
 */
public class GuidProbeTest {

	static String call(String mid, String filename, String guid, String platform, int ct) throws Exception {
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
		param.addProperty("platform", platform);

		JsonObject req = new JsonObject();
		req.addProperty("module", "vkey.GetVkeyServer");
		req.addProperty("method", "CgiGetVkey");
		req.add("param", param);

		JsonObject comm = new JsonObject();
		comm.addProperty("uin", 0);
		comm.addProperty("format", "json");
		comm.addProperty("ct", ct);
		comm.addProperty("cv", 0);
		if (guid != null) comm.addProperty("guid", guid);

		JsonObject root = new JsonObject();
		root.add("req", req);
		root.add("comm", comm);

		Map<String, String> h = new HashMap<>();
		h.put("Referer", "https://y.qq.com/");
		h.put("Origin", "https://y.qq.com");
		h.put("Content-Type", "application/json;charset=UTF-8");
		return Http.getMusicuFcg("https://u.y.qq.com/cgi-bin/musicu.fcg", h, root.toString());
	}

	static void tryOne(String label, String mid, String quality, String guid, String platform, int ct) {
		String ext = quality.startsWith("F") ? ".flac" : (quality.startsWith("C") ? ".m4a" : ".mp3");
		String filename = quality + mid + mid + ext;
		try {
			String body = call(mid, filename, guid, platform, ct);
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			JsonObject data = root.getAsJsonObject("req").getAsJsonObject("data");
			String retcodeMsg = data.has("msg") ? data.get("msg").getAsString() : "";
			int retcode = data.has("retcode") ? data.get("retcode").getAsInt() : -1;
			JsonObject info = data.getAsJsonArray("midurlinfo").get(0).getAsJsonObject();
			String purl = info.has("purl") && !info.get("purl").isJsonNull()
					? info.get("purl").getAsString() : "";
			int result = info.has("result") ? info.get("result").getAsInt() : -1;
			String sip = data.has("sip") && !data.getAsJsonArray("sip").isEmpty()
					? data.getAsJsonArray("sip").get(0).getAsString() : "";

			System.out.printf("  %-34s retcode=%-4d result=%-7d %s  %s%n",
					label, retcode, result,
					purl.isBlank() ? "purl=空 ❌" : "purl=有 ✓",
					sip.isBlank() ? "" : ("sip=" + sip));
			if (!retcodeMsg.isBlank() && retcode != 0) {
				System.out.println("       msg=" + retcodeMsg);
			}
		} catch (Exception e) {
			System.out.println("  " + label + " → 异常: " + e);
		}
	}

	public static void main(String[] args) {
		String free = "000Gf4pO0WMcbF";
		System.out.println("=== 免费歌，换不同 guid / platform / ct ===");
		tryOne("无 guid, platform=20, ct=24", free, "M500", null, "20", 24);
		tryOne("guid=10000, platform=20, ct=24", free, "M500", "10000", "20", 24);
		tryOne("guid=1001603, platform=20, ct=24", free, "M500", "1001603", "20", 24);
		tryOne("guid=10000, platform=yqq.json, ct=24", free, "M500", "10000", "yqq.json", 24);
		tryOne("guid=1001603, platform=yqq, ct=24", free, "M500", "1001603", "yqq", 24);
		tryOne("guid=10000, platform=20, ct=24 无loginflag", free, "M500", "10000", "20", 24);

		System.out.println();
		System.out.println("=== 晴天（VIP）在最好的组合下 ===");
		tryOne("guid=1001603, platform=20, ct=24", "0039MnYb0qxYhV", "M500", "1001603", "20", 24);
		tryOne("guid=1001603, platform=20, ct=24 (F000)", "0039MnYb0qxYhV", "F000", "1001603", "20", 24);
		tryOne("guid=10000, platform=yqq.json", "0039MnYb0qxYhV", "M500", "10000", "yqq.json", 24);

		System.out.println();
		System.out.println("注意：本测试【未登录】，VIP 歌本来就不该给地址。");
		System.out.println("      关键是看【免费歌】在哪个 guid 组合下能拿到 purl —— 那才是真 bug。");
	}
}
