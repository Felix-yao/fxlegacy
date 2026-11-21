#!/usr/bin/env bash
# Build the FX遗产 APK using aapt2 + javac + d8 + apksig (no Gradle required).
# Usage:  bash build.sh
# Output: apk/FX遗产.apk
set -e
cd "$(dirname "$0")"

AAPT2="tools/aapt2_extract/aapt2.exe"
ANDROID_JAR="tools/android_sdk/android-35/android.jar"
R8="tools/r8.jar"
APKSIG="tools/apksig.jar"

# ---- locate JDK (javac / keytool) ----
# Prefer a real JDK under Program Files; PATH may only contain the Oracle
# javapath stubs, whose keytool cannot generate a keystore.
JAVAC="$(find /c/Program\ Files/Java /c/Program\ Files\ \(x86\)/Java -path '*/bin/javac.exe' 2>/dev/null | head -1)"
if [ -z "$JAVAC" ] && [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
fi
if [ -z "$JAVAC" ] && command -v javac >/dev/null 2>&1; then
  JAVAC="$(command -v javac)"
fi
[ -n "$JAVAC" ] || { echo "ERROR: JDK not found. Install JDK 17+ or set JAVA_HOME."; exit 1; }
KEYTOOL="$(dirname "$JAVAC")/keytool"

# ---- signing keystore: never commit it; generate on first build ----
JKS="r1app.jks"
STOREPASS="fxlegacy"
ALIAS="fxlegacy"
if [ ! -f "$JKS" ]; then
  echo "[0/7] generating signing keystore ($JKS)..."
  "$KEYTOOL" -genkeypair -v -keystore "$JKS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=FXLegacy, OU=Personal, O=FXLegacy, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi

# native Windows exe needs Windows-style paths
case "$JAVAC" in
  *.exe) w() { cygpath -w "$1"; } ;;
  *)     w() { printf '%s' "$1"; } ;;   # non-Windows: plain paths
esac

rm -rf build gen 2>/dev/null || true
mkdir -p build/classes build/dex gen/com/r1/controller apk

echo "[1/7] compile resources..."
"$AAPT2" compile --dir "$(w res)" -o "$(w build/res.zip)"

echo "[2/7] link resources + manifest (generates R.java)..."
"$AAPT2" link -o "$(w build/r1-unsigned.apk)" \
  -I "$(w "$ANDROID_JAR")" \
  --manifest "$(w AndroidManifest.xml)" \
  --java "$(w gen)" \
  --min-sdk-version 21 \
  --target-sdk-version 35 \
  "$(w build/res.zip)"

echo "[3/7] compile Java..."
find src gen -name "*.java" > build/sources.txt
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d build/classes @build/sources.txt 2>&1 | grep -v "bootstrap class path" || true

echo "[4/7] dex..."
CLASS_FILES=$(find build/classes -name "*.class")
java -cp "$R8" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" --min-api 21 \
  --output build/dex $CLASS_FILES

echo "[5/7] add classes.dex into APK..."
cp build/dex/classes.dex build/classes.dex
python - <<'PY'
import zipfile, os
apk = os.path.join("build", "r1-unsigned.apk")
dex = os.path.join("build", "classes.dex")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("  classes.dex added, apk size:", os.path.getsize(apk))
PY

echo "[6/7] sign APK (v1+v2+v3 with apksig - MIUI needs v2)..."
"$JAVAC" -cp "$APKSIG" -d build sign_apk.java >/dev/null 2>&1
java -cp "build;$APKSIG" sign_apk \
  build/r1-unsigned.apk build/signed.apk "$JKS" "$STOREPASS" "$ALIAS" "$STOREPASS"

cp build/signed.apk "apk/FX遗产.apk"

echo ""
echo "=== DONE ==="
ls -la "apk/FX遗产.apk"
