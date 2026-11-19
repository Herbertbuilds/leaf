#!/usr/bin/env bash
# Runs parser tests on the desktop JVM (no device, no Android SDK, no Gradle).
#   * smoke.kt  - pure helpers: magic bytes, chunking, Markdown, RTF, PalmDOC
#   * Formats.kt - builds real EPUB/DOCX/XLSX/ODT/MOBI containers and parses them
# kxml2 stands in for Android's Expat-backed XmlPullParser.
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TC="${LEAF_TOOLCHAIN:-$HOME/.cache/leaf-toolchain}"
JAVA_DIR="$(ls -d "$TC"/jdk-* 2>/dev/null | head -1 || true)"
KOTLINC="$TC/kotlinc/bin/kotlinc"
STDLIB="$TC/kotlinc/lib/kotlin-stdlib.jar"
KXML="$PROJ/tests/lib/kxml2-2.3.0.jar"

if [ -z "$JAVA_DIR" ] || [ ! -x "$KOTLINC" ]; then
  echo "Toolchain missing. See README.md -> 'Building it yourself'." >&2
  exit 1
fi
if [ ! -f "$KXML" ]; then
  echo "Missing $KXML" >&2
  echo "Fetch it with:" >&2
  echo "  curl -L -o '$KXML' https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar" >&2
  exit 1
fi

export JAVA_HOME="$JAVA_DIR"
export PATH="$JAVA_DIR/bin:$PATH"

mkdir -p "$PROJ/build/test"
"$KOTLINC" \
  "$PROJ/tests/Smoke.kt" "$PROJ/tests/Formats.kt" "$PROJ/tests/stub" \
  "$PROJ/src/io/herbert/leaf/core" "$PROJ/src/io/herbert/leaf/format" "$PROJ/src/io/herbert/leaf/Sniff.kt" \
  -classpath "$KXML" -nowarn -d "$PROJ/build/test/smoke.jar"

RUN_CP="$PROJ/build/test/smoke.jar:$KXML:$STDLIB"
"$JAVA_DIR/bin/java" -cp "$RUN_CP" SmokeKt
echo
"$JAVA_DIR/bin/java" -cp "$RUN_CP" FormatsKt
