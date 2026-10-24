package com.yue.music.client.ui;

import com.yue.music.client.audio.ClientAudio;
import com.yue.music.client.lyrics.LyricsClient;
import com.yue.music.config.MusicConfig;
import com.yue.music.model.Lyrics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * HUD 面板拖拽编辑器。
 *
 * <p>在点歌台的「设置」页打开「调整布局」后：屏幕上会画出两个面板的边框，
 * 直接用鼠标拖动就能改位置，松手即保存到配置。点「完成」退出编辑。
 *
 * <p>拖拽时把面板「吸」在屏幕内，避免拖出可视区域后找不回来。
 */
public final class PanelEditor {

	private PanelEditor() {
	}

	private static final int BORDER = 0xFFFFFFFF;
	private static final int BORDER_DRAG = 0xFF7C6CF0;
	private static final int LABEL_BG = 0xCC000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int WARN = 0xFFFF8A65;

	private static boolean editing;
	private static int dragTarget; // 0=无 1=音乐面板 2=歌词面板
	private static double grabDx;
	private static double grabDy;

	private static int musicX;
	private static int musicY;
	private static int lyricsX;
	private static int lyricsY;
	private static int lyricsH = 40;

	/**
	 * 拖拽中的临时位置（尚未写入配置之前用）。
	 * <p>-1 表示还没拖过，此时位置由 {@link MusicHud#resolveMusicX} 等按角标推算。
	 * <p>搞这个「幽灵位置」是为了避免一打开编辑模式就把面板瞬移到 (0,0)。
	 */
	private static int dragMusicX = -1;
	private static int dragMusicY = -1;
	private static int dragLyricsX = -1;
	private static int dragLyricsY = -1;

	public static boolean isEditing() {
		return editing;
	}

	public static void start() {
		editing = true;
		dragTarget = 0;
		dragMusicX = -1;
		dragMusicY = -1;
		dragLyricsX = -1;
		dragLyricsY = -1;
	}

	/** 退出编辑并保存。 */
	public static void finish() {
		editing = false;
		dragTarget = 0;
		MusicConfig.get().save();
	}

	/** 复位到默认角标位置。 */
	public static void reset() {
		MusicConfig.get().resetPanelPositions();
		dragTarget = 0;
		dragMusicX = -1;
		dragMusicY = -1;
		dragLyricsX = -1;
		dragLyricsY = -1;
	}

	// -------- 位置解算：编辑时优先用拖拽中的幽灵位置 --------

	public static int resolvedMusicX(int screenW) {
		MusicConfig cfg = MusicConfig.get();
		if (editing && dragMusicX >= 0) return dragMusicX;
		return MusicHud.resolveMusicX(cfg.musicPanelX, cfg.hudPosition, screenW, MusicHud.MUSIC_W);
	}

	public static int resolvedMusicY(int screenH) {
		MusicConfig cfg = MusicConfig.get();
		if (editing && dragMusicY >= 0) return dragMusicY;
		return MusicHud.resolveMusicY(cfg.musicPanelY, cfg.hudPosition, screenH, MusicHud.MUSIC_H);
	}

	public static int resolvedLyricsX(int screenW, int musicX) {
		MusicConfig cfg = MusicConfig.get();
		if (editing && dragLyricsX >= 0) return dragLyricsX;
		if (cfg.lyricsPanelX >= 0) return cfg.lyricsPanelX;
		return musicX;
	}

	public static int resolvedLyricsY(int screenH, int musicY) {
		MusicConfig cfg = MusicConfig.get();
		if (editing && dragLyricsY >= 0) return dragLyricsY;
		if (cfg.lyricsPanelY >= 0) return cfg.lyricsPanelY;
		return musicY + MusicHud.MUSIC_H + 3;
	}

	/** 歌词面板高度（拖拽判定与绘制都要用）。 */
	public static int boxH() {
		return lyricsH > 0 ? lyricsH : 40;
	}

	// ---------------------------------------------------------------- 交互

	public static boolean mouseClicked(double mx, double my) {
		if (!editing) return false;
		MusicConfig cfg = MusicConfig.get();
		boolean hasLyrics = cfg.showLyrics && ClientAudio.get().currentTrack() != null;

		if (hasLyrics && inside(mx, my, lyricsX, lyricsY, MusicHud.LYRICS_W, boxH())) {
			dragTarget = 2;
			grabDx = mx - lyricsX;
			grabDy = my - lyricsY;
			return true;
		}
		if (inside(mx, my, musicX, musicY, MusicHud.MUSIC_W, MusicHud.MUSIC_H)) {
			dragTarget = 1;
			grabDx = mx - musicX;
			grabDy = my - musicY;
			return true;
		}
		return false;
	}

	public static boolean mouseDragged(double mx, double my, int screenW, int screenH) {
		if (!editing || dragTarget == 0) return false;
		int nx = (int) Math.round(mx - grabDx);
		int ny = (int) Math.round(my - grabDy);

		if (dragTarget == 1) {
			dragMusicX = clamp(nx, 0, Math.max(0, screenW - MusicHud.MUSIC_W));
			dragMusicY = clamp(ny, 0, Math.max(0, screenH - MusicHud.MUSIC_H));
			musicX = dragMusicX;
			musicY = dragMusicY;
		} else {
			dragLyricsX = clamp(nx, 0, Math.max(0, screenW - MusicHud.LYRICS_W));
			dragLyricsY = clamp(ny, 0, Math.max(0, screenH - boxH()));
			lyricsX = dragLyricsX;
			lyricsY = dragLyricsY;
		}
		return true;
	}

	/** 松手：把幽灵位置写进配置。 */
	public static void mouseReleased() {
		if (dragTarget == 0) return;
		MusicConfig cfg = MusicConfig.get();
		if (dragTarget == 1 && dragMusicX >= 0) {
			cfg.musicPanelX = dragMusicX;
			cfg.musicPanelY = dragMusicY;
		} else if (dragTarget == 2 && dragLyricsX >= 0) {
			cfg.lyricsPanelX = dragLyricsX;
			cfg.lyricsPanelY = dragLyricsY;
		}
		dragTarget = 0;
		cfg.save();
	}

	/** 设置页的「吸附到音乐面板下方」按钮。 */
	public static void snapLyricsUnderMusic(int screenW) {
		MusicConfig cfg = MusicConfig.get();
		int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
		int mx = MusicHud.resolveMusicX(cfg.musicPanelX, cfg.hudPosition, screenW, MusicHud.MUSIC_W);
		int my = MusicHud.resolveMusicY(cfg.musicPanelY, cfg.hudPosition, screenH, MusicHud.MUSIC_H);
		cfg.lyricsPanelX = mx;
		cfg.lyricsPanelY = my + MusicHud.MUSIC_H + 3;
		cfg.save();
	}

	/**
	 * 切换「调整布局」时调用：进入编辑时根据当前实际位置初始化幽灵位置，
	 * 否则会被当成「未设置」而跳回角标。
	 */
	public static void syncFromConfig(int screenW, int screenH) {
		MusicConfig cfg = MusicConfig.get();
		int mx = MusicHud.resolveMusicX(cfg.musicPanelX, cfg.hudPosition, screenW, MusicHud.MUSIC_W);
		int my = MusicHud.resolveMusicY(cfg.musicPanelY, cfg.hudPosition, screenH, MusicHud.MUSIC_H);
		dragMusicX = mx;
		dragMusicY = my;
		dragLyricsX = cfg.lyricsPanelX >= 0 ? cfg.lyricsPanelX : mx;
		dragLyricsY = cfg.lyricsPanelY >= 0 ? cfg.lyricsPanelY : my + MusicHud.MUSIC_H + 3;
		musicX = dragMusicX;
		musicY = dragMusicY;
		lyricsX = dragLyricsX;
		lyricsY = dragLyricsY;
	}

	// ---------------------------------------------------------------- 绘制

	/**
	 * 画出拖拽边框。
	 *
	 * <p><b>必须由 MusicScreen 在 super.render() 之后调用</b>（而不是画在 HUD 层）：
	 * HUD 在主界面下面，画在 HUD 上会被大面板整个盖住，导致「看不见也拖不到」。
	 */
	public static void renderOverlay(DrawContext ctx, int mX, int mY, int lX, int lY,
									 boolean showLyrics, boolean hasTrack) {
		musicX = mX;
		musicY = mY;
		lyricsX = lX;
		lyricsY = lY;
		lyricsH = MusicHud.lyricsBoxHeight(MusicConfig.get(), hasTranslation());

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.textRenderer == null) return;

		// 音乐面板
		drawBox(ctx, mc, mX, mY, MusicHud.MUSIC_W, MusicHud.MUSIC_H,
				"音乐信息" + (dragTarget == 1 ? " ← 拖动中" : ""), dragTarget == 1);

		// 歌词面板
		if (showLyrics && hasTrack) {
			drawBox(ctx, mc, lX, lY, MusicHud.LYRICS_W, boxH(),
					"歌词" + (dragTarget == 2 ? " ← 拖动中" : ""), dragTarget == 2);
		}

		// 顶部提示
		String tip = "布局调整：拖动这两个框到想要的位置 · 松手自动保存 · 点「完成调整」退出";
		int w = mc.textRenderer.getWidth(tip);
		int cx = (ctx.getScaledWindowWidth() - w) / 2;
		ctx.fill(cx - 6, 4, cx + w + 6, 18, LABEL_BG);
		ctx.drawTextWithShadow(mc.textRenderer, tip, cx, 8, TEXT);
	}

	/**
	 * 画一个「可拖拽框」：半透明填充 + 亮边框 + 四角把手 + 标签。
	 * <p>填充用不透明一点的深色，这样即使它盖在设置页大面板上也能一眼看到边界。
	 */
	private static void drawBox(DrawContext ctx, MinecraftClient mc, int x, int y, int w, int h,
								String label, boolean active) {
		int color = active ? BORDER_DRAG : BORDER;
		// 填充：半透明，保证在被大面板遮挡的区域也能看清边界
		ctx.fill(x, y, x + w, y + h, active ? 0x557C6CF0 : 0x44FFFFFF);
		// 四边
		ctx.fill(x, y, x + w, y + 1, color);
		ctx.fill(x, y + h - 1, x + w, y + h, color);
		ctx.fill(x, y, x + 1, y + h, color);
		ctx.fill(x + w - 1, y, x + w, y + h, color);
		// 四角手柄（更粗，提示可以拖）
		int hs = 5;
		ctx.fill(x, y, x + hs, y + 2, color);
		ctx.fill(x, y, x + 2, y + hs, color);
		ctx.fill(x + w - hs, y, x + w, y + 2, color);
		ctx.fill(x + w - 2, y, x + w, y + hs, color);
		ctx.fill(x, y + h - 2, x + hs, y + h, color);
		ctx.fill(x, y + h - hs, x + 2, y + h, color);
		ctx.fill(x + w - hs, y + h - 2, x + w, y + h, color);
		ctx.fill(x + w - 2, y + h - hs, x + w, y + h, color);
		// 标签
		int lw = mc.textRenderer.getWidth(label) + 8;
		int ly = y - 12 < 0 ? y + h + 2 : y - 12;
		ctx.fill(x, ly, x + lw, ly + 11, LABEL_BG);
		ctx.drawTextWithShadow(mc.textRenderer, label, x + 4, ly + 2, color);
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private static boolean hasTranslation() {
		Lyrics lyr = LyricsClient.get().current();
		if (lyr == null || !lyr.ok() || lyr.lines().isEmpty()) return false;
		MusicConfig cfg = MusicConfig.get();
		if (!cfg.showTranslation) return false;
		int idx = LyricsClient.get().currentLineIndex();
		if (idx < 0 || idx >= lyr.lines().size()) return false;
		String t = lyr.lines().get(idx).trans();
		return t != null && !t.isBlank();
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
