#!/usr/bin/env bash
# 一键构建 Android APK（Linux / macOS / WSL / Git Bash）
#
# 产物：build/apk/光影相册.apk
# 已内置国内镜像加速（清华 / 阿里 / 中科大 + 腾讯 Maven & Gradle），
# 首次运行自动下载 Flutter SDK 与 Android SDK，约 20~40 分钟。
set -e
cd "$(dirname "$0")"

# ---------------------------------------------------------------- 镜像配置
# Flutter / Dart SDK（三选一，脚本会自动挑一个能连通的）
MIRROR_FLUTTER_LIST=(
  "https://mirrors.tuna.tsinghua.edu.cn/flutter"
  "https://mirrors.aliyun.com/flutter"
  "https://mirrors.ustc.edu.cn/flutter"
)
# Pub（Dart 包管理）
MIRROR_PUB_LIST=(
  "https://mirrors.tuna.tsinghua.edu.cn/dart-pub"
  "https://mirrors.aliyun.com/pub"
  "https://mirrors.ustc.edu.cn/dart-pub"
)
# Gradle 发行版 / Maven 构件 / Android SDK 组件
MIRROR_GRADLE="https://mirrors.cloud.tencent.com/gradle"
MIRROR_MAVEN="https://mirrors.cloud.tencent.com/nexus/repository/maven-public"
MIRROR_ANDROID_SDK="https://mirrors.cloud.tencent.com/AndroidSDK"

probe() {   # 探测镜像是否可用（返回 200/206 视为可用）
  local code
  code=$(curl -sL -o /dev/null -w "%{http_code}" --max-time 12 "$1" 2>/dev/null || echo 000)
  [ "$code" = "200" ] || [ "$code" = "206" ]
}

echo "==> 探测可用镜像"
FLUTTER_STORAGE_BASE_URL=""
for m in "${MIRROR_FLUTTER_LIST[@]}"; do
  if probe "$m/flutter_infra_release/releases/releases_linux.json"; then
    export FLUTTER_STORAGE_BASE_URL="$m"
    echo "  Flutter SDK 镜像: $m  ✅"
    break
  else
    echo "  Flutter SDK 镜像: $m  ❌ 不可用"
  fi
done

PUB_HOSTED_URL=""
for m in "${MIRROR_PUB_LIST[@]}"; do
  if probe "$m/api/packages/flutter"; then
    export PUB_HOSTED_URL="$m"
    echo "  Pub 镜像: $m  ✅"
    break
  fi
done
[ -n "$PUB_HOSTED_URL" ] || echo "  ⚠️ Pub 镜像均未连通，将使用官方源（可能较慢）"

export GRADLE_DISTRIBUTION_URL="$MIRROR_GRADLE"
export ANDROID_SDK_MIRROR="$MIRROR_ANDROID_SDK"
export MAVEN_MIRROR="$MIRROR_MAVEN"
echo "  Gradle 镜像: $MIRROR_GRADLE"
echo "  Maven 镜像: $MIRROR_MAVEN"
echo "  Android SDK 镜像: $MIRROR_ANDROID_SDK"

# ---------------------------------------------------------------- 环境自检
echo
echo "==> 环境自检"
python3 -c "import sys;sys.exit(0 if sys.version_info>=(3,10) else 1)" \
  || { echo "❌ 需要 Python 3.10+，当前：$(python3 -V 2>&1)"; exit 1; }
echo "  Python: $(python3 -V 2>&1) OK"

if command -v java >/dev/null 2>&1; then
  JV=$(java -version 2>&1 | head -1)
  echo "  Java: $JV"
  echo "$JV" | grep -qE '"(1[7-9]|[2-9][0-9])' \
    && echo "  JDK 版本满足要求（17+）" \
    || echo "  ⚠️ 建议 JDK 17+（当前偏低可能导致 Gradle 报错）"
else
  echo "  ❌ 未检测到 Java。请先安装 JDK 17："
  echo "     Ubuntu/Debian: sudo apt install openjdk-17-jdk"
  echo "     macOS:        brew install openjdk@17"
  echo "     Windows:      https://adoptium.net/"
  exit 1
fi

FREE=$(df -k . | awk 'NR==2{print int($4/1048576)}')
echo "  可用磁盘: ${FREE} GB"
[ "$FREE" -ge 8 ] || echo "  ⚠️ 建议预留 8GB 以上磁盘空间"

echo
echo "==> 安装 Python 依赖"
python3 -m pip install -r requirements.txt

echo
echo "==> 构建 APK（首次会下载 Flutter SDK / Android SDK，请耐心等待）"
flet build apk --yes \
  --project photobox \
  --product "光影相册" \
  --org "cn.photobox" \
  --bundle-id "cn.photobox.app" \
  --description "本地照片管理与清理工具" \
  --build-version "1.0.0" \
  --permissions photo_library \
  --android-permissions \
      android.permission.READ_MEDIA_IMAGES=true \
      android.permission.READ_MEDIA_VIDEO=true \
      android.permission.READ_MEDIA_VISUAL_USER_SELECTED=true \
      android.permission.READ_EXTERNAL_STORAGE=true \
      android.permission.INTERNET=true \
  --android-legacy-packaging \
  -o build

echo
echo "==> 完成"
ls -lh build/apk/*.apk 2>/dev/null || echo "未找到 APK，请检查上方日志"
