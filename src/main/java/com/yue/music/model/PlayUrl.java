package com.yue.music.model;

/**
 * 解析出来的播放地址。
 *
 * @param url      可直接 HTTP 拉取的音频地址（可能是 mp3 / m4a / flac）
 * @param codec    音频格式提示：mp3 / m4a / flac / ogg / unknown
 * @param quality  音质描述，如 128kbps
 * @param size     文件大小（字节），未知为 0
 * @param error    失败原因；不为空时 url 无意义
 */
public record PlayUrl(String url, String codec, String quality, long size, String error) {

	public boolean ok() {
		return error == null && url != null && !url.isBlank();
	}

	public static PlayUrl of(String url, String codec, String quality, long size) {
		return new PlayUrl(url, codec, quality, size, null);
	}

	public static PlayUrl fail(String reason) {
		return new PlayUrl(null, null, null, 0, reason);
	}
}
