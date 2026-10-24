package com.yue.music.client;

import com.yue.music.YueMusic;
import com.yue.music.api.MusicApi;
import com.yue.music.client.audio.ClientAudio;
import com.yue.music.client.lyrics.LyricsClient;
import com.yue.music.client.ui.MusicHud;
import com.yue.music.client.ui.MusicScreen;
import com.yue.music.config.MusicConfig;
import com.yue.music.net.MusicNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 客户端入口：按键、HUD（音乐信息 + 歌词）、网络接收、音频播放。 */
public class YueMusicClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("YueMusic/Client");

	public static KeyBinding openKey;

	@Override
	public void onInitializeClient() {
		MusicApi.init();

		// ---- 快捷键（默认分号 ;）----
		int keyCode = MusicConfig.get().openKey > 0 ? MusicConfig.get().openKey : GLFW.GLFW_KEY_SEMICOLON;
		// 1.21.11 的 KeyBinding 需要传 Category（不再收字符串分类）
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(YueMusic.MOD_ID, "music"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key." + YueMusic.MOD_ID + ".open",
				InputUtil.Type.KEYSYM,
				keyCode,
				category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.currentScreen == null) {
					try {
						client.setScreen(new MusicScreen(client.currentScreen));
					} catch (Throwable t) {
						LOGGER.error("[YueMusic] 打开点歌台失败", t);
						if (client.player != null) {
							client.player.sendMessage(net.minecraft.text.Text.literal(
									"[点歌台] 打开失败：" + t), false);
						}
					}
				}
			}
			ClientAudio.get().tick();
			LyricsClient.get().tick();
		});

		// ---- 接收服务端广播 ----
		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.QueueS2C.ID,
				(payload, context) -> context.client().execute(() -> ClientAudio.get().onServerState(payload)));

		// ---- 进服请求一次状态 ----
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (ClientPlayNetworking.canSend(MusicNetworking.RequestSyncC2S.ID)) {
				ClientPlayNetworking.send(new MusicNetworking.RequestSyncC2S());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientAudio.get().onDisconnect();
			LyricsClient.get().clearCache();
		});

		// ---- HUD：音乐信息面板 + 歌词面板 ----
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.of(YueMusic.MOD_ID, "now_playing"),
				MusicHud::render);

		LOGGER.info("[{}] 客户端初始化完成，快捷键 = 分号(;)，歌词显示 = {}",
				YueMusic.MOD_NAME, MusicConfig.get().showLyrics ? "开" : "关");
	}
}
