package com.yue.music.api;

import com.yue.music.model.MusicTrack;
import com.yue.music.model.PlayUrl;

import java.util.List;

/**
 * 音乐平台适配接口。
 * <p>要支持新平台（比如酷我、咪咕），只要实现这个接口并注册到 {@link Providers} 即可，
 * 其余逻辑（UI、队列、同步、播放）完全不用改。
 */
public interface MusicProvider {

	/** 平台 id，和 {@link com.yue.music.model.MusicSource#id()} 对应。 */
	String id();

	/** 显示名。 */
	String displayName();

	/**
	 * 搜索歌曲。实现里请捕获异常并返回空列表，避免拖垮 UI。
	 *
	 * @param keyword 关键词
	 * @param limit   最多返回多少条
	 */
	List<MusicTrack> search(String keyword, int limit) throws Exception;

	/**
	 * 解析播放地址。返回 {@link PlayUrl#fail(String)} 表示这首歌当前放不了
	 * （版权 / 需要会员 / 接口变动），由上层决定是否去别的平台兜底。
	 */
	PlayUrl resolvePlayUrl(MusicTrack track) throws Exception;

	/**
	 * 获取歌词（LRC）。默认返回「没有歌词」，各平台自己实现。
	 * <p>失败时返回带 error 的 {@link com.yue.music.model.Lyrics}，不要把异常抛给 UI。
	 */
	default com.yue.music.model.Lyrics lyrics(MusicTrack track) {
		return com.yue.music.model.Lyrics.empty(track.source() + ":" + track.id(), "该平台未实现歌词");
	}

	/** 是否已登录（影响能不能放会员歌）。 */
	default boolean loggedIn() {
		return false;
	}

	/** 登录提示文案，显示在 UI 上。 */
	default String loginHint() {
		return "无需登录";
	}
}
