# _ref/e2e —— 端到端测试

这些是开发时用来验证行为的小程序（纯 Java，不依赖游戏启动器）。

## 目录里有什么

| 类别 | 例子 |
|---|---|
| 核心回归 | `E2E.java`、`LyricsTest.java`、`VerifyPcmTest.java` |
| 音频行为 | `OverlapSoundTest.java`（声音不叠加）、`PositionSanityTest.java`（进度合理性）|
| 队列/房间 | `RoomQueueKeepTest.java`、`PlayModeTest.java`、`QueueLimitTest.java` |
| 网络协议 | `QueueJsonLimitTest.java`（报文大小）、`GuiScaleScrollTest.java` |

## 怎么跑

1. **先构建 mod**（需要 JDK 25，见根目录 README）：

   ``bash
   ./gradlew build
   ``

2. **生成 classpath**（`classpath.txt` 是本地文件、不提交，因为含绝对路径）：

   ``bash
   bash _ref/e2e/make-classpath.sh
   ``

3. **编译并运行某个测试**：

   ```bash
   CP="$(cat _ref/e2e/classpath.txt)"
   javac -encoding UTF-8 -cp "$CP" -d _ref/e2e _ref/e2e/E2E.java
   java -Dstdout.encoding=UTF-8 -cp "_ref/e2e:$CP" E2E
   ```

   Windows PowerShell 用 `;` 分隔、`\` 路径。

## 注意

- 部分测试**会联网**（搜歌、解析直链、抓日志），跑之前注意。
- `classpath.txt` 里如果缺 jflac，见 `make-classpath.sh` 末尾的注释
  （jflac 是以 `include()` 打进 mod jar 的，单独测试要另外下载）。