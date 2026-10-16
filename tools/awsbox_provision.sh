#!/usr/bin/env bash
# Idempotent build-toolchain setup for living-world on awsbox (Ubuntu, user `ubuntu`).
# Run via: python3 tools/lw_remote.py --push --run 'bash ~/living-world/tools/awsbox_provision.sh'
# Installs: OpenJDK 17, Android cmdline-tools + platform 34 / build-tools 34.0.0, Gradle 8.8 (only to generate the wrapper).
# Every download is SHA-256 checked. Prints a summary ending in END.
set -euo pipefail

SDK="$HOME/android-sdk"
CMDLINE_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_SHA="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"  # developer.android.com/studio, 2026-10-16
GRADLE_VER="8.8"
OPT="$HOME/opt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

free_gb=$(df -BG --output=avail "$HOME" | tail -1 | tr -dc 0-9)
if [ "$free_gb" -lt 8 ]; then echo "ABORT: only ${free_gb}G free"; echo END; exit 1; fi

# 1. JDK 17
if ! /usr/lib/jvm/java-17-openjdk-amd64/bin/java -version >/dev/null 2>&1; then
  sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless unzip >/dev/null
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

# 2. Android SDK
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o "$WORK/$CMDLINE_ZIP" "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  echo "$CMDLINE_SHA  $WORK/$CMDLINE_ZIP" | sha256sum -c --quiet
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$WORK/$CMDLINE_ZIP" -d "$WORK/ct"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$WORK/ct/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null

# 3. Gradle distribution (wrapper generation only; builds use ./gradlew)
if [ ! -x "$OPT/gradle-$GRADLE_VER/bin/gradle" ]; then
  base="https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  curl -fsSL -o "$WORK/g.zip" "$base"
  echo "$(curl -fsSL "$base.sha256")  $WORK/g.zip" | sha256sum -c --quiet
  mkdir -p "$OPT" && unzip -q "$WORK/g.zip" -d "$OPT"
fi

# 4. Project-local settings
cd "$HOME/living-world"
printf 'sdk.dir=%s\n' "$SDK" > local.properties
if [ ! -f gradlew ]; then
  # Generate in an empty scratch project: `gradle wrapper` configures the build it runs in.
  mkdir -p "$WORK/wrap" && touch "$WORK/wrap/settings.gradle.kts"
  (cd "$WORK/wrap" && "$OPT/gradle-$GRADLE_VER/bin/gradle" wrapper --gradle-version "$GRADLE_VER" --distribution-type bin -q)
  cp -r "$WORK/wrap/gradlew" "$WORK/wrap/gradlew.bat" "$WORK/wrap/gradle" .
fi
want=$(curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VER-wrapper.jar.sha256")
got=$(sha256sum gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)

echo "java: $(java -version 2>&1 | head -1)"
echo "sdk: $(ls "$SDK/platforms" | tr '\n' ' ') | build-tools: $(ls "$SDK/build-tools" | tr '\n' ' ')"
echo "gradle dist: $GRADLE_VER | wrapper jar sha ok: $([ "$want" = "$got" ] && echo yes || echo NO)"
echo "free: $(df -h --output=avail "$HOME" | tail -1)"
echo END
