#!/usr/bin/env bash
# ------------------------------------------------------------------
# 构建 e2e 测试用的 classpath（替代本地的 classpath.txt）
#
# 原来的 classpath.txt 里写死了作者的绝对路径，别人 clone 下来用不了。
# 这个脚本从 Gradle 缓存里自动找依赖，生成到 _ref/e2e/classpath.txt。
#
# 用法（在项目根目录）：
#   bash _ref/e2e/make-classpath.sh
# 然后：
#   javac -encoding UTF-8 -cp "$(cat _ref/e2e/classpath.txt)" \
#         -d _ref/e2e _ref/e2e/E2E.java
#   java -Dstdout.encoding=UTF-8 -cp "_ref/e2e:$(cat _ref/e2e/classpath.txt)" E2E
# ------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/_ref/e2e/classpath.txt"
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

# 在 Gradle 缓存里找某个 artifact 的 jar
find_jar() {
  local group="$1" name="$2" ver="$3"
  local base="$GRADLE_CACHE/$group/$name/$ver"
  if [ ! -d "$base" ]; then
    echo ""
    return
  fi
  find "$base" -name "$name-$ver.jar" -type f 2>/dev/null | head -n1
}

JARS=()

# 项目自己的 mod jar（先构建：./gradlew build）
MOD_JAR="$(find "$ROOT/build/libs" -name 'SayoHonami_Music-*.jar' ! -name '*-sources.jar' 2>/dev/null | head -n1)"
if [ -n "$MOD_JAR" ]; then
  JARS+=("$MOD_JAR")
else
  echo "⚠ 找不到 build/libs 里的 mod jar，请先运行 ./gradlew build" >&2
fi

# 测试需要的外部依赖
add() {
  local j
  j="$(find_jar "$1" "$2" "$3")"
  if [ -n "$j" ]; then
    JARS+=("$j")
  else
    echo "⚠ 找不到依赖 $2-$3（可能版本不同，请自行调整）" >&2
  fi
}

add "org.ow2.asm"                    "asm"             "9.10.1"
add "net.fabricmc"                   "fabric-loader"   "0.19.4"
add "com.google.code.gson"           "gson"            "2.13.2"
add "org.slf4j"                      "slf4j-api"       "2.0.17"
add "com.googlecode.soundlibs"       "jlayer"          "1.0.1.4"
add "com.googlecode.soundlibs"       "mp3spi"          "1.9.5.4"
add "com.googlecode.soundlibs"       "tritonus-share"  "0.3.7.4"
# jflac 通过 Loom 的 include() 打进 mod jar 里，但测试时需要单独的 jar：
#   下载 https://repo1.maven.org/maven2/org/jflac/jflac-codec/1.5.2/jflac-codec-1.5.2.jar
#   放到 _ref/e2e/libs/ 或者下面的路径
if [ -f "$ROOT/_ref/e2e/libs/jflac-codec-1.5.2.jar" ]; then
  JARS+=("$ROOT/_ref/e2e/libs/jflac-codec-1.5.2.jar")
fi

# 用 ';' 分隔（Windows）还是 ':'（Unix）？Java 本身在两个平台都认 ';' 在 Windows、':' 在 Unix
SEP=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
esac

printf "%s" "$(IFS=$SEP; echo "${JARS[*]}")" > "$OUT"
echo "✓ 已生成 $OUT"
echo "  包含 $(( ${#JARS[@]} )) 个 jar"
