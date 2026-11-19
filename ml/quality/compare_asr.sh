#!/usr/bin/env bash
# Compare the speech models on the same audio: the voice clips Drift's quality log kept ("Keep voice clips" in
# Developer options) plus a bundled espeak clip. Installs the debug build next to the daily one, runs
# AsrModelComparisonTest (downloads any model the debug app lacks, ~410 MB on first run), pulls the results and prints
# them. The debug app is uninstalled afterwards unless KEEP_DEBUG=1.
#
#   ml/quality/compare_asr.sh            # all kept clips
#   LIMIT=10 ml/quality/compare_asr.sh   # the 10 newest clips
#
# Clips go to the debug app's internal storage via /data/local/tmp and run-as: files adb writes into the app's
# external folder belong to the shell user and the app cannot read them. The app is kept in the foreground and the
# screen awake during the run, because Vivo's freezer stops an instrumented process that has nothing on screen.
set -euo pipefail
cd "$(dirname "$0")/../.."
PKG=io.github.seshuthota.drift.debug
RELEASE_DIR=/sdcard/Android/data/io.github.seshuthota.drift/files/diagnostics/voice
STAGE=/data/local/tmp/asr-compare
OUT=ml/quality/data/asr-compare
mkdir -p "$OUT"

./gradlew -q :app:installDebug :app:installDebugAndroidTest
adb shell "rm -rf $STAGE && mkdir -p $STAGE && chmod 777 $STAGE"
clips=$(adb shell "ls -t $RELEASE_DIR/*.wav 2>/dev/null" | tr -d '\r' | head -n "${LIMIT:-1000}")
for clip in $clips; do adb shell "cp $clip $STAGE/ && chmod 644 $STAGE/$(basename "$clip")"; done
adb shell "run-as $PKG sh -c 'rm -rf files/asr-compare && mkdir -p files/asr-compare && cp $STAGE/*.wav files/asr-compare/ 2>/dev/null; true'"
echo "clips: $(echo "$clips" | grep -c . || true) kept + 1 bundled"
# Optional: MODELS_DIR=dir with one sub-folder per model id (e.g. moonshine-small-streaming-en/) holding the model's
# files, to skip the download.
if [ -n "${MODELS_DIR:-}" ]; then
  for dir in "$MODELS_DIR"/*/; do
    id=$(basename "$dir")
    adb shell "mkdir -p $STAGE/models/$id"
    for f in "$dir"*; do adb push "$f" "$STAGE/models/$id/" >/dev/null; done
    adb shell "chmod -R a+rX $STAGE/models"
    adb shell "run-as $PKG sh -c 'mkdir -p files/asr/$id && cp $STAGE/models/$id/* files/asr/$id/'"
    echo "model pushed: $id"
  done
fi

# Keep the process unfrozen: screen awake and the app's own window in front while the test runs.
( while true; do
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    sleep 30
  done ) &
waker=$!
trap 'kill $waker 2>/dev/null || true' EXIT
( sleep 3; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 ) &
adb shell am instrument -w -e class io.github.seshuthota.drift.ime.asr.AsrModelComparisonTest \
  $PKG.test/androidx.test.runner.AndroidJUnitRunner | tail -3
adb exec-out run-as $PKG cat files/asr-compare/results.json > "$OUT/results.json"
adb shell rm -rf $STAGE
[ "${KEEP_DEBUG:-0}" = 1 ] || { adb uninstall $PKG.test >/dev/null; adb uninstall $PKG >/dev/null; }
python3 ml/quality/compare_asr.py "$OUT/results.json"
