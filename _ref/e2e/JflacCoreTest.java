import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.Metadata;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 直接用 jflac 核心 API 解码，看清 ByteData 的真实语义。
 *
 * <p>目的：判断 jflac 解码 24-bit FLAC 时，ByteData 里装的到底是
 * 16-bit 还是 24-bit PCM（这决定了我们要不要自己做位深转换）。
 */
public class JflacCoreTest {

	public static void main(String[] args) throws Exception {
		String path = args[0];
		System.out.println("文件: " + path);

		try (InputStream in = new BufferedInputStream(new FileInputStream(path), 64 * 1024)) {
			FLACDecoder decoder = new FLACDecoder(in);
			Metadata[] meta = decoder.readMetadata();
			StreamInfo si = decoder.getStreamInfo();
			System.out.println("\n=== StreamInfo ===");
			System.out.println("  采样率     = " + si.getSampleRate());
			System.out.println("  声道数     = " + si.getChannels());
			System.out.println("  位深       = " + si.getBitsPerSample());
			System.out.println("  总样本数   = " + si.getTotalSamples());
			System.out.println("  最大块大小 = " + si.getMaxBlockSize());
			System.out.println("  元数据块数 = " + meta.length);

			int bits = si.getBitsPerSample();
			int channels = si.getChannels();

			System.out.println("\n=== 解几帧看 ByteData ===");
			ByteData pcm = null;
			for (int i = 0; i < 3; i++) {
				Frame frame = decoder.readNextFrame();
				if (frame == null) {
					System.out.println("  第 " + (i + 1) + " 帧: null（结束）");
					break;
				}
				int blockSize = frame.header.blockSize;
				pcm = decoder.decodeFrame(frame, pcm);
				int len = pcm.getLen();
				long expect16 = (long) blockSize * channels * 2;
				long expect24 = (long) blockSize * channels * 3;
				System.out.printf("  第 %d 帧: blockSize=%d, ByteData.len=%d 字节%n",
						i + 1, blockSize, len);
				System.out.printf("      若 16-bit 应为 %d 字节 → %s%n", expect16,
						len == expect16 ? "★匹配" : "不匹配");
				System.out.printf("      若 24-bit 应为 %d 字节 → %s%n", expect24,
						len == expect24 ? "★匹配" : "不匹配");

				byte[] d = pcm.getData();
				StringBuilder sb = new StringBuilder();
				for (int k = 0; k < Math.min(24, len); k++) sb.append(String.format("%02X ", d[k]));
				System.out.println("      前 24 字节: " + sb);

				// 按不同位深解读前几个样本，看哪个幅度合理
				System.out.print("      按16bit小端解读: ");
				for (int k = 0; k + 1 < Math.min(8, len); k += 2) {
					short v = (short) ((d[k] & 0xFF) | (d[k + 1] << 8));
					System.out.print(v + " ");
				}
				System.out.println();
				if (len >= 24) {
					System.out.print("      按24bit小端解读: ");
					for (int k = 0; k + 2 < 9; k += 3) {
						int v = ((d[k + 2] & 0xFF) << 16) | ((d[k + 1] & 0xFF) << 8) | (d[k] & 0xFF);
						if ((v & 0x800000) != 0) v |= 0xFF000000;
						System.out.print(v + " ");
					}
					System.out.println();
				}
			}
			System.out.println("\n结论：位深=" + bits + "，看上面哪个 ★匹配 就知道 ByteData 的真实格式");
		}
	}
}
