#!/bin/bash
# =============================================================================
# audiosub — 빌드 환경 초기화 스크립트 (Termux)
# Stage 0-A: gradle-wrapper.jar 확보
# Stage 0-B: local.properties 자동 생성
# =============================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== audiosub 빌드 환경 설정 ==="
echo "프로젝트 경로: $PROJECT_DIR"
cd "$PROJECT_DIR"

# -----------------------------------------------------------------------
# 0-A: gradle-wrapper.jar 확보
# -----------------------------------------------------------------------
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
    echo "[OK] gradle-wrapper.jar 이미 존재합니다."
else
    echo "[...] gradle-wrapper.jar 없음 — 생성 시도"

    # 방법 1: gradle 명령 직접 사용 (pkg install gradle 필요)
    if command -v gradle &>/dev/null; then
        echo "  gradle 명령으로 wrapper 생성..."
        gradle wrapper --gradle-version 8.6 --distribution-type bin
        echo "[OK] gradle-wrapper.jar 생성 완료"
    else
        # 방법 2: 기존 Gradle 캐시에서 복사
        CACHE_JAR=$(find "$HOME/.gradle/wrapper/dists" \
            -name "gradle-wrapper.jar" 2>/dev/null | head -1)
        if [ -n "$CACHE_JAR" ]; then
            cp "$CACHE_JAR" "$WRAPPER_JAR"
            echo "[OK] 캐시에서 복사: $CACHE_JAR"
        else
            # 방법 3: curl로 직접 다운로드
            echo "  curl로 다운로드 중..."
            GRADLE_VER="8.6"
            WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VER}.0/gradle/wrapper/gradle-wrapper.jar"
            # GitHub 소스에서 wrapper jar를 직접 얻기는 어려우므로 배포판 사용
            # 아래 URL은 gradle.org의 공식 배포판 내 wrapper jar
            curl -L -o "$WRAPPER_JAR" \
                "https://services.gradle.org/distributions/gradle-${GRADLE_VER}-wrapper.jar" \
                2>/dev/null || true
            if [ -f "$WRAPPER_JAR" ] && [ -s "$WRAPPER_JAR" ]; then
                echo "[OK] 다운로드 완료"
            else
                rm -f "$WRAPPER_JAR"
                echo "[ERROR] gradle-wrapper.jar를 자동으로 확보하지 못했습니다."
                echo ""
                echo "  수동으로 해결하는 방법:"
                echo "  1) pkg install gradle  (Termux)"
                echo "     그 다음: gradle wrapper --gradle-version 8.6"
                echo ""
                echo "  2) 다른 Android 프로젝트의 gradle/wrapper/gradle-wrapper.jar 복사"
                exit 1
            fi
        fi
    fi
fi

# -----------------------------------------------------------------------
# 0-B: local.properties 자동 생성
# -----------------------------------------------------------------------
LOCAL_PROPS="local.properties"
ANDROID_HOME_CANDIDATES=(
    "$HOME/android-sdk"
    "$HOME/Android/Sdk"
    "/data/data/com.termux/files/home/android-sdk"
    "/opt/android-sdk"
    "$ANDROID_HOME"
    "$ANDROID_SDK_ROOT"
)

SDK_DIR=""
for candidate in "${ANDROID_HOME_CANDIDATES[@]}"; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        SDK_DIR="$candidate"
        break
    fi
done

if [ -n "$SDK_DIR" ]; then
    echo "sdk.dir=$SDK_DIR" > "$LOCAL_PROPS"
    echo "[OK] local.properties 생성: sdk.dir=$SDK_DIR"
else
    echo "[경고] Android SDK를 자동으로 찾지 못했습니다."
    echo "       local.properties 를 직접 편집하여 sdk.dir 을 설정하세요."
    echo "       예: sdk.dir=/data/data/com.termux/files/home/android-sdk"
    # 기본값으로 파일 생성 (편집 안내)
    if [ ! -f "$LOCAL_PROPS" ]; then
        echo "sdk.dir=/data/data/com.termux/files/home/android-sdk" > "$LOCAL_PROPS"
        echo "       임시 경로로 파일을 생성했습니다 — 실제 경로로 수정하세요."
    fi
fi

# -----------------------------------------------------------------------
# 0-C: gradlew 실행 권한 부여 + 빌드 테스트
# -----------------------------------------------------------------------
chmod +x gradlew
echo ""
echo "=== 설정 완료. 이제 빌드를 실행하세요: ==="
echo ""
echo "  cd $PROJECT_DIR"
echo "  ./gradlew test               # 단위 테스트 (JVM, 빠름)"
echo "  ./gradlew assembleDebug      # APK 빌드"
echo "  ./gradlew lint               # 린트 검사"
echo ""
echo "첫 빌드 시 Gradle 8.6 (~120MB) 자동 다운로드됩니다."
