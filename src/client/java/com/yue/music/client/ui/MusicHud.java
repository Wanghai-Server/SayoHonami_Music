package com.yue.music.client.ui;

import com.yue.music.client.audio.ClientAudio;
import com.yue.music.client.audio.ClientAudioAccess;
import com.yue.music.client.lyrics.LyricsClient;
import com.yue.music.config.MusicConfig;
import com.yue.music.model.Lyrics;
import com.yue.music.model.MusicTrack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;

/**
 * 游戏内 HUD：
 * <ol>
 *   <li><b>音乐信息面板</b>——歌名/歌手/进度条/时间，默认右上角</li>
 *   <li><b>歌词面板</b>——默认紧贴在音乐面板下方，逐行高亮当前句</li>
 * </ol>
 *
 * <p>两个面板的位置都可以拖拽调整（见 {@link PanelEditor}），坐标存进配置。
 * 未拖拽过时按配置里的角标（top_right 等）自动排布。
 *
 * <p>注意：这里所有方法都跑在渲染线程，不能做网络/IO。
 */
public final class MusicHud {

	private MusicHud() {
	}

	// ---- 配色 ----
	private static final int BG = 0xB0101014;
	private static final int BG_LYRICS = 0x8C0A0A10;
	private static final int TITLE = 0xFFFFFFFF;
	private static final int SUB = 0xFFA8A8B0;
	private static final int BAR_BG = 0xFF2A2A32;
	private static final int BAR_FG = 0xFF4FD1C5;
	private static final int ACCENT = 0xFF7C6CF0;
	private static final int LYRIC_ACTIVE = 0xFF7C6CF0;
	private static final int LYRIC_NEAR = 0xFFD8D8E2;
	private static final int LYRIC_FAR = 0xFF8A8A96;
	private static final int LYRIC_TRANS = 0xFF9A9AA8;

	/** 音乐面板尺寸。 */
	public static final int MUSIC_W = 170;
	public static final int MUSIC_H = 44;
	/** 歌词面板宽度（与音乐面板对齐）。 */
	public static final int LYRICS_W = 170;
	/** 歌词行高。 */
	private static final int LYRIC_LINE_H = 11;

	// ---------------------------------------------------------------- 入口

	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.textRenderer == null || mc.player == null) return;
		if (mc.options != null && mc.options.hudHidden) return;

		MusicConfig cfg = MusicConfig.get();
		ClientAudio audio = ClientAudio.get();
		MusicTrack track = audio.currentTrack();

		int screenW = ctx.getScaledWindowWidth();
		int screenH = ctx.getScaledWindowHeight();

		// 编辑布局时用拖拽中的临时位置，做到「边拖边看」
		int musicX = PanelEditor.resolvedMusicX(screenW);
		int musicY = PanelEditor.resolvedMusicY(screenH);
		int lyricsX = PanelEditor.resolvedLyricsX(screenW, musicX);
		int lyricsY = PanelEditor.resolvedLyricsY(screenH, musicY);

		// ---- 歌词面板（先画，它在下面不遮挡） ----
		if (cfg.showLyrics && track != null) {
			renderLyrics(ctx, mc, cfg, lyricsX, lyricsY);
		}

		// ---- 音乐信息面板 ----
		if (cfg.showHud && track != null) {
			renderMusicPanel(ctx, mc, cfg, audio, track, musicX, musicY);
		}

		// 注意：拖拽边框**不在这里画**。HUD 层在主界面之下，
		// 画在这里会被设置页的大面板整个盖住（既看不见也拖不到）。
		// 边框由 MusicScreen 在 super.render() 之后绘制，见 PanelEditor.renderOverlay。
	}

	// ---------------------------------------------------------------- 音乐面板

	private static void renderMusicPanel(DrawContext ctx, MinecraftClient mc, MusicConfig cfg,
										 ClientAudio audio, MusicTrack track, int x, int y) {
		ctx.fill(x, y, x + MUSIC_W, y + MUSIC_H, BG);
		ctx.fill(x, y, x + 2, y + MUSIC_H, ACCENT); // 左侧强调条

		boolean paused = audio.paused();
		ctx.drawTextWithShadow(mc.textRenderer, paused ? "❚❚" : "▶", x + 7, y + 5, ACCENT);

		String title = clip(mc, track.title(), MUSIC_W - 34);
		ctx.drawTextWithShadow(mc.textRenderer, title, x + 20, y + 5, paused ? SUB : TITLE);

		String artists = clip(mc, track.artists(), MUSIC_W - 62);
		ctx.drawTextWithShadow(mc.textRenderer, artists, x + 20, y + 16, SUB);
		String time = fmt(audio.displayPositionSeconds()) + " / " + fmt(track.duration());
		ctx.drawTextWithShadow(mc.textRenderer, time,
				x + MUSIC_W - mc.textRenderer.getWidth(time) - 6, y + 16, SUB);

		int barX = x + 6;
		int barY = y + 30;
		int barW = MUSIC_W - 12;
		ctx.fill(barX, barY, barX + barW, barY + 2, BAR_BG);
		int dur = track.duration();
		float p = dur > 0 ? MathHelper.clamp(audio.displayPositionSeconds() / (float) dur, 0f, 1f)
				: MathHelper.clamp(audio.player().progress(), 0f, 1f);
		int filled = (int) (barW * p);
		if (filled > 0) ctx.fill(barX, barY, barX + filled, barY + 2, BAR_FG);

		String hint = "按 " + keyName() + " 打开点歌台";
		ctx.drawTextWithShadow(mc.textRenderer, clip(mc, hint, MUSIC_W - 12), x + 6, y + 34, SUB);
	}

	// ---------------------------------------------------------------- 歌词面板

	/**
	 * 歌词面板。
	 *
	 * <p>排版规则：以「当前句」为锚点，按配置行数向上下展开；
	 * 奇数行时当前句正好在正中间，偶数行时在上半区最后一行。
	 * 水平方向上，当前句居中显示，上下文行左对齐。
	 */
	private static void renderLyrics(DrawContext ctx, MinecraftClient mc, MusicConfig cfg, int x, int y) {
		LyricsClient lc = LyricsClient.get();
		Lyrics lyrics = lc.current();
		float scale = cfg.lyricsScale;
		int rows = cfg.lyricsLines;
		int textW = Math.max(40, (int) (LYRICS_W / scale));

		// ---- 先把「要显示什么」算清楚 ----
		Lyrics.Line currentLine = null;
		int currentIdx = -1;
		String fallback;   // 没有歌词时显示的提示
		boolean hasLyrics = lyrics != null && lyrics.ok() && !lyrics.lines().isEmpty();

		if (hasLyrics) {
			currentIdx = lc.currentLineIndex();
			if (currentIdx < 0) currentIdx = 0; // 还没到第一句，先显示第一句
			currentLine = lyrics.lines().get(currentIdx);
			fallback = null;
		} else if (lyrics == null && lc.loading()) {
			fallback = "歌词加载中…";
		} else if (lyrics != null && !lyrics.ok()) {
			fallback = "歌词获取失败（" + (lyrics.error() == null ? "未知" : lyrics.error()) + "）";
		} else {
			fallback = "♪ 纯音乐，请欣赏";
		}

		// ---- 面板尺寸 ----
		String trans = currentLine != null && cfg.showTranslation && currentLine.trans() != null
				&& !currentLine.trans().isBlank() ? currentLine.trans() : null;
		int boxH = 6 + rows * LYRIC_LINE_H + (trans != null ? LYRIC_LINE_H : 0);
		ctx.fill(x, y, x + LYRICS_W, y + boxH, BG_LYRICS);
		ctx.fill(x, y, x + 2, y + boxH, ACCENT & 0x55FFFFFF);

		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x + 5f, y + 3f);
		ctx.getMatrices().scale(scale, scale);

		if (fallback != null) {
			String clipped = clip(mc, fallback, textW);
			ctx.drawTextWithShadow(mc.textRenderer, clipped,
					(textW - mc.textRenderer.getWidth(clipped)) / 2, 0, LYRIC_FAR);
		} else {
			// 当前句在面板里的行号
			int curRow = (rows - 1) / 2;
			for (int r = 0; r < rows; r++) {
				int lineIdx = currentIdx - curRow + r;
				if (lineIdx < 0 || lineIdx >= lyrics.lines().size()) continue;
				String text = clip(mc, lyrics.lines().get(lineIdx).text(), textW);
				int cy = r * LYRIC_LINE_H;
				if (lineIdx == currentIdx) {
					// 当前句居中 + 高亮
					ctx.drawTextWithShadow(mc.textRenderer, text,
							Math.max(0, (textW - mc.textRenderer.getWidth(text)) / 2), cy, LYRIC_ACTIVE);
				} else {
					int color = Math.abs(lineIdx - currentIdx) == 1 ? LYRIC_NEAR : LYRIC_FAR;
					ctx.drawTextWithShadow(mc.textRenderer, text, 0, cy, color);
				}
			}
			if (trans != null) {
				String t = clip(mc, trans, textW);
				ctx.drawTextWithShadow(mc.textRenderer, t,
						Math.max(0, (textW - mc.textRenderer.getWidth(t)) / 2),
						rows * LYRIC_LINE_H, LYRIC_TRANS);
			}
		}

		ctx.getMatrices().popMatrix();
	}

	// ---------------------------------------------------------------- 位置计算

	/** 面板左边界：用过拖拽坐标就按坐标，否则按角标。 */
	public static int resolveMusicX(int savedX, String corner, int screenW, int panelW) {
		if (savedX >= 0) return savedX;
		return switch (corner == null ? "top_right" : corner) {
			case "top_left", "bottom_left" -> 4;
			default -> screenW - panelW - 4;
		};
	}

	public static int resolveMusicY(int savedY, String corner, int screenH, int panelH) {
		if (savedY >= 0) return savedY;
		return switch (corner == null ? "top_right" : corner) {
			case "bottom_left", "bottom_right" -> screenH - panelH - 4;
			default -> 4;
		};
	}

	/** 歌词面板的高度（外部画框/拖拽判定都要用）。 */
	public static int lyricsBoxHeight(MusicConfig cfg, boolean hasTranslation) {
		return 6 + cfg.lyricsLines * LYRIC_LINE_H + (hasTranslation ? LYRIC_LINE_H : 0);
	}

	// ---------------------------------------------------------------- 小工具

	private static String keyName() {
		String k = ClientAudioAccess.openKeyName();
		return k == null || k.isBlank() ? ";" : k;
	}

	private static String clip(MinecraftClient mc, String s, int maxWidth) {
		if (s == null) return "";
		if (maxWidth <= 0) return "";
		if (mc.textRenderer.getWidth(s) <= maxWidth) return s;
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (mc.textRenderer.getWidth(sb.toString() + c + "…") > maxWidth) break;
			sb.append(c);
		}
		return sb + "…";
	}

	private static String fmt(int seconds) {
		if (seconds <= 0) return "0:00";
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}
}
