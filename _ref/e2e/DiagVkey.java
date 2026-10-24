import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.api.Http;
import com.yue.music.api.QQLogin;

import java.util.HashMap;
import java.util.Map;

/** 单独诊断 QQ vkey 请求：把请求体和响应原样打出来对照。 */
public class DiagVkey {

	public static void main(String[] args) throws Exception {
		String mid = args.length > 0 ? args[0] : "001zOWbJ3Q94ca";
		QQLogin login = new QQLogin();

		for (String[] q : new String[][]{{"M500", "mp3"}, {"C400", "m4a"}}) {
			JsonObject param = new JsonObject();
			param.addProperty("guid", "10000");
			JsonArray mids = new JsonArray();
			mids.add(mid);
			param.add("songmid", mids);
			JsonArray files = new JsonArray();
			files.add(q[0] + mid + mid + "." + q[1]);
			param.add("filename", files);
			JsonArray types = new JsonArray();
			types.add(1);
			param.add("songtype", types);
			param.addProperty("uin", login.uin());
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

			JsonObject root = new JsonObject();
			root.add("req", req);
			root.add("comm", comm);

			Map<String, String> h = new HashMap<>();
			h.put("Referer", "https://y.qq.com/");
			h.put("Origin", "https://y.qq.com");
			String body = Http.getMusicuFcg("https://u.y.qq.com/cgi-bin/musicu.fcg", h, root.toString());
			System.out.println("--- 音质 " + q[0] + " 请求体 ---");
			System.out.println(root);
			System.out.println("--- 响应 ---");
			System.out.println(body.length() > 700 ? body.substring(0, 700) + "..." : body);
			try {
				JsonObject resp = JsonParser.parseString(body).getAsJsonObject();
				JsonObject info = resp.getAsJsonObject("req").getAsJsonObject("data")
						.getAsJsonArray("midurlinfo").get(0).getAsJsonObject();
				System.out.println("result=" + info.get("result") + " filename=" + info.get("filename")
						+ " purl空=" + info.get("purl").getAsString().isBlank());
				String purl = info.get("purl").getAsString();
				if (!purl.isBlank()) {
					String url = "https://isure.stream.qqmusic.qq.com/" + purl;
					System.out.println("URL: " + (url.length() > 110 ? url.substring(0, 110) + "..." : url));
				}
			} catch (Exception e) {
				System.out.println("解析失败: " + e);
			}
			System.out.println();
		}
	}
}
