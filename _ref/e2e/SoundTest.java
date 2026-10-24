import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;

/** 测试新的 Java Sound 播放路径：能不能拿到音频设备、能不能真的写数据进去。 */
public class SoundTest {

	public static void main(String[] args) throws Exception {
		// 1) 列出系统音频设备
		System.out.println("=== 系统 Mixer 列表 ===");
		Mixer.Info[] mixers = AudioSystem.getMixerInfo();
		if (mixers.length == 0) System.out.println("  (没有任何 mixer!)");
		for (Mixer.Info mi : mixers) {
			System.out.println("  " + mi.getName() + " | " + mi.getDescription() + " | vendor=" + mi.getVendor());
		}

		AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		System.out.println("\n=== 是否支持 44100/16/2 ===");
		System.out.println("  AudioSystem.isLineSupported = " + AudioSystem.isLineSupported(info));

		SourceDataLine line = null;
		try {
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format, 44100 * 4 / 2);
			System.out.println("  打开成功! bufferSize=" + line.getBufferSize()
					+ " 音量控件=" + line.isControlSupported(FloatControl.Type.MASTER_GAIN));
			if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl g = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
				System.out.println("  音量范围: " + g.getMinimum() + " ~ " + g.getMaximum() + " dB");
				g.setValue(-6f);
				System.out.println("  设置 -6dB 成功, 当前=" + g.getValue());
			}
			line.start();

			// 2) 真的播一段：解码真实 mp3 的前几秒写进去
			String mp3 = args.length > 0 ? args[0]
					: "K:\\AI\\Deepseek\\mc-music-mod\\_ref\\test_ncm.mp3";
			if (Files.exists(Path.of(mp3))) {
				try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(mp3));
					 AudioInputStream pcm = AudioSystem.getAudioInputStream(format, in)) {
					byte[] buf = new byte[64 * 1024];
					long total = 0;
					int n, writes = 0;
					long t0 = System.currentTimeMillis();
					// 只播 6 秒左右，够验证链路
					while ((n = pcm.read(buf)) > 0 && total < 44100 * 4 * 6) {
						line.write(buf, 0, n);
						total += n;
						writes++;
					}
					long ms = System.currentTimeMillis() - t0;
					System.out.println("\n=== 写入声卡 ===");
					System.out.println("  写了 " + total + " 字节（约 " + (total / (44100.0 * 4)) + " 秒音频），"
							+ writes + " 次，耗时 " + ms + "ms");
					System.out.println("  getLongFramePosition = " + line.getLongFramePosition());
					System.out.println("  推算播放秒数 = " + (line.getLongFramePosition() / 44100L));
					System.out.println("  [PASS] Java Sound 播放链路可用");
				}
			} else {
				System.out.println("  跳过音频文件测试（找不到 " + mp3 + "）");
			}
		} catch (LineUnavailableException e) {
			System.out.println("  [FAIL] 打不开音频设备: " + e);
			System.out.println("  （如果是在没有声卡的环境里运行，这属于预期；游戏里会有真实设备）");
		} finally {
			if (line != null) {
				try {
					line.drain();
					line.stop();
					line.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
}
