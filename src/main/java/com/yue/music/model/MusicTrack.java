package com.yue.music.model;

import com.google.gson.JsonObject;

/**
 * 一首歌（跨平台统一表示）。
 *
 * @param id       平台侧的唯一 ID（网易云是数字 id，QQ 音乐是 songmid）
 * @param source   来源平台 id：{@code netease} / {@code qq}
 * @param title    歌名
 * @param artists  歌手，逗号分隔（已拼接好，方便直接显示与网络传输）
 * @param album    专辑名
 * @param duration 时长（秒），未知为 0
 * @param albumMid 专辑 mid（QQ 音乐用，取封面）
 * @param vip      是否需要会员才能完整播放
 */
public record MusicTrack(
		String id,
		String source,
		String title,
		String artists,
		String album,
		int duration,
		String albumMid,
		boolean vip
) {
	public String display() {
		return artists.isEmpty() ? title : title + " - " + artists;
	}

	public String durationText() {
		if (duration <= 0) return "--:--";
		return String.format("%d:%02d", duration / 60, duration % 60);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("source", source);
		o.addProperty("title", title);
		o.addProperty("artists", artists);
		o.addProperty("album", album);
		o.addProperty("duration", duration);
		o.addProperty("albumMid", albumMid);
		o.addProperty("vip", vip);
		return o;
	}

	public static MusicTrack fromJson(JsonObject o) {
		return new MusicTrack(
				str(o, "id"),
				str(o, "source"),
				str(o, "title"),
				str(o, "artists"),
				str(o, "album"),
				o.has("duration") ? o.get("duration").getAsInt() : 0,
				str(o, "albumMid"),
				o.has("vip") && o.get("vip").getAsBoolean()
		);
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
