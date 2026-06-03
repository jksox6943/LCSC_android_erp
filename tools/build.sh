#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

case "${1:-}" in
    debug)
        BUILD_TYPE="debug"
        GRADLE_TASK=":app:assembleDebug"
        APK_PATH="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        BUILD_TYPE="release"
        GRADLE_TASK=":app:assembleRelease"
        APK_PATH="${REPO_ROOT}/app/build/outputs/apk/release/app-release.apk"
        ;;
    *)
        echo "Usage: $0 {debug|release}" >&2
        exit 1
        ;;
esac

cd "${REPO_ROOT}"

echo "Building ${BUILD_TYPE} APK..."
./gradlew "${GRADLE_TASK}"

if [[ ! -f "${APK_PATH}" ]]; then
    echo "${BUILD_TYPE} build finished, but APK was not found: ${APK_PATH}" >&2
    exit 1
fi

echo
echo "${BUILD_TYPE} APK:"
ls -lh "${APK_PATH}"
