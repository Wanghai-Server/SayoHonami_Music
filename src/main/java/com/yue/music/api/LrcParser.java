package com.yue.music.api;

import com.yue.music.model.Lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析（QQ音乐 / 网易云都返回这个格式）。
 *
 * <p>支持：
 * <ul>
 *   <li>一行多个时间戳：{@code [00:12.34][01:20.00]歌词}</li>
 *   <li>毫秒 2~3 位、以及 {@code [mm:ss]} 无小数</li>
 *   <li>元信息行 {@code [ti:] [ar:] [al:] [by:] [offset:]} 自动丢弃</li>
 *   <li>每行独立的 {@code [offset:±毫秒]} 校正</li>
 *   <li>网易云的翻译单独一份 LRC，按时间戳合并</li>
 * </ul>
 */
public final class LrcParser {

	private LrcParser() {
	}

	private static final Pattern TIME_TAG =
			Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern OFFSET_TAG =
			Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
	private static final Pattern META_TAG =
			Pattern.compile("^\\[[a-zA-Z]+:.*]$");

	/** 解析主歌词。 */
	public static List<Lyrics.Line> parse(String lrc) {
		return parse(lrc, null);
	}

	/**
	 * 解析歌词，并可合并翻译。
	 *
	 * @param main  主歌词 LRC
	 * @param trans 翻译 LRC，可为 null
	 */
	public static List<Lyrics.Line> parse(String lrc, String transLrc) {
		if (lrc == null || lrc.isBlank()) return List.of();
		List<Lyrics.Line> out = new ArrayList<>();

		int offsetMs = 0;
		Matcher om = OFFSET_TAG.matcher(lrc);
		if (om.find()) {
			try {
				offsetMs = Integer.parseInt(om.group(1));
			} catch (NumberFormatException ignored) {
			}
		}

		for (String raw : lrc.split("\r?\n")) {
			String line = raw.trim();
			if (line.isEmpty()) continue;
			Matcher tm = TIME_TAG.matcher(line);
			if (!tm.find()) continue;
			// 收集这一行所有时间戳
			List<Integer> times = new ArrayList<>();
			int end = 0;
			Matcher all = TIME_TAG.matcher(line);
			while (all.find()) {
				times.add(toMillis(all.group(1), all.group(2), all.group(3)) + offsetMs);
				end = all.end();
			}
			String text = line.substring(end).trim();
			for (int t : times) {
				if (text.isEmpty()) continue;
				if (META_TAG.matcher(text).matches()) continue;
				out.add(new Lyrics.Line(Math.max(0, t), text, ""));
			}
		}

		// 合并翻译
		if (transLrc != null && !transLrc.isBlank()) {
			List<Lyrics.Line> trans = parsePlain(transLrc);
			if (!trans.isEmpty() && !out.isEmpty()) {
				out = mergeTranslation(out, trans);
			}
		}

		out.sort(Comparator.comparingInt(Lyrics.Line::timeMs));
		return out;
	}

	/** 解析翻译用：不做 META 过滤之外的额外处理。 */
	private static List<Lyrics.Line> parsePlain(String lrc) {
		List<Lyrics.Line> out = new ArrayList<>();
		for (String raw : lrc.split("\r?\n")) {
			String line = raw.trim();
			if (line.isEmpty()) continue;
			Matcher all = TIME_TAG.matcher(line);
			List<Integer> times = new ArrayList<>();
			int end = 0;
			while (all.find()) {
				times.add(toMillis(all.group(1), all.group(2), all.group(3)));
				end = all.end();
			}
			if (times.isEmpty()) continue;
			String text = line.substring(end).trim();
			if (text.isEmpty()) continue;
			out.add(new Lyrics.Line(times.get(0), text, ""));
		}
		return out;
	}

	/** 翻译按时间戳贴近（±600ms）合并到主歌词。 */
	private static List<Lyrics.Line> mergeTranslation(List<Lyrics.Line> main, List<Lyrics.Line> trans) {
		List<Lyrics.Line> out = new ArrayList<>(main.size());
		int ti = 0;
		for (Lyrics.Line m : main) {
			String t = "";
			while (ti < trans.size() && trans.get(ti).timeMs() < m.timeMs() - 600) ti++;
			if (ti < trans.size() && Math.abs(trans.get(ti).timeMs() - m.timeMs()) <= 600) {
				t = trans.get(ti).text();
			}
			out.add(new Lyrics.Line(m.timeMs(), m.text(), t));
		}
		return out;
	}

	private static int toMillis(String mm, String ss, String frac) {
		int m = Integer.parseInt(mm);
		int s = Integer.parseInt(ss);
		int ms = 0;
		if (frac != null) {
			// 2 位 = 百分之一秒，3 位 = 毫秒
			ms = frac.length() == 1 ? Integer.parseInt(frac) * 100
					: frac.length() == 2 ? Integer.parseInt(frac) * 10
					: Integer.parseInt(frac.substring(0, 3));
		}
		return m * 60_000 + s * 1000 + ms;
	}

}
