#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PROPS="$PROJECT_DIR/keystore.properties"
CLASSES_JAR="$PROJECT_DIR/app/build/intermediates/runtime_app_classes_jar/release/bundleReleaseClassesToRuntimeJar/classes.jar"
OUT_DIR="$PROJECT_DIR/app/build/outputs/plugin"
OUT="$OUT_DIR/CloudMusic-v1.0.5.ppmusic"

property() {
  sed -n "s/^$1=//p" "$PROPS" | tail -n 1
}

if [ ! -f "$PROPS" ]; then
  echo "Missing keystore.properties; a music pack must use the trusted release signer." >&2
  exit 1
fi

cd "$PROJECT_DIR"
./gradlew :app:clean :app:testReleaseUnitTest :app:bundleReleaseClassesToRuntimeJar

# 功能包只能调用跑跑桌面正式版保留下来的共享库类；清单不全时正式版桌面会直接崩
HOST_RULES="${CARHOME_HOST_RULES:-$HOME/project/CarHome/carhome-app/app/proguard-rules.pro}"
if [ -f "$HOST_RULES" ]; then
  python3 "$PROJECT_DIR/tools/check-host-abi.py" --rules "$HOST_RULES" "$CLASSES_JAR"
else
  echo "Warning: $HOST_RULES not found; skipped host ABI check." >&2
fi

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/cloudmusic-plugin.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

SDK_DIR=$(sed -n 's/^sdk.dir=//p' "$PROJECT_DIR/local.properties" | tail -n 1)
D8=$(find "$SDK_DIR/build-tools" -maxdepth 2 -type f -name d8 | sort -V | tail -n 1)
if [ ! -x "$D8" ]; then
  echo "Android d8 was not found under $SDK_DIR/build-tools." >&2
  exit 1
fi
mkdir "$TEMP_DIR/dex"
"$D8" \
  --min-api 31 \
  --lib "$SDK_DIR/platforms/android-36/android.jar" \
  --output "$TEMP_DIR/dex" \
  "$CLASSES_JAR"
mv "$TEMP_DIR/dex/classes.dex" "$TEMP_DIR/classes.dex"
cp "$PROJECT_DIR/app/src/main/res/drawable-nodpi/ic_cloudmusic_app.png" "$TEMP_DIR/icon.png"
cp "$PROJECT_DIR/plugin/plugin.json" "$TEMP_DIR/plugin.json"

mkdir -p "$OUT_DIR"
rm -f "$OUT"
(cd "$TEMP_DIR" && jar --create --file "$OUT" plugin.json classes.dex icon.png)

STORE_FILE=$(property storeFile)
STORE_PASSWORD=$(property storePassword)
KEY_ALIAS=$(property keyAlias)
KEY_PASSWORD=$(property keyPassword)
case "$STORE_FILE" in
  /*) ;;
  *) STORE_FILE="$PROJECT_DIR/$STORE_FILE" ;;
esac

jarsigner \
  -keystore "$STORE_FILE" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -sigalg SHA256withRSA \
  -digestalg SHA-256 \
  "$OUT" "$KEY_ALIAS"
# The release certificate is intentionally private/self-signed; authenticity is established by
# the certificate SHA-256 pin in Paopao Desktop, not by the public Web PKI.
jarsigner -verify "$OUT"

echo "$OUT"
shasum -a 256 "$OUT"
