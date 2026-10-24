package com.yue.music.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.YueMusic;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 模组配置。存放在 {@code config/sayo-honami-music.json}。
 *
 * <p>面板位置用「绝对值 + 归一化」两套记录：
 * <ul>
 *   <li>{@code musicPanelX/Y} 是屏幕坐标（游戏内拖拽后保存），-1 表示「用默认角标位置」</li>
 *   <li>{@code musicPanelPos} 是旧的角标设置（top_right 等），保留做兼容与「一键复位」</li>
 * </ul>
 */
public class MusicConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve(YueMusic.MOD_ID + ".json");

	private static MusicConfig instance;

	// ---- 平台 / 播放 ----
	/** 默认平台：qq / netease */
	public String defaultSource = "qq";
	/** 播放音量 0.0 ~ 1.0 */
	public float volume = 0.7f;
	/** 点不到原唱时自动去另一个平台找同名歌曲 */
	public boolean crossSourceFallback = true;
	/** 是否启用游戏内音频播放（关闭后仍可搜索/点歌/看队列，只是不出声） */
	public boolean audioEnabled = true;

	// ---- 服务端白名单 ----
	/**
	 * 可选：自建 API 服务地址（留空 = 只用内置公开接口）。
	 * <p>服务端据此放行自建服务返回的音频地址；普通玩家一般不用改。
	 */
	public String customApiBase = "";

	// ---- 音乐信息面板（右上角那个） ----
	/** 显示音乐信息面板 */
	public boolean showHud = true;
	/** 面板 X（-1 = 按 hudPosition 自动放） */
	public int musicPanelX = -1;
	/** 面板 Y（-1 = 按 hudPosition 自动放） */
	public int musicPanelY = -1;
	/** 旧字段：角标位置，作为未拖拽时的默认位置 */
	public String hudPosition = "top_right";

	// ---- 歌词面板 ----
	/** 显示歌词 */
	public boolean showLyrics = true;
	/** 歌词面板 X（-1 = 跟随音乐面板） */
	public int lyricsPanelX = -1;
	/** 歌词面板 Y（-1 = 音乐面板下方） */
	public int lyricsPanelY = -1;
	/** 歌词显示行数（1~7） */
	public int lyricsLines = 3;
	/** 歌词字号（0.6 ~ 1.4） */
	public float lyricsScale = 0.9f;

	// ---- 界面自动跟随「游戏 GUI 缩放」 ----
	/**
	 * 是否自动跟随游戏设置的 GUI 缩放（默认开）。
	 *
	 * <p>开启后启动时会识别原版 GUI 缩放并自动调整界面尺寸，
	 * 玩家不需要手动调任何东西。关掉就用固定尺寸。
	 */
	public boolean guiScaleAuto = true;

	/** 上一次识别到的游戏 GUI 缩放值（变了就自动重置一次界面尺寸）。 */
	public int lastGuiScale = 0;
	/** 是否显示翻译行 */
	public boolean showTranslation = true;

	// ---- 服务端 ----
	/** 服务端：是否允许普通玩家点歌 */
	public boolean allowPlayerRequest = true;
	/**
	 * 队列上限的硬上限（{@code /SyMus limit} 与配置文件都受它约束）。
	 *
	 * <p>从 1000 提到 <b>10000</b>：用户要把整张上万首的大歌单一次导进去。
	 * 内存代价很小（实测每首约 290 字节 → 10000 首约 2.9 MB），
	 * 报文侧由 {@code MusicNetworking.MAX_JSON_CHARS} + 自动压缩兜住。
	 */
	public static final int MAX_QUEUE_LIMIT = 10_000;

	/** 服务端：队列上限（默认 20，最大 {@link #MAX_QUEUE_LIMIT} 首） */
	public int maxQueueSize = 20;
	/** 服务端：点歌冷却（秒） */
	public int requestCooldownSeconds = 10;

	// ---- 交互 ----
	/** 打开点歌台的按键（GLFW 键码，默认分号 ; ） */
	public int openKey = 59;

	// ---- 登录态（自动保存） ----
	/** QQ 扫码登录的 Cookie。 */
	public Map<String, String> qqCookies = new HashMap<>();
	public String qqNickname = "";
	/** 微信扫码登录的 Cookie。 */
	public Map<String, String> wechatCookies = new HashMap<>();
	public String wechatNickname = "";
	/** 上次使用的登录方式：qq / wechat。 */
	public String lastLoginMethod = "qq";

	// ---- 房间记忆（自动保存） ----
	/**
	 * 玩家名 → 房间名。
	 *
	 * <p>用来实现「退出重进还在原来的房间」：玩家退出时记住他在哪个房间，
	 * 再进服时自动送回去。用<b>玩家名</b>而不是 UUID，因为改名的玩家
	 * 也能继续认得出来（同名即视为同一人，Minecraft 正版服同名不可能共存）。
	 */
	public Map<String, String> playerRooms = new HashMap<>();

	/**
	 * 房间名 → 该房间的队列上限（覆盖全局 {@link #maxQueueSize}）。
	 *
	 * <p>用 {@code /SyMus limit <房间> <数字>} 设置。
	 * 没设置过的房间用全局 {@code maxQueueSize}。
	 */
	public Map<String, Integer> roomLimits = new HashMap<>();

	public static MusicConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static MusicConfig load() {
		try {
			if (Files.exists(FILE)) {
				String json = Files.readString(FILE);
				JsonObject o = JsonParser.parseString(json).getAsJsonObject();
				MusicConfig cfg = GSON.fromJson(o, MusicConfig.class);
				if (cfg != null) {
					if (cfg.qqCookies == null) cfg.qqCookies = new HashMap<>();
					if (cfg.wechatCookies == null) cfg.wechatCookies = new HashMap<>();
					if (cfg.playerRooms == null) cfg.playerRooms = new HashMap<>();
					if (cfg.roomLimits == null) cfg.roomLimits = new HashMap<>();
					cfg.sanitize();
					return cfg;
				}
			}
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 配置读取失败，使用默认值: {}", e.toString());
		}
		MusicConfig cfg = new MusicConfig();
		cfg.save();
		return cfg;
	}

	/** 把越界的值夹回合理范围，避免坏配置导致界面画到屏幕外。 */
	public void sanitize() {
		if (playerRooms == null) playerRooms = new HashMap<>();
		else playerRooms.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		// 房间上限：清掉非法项，并把数值夹到 1~MAX_QUEUE_LIMIT
		if (roomLimits == null) roomLimits = new HashMap<>();
		else roomLimits.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		roomLimits.replaceAll((k, v) -> clamp(v, 1, MAX_QUEUE_LIMIT));
		volume = clamp(volume, 0f, 1f);
		lyricsScale = clamp(lyricsScale, 0.6f, 1.4f);
		lyricsLines = Math.max(1, Math.min(7, lyricsLines));
		maxQueueSize = Math.max(1, Math.min(MAX_QUEUE_LIMIT, maxQueueSize));
		requestCooldownSeconds = Math.max(0, Math.min(600, requestCooldownSeconds));
		if (defaultSource == null || (!defaultSource.equals("qq") && !defaultSource.equals("netease"))) {
			defaultSource = "qq";
		}
		if (hudPosition == null) hudPosition = "top_right";
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	// ------------------------------------------------------------ 房间队列上限

	/** 某个房间的队列上限（没单独设过就用全局值）。 */
	public int limitOf(String room) {
		if (room != null) {
			Integer v = roomLimits.get(room);
			if (v != null) return clamp(v, 1, MAX_QUEUE_LIMIT);
		}
		return clamp(maxQueueSize, 1, MAX_QUEUE_LIMIT);
	}

	/**
	 * 设置某个房间的队列上限。
	 *
	 * @param limit 1~{@link #MAX_QUEUE_LIMIT}（越界会被夹回）
	 * @return 实际生效的值
	 */
	public synchronized int setLimit(String room, int limit) {
		int v = clamp(limit, 1, MAX_QUEUE_LIMIT);
		if (room != null) {
			roomLimits.put(room, v);
			save();
		}
		return v;
	}

	/** 清除某个房间的单独上限（回到全局值）。 */
	public synchronized void clearLimit(String room) {
		if (room != null && roomLimits.remove(room) != null) save();
	}

	public synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 配置保存失败: {}", e.toString());
		}
	}

	/** 把两个面板重置回默认角标位置。 */
	public void resetPanelPositions() {
		musicPanelX = -1;
		musicPanelY = -1;
		lyricsPanelX = -1;
		lyricsPanelY = -1;
		save();
	}
}
