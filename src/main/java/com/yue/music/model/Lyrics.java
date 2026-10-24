package com.yue.music.model;

import java.util.List;

/**
 * 一首歌的歌词（已解析好的 LRC）。
 *
 * @param lines        按时间排序的歌词行
 * @param hasTranslation 是否带翻译
 * @param error        获取失败原因；为空表示成功（lines 可能仍为空 = 纯音乐）
 */
public record Lyrics(String trackKey, List<Line> lines, boolean hasTranslation, String error) {

	/**
	 * 一行歌词。
	 *
	 * @param timeMs 开始时间（毫秒）
	 * @param text   原文
	 * @param trans  翻译（可能为空）
	 */
	public record Line(int timeMs, String text, String trans) {
	}

	public static Lyrics empty(String trackKey, String error) {
		return new Lyrics(trackKey, List.of(), false, error);
	}

	public boolean ok() {
		return error == null;
	}

	/**
	 * 找出某一时刻应该显示哪一行。
	 *
	 * @return 行下标；还没到第一句时返回 -1
	 */
	public int indexAt(int positionSeconds) {
		if (lines.isEmpty()) return -1;
		int ms = positionSeconds * 1000;
		int idx = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).timeMs() <= ms) idx = i;
			else break;
		}
		return idx;
	}

}
