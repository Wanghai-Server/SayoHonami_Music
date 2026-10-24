package com.yue.music.client.audio;

import com.yue.music.client.YueMusicClient;

/**
 * 给 UI/HUD 用的小桥接：避免 UI 包直接依赖客户端初始化类。
 */
public final class ClientAudioAccess {

	private ClientAudioAccess() {
	}

	/** 当前快捷键的显示名（例如 ";"）。 */
	public static String openKeyName() {
		try {
			if (YueMusicClient.openKey == null) return ";";
			return YueMusicClient.openKey.getBoundKeyLocalizedText().getString();
		} catch (Throwable t) {
			return ";";
		}
	}
}
