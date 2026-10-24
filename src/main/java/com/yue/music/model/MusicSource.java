package com.yue.music.model;

import java.util.Arrays;
import java.util.List;

/** 支持的音乐平台。新增平台只需在这里加一项 + 写一个 Provider 实现。 */
public enum MusicSource {
	QQ("qq", "QQ音乐"),
	NETEASE("netease", "网易云");

	private final String id;
	private final String displayName;

	MusicSource(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	/** 两个平台顺序即 UI 里的切换顺序。 */
	public static List<MusicSource> all() {
		return Arrays.asList(values());
	}

	public static MusicSource byId(String id) {
		for (MusicSource s : values()) {
			if (s.id.equalsIgnoreCase(id)) return s;
		}
		return null;
	}
}
