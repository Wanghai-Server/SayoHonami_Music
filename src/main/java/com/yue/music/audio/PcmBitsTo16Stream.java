package com.yue.music.audio;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把 24-bit PCM 流转成 16-bit PCM 流。
 *
 * <p><b>为什么需要它</b>：QQ音乐的无损（F000）FLAC 是 <b>24-bit</b> 的，
 * 而播放器的目标格式是 16-bit。jflac 的 {@code FormatConversionProvider}
 * <b>不支持 24→16 位深转换</b>，会抛
 * {@code IllegalArgumentException: Unsupported conversion ... 24 bit ... 16 bit}，
 * 结果就是「VIP 歌拿到了直链但放不出声」。
 *
 * <p>转换方式：把每个 24-bit 采样右移 8 位得到 16-bit（等价于 ÷256）。
 * 这是线性缩放，音质损失只有 8 位量化噪声，实际听感无损。
 *
 * <p>字节序按 little-endian 处理（Java Sound 的 PCM_SIGNED 默认小端）。
 */
final class PcmBitsTo16Stream extends InputStream {

	/** 源格式（24-bit）。 */
	private final AudioFormat source;
	private final InputStream in;
	private final int srcFrameSize;

	/** 一次读多少源帧（尽量凑成整数帧）。 */
	private static final int FRAMES_PER_READ = 2048;

	private final byte[] srcBuf;
	private final byte[] outBuf;
	private int outPos;
	private int outLen;
	private boolean eof;

	PcmBitsTo16Stream(InputStream in, AudioFormat source) {
		this.in = in;
		this.source = source;
		this.srcFrameSize = Math.max(1, source.getFrameSize());
		this.srcBuf = new byte[srcFrameSize * FRAMES_PER_READ];
		// 输出固定 16-bit：每帧 2 字节 × 声道数
		this.outBuf = new byte[2 * source.getChannels() * FRAMES_PER_READ];
	}

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
	public int available() throws IOException {
		int n = outLen - outPos;
		return n > 0 ? n : in.available();
	}

	@Override
	public void close() throws IOException {
		in.close();
	}

	/** 需要时把下一批源数据转成 16-bit 放进 outBuf。返回是否还有数据。 */
	private boolean fill() throws IOException {
		if (outPos < outLen) return true;
		if (eof) return false;

		int total = 0;
		while (total < srcBuf.length) {
			int n = in.read(srcBuf, total, srcBuf.length - total);
			if (n < 0) break;
			total += n;
		}
		if (total <= 0) {
			eof = true;
			return false;
		}

		int frames = total / srcFrameSize;
		if (frames <= 0) {
			// 残字节不足以构成一帧，丢弃（正常流不会发生）
			eof = true;
			return false;
		}

		int channels = Math.max(1, source.getChannels());
		boolean bigEndian = source.isBigEndian();
		int out = 0;
		for (int f = 0; f < frames; f++) {
			int base = f * srcFrameSize;
			for (int c = 0; c < channels; c++) {
				int p = base + c * 3;
				if (p + 2 >= total) break;
				int v24;
				if (bigEndian) {
					v24 = ((srcBuf[p] & 0xFF) << 16)
							| ((srcBuf[p + 1] & 0xFF) << 8)
							| (srcBuf[p + 2] & 0xFF);
				} else {
					v24 = ((srcBuf[p + 2] & 0xFF) << 16)
							| ((srcBuf[p + 1] & 0xFF) << 8)
							| (srcBuf[p] & 0xFF);
				}
				// 24 → 16：右移 8 位（含符号位，算术右移会自动保留正负）
				short v16 = (short) (v24 >> 8);
				// 输出统一小端
				outBuf[out++] = (byte) (v16 & 0xFF);
				outBuf[out++] = (byte) ((v16 >> 8) & 0xFF);
			}
		}

		outPos = 0;
		outLen = out;
		return out > 0;
	}
}
