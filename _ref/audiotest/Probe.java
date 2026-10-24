import javax.sound.sampled.*;
import java.io.*;

public class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("AudioSystem 支持的源格式:");
        for (AudioFileFormat.Type t : AudioSystem.getAudioFileTypes()) System.out.println("  " + t);
        if (args.length == 0) return;
        File f = new File(args[0]);
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            AudioFormat src = in.getFormat();
            System.out.println("解码成功! 源格式: " + src);
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                byte[] buf = new byte[65536];
                long total = 0; int n;
                while ((n = pcm.read(buf)) > 0) total += n;
                System.out.println("PCM 总字节: " + total + "  约 " + (total / (44100.0*4)) + " 秒");
            }
        }
    }
}