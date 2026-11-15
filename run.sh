#!/data/data/com.termux/files/usr/bin/bash
# Build the release APK, install it with root, and launch it.
set -e
cd "$(dirname "$0")"
export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk
gradle assembleRelease --console=plain -q 2>&1 | grep -E '^e:|What went wrong' -A6 || true
cp app/build/outputs/apk/release/app-release.apk "$TMPDIR/bloom.apk"
/system/bin/su -c "cp $TMPDIR/bloom.apk /data/local/tmp/bloom.apk && pm install -r /data/local/tmp/bloom.apk && rm /data/local/tmp/bloom.apk && cmd package compile -m speed-profile -f app.bloom >/dev/null && logcat -c && am start -n app.bloom/.MainActivity"
