package com.yue.music.client.ui;

import com.yue.music.YueMusic;
import com.yue.music.api.MusicApi;
import com.yue.music.api.ScanLogin;
import com.yue.music.client.audio.ClientAudio;
import com.yue.music.client.audio.ClientAudioAccess;
import com.yue.music.config.MusicConfig;
import com.yue.music.model.MusicSource;
import com.yue.music.model.MusicTrack;
import com.yue.music.net.MusicNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 点歌台主界面（0.5 屏大小，四个页签）。
 *
 * <p>页签：搜索 / 队列 / 播放 / 设置
 * <p>UI 全部用原版控件自绘，不依赖任何额外界面库，避免和 ModernUI / Sodium 之类的模组冲突。
 */
public class MusicScreen extends Screen {

	private static final int BG = 0xF0121218;
	private static final int BG_HEAD = 0xFF1B1B24;
	private static final int LINE = 0xFF2C2C38;
	private static final int ACCENT = 0xFF7C6CF0;
	private static final int TEXT = 0xFFEDEDF2;
	private static final int SUB = 0xFF9A9AA8;
	private static final int OK = 0xFF4FD1C5;
	private static final int WARN = 0xFFFF8A65;
	private static final int ROW_HOVER = 0x22FFFFFF;
	private static final int ROW_CUR = 0x337C6CF0;
	private static final int ROW_VIP = 0x22FF8A65;

	private static final String[] TABS = {"搜索", "队列", "播放", "设置"};

	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	private int tab;
	private int px;
	private int py;
	private int pw;
	private int ph;

	private TextFieldWidget searchField;
	private ButtonWidget searchButton;
	private ButtonWidget sourceButton;
	private ButtonWidget loginButton;
	private ButtonWidget loginMethodButton;
	/** 手动粘贴 Cookie 登录（扫码失败时的备用方案）。 */
	private TextFieldWidget cookieField;
	private ButtonWidget cookieButton;
	/** 播放模式按钮（顺序/单曲/随机）。 */
	private ButtonWidget modeButton;
	private ButtonWidget volumeDown;
	private ButtonWidget volumeUp;
	private ButtonWidget[] tabButtons = new ButtonWidget[TABS.length];
	/** 房间切换按钮（标题行右侧）。 */
	private ButtonWidget roomButton;
	/** 队列上限按钮（设置页，点击循环切换）。 */
	private ButtonWidget queueSizeButton;
	/** 歌单导入：链接输入框 + 导入按钮（队列页）。 */
	private TextFieldWidget importField;
	private ButtonWidget importButton;
	/** 歌单导入的状态提示（画在队列页）。 */
	private String importStatus = "";
	/** 队列页的搜索词（空 = 显示完整队列）。 */
	private String queueQuery = "";
	/** 房间按钮轮换用的序号（room1~room10）。 */
	private int roomSeq = 1;

	/** 队列上限可选值（点击循环）。 */
	private static final int[] QUEUE_SIZES = {10, 20, 50, 100, 200, 500, 1000, 10000};

	/** 队列上限按钮文字。 */
	private Text queueSizeText() {
		return Text.literal("队列上限 " + ClientAudio.get().effectiveQueueLimit());
	}

	/** 取下一个可选的队列上限（当前值不在列表里就取第一个更大的）。 */
	private static int nextQueueSize(int current) {
		for (int v : QUEUE_SIZES) {
			if (v > current) return v;
		}
		return QUEUE_SIZES[0];
	}

	/** 房间按钮文字：只显示房间名（按钮窄，完整说明在 tooltip 里）。 */
	private Text roomText() {
		String r = ClientAudio.get().room();
		if (r == null || r.isBlank()) r = "default";
		if (r.length() > 8) r = r.substring(0, 8);
		return Text.literal("房:" + r);
	}

	/** 房间切换后刷新按钮文字（由 ClientAudio 在收到广播时调用）。 */
	public static void refreshRoomButton() {
		var screen = net.minecraft.client.MinecraftClient.getInstance().currentScreen;
		if (screen instanceof MusicScreen ms && ms.roomButton != null) {
			ms.roomButton.setMessage(ms.roomText());
		}
	}

	/** 是否按住 Shift（1.21.11 没有 Screen.hasShiftDown，直接用 InputUtil 查窗口）。 */
	private static boolean shiftDown() {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (client == null || client.getWindow() == null) return false;
		return net.minecraft.client.util.InputUtil.isKeyPressed(client.getWindow(),
				net.minecraft.client.util.InputUtil.GLFW_KEY_LEFT_SHIFT)
				|| net.minecraft.client.util.InputUtil.isKeyPressed(client.getWindow(),
				net.minecraft.client.util.InputUtil.GLFW_KEY_RIGHT_SHIFT);
	}

	private List<MusicTrack> results = List.of();
	private String resultError = "";
	private boolean searching;
	private int scroll;

	/**
	 * 紧凑模式：逻辑分辨率很小时（例如 1080p 把 GUI 缩放拉到 4，逻辑只有 480x270）
	 * 自动把按钮行高从 22px 压到 17px，否则 9 行设置按钮会顶到播放控制行上。
	 */
	private boolean compact;
	private int rowH = 22;
	/** 按钮高度 = rowH - 4，行与行之间永远留 4px 空隙。 */
	private int btnH = 18;

	/** 设置页的开关按钮文案需要动态刷新 */
	private ButtonWidget toggleFallback;
	private ButtonWidget toggleHud;
	private ButtonWidget toggleAudio;
	private ButtonWidget toggleAllowRequest;
	// 歌词 / 布局
	private ButtonWidget toggleLyrics;
	private ButtonWidget lyricsLinesButton;
	private ButtonWidget lyricsScaleDown;
	private ButtonWidget lyricsScaleUp;
	private ButtonWidget toggleTranslation;
	private ButtonWidget editLayoutButton;
	private ButtonWidget snapButton;
	private ButtonWidget resetLayoutButton;
	private ButtonWidget reloadLyricsButton;
	private ButtonWidget hudPosButton;
	private ButtonWidget defaultSourceButton;
	private ButtonWidget saveConfigButton;

	// QQ 扫码登录
	private boolean loginOpen;
	private byte[] qrImage;
	private String loginState = "";
	private long lastPollAt;
	private long qrShownAt;

	public MusicScreen(Screen parent) {
		super(Text.literal("点歌台"));
	}

	/**
	 * 自动跟随「游戏设置的 GUI 缩放」。
	 *
	 * <p><b>为什么需要它</b>：界面里的尺寸用的都是**逻辑像素**，
	 * 而原版会根据「选项 → 显示设置 → GUI 缩放」把逻辑像素放大。
	 * 同一个面板在不同缩放下的观感差别很大：
	 * <ul>
	 *   <li>缩放 4 时逻辑分辨率只有 480×270，面板相对屏幕就显得**特别大**（几乎铺满）</li>
	 *   <li>缩放 2 时逻辑分辨率 960×540，面板又显得**特别小**</li>
	 * </ul>
	 * 所以这里读原版的缩放值，按 {@link #BASE_GUI_SCALE}（=3，最常见的设置）为基准
	 * 自动把面板尺寸缩放一下 —— 各个缩放档位下的观感就一致了。
	 *
	 * <p>数值会存进配置（{@code guiScaleAuto} / {@code lastGuiScale}），
	 * 启动时自动识别、发现游戏缩放变了就自动重置，不需要玩家手动调。
	 *
	 * @return 缩放系数（1.0 = 保持原尺寸）
	 */
	private static int detectGuiScale() {
		try {
			var mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc == null || mc.options == null) return 0;
			return mc.options.getGuiScale().getValue();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 自动缩放的基准：GUI 缩放 3（原版默认值，也是绝大多数玩家的设置）。 */
	private static final int BASE_GUI_SCALE = 3;

	/** 按原版缩放算出的尺寸系数（已夹到安全范围，防止面板小到没法用）。 */
	private static float autoGuiFactor() {
		int v = detectGuiScale();
		if (v <= 0) return 1f;                       // 0 = 自动，交给原版，不额外缩放
		float f = (float) BASE_GUI_SCALE / v;
		return Math.max(0.7f, Math.min(1.5f, f));    // 夹住，避免极端缩放下面板失真
	}

	@Override
	protected void init() {
		MusicConfig cfg = MusicConfig.get();

		// —— 自动识别游戏 GUI 缩放 ——
		// 发现游戏缩放变了就自动重置一次（玩家不用手动改任何东西）
		int nowScale = detectGuiScale();
		if (cfg.guiScaleAuto && nowScale > 0 && nowScale != cfg.lastGuiScale) {
			int old = cfg.lastGuiScale;
			cfg.lastGuiScale = nowScale;
			cfg.save();
			YueMusic.LOGGER.info("[{}] 检测到游戏 GUI 缩放变化：{} → {}，界面尺寸已自动重置",
					YueMusic.MOD_NAME, old <= 0 ? "未知" : old, nowScale);
		}
		float guiF = cfg.guiScaleAuto ? autoGuiFactor() : 1f;

		// 面板高度上限：设置页有 10 行控件，行高要按**原版 GUI 的 20px** 才够。
		// 验算：sy(46) + 10×20 + 4 + 20(控制行) + 8(底边距) = 278，
		// 而 424 还留了一行「登录提示」的 12px —— 正好放得下。
		int maxPh = Math.round(424 * guiF);
		int rawPh = Math.min(this.height - 44, maxPh);
		// 先判断空间：面板可用高度扣掉标题/页签/说明行/控制行之后还剩多少给 10 行按钮。
		// 设置页顶部还有一行「登录提示」，也要算进去（否则它会压到第一排按钮上）。
		boolean roomForHint = rawPh - 24 - 4 - 12 - 8 - 30 - 26 >= 10 * 20;
		// 原版按钮高度是 20px、行距 20 —— 这里对齐原版手感
		rowH = roomForHint ? 20 : 16;
		// 按钮高度：不紧凑时直接用原版 20px；紧凑模式才留 2px 间隙
		btnH = roomForHint ? 20 : 14;
		compact = !roomForHint;
		pw = Math.min(this.width - 40, Math.round(470 * guiF));
		ph = rawPh;
		pw = Math.max(pw, 200);
		ph = Math.max(ph, 140);
		px = (this.width - pw) / 2;
		py = Math.max(20, (this.height - ph) / 2);

		// 页签行：4 个页签 + 1 个房间按钮，**五等分**（按钮不够宽就会互相压字）
		int tabY = py + 6;
		int tabW = (pw - 16) / 5;
		tabButtons = new ButtonWidget[TABS.length];
		for (int i = 0; i < TABS.length; i++) {
			final int index = i;
			tabButtons[i] = ButtonWidget.builder(Text.literal(TABS[i]), b -> {
						tab = index;
						scroll = 0;
						refreshWidgets();
					})
					.dimensions(px + 8 + i * tabW, tabY, tabW - 2, 20)
					.build();
			addDrawableChild(tabButtons[i]);
		}

		// 设置页顶部有一行登录提示；有空间时把按钮区整体下移 12px 给它让位
		int bodyTop = py + 30 + (roomForHint ? 12 : 0);
		int bodyBottom = py + ph - 8;
		// 第一个按钮区行宽：三等分（放「游戏内播放 / 登录方式 / 队列上限」三个按钮）
		int sw3 = (pw - 32) / 3;

		// ---- 房间按钮（页签行第 5 格）----
		// 五等分：前 4 格是页签，第 5 格固定给房间。
		// 之前房间按钮是「贴在行末的固定宽度」，和页签抢空间，窄面板就会压住「设置」。
		// 现在它和后 4 个页签宽度完全一致，**结构上不可能重叠**。
		{
			int roomX = px + 8 + 4 * tabW;
			roomButton = ButtonWidget.builder(roomText(), b -> {
						if (shiftDown()) {
							// Shift 点击 → 回默认房间
							ClientAudio.get().leaveRoom();
						} else {
							// 普通点击 → 轮流加入 room1~room10（方便快速分房）
							String want = "room" + roomSeq;
							roomSeq = roomSeq % 10 + 1;
							ClientAudio.get().joinRoom(want);
						}
					})
					.dimensions(roomX, tabY, tabW - 2, 20)
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
							"当前房间：点一下轮流进 room1~room10\n按住 Shift 点：回到默认房间\n"
									+ "精确指定：/symus room join <房间名>")))
					.build();
			addDrawableChild(roomButton);
		}

		// ---- 队列页：歌单导入 / 队列搜索（共用一个输入框）----
		// 放在列表上方那条空白带里（详见 importRowY 的注释：底部和页签行都没位置）。
		//
		// 为什么共用一个框：这样**不用再加一行**，避免又和别的东西挤。
		// 语义也好懂 —— 填链接就是导入歌单，填文字就是搜队列里的歌。
		{
			int iy = importRowY();
			importField = new TextFieldWidget(this.textRenderer, px + 8, iy,
					pw - 16 - 72, 20, Text.literal("歌单链接或搜索词"));
			importField.setMaxLength(512);
			importField.setPlaceholder(Text.literal("歌单链接=导入 ｜ 文字=搜队列，回车执行"));
			addDrawableChild(importField);

			importButton = ButtonWidget.builder(Text.literal("导入/搜索"), b -> doImportOrSearch())
					.dimensions(px + pw - 8 - 64, iy, 64, 20)
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
							"填歌单链接 → 导入整个歌单\n"
									+ "  形如 y.qq.com/n/ryqq/playlist/8732463559（或纯数字 ID）\n"
									+ "填文字 → 在「本房间队列」里搜歌\n"
									+ "  搜到后点歌名可插播；框清空即恢复完整队列")))
					.build();
			addDrawableChild(importButton);
		}

		// ---- 搜索页控件 ----
		searchField = new TextFieldWidget(this.textRenderer, px + 8, bodyTop, pw - 130, 20,
				Text.literal("搜索歌曲"));
		searchField.setMaxLength(60);
		searchField.setPlaceholder(Text.literal("输入歌名 / 歌手，回车搜索"));
		addDrawableChild(searchField);

		sourceButton = ButtonWidget.builder(sourceText(), b -> {
					MusicConfig c = MusicConfig.get();
					MusicSource cur = MusicSource.byId(c.defaultSource);
					int idx = MusicSource.all().indexOf(cur);
					MusicSource next = MusicSource.all().get((idx + 1) % MusicSource.all().size());
					c.defaultSource = next.id();
					c.save();
					b.setMessage(sourceText());
				})
				.dimensions(px + pw - 118, bodyTop, 54, 20)
				.build();
		addDrawableChild(sourceButton);

		searchButton = ButtonWidget.builder(Text.literal("搜索"), b -> doSearch())
				.dimensions(px + pw - 60, bodyTop, 52, 20)
				.build();
		addDrawableChild(searchButton);

		// ---- 播放页控件 ----
		int ctlY = bodyBottom - 22;
		int cx = px + 8;
		int bw = 34;
		addDrawableChild(ButtonWidget.builder(Text.literal("⏮"), b ->
						ClientAudio.get().sendControl(MusicNetworking.Action.PREV, 0))
				.dimensions(cx, ctlY, bw, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("⏯"), b -> {
					ClientAudio a = ClientAudio.get();
					a.sendControl(a.paused() ? MusicNetworking.Action.RESUME : MusicNetworking.Action.PAUSE, 0);
				})
				.dimensions(cx + bw + 2, ctlY, bw, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("⏭"), b ->
						ClientAudio.get().sendControl(MusicNetworking.Action.NEXT, 0))
				.dimensions(cx + (bw + 2) * 2, ctlY, bw, 20).build());
		// 播放模式：顺序 → 单曲 → 随机（点击轮换）
		// 按钮文字由服务端广播回来的 playMode 决定，所以每帧从 ClientAudio 取
		modeButton = ButtonWidget.builder(Text.literal(ClientAudio.get().playModeCn()), b ->
						ClientAudio.get().sendControl(MusicNetworking.Action.MODE, 0))
				.dimensions(cx + (bw + 2) * 3, ctlY, 56, 20)
				.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
						"播放模式（点击轮换）\n顺序播放 → 单曲循环 → 随机播放")))
				.build();
		addDrawableChild(modeButton);
		addDrawableChild(ButtonWidget.builder(Text.literal("清空"), b ->
						ClientAudio.get().sendControl(MusicNetworking.Action.CLEAR, 0))
				.dimensions(cx + (bw + 2) * 3 + 58, ctlY, 44, 20).build());

		// 音量：小步长（3%）+ 长按连续调节，手感更细腻
		volumeDown = ButtonWidget.builder(Text.literal("音量 −"), b ->
						ClientAudio.get().adjustVolume(-3))
				.dimensions(px + pw - 116, ctlY, 54, 20).build();
		addDrawableChild(volumeDown);
		volumeUp = ButtonWidget.builder(Text.literal("音量 +"), b ->
						ClientAudio.get().adjustVolume(3))
				.dimensions(px + pw - 60, ctlY, 54, 20).build();
		addDrawableChild(volumeUp);

		// ---- 设置页控件 ----
		int sy = bodyTop + 4;
		int sw = (pw - 24) / 2;
		defaultSourceButton = ButtonWidget.builder(defSourceText(), b -> {
					MusicConfig c = MusicConfig.get();
					MusicSource cur = MusicSource.byId(c.defaultSource);
					int idx = MusicSource.all().indexOf(cur);
					c.defaultSource = MusicSource.all().get((idx + 1) % MusicSource.all().size()).id();
					c.save();
					b.setMessage(defSourceText());
					if (sourceButton != null) sourceButton.setMessage(sourceText());
				})
				.dimensions(px + 8, sy, sw, btnH).build();
		addDrawableChild(defaultSourceButton);

		toggleFallback = ButtonWidget.builder(fallbackText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.crossSourceFallback = !c.crossSourceFallback;
					c.save();
					b.setMessage(fallbackText());
				})
				.dimensions(px + 16 + sw, sy, sw, btnH).build();
		addDrawableChild(toggleFallback);

		toggleHud = ButtonWidget.builder(hudText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.showHud = !c.showHud;
					c.save();
					b.setMessage(hudText());
				})
				.dimensions(px + 8, sy + rowH, sw, btnH).build();
		addDrawableChild(toggleHud);

		hudPosButton = ButtonWidget.builder(hudPosText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.hudPosition = switch (c.hudPosition) {
						case "top_left" -> "top_right";
						case "top_right" -> "bottom_right";
						case "bottom_right" -> "bottom_left";
						default -> "top_left";
					};
					c.save();
					b.setMessage(hudPosText());
				})
				.dimensions(px + 16 + sw, sy + rowH, sw, btnH).build();
		addDrawableChild(hudPosButton);

		toggleAudio = ButtonWidget.builder(audioText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.audioEnabled = !c.audioEnabled;
					c.save();
					// 关掉时必须立刻停播：只改配置的话，正在播的那首会继续放（已修的 bug）
					if (!c.audioEnabled) {
						ClientAudio.get().stopPlayback();
					}
					b.setMessage(audioText());
				})
				.dimensions(px + 8, sy + rowH * 2, sw3, btnH).build();
		addDrawableChild(toggleAudio);

		loginMethodButton = ButtonWidget.builder(loginMethodText(), b -> {
					// 在 QQ / 微信 / 网易云 之间循环切换登录方式
					ScanLogin.Method[] all = {
							ScanLogin.Method.QQ, ScanLogin.Method.WECHAT, ScanLogin.Method.NETEASE};
					ScanLogin.Method cur = MusicApi.activeLoginMethod();
					int i = 0;
					for (int k = 0; k < all.length; k++) {
						if (all[k] == cur) {
							i = k;
							break;
						}
					}
					MusicApi.setActiveLoginMethod(all[(i + 1) % all.length]);
					loginOpen = false;
					loginState = "";
					qrTextureId = null;
					b.setMessage(loginMethodText());
					if (loginButton != null) loginButton.setMessage(loginButtonText());
				})
				.dimensions(px + 16 + sw3, sy + rowH * 2, sw3, btnH).build();
		addDrawableChild(loginMethodButton);

		// 同行的第三格：队列上限（点击循环切换 10 → 20 → … → 1000 → 10000）
		// 这一行改成三等分，三个按钮刚好铺满且互不重叠。
		//
		// ⚠ 重点：队列上限是**服务端**判定的（导入歌单、点歌都走服务端），
		//    而模组配置两端各读各的文件。所以这里不是改本地配置，
		//    而是把新值发给服务端去改它的配置（单人存档时才退回改本地）。
		queueSizeButton = ButtonWidget.builder(queueSizeText(), b -> {
					ClientAudio ca = ClientAudio.get();
					int next = nextQueueSize(ca.effectiveQueueLimit());
					boolean sent = ca.requestQueueLimit(next);
					// 发出去了就先显示"新值…"，等服务端广播回来会变成确认值
					b.setMessage(sent ? Text.literal("队列上限 " + next + "…") : queueSizeText());
				})
				.dimensions(px + 16 + sw3 * 2, sy + rowH * 2, sw3, btnH)
				.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
						"服务端队列上限（点击切换）\n可设 10 / 20 / 50 / 100 / 200 / 500 / 1000 / 10000 首\n"
								+ "连服务器时需要 OP 权限；没有权限就让服主用 /SyMus limit <数字>")))
				.build();
		addDrawableChild(queueSizeButton);

		// 登录方式 + 登录按钮各占一行（原来只有 QQ，现在 QQ/微信 可切换）
		int loginRow = sy + rowH * 3;
		loginButton = ButtonWidget.builder(loginButtonText(), b -> openLogin())
				.dimensions(px + 8, loginRow, pw - 16, btnH).build();
		addDrawableChild(loginButton);

		// ---- 歌词设置行 ----
		int row3 = sy + rowH * 4;
		toggleLyrics = ButtonWidget.builder(lyricsText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.showLyrics = !c.showLyrics;
					c.save();
					b.setMessage(lyricsText());
				})
				.dimensions(px + 8, row3, sw, btnH).build();
		addDrawableChild(toggleLyrics);

		lyricsLinesButton = ButtonWidget.builder(lyricsLinesText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.lyricsLines = c.lyricsLines >= 7 ? 1 : c.lyricsLines + 2;
					c.save();
					b.setMessage(lyricsLinesText());
				})
				.dimensions(px + 16 + sw, row3, sw, btnH).build();
		addDrawableChild(lyricsLinesButton);

		int row4 = sy + rowH * 5;
		int third = (sw - 4) / 2;
		// 字号：无级调整（每次 0.02，长按连续变；见 tick() 里的长按逻辑）
		lyricsScaleDown = ButtonWidget.builder(scaleText(), b -> stepScale(-1))
				.dimensions(px + 8, row4, third, btnH).build();
		addDrawableChild(lyricsScaleDown);

		lyricsScaleUp = ButtonWidget.builder(scaleText(), b -> stepScale(1))
				.dimensions(px + 10 + third, row4, third, btnH).build();
		addDrawableChild(lyricsScaleUp);

		toggleTranslation = ButtonWidget.builder(transText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.showTranslation = !c.showTranslation;
					c.save();
					b.setMessage(transText());
				})
				.dimensions(px + 12 + third * 2, row4, sw - third + 4, btnH).build();
		addDrawableChild(toggleTranslation);

		// ---- 布局调整 ----
		int row5 = sy + rowH * 6;
		editLayoutButton = ButtonWidget.builder(editLayoutText(), b -> {
					if (PanelEditor.isEditing()) {
						PanelEditor.finish();
					} else {
						// 进入编辑前先把「幽灵位置」同步成当前真实位置，
						// 否则 -1 会被当成已设置而把面板瞬移到左上角
						PanelEditor.syncFromConfig(this.width, this.height);
						PanelEditor.start();
					}
					b.setMessage(editLayoutText());
				})
				.dimensions(px + 8, row5, sw, btnH).build();
		addDrawableChild(editLayoutButton);

		snapButton = ButtonWidget.builder(Text.literal("歌词吸附到面板下方"), b -> {
					PanelEditor.snapLyricsUnderMusic(this.width);
					b.setMessage(Text.literal("已吸附 ✓"));
				})
				.dimensions(px + 16 + sw, row5, sw, btnH).build();
		addDrawableChild(snapButton);

		int row6 = sy + rowH * 7;
		resetLayoutButton = ButtonWidget.builder(Text.literal("恢复默认位置"), b -> {
					PanelEditor.reset();
					b.setMessage(Text.literal("已恢复默认 ✓"));
				})
				.dimensions(px + 8, row6, sw, btnH).build();
		addDrawableChild(resetLayoutButton);

		reloadLyricsButton = ButtonWidget.builder(Text.literal("重新加载歌词"), b -> {
					com.yue.music.client.lyrics.LyricsClient.get().clearCache();
					b.setMessage(Text.literal("已重新加载 ✓"));
				})
				.dimensions(px + 16 + sw, row6, sw, btnH).build();
		addDrawableChild(reloadLyricsButton);

		int row7 = sy + rowH * 8;
		toggleAllowRequest = ButtonWidget.builder(allowText(), b -> {
					MusicConfig c = MusicConfig.get();
					c.allowPlayerRequest = !c.allowPlayerRequest;
					c.save();
					b.setMessage(allowText());
				})
				.dimensions(px + 8, row7, sw, btnH).build();
		addDrawableChild(toggleAllowRequest);

		saveConfigButton = ButtonWidget.builder(Text.literal("保存配置到文件"), b -> {
					MusicConfig.get().save();
					b.setMessage(Text.literal("已保存 ✓"));
				})
				.dimensions(px + 16 + sw, row7, sw, btnH).build();
		addDrawableChild(saveConfigButton);

		// ---- 第 10 行：手动粘贴 Cookie 登录（扫码被 QQ 拒绝时的备用方案）----
		int row8 = sy + rowH * 9;
		cookieField = new TextFieldWidget(this.textRenderer, px + 8, row8,
				pw - 16 - 88, btnH, Text.literal("Cookie"));
		cookieField.setMaxLength(4096);
		cookieField.setPlaceholder(Text.literal("扫码登不上 / 网易云 → 把浏览器里的 Cookie 粘到这里"));
		addDrawableChild(cookieField);

		cookieButton = ButtonWidget.builder(Text.literal("导入"), b -> {
					ScanLogin login = MusicApi.activeLogin();
					if (login == null) {
						loginState = "未初始化";
						return;
					}
					final String raw = cookieField.getText();
					// ⚠ 导入要联网验证（QQ 取昵称 / 网易云查账号），必须在后台线程做，
					// 否则会把渲染线程卡住最长十几秒（画面定格）。
					loginState = "正在验证 Cookie…";
					b.setMessage(Text.literal("验证中…"));
					workers.submit(() -> {
						String err = login.importCookies(raw);
						net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
							if (err == null) {
								MusicApi.persistLogin(login);
								loginState = "登录成功：" + login.method().label()
										+ (login.nickname().isBlank() ? "" : " · " + login.nickname());
								b.setMessage(Text.literal("已导入 ✓"));
								if (loginButton != null) loginButton.setMessage(loginButtonText());
							} else {
								loginState = "导入失败：" + err;
								b.setMessage(Text.literal("导入失败"));
							}
						});
					});
				})
				.dimensions(px + pw - 88, row8, 80, btnH).build();
		addDrawableChild(cookieButton);

		refreshWidgets();
	}

	private void refreshWidgets() {
		boolean isSearch = tab == 0;
		boolean isQueue = tab == 1;
		boolean isPlayer = tab == 2;
		boolean isSettings = tab == 3;
		if (searchField != null) {
			searchField.setVisible(isSearch);
			searchField.setEditable(isSearch);
		}
		if (searchButton != null) searchButton.visible = isSearch;
		if (sourceButton != null) sourceButton.visible = isSearch;
		// 播放模式按钮文字跟随服务端广播（顺序/单曲/随机）
		if (modeButton != null) {
			modeButton.visible = isPlayer;
			modeButton.setMessage(Text.literal(ClientAudio.get().playModeCn()));
		}
		if (volumeDown != null) volumeDown.visible = isPlayer;
		if (volumeUp != null) volumeUp.visible = isPlayer;
		if (defaultSourceButton != null) defaultSourceButton.visible = isSettings;
		if (toggleFallback != null) toggleFallback.visible = isSettings;
		if (toggleHud != null) toggleHud.visible = isSettings;
		if (toggleAudio != null) toggleAudio.visible = isSettings;
		if (toggleLyrics != null) toggleLyrics.visible = isSettings;
		if (lyricsLinesButton != null) lyricsLinesButton.visible = isSettings;
		if (lyricsScaleDown != null) lyricsScaleDown.visible = isSettings;
		if (lyricsScaleUp != null) lyricsScaleUp.visible = isSettings;
		if (toggleTranslation != null) toggleTranslation.visible = isSettings;
		if (editLayoutButton != null) editLayoutButton.visible = isSettings;
		if (snapButton != null) snapButton.visible = isSettings;
		if (resetLayoutButton != null) resetLayoutButton.visible = isSettings;
		if (reloadLyricsButton != null) reloadLyricsButton.visible = isSettings;
		if (hudPosButton != null) hudPosButton.visible = isSettings;
		// 这里必须把**每一个**设置页控件都列上：漏掉的话它会在所有页签都显示
		// （之前漏了 loginMethodButton，结果「登录方式」按钮在播放页也飘着）
		if (loginMethodButton != null) loginMethodButton.visible = isSettings;
		if (cookieField != null) {
			cookieField.visible = isSettings;
			cookieField.setEditable(isSettings);
		}
		if (cookieButton != null) cookieButton.visible = isSettings;
		if (loginButton != null) {
			loginButton.visible = isSettings;
			loginButton.setMessage(loginButtonText());
		}
		if (toggleAllowRequest != null) toggleAllowRequest.visible = isSettings;
		if (saveConfigButton != null) saveConfigButton.visible = isSettings;
		// 队列上限按钮：只在设置页显示（漏了这行 → 它在所有页签都飘着，就是那个"队列上限 500"）
		if (queueSizeButton != null) queueSizeButton.visible = isSettings;
		// 歌单导入控件：只在队列页显示（漏写这行就会在所有页签飘着 —— 这种错小夜犯过两次了）
		if (importField != null) {
			importField.visible = isQueue;
			importField.setEditable(isQueue);
		}
		if (importButton != null) importButton.visible = isQueue;
	}

	// ---------------------------------------------------------------- 文本

	private Text sourceText() {
		MusicSource s = MusicSource.byId(MusicConfig.get().defaultSource);
		return Text.literal(s == null ? "QQ音乐" : s.displayName());
	}

	private Text defSourceText() {
		MusicSource s = MusicSource.byId(MusicConfig.get().defaultSource);
		return Text.literal("默认平台：" + (s == null ? "QQ音乐" : s.displayName()));
	}

	private Text fallbackText() {
		return Text.literal("跨平台兜底：" + (MusicConfig.get().crossSourceFallback ? "开" : "关"));
	}

	private Text hudText() {
		return Text.literal("HUD 显示：" + (MusicConfig.get().showHud ? "开" : "关"));
	}

	private Text audioText() {
		return Text.literal("游戏内播放：" + (MusicConfig.get().audioEnabled ? "开" : "关"));
	}

	private Text lyricsText() {
		return Text.literal("歌词显示：" + (MusicConfig.get().showLyrics ? "开" : "关"));
	}

	private Text lyricsLinesText() {
		return Text.literal("歌词行数：" + MusicConfig.get().lyricsLines);
	}

	private Text transText() {
		return Text.literal("翻译：" + (MusicConfig.get().showTranslation ? "开" : "关"));
	}

	private Text editLayoutText() {
		return Text.literal(PanelEditor.isEditing() ? "完成调整 ✓（点这里退出）" : "调整布局（拖拽）");
	}

	private Text hudPosText() {
		String p = switch (MusicConfig.get().hudPosition) {
			case "top_left" -> "左上";
			case "bottom_left" -> "左下";
			case "bottom_right" -> "右下";
			default -> "右上";
		};
		return Text.literal("HUD 位置：" + p);
	}

	private Text allowText() {
		return Text.literal("允许玩家点歌：" + (MusicConfig.get().allowPlayerRequest ? "开" : "关"));
	}

	/** 登录方式切换按钮：QQ音乐 / 微信 / 网易云。 */
	private Text loginMethodText() {
		return Text.literal("登录方式：" + MusicApi.activeLoginMethod().label());
	}

	/** 登录按钮文案：未登录 = 「XXX 扫码登录」，已登录 = 「已登录：昵称（点此重新登录）」。 */
	private Text loginButtonText() {
		ScanLogin login = MusicApi.activeLogin();
		if (login != null && login.loggedIn()) {
			String nick = login.nickname() == null || login.nickname().isBlank()
					? login.method().label() : login.nickname();
			return Text.literal("已登录（" + nick + "）· 点此重新登录");
		}
		// 网易云没有二维码接口，按钮文案直说「用 Cookie」
		if (MusicApi.activeLoginMethod() == ScanLogin.Method.NETEASE) {
			return Text.literal("网易云：粘 Cookie 登录（点此看说明）");
		}
		return Text.literal(MusicApi.activeLoginMethod().buttonLabel() + "（扫码）");
	}

	// ---------------------------------------------------------------- 字号（无级 + 长按连调）

	/** 每次点击的字号步长（0.02，非常细腻）。 */
	private static final float SCALE_STEP = 0.02f;

	/** 正在长按的字号按钮方向：-1 / +1 / 0。 */
	private int scaleHoldDir;
	/** 上次因长按调整的时间。 */
	private long lastScaleStep;

	/** 点一下：走一小步。 */
	private void stepScale(int dir) {
		MusicConfig c = MusicConfig.get();
		float next = Math.round((c.lyricsScale + dir * SCALE_STEP) * 100f) / 100f;
		c.lyricsScale = Math.max(0.6f, Math.min(1.4f, next));
		c.save();
		scaleHoldDir = dir;
		lastScaleStep = System.currentTimeMillis();
		refreshScaleLabels();
	}

	/** 鼠标按住时持续调整，并且越按越快（实现「无级」手感）。 */
	private void tickScale() {
		if (scaleHoldDir == 0 || lyricsScaleUp == null) return;
		if (!lyricsScaleUp.isHovered() && !lyricsScaleDown.isHovered()) return;
		long held = System.currentTimeMillis() - lastScaleStep;
		if (held < 70) return;
		MusicConfig c = MusicConfig.get();
		// 按得越久步子越大：0.02 → 最多 0.10
		float step = Math.min(0.10f, SCALE_STEP + held / 1000f * 0.04f);
		float next = Math.round((c.lyricsScale + scaleHoldDir * step) * 100f) / 100f;
		float clamped = Math.max(0.6f, Math.min(1.4f, next));
		if (clamped == c.lyricsScale) return;
		c.lyricsScale = clamped;
		c.save();
		lastScaleStep = System.currentTimeMillis();
		refreshScaleLabels();
	}

	private void refreshScaleLabels() {
		Text t = scaleText();
		if (lyricsScaleDown != null) lyricsScaleDown.setMessage(t);
		if (lyricsScaleUp != null) lyricsScaleUp.setMessage(t);
	}

	private Text scaleText() {
		return Text.literal(String.format("字号 %.2f", MusicConfig.get().lyricsScale));
	}

	// ---------------------------------------------------------------- 搜索

	private void doSearch() {
		String kw = searchField.getText().trim();
		if (kw.isEmpty() || searching) return;
		searching = true;
		resultError = "";
		scroll = 0;
		MusicSource src = MusicSource.byId(MusicConfig.get().defaultSource);
		if (src == null) src = MusicSource.QQ;
		final MusicSource source = src;
		ClientAudio.get().search(source, kw, result -> {
			searching = false;
			results = result.tracks();
			resultError = result.hasError() ? result.error() : "";
		});
	}

	// ---------------------------------------------------------------- 登录

	private void openLogin() {
		ScanLogin login = MusicApi.activeLogin();
		if (login == null) return;
		if (login.loggedIn()) {
			loginState = "已登录：" + login.method().label() + " · " + login.nickname();
			return;
		}
		// 网易云没有二维码接口 → 直接引导到下面的 Cookie 输入框
		if (MusicApi.activeLoginMethod() == ScanLogin.Method.NETEASE) {
			loginOpen = false;
			loginState = "网易云登录：把 MUSIC_U 粘到下面的输入框 → 点「导入」"
					+ "（怎么拿：浏览器登录 music.163.com → F12 → Application → Cookies → MUSIC_U）";
			return;
		}
		loginOpen = true;
		loginState = "正在获取二维码…";
		workers.submit(() -> {
			try {
				byte[] png = login.fetchQrCode();
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					if (png == null || png.length < 200) {
						// 微信的二维码要两步（先拿 HTML 再拿图片），失败时给出明确原因
						loginOpen = false;
						loginState = login.message() == null || login.message().isBlank()
								? "获取二维码失败" : login.message();
						return;
					}
					qrImage = png;
					qrShownAt = System.currentTimeMillis();
					loginState = "请用手机" + login.method().label() + "扫码";
					uploadQrTexture(png);
				});
			} catch (Exception e) {
				net.minecraft.client.MinecraftClient.getInstance()
						.execute(() -> loginState = "获取二维码失败：" + e.getMessage());
			}
		});
	}

	private void uploadQrTexture(byte[] png) {
		if (png == null) return;
		try {
			net.minecraft.client.texture.NativeImage img = net.minecraft.client.texture.NativeImage.read(png);
			net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
			net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(YueMusic.MOD_ID, "qq_qr");
			// 重新登录时先释放上一张，避免纹理泄漏
			if (qrTextureId != null) {
				mc.getTextureManager().destroyTexture(qrTextureId);
			}
			net.minecraft.client.texture.NativeImageBackedTexture tex =
					new net.minecraft.client.texture.NativeImageBackedTexture(() -> "yue-music-qq-qr", img);
			mc.getTextureManager().registerTexture(id, tex);
			qrTextureId = id;
		} catch (Exception e) {
			loginState = "二维码图片解析失败：" + e.getMessage();
		}
	}

	private net.minecraft.util.Identifier qrTextureId;

	private void pollLogin() {
		ScanLogin login = MusicApi.activeLogin();
		if (login == null || !loginOpen) return;
		long now = System.currentTimeMillis();
		if (now - lastPollAt < 1500) return;
		lastPollAt = now;
		workers.submit(() -> {
			try {
				login.pollOnce();
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					loginState = login.message();
					if (login.loggedIn()) {
						loginOpen = false;
						// 按登录方式分别持久化 Cookie（QQ / 微信 各存一份）
						MusicApi.persistLogin(login);
						loginState = "登录成功：" + login.method().label() + " · " + login.nickname();
						if (loginButton != null) loginButton.setMessage(loginButtonText());
					}
					if (login.state() == ScanLogin.State.EXPIRED) {
						loginOpen = false;
					}
				});
			} catch (Exception e) {
				net.minecraft.client.MinecraftClient.getInstance()
						.execute(() -> loginState = "轮询失败：" + e.getMessage());
			}
		});
	}

	// ---------------------------------------------------------------- 渲染

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// 注意：1.21.11 的 Screen.renderWithTooltip() 已经自动调用过 renderBackground()（含模糊+暗化），
		// 这里绝对不能再调一次——同一帧模糊两次会抛 "Can only blur once per frame"，
		// 而该异常每帧重复抛出会让游戏看起来像卡死、CPU 飙高（已踩过一次，勿删此注释）。
		// 面板反而要自己补一层半透明底，保证文字在任何背景上都看得清。
		ctx.fill(px - 2, py - 14, px + pw + 2, py + ph + 2, 0x66000000);
		if (tab == 3 && loginOpen) pollLogin();

		ctx.fill(px, py, px + pw, py + ph, BG);
		ctx.fill(px, py, px + pw, py + 28, BG_HEAD);
		ctx.fill(px, py + 28, px + pw, py + 29, LINE);

		// 标题（画在面板上方，py 已经预留了空间）
		ctx.drawTextWithShadow(this.textRenderer, "♪ 点歌台", px + 6, py - 11, TEXT);
		ctx.drawTextWithShadow(this.textRenderer,
				"QQ音乐 / 网易云 · 服务端同步", px + 62, py - 11, SUB);
		// 右侧提示页签快捷键（设置页 = 4）
		// 页签行现在被 5 个按钮占满，这行提示改到**标题右边**，
		// 位置自动避让「副标题」，不够宽就不画（它只是提示）。
		String tabTip = "1 搜索  2 队列  3 播放  4 设置";
		int subW = this.textRenderer.getWidth("QQ音乐 / 网易云 · 服务端同步");
		int tipX = px + 62 + subW + 12;
		if (tipX + this.textRenderer.getWidth(tabTip) <= px + pw - 8) {
			ctx.drawTextWithShadow(this.textRenderer, tabTip, tipX, py - 11, SUB);
		}

		switch (tab) {
			case 0 -> renderSearchTab(ctx, mouseX, mouseY);
			case 1 -> renderQueueTab(ctx, mouseX, mouseY);
			case 2 -> renderPlayerTab(ctx, mouseX, mouseY);
			default -> renderSettingsTab(ctx, mouseX, mouseY);
		}

		super.render(ctx, mouseX, mouseY, delta);

		// 拖拽边框必须画在**最上层**（super.render 之后）。
		// 如果画在 HUD 层，会被这个设置页大面板整个盖住，导致「看不见也拖不到」。
		if (PanelEditor.isEditing()) {
			int screenW = ctx.getScaledWindowWidth();
			int screenH = ctx.getScaledWindowHeight();
			int mx = PanelEditor.resolvedMusicX(screenW);
			int my = PanelEditor.resolvedMusicY(screenH);
			int lx = PanelEditor.resolvedLyricsX(screenW, mx);
			int ly = PanelEditor.resolvedLyricsY(screenH, my);
			PanelEditor.renderOverlay(ctx, mx, my, lx, ly,
					MusicConfig.get().showLyrics, ClientAudio.get().currentTrack() != null);
		}

		// 二维码画在主面板外面，且在最上层（避免被按钮挡住）
		renderQrOutsidePanel(ctx, mouseX, mouseY);
	}

	/**
	 * 队列页的「歌单导入」控件行 Y 坐标。
	 *
	 * <p><b>UI 冲突分析（这次先算清楚再放）</b>：
	 * <ul>
	 *   <li>底部控制行（上一/播放/下一/循环/清空）横跨 x=[8,198]，
	 *       音量按钮横跨 x=[pw-116, pw-6] → <b>底部完全没有空位</b>，不能放</li>
	 *   <li>页签行被 5 个等分按钮占满 → 不能放</li>
	 *   <li>设置页已经有 10 行控件 → 再加会顶到控制行</li>
	 *   <li><b>队列页列表上方有一条空白带</b>（原来只有一行「共 N 首」提示文字），
	 *       正好放得下 18px 高的输入框 + 按钮 → <b>就放这里</b></li>
	 * </ul>
	 * 放这里的好处：语义也最贴切 —— 导入的歌就是直接进队列。
	 */
	private int importRowY() {
		return py + 42;
	}

	/** 队列列表的顶边。队列页要给导入控件让出 30px。 */
	private int listTop() {
		int t = tab == 1 ? py + 74 : py + 54;
		// 极窄窗口下保证列表至少还有一行的高度（否则会出现负高度把渲染搞坏）
		int bottom = listBottom();
		if (bottom - t < 20) t = Math.max(py + 40, bottom - 20);
		return t;
	}

	private int listBottom() {
		return py + ph - 30;
	}

	// ------------------------------------------------------------ 右侧滚动条

	/** 滚动条宽度。 */
	private static final int SCROLLBAR_W = 4;

	/**
	 * 列表内容的右边界（滚动条左边留出间隔）。
	 *
	 * <p>歌名和右侧的 [删除] 都要按这个边界排版 —— 否则会被滚动条压住。
	 */
	private int listRight() {
		return px + pw - 10 - SCROLLBAR_W;
	}

	/** 列表内容总高度。 */
	private int listContentHeight() {
		if (tab == 0) return results.size() * 22;
		return visibleQueueIndexes().length * 20;
	}

	/** 列表可视高度。 */
	private int listViewHeight() {
		return Math.max(1, listBottom() - listTop());
	}

	/** 滚动上限。 */
	private int scrollMax() {
		return Math.max(0, listContentHeight() - listViewHeight());
	}

	/**
	 * 画右侧滚动条（内容超出可视区才画）。
	 *
	 * <p>以前只能用鼠标滚轮，而且**完全看不出还有多少内容** ——
	 * 队列几百首时根本不知道滚到哪了。
	 */
	private void drawScrollbar(DrawContext ctx) {
		int content = listContentHeight();
		int view = listViewHeight();
		if (content <= view) return;              // 内容没超出，不需要滚动条

		int top = listTop();
		int bottom = listBottom();
		int x = px + pw - 10;
		// 轨道
		ctx.fill(x, top, x + SCROLLBAR_W, bottom, 0x40FFFFFF);

		// 滑块：长度按「可视 / 总内容」比例
		int trackH = bottom - top;
		int thumbH = Math.max(12, (int) ((long) trackH * view / content));
		int maxScroll = scrollMax();
		int travel = trackH - thumbH;
		int thumbY = top + (maxScroll <= 0 ? 0
				: (int) ((long) travel * Math.min(scroll, maxScroll) / maxScroll));
		ctx.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, 0xFFB0B0B0);
	}

	/** 滚动条拖拽状态。 */
	private boolean draggingScrollbar;

	/**
	 * 点击/拖拽滚动条。
	 *
	 * @param dragging true = 正在拖拽（按下后移动）
	 * @return 是否消费了这次点击
	 */
	private boolean scrollbarClick(double mouseY, boolean dragging) {
		int content = listContentHeight();
		int view = listViewHeight();
		if (content <= view) return false;
		int top = listTop();
		int bottom = listBottom();
		if (mouseY < top || mouseY > bottom) return false;

		int trackH = bottom - top;
		int thumbH = Math.max(12, (int) ((long) trackH * view / content));
		int travel = Math.max(1, trackH - thumbH);
		int maxScroll = scrollMax();
		// 按鼠标位置反推滚动量（点哪跳哪，拖拽走同一套）
		int rel = (int) (mouseY - top - thumbH / 2.0);
		scroll = MathHelper.clamp((int) ((long) rel * maxScroll / travel), 0, maxScroll);
		if (dragging) draggingScrollbar = true;
		return true;
	}

	/** 鼠标是否落在滚动条上。 */
	private boolean overScrollbar(double mouseX, double mouseY) {
		return mouseX >= px + pw - 12 && mouseX <= px + pw - 6
				&& mouseY >= listTop() && mouseY <= listBottom();
	}

	private void renderSearchTab(DrawContext ctx, int mouseX, int mouseY) {
		int top = listTop();
		int bottom = listBottom();
		if (searching) {
			ctx.drawTextWithShadow(this.textRenderer, "搜索中…", px + 10, top + 4, SUB);
			return;
		}
		if (!resultError.isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer, "⚠ " + resultError, px + 10, top + 4, WARN);
			return;
		}
		if (results.isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer,
					"输入关键词后按回车搜索（QQ音乐 / 网易云 切换按钮在右上角）", px + 10, top + 4, SUB);
			return;
		}
		ctx.enableScissor(px + 6, top, listRight() + 2, bottom);
		int y = top - scroll;
		for (int i = 0; i < results.size(); i++) {
			MusicTrack t = results.get(i);
			int rh = 22;
			if (y + rh > top && y < bottom) {
				boolean hover = mouseX >= px + 8 && mouseX <= px + pw - 8 && mouseY >= y && mouseY < y + rh;
				if (hover) ctx.fill(px + 8, y, px + pw - 8, y + rh, ROW_HOVER);
				if (t.vip()) ctx.fill(px + 8, y, px + 12, y + rh, ROW_VIP);
				String title = trim(t.title(), pw - 120);
				ctx.drawTextWithShadow(this.textRenderer, title, px + 16, y + 3, TEXT);
				String sub = trim(t.artists() + " · " + t.album(), pw - 120);
				ctx.drawTextWithShadow(this.textRenderer, sub, px + 16, y + 12, SUB);
				String dur = t.durationText();
				ctx.drawTextWithShadow(this.textRenderer, dur, px + pw - 54, y + 7, SUB);
				ctx.drawTextWithShadow(this.textRenderer, t.vip() ? "VIP" : "点歌",
						px + pw - 32, y + 7, t.vip() ? WARN : OK);
			}
			y += rh;
		}
		ctx.disableScissor();
		drawScrollbar(ctx);   // 搜索页也加滚动条
	}

	/**
	 * 队列页要显示的下标列表（受搜索词过滤）。
	 *
	 * <p>返回的是**原始下标** —— 插播和删除都要用原始下标，
	 * 否则过滤之后点第 3 行会去操作完整队列里的第 3 首。
	 */
	private int[] visibleQueueIndexes() {
		List<MusicTrack> q = ClientAudio.get().queue();
		if (queueQuery.isEmpty()) {
			int[] all = new int[q.size()];
			for (int i = 0; i < all.length; i++) all[i] = i;
			return all;
		}
		java.util.List<Integer> hit = new java.util.ArrayList<>();
		for (int i = 0; i < q.size(); i++) {
			MusicTrack t = q.get(i);
			if (t.display().toLowerCase().contains(queueQuery)) hit.add(i);
		}
		int[] out = new int[hit.size()];
		for (int i = 0; i < out.length; i++) out[i] = hit.get(i);
		return out;
	}

	private void renderQueueTab(DrawContext ctx, int mouseX, int mouseY) {
		List<MusicTrack> queue = ClientAudio.get().queue();
		int top = listTop();
		int bottom = listBottom();
		if (queue.isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer,
					"队列是空的～ 去「搜索」页点一首吧", px + 10, top + 4, SUB);
			return;
		}
		int[] vis = visibleQueueIndexes();
		// 头部：搜索/导入状态优先显示，其次是队列概况
		String head;
		int headColor;
		if (!importStatus.isEmpty()) {
			head = importStatus;
			headColor = importStatus.startsWith("✗") ? WARN
					: (importStatus.startsWith("✓") ? OK : SUB);
		} else if (!queueQuery.isEmpty()) {
			head = "🔍 在队列里搜「" + queueQuery + "」找到 " + vis.length + " 首"
					+ " · 清空输入框恢复完整队列";
			headColor = vis.length > 0 ? OK : WARN;
		} else {
			head = "共 " + queue.size() + " 首 · 点歌名插播 · 点右侧 [删除] 移出队列";
			headColor = SUB;
		}
		ctx.drawTextWithShadow(this.textRenderer, trim(head, pw - 20), px + 10, top - 12, headColor);
		ctx.enableScissor(px + 6, top, listRight() + 2, bottom);
		int y = top - scroll;
		int cur = ClientAudio.get().currentIndex();
		for (int row = 0; row < vis.length; row++) {
			int i = vis[row];
			MusicTrack t = queue.get(i);
			int rh = 20;
			if (y + rh > top && y < bottom) {
				boolean hover = mouseX >= px + 8 && mouseX <= px + pw - 8 && mouseY >= y && mouseY < y + rh;
				if (i == cur) ctx.fill(px + 8, y, px + pw - 8, y + rh, ROW_CUR);
				else if (hover) ctx.fill(px + 8, y, px + pw - 8, y + rh, ROW_HOVER);

				// 删除按钮占右侧固定宽度；歌名要相应缩短，避免被它压住
				String del = "[删除]";
				int delW = this.textRenderer.getWidth(del);
				int delX = listRight() - delW;
				boolean delHover = hover && mouseX >= delX - 3;

				String prefix = (i == cur ? "▶ " : (i + 1) + ". ");
				int nameMax = delX - (px + 14) - 6;
				ctx.drawTextWithShadow(this.textRenderer,
						prefix + trim(t.display(), Math.max(40, nameMax)), px + 14, y + 6, i == cur ? OK : TEXT);
				ctx.drawTextWithShadow(this.textRenderer, t.durationText(), delX - 36, y + 6, SUB);
				ctx.drawTextWithShadow(this.textRenderer, del, delX, y + 6, delHover ? WARN : SUB);
			}
			y += rh;
		}
		ctx.disableScissor();
		drawScrollbar(ctx);
	}

	private void renderPlayerTab(DrawContext ctx, int mouseX, int mouseY) {
		ClientAudio audio = ClientAudio.get();
		MusicTrack t = audio.currentTrack();
		int top = listTop();
		if (t == null) {
			ctx.drawTextWithShadow(this.textRenderer, "现在没有在播放的歌", px + 10, top + 4, SUB);
			return;
		}
		// 大标题
		ctx.drawTextWithShadow(this.textRenderer, trim(t.title(), pw - 20), px + 10, top, TEXT);
		ctx.drawTextWithShadow(this.textRenderer, trim(t.artists(), pw - 20), px + 10, top + 12, SUB);
		ctx.drawTextWithShadow(this.textRenderer, trim("专辑：" + t.album(), pw - 20), px + 10, top + 24, SUB);

		// 进度条
		int barX = px + 10;
		int barY = top + 44;
		int barW = pw - 20;
		ctx.fill(barX, barY, barX + barW, barY + 4, 0xFF2A2A32);
		int dur = t.duration();
		int pos = audio.displayPositionSeconds();
		float p = dur > 0 ? MathHelper.clamp(pos / (float) dur, 0f, 1f) : audio.player().progress();
		int filled = (int) (barW * p);
		if (filled > 0) ctx.fill(barX, barY, barX + filled, barY + 4, ACCENT);
		ctx.drawTextWithShadow(this.textRenderer, fmt(pos) + " / " + fmt(dur), barX, barY + 8, SUB);

		// 状态
		String state = audio.paused() ? "已暂停" : "播放中";
		ctx.drawTextWithShadow(this.textRenderer, state + " · 音量 " + audio.volume() + "%",
				barX + 80, barY + 8, SUB);
		if (!audio.error().isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer, "⚠ " + trim(audio.error(), pw - 20),
					px + 10, barY + 22, WARN);
		}
		if (!audio.notice().isEmpty()
				&& System.currentTimeMillis() - audio.noticeAt() < 8000) {
			ctx.drawTextWithShadow(this.textRenderer, "• " + trim(audio.notice(), pw - 20),
					px + 10, barY + 34, OK);
		}
	}

	private void renderSettingsTab(DrawContext ctx, int mouseX, int mouseY) {
		int top = listTop();
		// 顶部一行：登录说明。位置固定在「页签下、按钮上」那条 12px 的缝里，
		// 只有 roomForHint 为真时才画（否则会压到第一排按钮）。
		if (settingsRoomForHint()) {
			ctx.drawTextWithShadow(this.textRenderer,
					trim("登录后可以播放会员歌曲 · Cookie 只存在你本机", pw - 20),
					px + 10, py + 32, SUB);
		}

		// 底部两行说明 + 登录状态：见 renderSettingsInfo()（已避开播放控制行）
		renderSettingsInfo(ctx);
	}

	/**
	 * 说明文字区。
	 *
	 * <p>位置要满足两点，否则会出现「文字被挡住」：
	 * <ol>
	 *   <li>在最后一排设置按钮（row7 = sy+176）**下面**</li>
	 *   <li>在播放控制行（{@code bodyBottom - 22}）**上面**——那行按钮是画在所有页签上的，
	 *       文字压到它就会被盖住</li>
	 * </ol>
	 * 所以这里只保留两行紧凑的说明。
	 */
	private void renderSettingsInfo(DrawContext ctx) {
		int y = settingsInfoY();
		int controlsTop = py + ph - 8 - 22;      // 播放控制行顶边
		int lastButtonBottom = py + 30 + (settingsRoomForHint() ? 12 : 0) + 4 + 10 * rowH;

		// 防重叠：小窗口下 9 行按钮本身就可能顶到控制行，这时候干脆不画说明文字，
		// 否则说明会被控制按钮盖住（就是「后面的字被挡住」那个问题）。
		boolean roomForTwoLines = lastButtonBottom + 1 + 11 + 9 < controlsTop;
		boolean roomForOneLine = lastButtonBottom + 1 + 9 < controlsTop;

		// 登录状态：能放就放（它比说明更重要）。
		// 服务端已登录时优先显示这个——玩家不需要自己登录，避免被误解成「没登录就用不了」。
		String stateText = loginState;
		boolean stateOk = loginState.startsWith("登录成功");
		if (ClientAudio.get().serverLoggedIn()) {
			stateText = "服务端账号已登录 · 你无需登录即可听歌";
			stateOk = true;
		}
		if (!stateText.isEmpty() && roomForOneLine) {
			ctx.drawTextWithShadow(this.textRenderer, trim(stateText, pw - 20),
					px + 10, Math.min(y - 11, controlsTop - 10),
					stateOk ? OK : SUB);
		}
		if (!roomForOneLine) return;

		ctx.drawTextWithShadow(this.textRenderer,
				trim("队列上限 " + ClientAudio.get().effectiveQueueLimit()
						+ " 首 · 冷却 " + MusicConfig.get().requestCooldownSeconds
						+ " 秒 · 按键 " + keyHint(), pw - 20),
				px + 10, y, SUB);
		if (!roomForTwoLines) return;

		String line2 = PanelEditor.isEditing()
				? "布局调整中：拖动屏幕上的两个框，松手自动保存"
				: "歌词在音乐面板下方 · 可用「调整布局」拖动";
		ctx.drawTextWithShadow(this.textRenderer, trim(line2, pw - 20), px + 10, y + 11,
				PanelEditor.isEditing() ? WARN : SUB);
	}

	/** 说明区起始 Y：夹在「最后一排设置按钮」与「播放控制行」之间。 */
	private int settingsInfoY() {
		int controlsTop = py + ph - 8 - 22;   // 播放控制行顶边（那行按钮画在所有页签上）
		int lastButtonBottom = py + 30 + (settingsRoomForHint() ? 12 : 0) + 4 + 10 * rowH;
		return Math.max(lastButtonBottom + 1, controlsTop - 23);
	}

	/** 设置页顶部那行提示是否有位置显示（与 init() 里的判断保持一致）。 */
	private boolean settingsRoomForHint() {
		return !compact;
	}

	/** 执行歌单导入（队列页的按钮/回车都走这里）。 */
	private void doImportPlaylist() {
		String link = importField == null ? "" : importField.getText();
		importStatus = "正在获取歌单…";
		ClientAudio.get().importPlaylist(link, msg -> importStatus = msg);
	}

	/** 当前输入框里的内容是不是「歌单链接/ID」。 */
	private boolean looksLikePlaylistInput(String s) {
		if (s == null) return false;
		String t = s.trim().toLowerCase();
		if (t.isEmpty()) return false;
		return t.contains("y.qq.com") || t.contains("playlist") || t.contains("disstid")
				|| t.contains("http") || t.matches("\\d{5,20}");
	}

	/**
	 * 队列页那个输入框的统一入口：**填链接就导入歌单，填文字就搜队列**。
	 *
	 * <p>共用一个框是为了不再多占一行空间（界面上已经没有空位了）。
	 */
	private void doImportOrSearch() {
		String text = importField == null ? "" : importField.getText();
		String t = text == null ? "" : text.trim();
		if (t.isEmpty()) {
			queueQuery = "";
			importStatus = "";
			return;
		}
		if (looksLikePlaylistInput(t)) {
			queueQuery = "";          // 导入时清掉搜索过滤，免得导入完看不到
			doImportPlaylist();
			return;
		}
		queueQuery = t.toLowerCase();
		importStatus = "";
		scroll = 0;
	}
	private String keyHint() {
		String k = ClientAudioAccess.openKeyName();
		return k == null || k.isBlank() ? ";" : k;
	}

	/**
	 * 画登录二维码。
	 *
	 * <p><b>必须画在主面板外面</b>——之前画在面板内右上角，正好被「QQ音乐登录」等按钮压住，
	 * 页面空白处会自动给面板让位，所以优先放面板右侧，右侧空间不够就放左侧，
	 * 两侧都不够（窗口特别窄）就放到面板正下方居中。
	 * <p>由 {@link #render} 在 super.render() 之后调用，保证在最上层不被遮挡。
	 */
	private void renderQrOutsidePanel(DrawContext ctx, int mouseX, int mouseY) {
		if (!loginOpen || qrTextureId == null) return;

		int size = 84;
		int pad = 10;
		int screenW = ctx.getScaledWindowWidth();
		int screenH = ctx.getScaledWindowHeight();

		int qx;
		int qy;
		boolean below = false;

		if (px + pw + pad + size + 8 <= screenW) {
			// 优先：放在主面板右侧
			qx = px + pw + pad;
			qy = Math.max(8, Math.min(py + 12, screenH - size - 34));
		} else if (px - pad - size - 8 >= 0) {
			// 其次：放在主面板左侧
			qx = px - pad - size;
			qy = Math.max(8, Math.min(py + 12, screenH - size - 34));
		} else {
			// 兜底：主面板下方居中。
			// 注意小逻辑分辨率（1080p @ GUI缩放4 → 480x270）：面板下方其实只剩 20 多像素，
			// 所以这里要主动收缩二维码并往上找位置，否则会掉到屏幕外面看不见。
			int controlsTop = py + ph - 8 - 22;
			int room = Math.max(0, controlsTop - (py + ph) - 4);
			if (room < size + 6) size = Math.max(48, Math.min(size, room - 6));
			below = true;
			qx = Math.max(8, Math.min(screenW - size - 8, (screenW - size) / 2));
			qy = Math.min(screenH - size - 24, py + ph + 8);
			qy = Math.max(2, qy);
			// 空间实在不够就叠到设置页右上角，但保证完整可见（它画在最上层，不会消失）
			if (qy + size + 18 > screenH) {
				qy = Math.max(2, Math.min(controlsTop - size - 2, py + 18));
			}
		}

		// 白色底板（二维码必须白底才能扫）
		ctx.fill(qx - 4, qy - 4, qx + size + 4, qy + size + 4, 0xFFFFFFFF);
		ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, qrTextureId,
				qx, qy, 0f, 0f, size, size, size, size);

		// 提示文字（详细的登录状态在旁边设置页已经显示了，这里只留扫码指引）
		String tip = "请用手机" + MusicApi.activeLoginMethod().label() + "扫码";
		int tw = this.textRenderer.getWidth(tip);
		int tx = Math.max(4, Math.min(screenW - tw - 4, qx + (size - tw) / 2));
		int ty = qy + size + 6;
		ctx.drawTextWithShadow(this.textRenderer, tip, tx, ty, TEXT);

		boolean expired = System.currentTimeMillis() - qrShownAt > 120000;
		String sub = expired ? "二维码已过期 · 再点一次「QQ音乐登录」刷新" : "扫码后手机确认即可";
		int sw = this.textRenderer.getWidth(sub);
		int sx = Math.max(4, Math.min(screenW - sw - 4, qx + (size - sw) / 2));
		ctx.drawTextWithShadow(this.textRenderer, sub, sx, ty + 11, expired ? WARN : SUB);
	}

	// ---------------------------------------------------------------- 交互

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		// 布局调整模式：优先响应 HUD 面板的拖拽
		if (PanelEditor.isEditing() && PanelEditor.mouseClicked(mouseX, mouseY)) {
			return true;
		}
		// 右侧滚动条：点哪跳哪，拖拽也能滚
		if (overScrollbar(mouseX, mouseY) && scrollbarClick(mouseY, true)) {
			return true;
		}
		if (tab == 0 && !results.isEmpty()) {
			int top = listTop();
			int bottom = listBottom();
			int y = top - scroll;
			for (MusicTrack t : results) {
				// 同样要限制在列表可视区内，否则下方的控制按钮会被当成「点了搜索结果」
				int rowTop = Math.max(y, top);
				int rowBottom = Math.min(y + 22, bottom);
				if (rowTop < rowBottom
						&& mouseY >= rowTop && mouseY < rowBottom
						&& mouseX >= px + 8 && mouseX <= px + pw - 8) {
					ClientAudio.get().requestSong(t, msg -> {
						var mc = net.minecraft.client.MinecraftClient.getInstance();
						if (mc.player != null) mc.player.sendMessage(Text.literal("[点歌台] " + msg), false);
					});
					return true;
				}
				y += 22;
			}
		}
		if (tab == 1) {
			List<MusicTrack> queue = ClientAudio.get().queue();
			int top = listTop();
			int bottom = listBottom();
			int y = top - scroll;
			int delW = this.textRenderer.getWidth("[删除]");
			int delLeft = listRight() - delW - 3;   // 让出右侧滚动条
			// 用「可见下标」遍历：搜索过滤后，点第 N 行要操作的是**它对应的原始下标**
			int[] vis = visibleQueueIndexes();
			for (int row = 0; row < vis.length; row++) {
				int i = vis[row];
				// ⚠ 必须把点击范围**限制在列表可视区域内**（top ~ bottom）。
				// 以前只判断 mouseY >= y，列表底边以下的点击（也就是下方那排
				// 上一首/播放/下一首/模式/清空/音量按钮所在的位置）也会被当成
				// 「点了第 N 首歌名」→ 于是出现
				// 「点空位置会错误切歌、下面 5 个按钮无效」。
				int rowTop = Math.max(y, top);
				int rowBottom = Math.min(y + 20, bottom);
				if (rowTop < rowBottom
						&& mouseY >= rowTop && mouseY < rowBottom
						&& mouseX >= px + 8 && mouseX <= px + pw - 8) {
					if (mouseX >= delLeft) {
						// 点右侧 [删除] → 从队列移除这首
						ClientAudio.get().removeFromQueue(i);
					} else {
						// 点歌名 → 插播这首（原始下标 + 1）
						ClientAudio.get().sendControl(MusicNetworking.Action.PLAY, i + 1);
					}
					return true;
				}
				y += 20;
			}
		}
		// 按钮优先：列表没命中就交给原版控件分发（否则下方按钮点不动）
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
		if (PanelEditor.isEditing()
				&& PanelEditor.mouseDragged(click.x(), click.y(), this.width, this.height)) {
			return true;
		}
		// 正在拖右侧滚动条 → 按鼠标 Y 反推滚动量
		if (draggingScrollbar && scrollbarClick(click.y(), true)) return true;
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public void tick() {
		// 字号长按连调（无级手感）
		tickScale();
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.gui.Click click) {
		if (PanelEditor.isEditing()) {
			PanelEditor.mouseReleased();
			if (editLayoutButton != null) editLayoutButton.setMessage(editLayoutText());
		}
		// 松开鼠标就停止字号连调、结束滚动条拖拽
		scaleHoldDir = 0;
		draggingScrollbar = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int max = scrollMax();
		scroll = MathHelper.clamp((int) (scroll - vertical * 16), 0, max);
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int keyCode = input.key();
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			if (tab == 0 && searchField != null && searchField.isFocused()) {
				doSearch();
				return true;
			}
			// 队列页：输入框回车 = 导入歌单（填的是链接）或搜队列（填的是文字）
			if (tab == 1 && importField != null && importField.isFocused()) {
				doImportOrSearch();
				return true;
			}
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			this.close();
			return true;
		}
		// 换页签：1~4
		//
		// ⚠ 打字的时候**不能**抢数字键！
		// 以前这里不看焦点，于是在搜索框里打「123」「2024」这类内容时，
		// 数字键会先把页签切走（1=搜索 2=队列 3=播放 4=设置），
		// 字根本没输进去 —— 表现就是「主键盘上方的数字键打不出来」。
		// 输入框有焦点时，按键交给输入框自己处理。
		if (!isTypingInTextField()
				&& keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4) {
			int idx = keyCode - GLFW.GLFW_KEY_1;
			if (idx < TABS.length) {
				tab = idx;
				scroll = 0;
				refreshWidgets();
				return true;
			}
		}
		return super.keyPressed(input);
	}

	/**
	 * 现在是不是正在某个输入框里打字？
	 *
	 * <p>用于让快捷键（1~4 切页签）在打字时<b>主动让位</b>，
	 * 否则搜索框里永远打不出数字。
	 */
	private boolean isTypingInTextField() {
		for (TextFieldWidget f : new TextFieldWidget[]{searchField, cookieField, importField}) {
			if (f != null && f.isFocused()) return true;
		}
		// 兜底：任何获得焦点的输入框都算（以后加新输入框也不用手动登记）
		return this.getFocused() instanceof TextFieldWidget;
	}

	@Override
	public void close() {
		workers.shutdownNow();
		if (qrTextureId != null) {
			var mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc != null) mc.getTextureManager().destroyTexture(qrTextureId);
		}
		super.close();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---------------------------------------------------------------- 小工具

	private String trim(String s, int maxWidth) {
		if (s == null) return "";
		if (this.textRenderer.getWidth(s) <= maxWidth) return s;
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (this.textRenderer.getWidth(sb.toString() + c + "…") > maxWidth) break;
			sb.append(c);
		}
		return sb + "…";
	}

	private static String fmt(int seconds) {
		if (seconds <= 0) return "0:00";
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

}
