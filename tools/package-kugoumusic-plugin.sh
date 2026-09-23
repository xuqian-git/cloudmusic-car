#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PROPS="$PROJECT_DIR/keystore.properties"
CLASSES_JAR="$PROJECT_DIR/kugoumusic/build/intermediates/runtime_app_classes_jar/release/bundleReleaseClassesToRuntimeJar/classes.jar"
OUT_DIR="$PROJECT_DIR/kugoumusic/build/outputs/plugin"
OUT="$OUT_DIR/KuGouMusic-v1.0.0.ppmusic"

property() {
  sed -n "s/^$1=//p" "$PROPS" | tail -n 1
}

if [ ! -f "$PROPS" ]; then
  echo "Missing keystore.properties; a music pack must use the trusted release signer." >&2
  exit 1
fi

cd "$PROJECT_DIR"
./gradlew :kugoumusic:clean :kugoumusic:testReleaseUnitTest :kugoumusic:bundleReleaseClassesToRuntimeJar

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kugoumusic-plugin.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

SDK_DIR=$(sed -n 's/^sdk.dir=//p' "$PROJECT_DIR/local.properties" | tail -n 1)
D8=$(find "$SDK_DIR/build-tools" -maxdepth 2 -type f -name d8 | sort -V | tail -n 1)
if [ ! -x "$D8" ]; then
  echo "Android d8 was not found under $SDK_DIR/build-tools." >&2
  exit 1
fi
mkdir "$TEMP_DIR/dex"
"$D8" --min-api 31 --lib "$SDK_DIR/platforms/android-36/android.jar" --output "$TEMP_DIR/dex" "$CLASSES_JAR"
mv "$TEMP_DIR/dex/classes.dex" "$TEMP_DIR/classes.dex"
cp "$PROJECT_DIR/kugoumusic/src/main/res/drawable-nodpi/ic_kugoumusic_app.png" "$TEMP_DIR/icon.png"
cp "$PROJECT_DIR/kugoumusic/plugin.json" "$TEMP_DIR/plugin.json"

mkdir -p "$OUT_DIR"
(cd "$TEMP_DIR" && jar --create --file "$OUT" plugin.json classes.dex icon.png)

STORE_FILE=$(property storeFile)
STORE_PASSWORD=$(property storePassword)
KEY_ALIAS=$(property keyAlias)
KEY_PASSWORD=$(property keyPassword)
case "$STORE_FILE" in
  /*) ;;
  *) STORE_FILE="$PROJECT_DIR/$STORE_FILE" ;;
esac

jarsigner -keystore "$STORE_FILE" -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUT" "$KEY_ALIAS"
jarsigner -verify "$OUT"

echo "$OUT"
shasum -a 256 "$OUT"
