#!/usr/bin/env bash
# Compila l'APK di debug senza Gradle (kotlinc + aapt + dx + apksigner).
set -euo pipefail
cd "$(dirname "$0")"
TOOLS=${TOOLS:-/home/claude/tools}
KOTLINC=$TOOLS/kotlinc/bin/kotlinc
ANDROID_JAR=$TOOLS/android-34.jar                      # API 34 per la compilazione Kotlin
AAPT_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar   # risorse framework per aapt
BT=/usr/lib/android-sdk/build-tools/debian
STDLIB=$TOOLS/stdlib-clean.jar
SRC=app/src/main
OUT=build
export JAVA_TOOL_OPTIONS= _JAVA_OPTIONS=

rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex
echo "1/5 Risorse (aapt)"
aapt package -f -0 arsc -M $SRC/AndroidManifest.xml -S $SRC/res -I $AAPT_JAR -F $OUT/app.unaligned.apk
echo "2/5 Compilazione Kotlin"
$KOTLINC $(find $SRC/java -name '*.kt') -cp $ANDROID_JAR -jvm-target 1.8 \
  -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -Xno-param-assertions -Xno-call-assertions \
  -d $OUT/classes 2>&1 | grep -v "^warning" || true
test -f $OUT/classes/it/impresasangalli/censimentomezzi/MainActivity.class
echo "3/5 DEX"
$BT/dx --dex --min-sdk-version=26 --output=$OUT/dex/classes.dex $OUT/classes $STDLIB
(cd $OUT/dex && aapt add ../app.unaligned.apk classes.dex >/dev/null)
echo "4/5 Allineamento"
zipalign -p -f 4 $OUT/app.unaligned.apk $OUT/app.aligned.apk
echo "5/5 Firma debug"
[ -f debug.keystore ] || keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Impresa Sangalli,C=IT" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out CensimentoMezzi-debug.apk $OUT/app.aligned.apk
apksigner verify --verbose CensimentoMezzi-debug.apk | head -5
ls -la CensimentoMezzi-debug.apk
