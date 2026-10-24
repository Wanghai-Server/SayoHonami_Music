package com.yue.music.audio;

import com.yue.music.YueMusic;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * 把任意可解码的音频流统一转成播放器需要的格式：44100Hz / 16bit / 立体声 / 小端。
 *
 * <p>解码能力来自 classpath 上的 SPI：
 * <ul>
 *   <li>MP3 —— mp3spi + jlayer</li>
 *   <li>FLAC —— jflac（含 16-bit 与 <b>24-bit</b>）</li>
 * </ul>
 *
 * <p><b>为什么要有 24→16 的手工转换</b>：QQ音乐无损（F000）FLAC 是 24-bit 的，
 * 而 jflac 的 FormatConversionProvider 不支持 24→16 位深转换，
 * 直接调 {@code AudioSystem.getAudioInputStream(TARGET, decoded)} 会抛
 * {@code Unsupported conversion}，表现就是「VIP 歌有直链但放不出声」。
 * 所以这里对 24-bit 源自己做线性缩放（右移 8 位）。
 */
public final class PcmSource implements AutoCloseable {

	/** 播放器输出格式。 */
	public static final int SAMPLE_RATE = 44100;

	public static final AudioFormat TARGET =
			new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 2, 4, SAMPLE_RATE, false);

	private final AudioInputStream in;

	private PcmSource(AudioInputStream in) {
		this.in = in;
	}

	public static PcmSource open(java.io.InputStream raw) throws Exception {
		// 0) FLAC 必须走专用解码器。
		//
		//    ⚠ 不能依赖 jflac 的 Java Sound SPI：它的
		//    Flac2PcmAudioInputStream.processPCM() 里用 `buffer.resize(len * 2)`
		//    硬编码假设 16-bit，遇到 24-bit FLAC（QQ音乐无损）就会缓冲区错位，
		//    播放出来是【一片噪音】，上层甚至能读到原始 "fLaC" 文件字节。
		//    所以这里直接用 jflac 的核心解码器自己解（见 FlacPcmStream）。
		if (looksLikeFlac(raw)) {
			AudioInputStream flac = FlacPcmStream.open(raw);
			if (flac != null) {
				AudioFormat ff = flac.getFormat();
				YueMusic.LOGGER.info("[YueMusic] FLAC 已解码：{}Hz / {}ch / 16-bit",
						ff.getSampleRate(), ff.getChannels());
				if (matches(ff)) {
					return new PcmSource(flac);
				}
				return new PcmSource(AudioSystem.getAudioInputStream(TARGET, flac));
			}
			YueMusic.LOGGER.warn("[YueMusic] FLAC 专用解码失败，回退到通用解码链");
		}

		AudioInputStream decoded = AudioSystem.getAudioInputStream(raw);
		AudioFormat fmt = decoded.getFormat();
		YueMusic.LOGGER.info("[YueMusic] 音频解码：{}", fmt);

		// 1) 已经是目标格式
		if (matches(fmt)) {
			return new PcmSource(decoded);
		}

		// 2) 非 16-bit 的整数 PCM → 手工归一到 16-bit（FLAC 已在上面处理，这里兜底）
		if (isDecodedIntegerPcm(fmt) && fmt.getSampleSizeInBits() != 16) {
			int bits = fmt.getSampleSizeInBits();
			int channels = Math.max(1, fmt.getChannels());
			AudioFormat mid = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED, fmt.getSampleRate(), 16,
					channels, channels * 2, fmt.getSampleRate(), false);
			AudioInputStream pcm16 = new AudioInputStream(
					new PcmBitsTo16Stream(decoded, fmt), mid, AudioSystem.NOT_SPECIFIED);
			YueMusic.LOGGER.info("[YueMusic] 音频源是 {}-bit / {} 编码，已手工归一为 16-bit",
					bits, fmt.getEncoding());
			if (matches(mid)) {
				return new PcmSource(pcm16);
			}
			return new PcmSource(AudioSystem.getAudioInputStream(TARGET, pcm16));
		}

		// 3) 其余情况交给标准转换（16-bit 但采样率/声道数不同等）
		return new PcmSource(AudioSystem.getAudioInputStream(TARGET, decoded));
	}

	/** 用前 4 字节判断是不是 FLAC（"fLaC"）。会先把字节读进缓冲再还原。 */
	private static boolean looksLikeFlac(java.io.InputStream raw) {
		try {
			if (!raw.markSupported()) return false;
			raw.mark(8);
			byte[] head = new byte[4];
			int n = raw.read(head);
			raw.reset();
			return n == 4 && head[0] == 0x66 && head[1] == 0x4C
					&& head[2] == 0x61 && head[3] == 0x43;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 这个格式里的数据是不是「已经解码好的整数 PCM」。
	 *
	 * <p>要同时认两种：
	 * <ul>
	 *   <li>{@code PCM_SIGNED} —— 标准 PCM（wav/mp3spi 解码后的结果）</li>
	 *   <li>{@code FLAC} —— jflac 的编码标记。它输出的**数据已经是解码后的 PCM**，
	 *       但 AudioFormat 里仍写着自定义的 {@code FLAC} 编码（frameSize 甚至是 -1）。
	 *       不认它的话，24-bit 无损就会跳过手工转换 → 抛 Unsupported conversion → 静音。</li>
	 * </ul>
	 */
	private static boolean isDecodedIntegerPcm(AudioFormat f) {
		if (f.getSampleSizeInBits() <= 0) return false;
		if (f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) return true;
		return isFlacEncoding(f);
	}

	/** 是否是 jflac 的 FLAC 编码（它解码后直接给 PCM 数据）。 */
	private static boolean isFlacEncoding(AudioFormat f) {
		Object enc = f.getEncoding();
		if (enc == null) return false;
		String n = enc.toString();
		return n != null && n.equalsIgnoreCase("FLAC");
	}

	private static boolean matches(AudioFormat f) {
		return f.getSampleRate() == TARGET.getSampleRate()
				&& f.getSampleSizeInBits() == 16
				&& f.getChannels() == 2
				&& !f.isBigEndian()
				&& f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
	}

	/** 读一块 PCM，返回实际字节数；-1 表示结束。 */
	public int read(byte[] buf) throws Exception {
		return in.read(buf);
	}

	/** 判断系统是否支持目标格式的输出。 */
	public static boolean outputSupported() {
		return AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, TARGET));
	}

	@Override
	public void close() throws Exception {
		in.close();
	}
}
