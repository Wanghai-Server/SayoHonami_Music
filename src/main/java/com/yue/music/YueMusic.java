package com.yue.music;

import com.yue.music.api.MusicApi;
import com.yue.music.config.MusicConfig;
import com.yue.music.server.MusicServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用（双端都会加载）入口。
 *
 * <p>服务端职责：维护点歌队列、广播状态、校验音频地址。
 * <p>客户端职责：搜索、解析直链、下载播放、UI/HUD。客户端入口见
 * {@code com.yue.music.client.YueMusicClient}。
 */
public class YueMusic implements ModInitializer {
	/** Fabric 的 mod id 必须是小写字母/数字/下划线/连字符，所以显示名用 SayoHonami_Music，id 用下面这个。 */
	public static final String MOD_ID = "sayo-honami-music";
	/** 显示用名字（界面、日志里出现）。 */
	public static final String MOD_NAME = "SayoHonami_Music";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	@Override
	public void onInitialize() {
		// 配置先加载，后面所有模块都要读
		MusicConfig.get();
		// 音乐平台 Provider 注册（双端都能用，服务端不主动联网）
		MusicApi.init();
		// 服务端队列与网络
		MusicServer.init();
		// 每 5 秒校正一次播放进度
		ServerTickEvents.END_SERVER_TICK.register(server -> MusicServer.tick());

		LOGGER.info("[YueMusic] 通用初始化完成（QQ音乐 / 网易云）");
	}
}
