#!/usr/bin/env bash
# Compila l'APK di debug su Linux senza Gradle (kotlinc + aapt + d8 + zipalign + apksigner).
# Equivalente di build.ps1; usato anche da GitHub Actions (.github/workflows/release.yml).
#
# Variabili d'ambiente:
#   ANDROID_HOME  Android SDK con platforms/android-34 e build-tools/$BUILD_TOOLS
#   KOTLIN_HOME   compilatore Kotlin (cartella che contiene lib/kotlin-compiler.jar)
#   BUILD_TOOLS   versione dei build-tools (predefinita 37.0.0)
#   KEYSTORE      keystore di firma (predefinito debug.keystore). NON viene mai creato:
#                 con una chiave diversa l'aggiornamento non si installa sui telefoni.
#   OUT_APK       nome dell'APK prodotto (predefinito CensimentoMezzi-debug.apk)
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_HOME:?ANDROID_HOME non impostata}"
: "${KOTLIN_HOME:?KOTLIN_HOME non impostata}"
BUILD_TOOLS=${BUILD_TOOLS:-37.0.0}
KEYSTORE=${KEYSTORE:-debug.keystore}
OUT_APK=${OUT_APK:-CensimentoMezzi-debug.apk}

JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAR=${JAVA_HOME:+$JAVA_HOME/bin/}jar
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
BT=$ANDROID_HOME/build-tools/$BUILD_TOOLS
KOTLIN_JAR=$KOTLIN_HOME/lib/kotlin-compiler.jar
STDLIB=$KOTLIN_HOME/lib/kotlin-stdlib.jar
SRC=app/src/main
OUT=build

for f in "$ANDROID_JAR" "$BT/aapt" "$BT/zipalign" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$KOTLIN_JAR" "$STDLIB"; do
  [ -e "$f" ] || { echo "Strumento mancante: $f" >&2; exit 1; }
done
[ -f "$KEYSTORE" ] || { echo "Keystore $KEYSTORE non trovato: serve la chiave originale" >&2; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
echo "1/5 Risorse (aapt)"
"$BT/aapt" package -f -0 arsc -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -I "$ANDROID_JAR" -F "$OUT/app.unaligned.apk"

echo "2/5 Compilazione Kotlin"
mapfile -t SOURCES < <(find "$SRC/java" -name '*.kt')
"$JAVA" -cp "$KOTLIN_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -kotlin-home "$KOTLIN_HOME" -no-reflect -cp "$ANDROID_JAR" -jvm-target 1.8 \
  -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -Xno-param-assertions -Xno-call-assertions -nowarn \
  -d "$OUT/classes" "${SOURCES[@]}"
test -f "$OUT/classes/it/impresasangalli/censimentomezzi/MainActivity.class"

echo "3/5 DEX (d8)"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB"
(cd "$OUT/dex" && "$BT/aapt" add ../app.unaligned.apk classes.dex >/dev/null)

echo "4/5 Allineamento"
"$BT/zipalign" -p -f 4 "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"

echo "5/5 Firma"
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT_APK" "$OUT/app.aligned.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT_APK"
ls -la "$OUT_APK"
