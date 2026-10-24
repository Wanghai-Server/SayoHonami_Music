package com.yue.music.audio;

import com.yue.music.YueMusic;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.Metadata;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * FLAC 解码流（用 jflac 的<b>核心解码器</b>，绕开它自带的有 bug 的 SPI）。
 *
 * <h3>为什么不能直接用 jflac 的 Java Sound SPI</h3>
 * {@code org.jflac.sound.spi.Flac2PcmAudioInputStream} 里是这样的：
 * <pre>
 *   public void processPCM(ByteData pcm) {
 *       buffer.resize(pcm.getLen() * 2);        // ← 硬编码假设 16-bit
 *       buffer.put(pcm.getData(), 0, pcm.getLen());
 *   }
 * </pre>
 * 实测 24-bit FLAC 时 {@code ByteData.len = 6144}（1024 块 × 2 声道 × <b>3</b> 字节），
 * 而它只分配 12288... 但 put 的长度用的是 {@code getLen()}（字节数），
 * 两处语义不一致 → 缓冲区错位 → 播放出来是<b>一片噪音</b>；
 * 更糟的是上层还会拿到原始文件字节（开头是 {@code fLaC}）。
 *
 * <h3>本实现的判据（实测确认）</h3>
 * jflac 核心 API 输出的 {@code ByteData} 是**正确的、位深对应的、小端的 PCM 字节**：
 * <ul>
 *   <li>16-bit：每样本 2 字节</li>
 *   <li>24-bit：每样本 3 字节（低字节在前）</li>
 * </ul>
 * 这里把它逐样本降到 16-bit（右移 8 位，线性缩放），得到可播放的 PCM。
 */
public final class FlacPcmStream extends InputStream {

	private final java.io.InputStream raw;
	private final FLACDecoder decoder;
	private final int channels;
	private final int bitsPerSample;
	private final int bytesPerSample;

	/** 降位后的输出格式（16-bit）。 */
	private final AudioFormat outFormat;

	private ByteData pcm;

	private byte[] outBuf;
	private int outPos;
	private int outLen;
	private boolean eof;

	/** 读多少帧解一次（太小会频繁调用解码器，太大会占内存）。 */
	private static final int FRAMES_PER_FILL = 2048;

	private FlacPcmStream(InputStream source, FLACDecoder decoder, StreamInfo si) {
		this.raw = source;
		this.decoder = decoder;
		this.channels = Math.max(1, si.getChannels());
		this.bitsPerSample = si.getBitsPerSample();
		this.bytesPerSample = Math.max(2, (bitsPerSample + 7) / 8);
		this.outFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
				si.getSampleRate(), 16, channels, channels * 2, si.getSampleRate(), false);
		this.pcm = new ByteData(FRAMES_PER_FILL * channels * 4);
		this.outBuf = new byte[FRAMES_PER_FILL * channels * 2];
	}

	/**
	 * 打开一个 FLAC 流。
	 *
	 * @return 解码流；如果这个流不是 FLAC 则返回 null（让调用方回退到别的解码器）
	 */
	public static AudioInputStream open(InputStream raw) {
		try {
			FLACDecoder decoder = new FLACDecoder(raw);
			Metadata[] meta = decoder.readMetadata();
			StreamInfo si = decoder.getStreamInfo();
			if (si == null) return null;
			YueMusic.LOGGER.info("[YueMusic] FLAC 解码器就绪：{}Hz / {}ch / {}-bit，元数据块 {} 个",
					si.getSampleRate(), si.getChannels(), si.getBitsPerSample(),
					meta == null ? 0 : meta.length);
			FlacPcmStream stream = new FlacPcmStream(raw, decoder, si);
			return new AudioInputStream(stream, stream.outFormat, AudioSystem_NOT_SPECIFIED);
		} catch (Throwable e) {
			YueMusic.LOGGER.warn("[YueMusic] 不是 FLAC 或解码器初始化失败: {}", e.toString());
			return null;
		}
	}

	/** AudioSystem.NOT_SPECIFIED，避免额外 import。 */
	private static final long AudioSystem_NOT_SPECIFIED = javax.sound.sampled.AudioSystem.NOT_SPECIFIED;

	@Override
	public int read() throws IOException {
		if (!fill()) return -1;
		return outBuf[outPos++] & 0xFF;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (len <= 0) return 0;
		int written = 0;
		while (written < len) {
			if (!fill()) return written > 0 ? written : -1;
			int chunk = Math.min(len - written, outLen - outPos);
			System.arraycopy(outBuf, outPos, b, off + written, chunk);
			outPos += chunk;
			written += chunk;
		}
		return written;
	}

	@Override
	public void close() throws IOException {
		try {
			raw.close();
		} catch (Throwable ignored) {
		}
	}

	/** 解下一帧并转成 16-bit。返回是否还有数据。 */
	private boolean fill() throws IOException {
		if (outPos < outLen) return true;
		if (eof) return false;

		int produced = 0;
		while (produced == 0) {
			Frame frame;
			try {
				frame = decoder.readNextFrame();
			} catch (Throwable t) {
				YueMusic.LOGGER.warn("[YueMusic] FLAC 读帧结束: {}", t.toString());
				eof = true;
				return false;
			}
			if (frame == null) {
				eof = true;
				return false;
			}
			try {
				pcm = decoder.decodeFrame(frame, pcm);
			} catch (Throwable t) {
				YueMusic.LOGGER.warn("[YueMusic] FLAC 解码帧失败: {}", t.toString());
				eof = true;
				return false;
			}
			int len = pcm.getLen();
			if (len <= 0) continue;
			produced = convert(pcm.getData(), len);
		}

		outPos = 0;
		outLen = produced;
		return true;
	}

	/**
	 * 把原始 PCM 字节降到 16-bit。
	 * jflac 输出的是**小端、位深对应**的字节（已实测确认）。
	 *
	 * @return 写进 outBuf 的字节数
	 */
	private int convert(byte[] src, int srcLen) {
		int samples = srcLen / bytesPerSample;
		// 确保输出缓冲够大
		if (outBuf.length < samples * 2) {
			outBuf = new byte[samples * 2];
		}
		int out = 0;
		for (int i = 0; i < samples; i++) {
			int p = i * bytesPerSample;
			int v;
			if (bytesPerSample == 2) {
				v = (short) ((src[p] & 0xFF) | (src[p + 1] << 8));
			} else if (bytesPerSample == 3) {
				v = ((src[p + 2] & 0xFF) << 16) | ((src[p + 1] & 0xFF) << 8) | (src[p] & 0xFF);
				if ((v & 0x800000) != 0) v -= 0x1000000;   // 符号扩展
				v >>= 8;                                    // 24 → 16
			} else {
				v = (src[p] & 0xFF) << 24 | (src[p + 1] & 0xFF) << 16
						| (src[p + 2] & 0xFF) << 8 | (src[p + 3] & 0xFF);
				v >>= 16;
			}
			outBuf[out++] = (byte) (v & 0xFF);
			outBuf[out++] = (byte) ((v >> 8) & 0xFF);
		}
		return out;
	}
}
