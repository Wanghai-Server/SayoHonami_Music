package com.yue.music.server;

import com.google.gson.JsonObject;
import com.yue.music.model.MusicTrack;

/**
 * 队列里的一项。
 *
 * @param track    歌曲信息
 * @param url      音频直链（客户端解析好后报给服务端，服务端只做域名校验）
 * @param duration 时长（秒）
 * @param addedBy  点歌玩家名
 */
public record QueueEntry(MusicTrack track, String url, int duration, String addedBy) {

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.add("track", track.toJson());
		o.addProperty("url", url);
		o.addProperty("duration", duration);
		o.addProperty("addedBy", addedBy);
		return o;
	}

	public static QueueEntry fromJson(JsonObject o) {
		MusicTrack t = MusicTrack.fromJson(o.getAsJsonObject("track"));
		return new QueueEntry(t,
				o.has("url") ? o.get("url").getAsString() : "",
				o.has("duration") ? o.get("duration").getAsInt() : 0,
				o.has("addedBy") ? o.get("addedBy").getAsString() : "");
	}
}
