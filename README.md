# Leaf

A minimal, fast document reader for Android. No toolbars you never use, no ads, no
network access, no third-party libraries at runtime — just your document, rendered as
quickly as the phone can draw it.

## What it opens

| Format | How it is rendered |
| --- | --- |
| PDF | Platform `PdfRenderer`, one bitmap per page, LRU cached, pinch to zoom |
| DOCX | White page with pictures at their real size, tables with shading and merged cells, paragraph alignment/indentation/spacing, numbered and bulleted lists, page breaks, and inline bold/italic/underline/colour/size |
| XLSX | Worksheet grid with column letters, row numbers, frozen headers, column widths, bold cells and right-aligned numbers |
| PPTX | Slides drawn as white canvases: pictures, background and shape fills, text position, alignment and sizes |
| ODT | The same white-page treatment: pictures, tables, lists, and the paragraph and text styles the file declares |
| RTF | Control-word parser to plain text |
| Markdown (`.md`) | Converted to HTML (headings, lists, code, emphasis, links) |
| TXT / CSV | Chunked into 6 KB rows so layout stays instant |
| Anything else | A message naming the supported formats — nothing is copied or parsed |

The reader opens exactly the formats above: `.pdf`, `.docx`, `.txt`, `.xlsx`, `.pptx`,
`.odt`, `.rtf`, `.md` and `.csv`. EPUB, MOBI, comic archives, web pages, images and
ordinary archives are refused with an explanation instead of a blank screen. The file
picker only offers those types, and any recents saved for formats Leaf used to read are
dropped the first time the home screen opens.

The first open copies the file into Leaf's cache; afterwards the copy is reused, so a
recent entry still works after a reboot, an app update or Android's permission cleanup
has taken the original provider grant away.

## Searching

Open **⋮ → Search**, type, and Leaf highlights the current match and scrolls it
into view. The counter shows `3/27`; `‹` and `›` walk the matches and wrap around. Works in
everything with real text — TXT, CSV, Markdown, RTF, DOCX, ODT, PPTX and spreadsheets. PDFs
have no text layer, so the menu entry is hidden there.

Text documents use Leaf's own matcher (`core/Finder.kt`, covered by the desktop tests); HTML
documents use the `WebView`'s native find, which needs no JavaScript.


## Why it is fast

- **Zero runtime dependencies.** No AndroidX, no Compose, no Kotlin coroutines runtime.
  The APK is a couple of hundred KB plus the Kotlin standard library.
- **Nothing blocks the UI thread.** Copying from the picker, sniffing, and parsing all
  happen on one background thread; only finished views are built on the main thread.
- **Bounded work per frame.** Text is chunked into 6 KB `TextView` rows, pages are
  rendered at view width into an LRU cache sized to `maxMemory / 6`.
- **One render thread.** Duplicate requests (fast flings recycle rows) are collapsed.
- **No JavaScript.** The `WebView` used for HTML-shaped formats runs with JS disabled.
- **No permissions.** There is no `INTERNET` permission; remote images in documents fail
  silently instead of calling home.

## Building it yourself

The build deliberately avoids Gradle. It needs a JRE 17, the Android build tools and
platform jar, and the Kotlin compiler, and produces the APK with
`aapt2` → `kotlinc` → `d8` → `zipalign` → `apksigner`.

One-time toolchain setup (about 300 MB, no root, no `sdkmanager`). Put it anywhere and
point `LEAF_TOOLCHAIN` at it (default `~/.cache/leaf-toolchain`):

```bash
TC=$HOME/.cache/leaf-toolchain && mkdir -p $TC && cd $TC

# JRE 17 (has keytool; a full JDK is not needed)
curl -L -o jre.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse"
tar xzf jre.tar.gz

# Android build-tools 34 and platform 34, straight from Google
curl -L -o bt.zip "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
unzip -q bt.zip -d btraw && rm bt.zip
mkdir -p sdk/build-tools sdk/platforms
mv "$(dirname "$(find btraw -name aapt2 -type f | head -1)")" sdk/build-tools/34.0.0

curl -L -o platform.zip "https://dl.google.com/android/repository/platform-34-ext7_r01.zip"
unzip -q platform.zip -d praw && rm platform.zip
mv "$(dirname "$(find praw -name android.jar -type f | head -1)")" sdk/platforms/android-34

# Kotlin compiler
curl -L -o kotlin.zip "https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip"
unzip -q kotlin.zip && rm kotlin.zip
```

Then:

```bash
./test.sh     # parser tests on the desktop JVM
./build.sh    # -> dist/Leaf-1.5.3.apk
```

`test.sh` needs `tests/lib/kxml2-2.3.0.jar` (43 KB) to stand in for Android's
Expat-backed `XmlPullParser`:

```bash
mkdir -p tests/lib
curl -L -o tests/lib/kxml2-2.3.0.jar \
  https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
```

`build.sh` generates `leaf-release.keystore` on first run (alias `leaf`, password
`leaf-leaf-leaf`) with `keytool`, or with `openssl` if the JDK's `keytool` is missing.
Keep that file: Android only allows updating an installed app with an APK signed by the
same key.

## Installing on the phone

**From Releases**

Download the latest `Leaf-1.5.3.apk` from the
[Releases page](https://github.com/Herbertbuilds/leaf/releases/latest) on the phone and
open it. The steps below apply either way.

**Sideload from your own build (no computer needed)**

1. Copy `dist/Leaf-1.5.3.apk` to the phone (USB, cloud drive, `python3 -m http.server`, …).
2. Open it from the Files app.
3. When Android says unknown apps are blocked, tap **Settings** and allow installs for
   the app you opened it from (Files, Chrome, …).
4. Tap **Install**. Play Protect may warn about an unknown developer — that is expected
   for a self-signed personal build; choose **Install anyway**.

**Over USB with adb**

```bash
adb install -r dist/Leaf-1.5.3.apk
```

`adb` ships in Android's platform-tools, not in this repo's toolchain — install it with
your distro package (`sudo pacman -S android-tools`) or download
`https://dl.google.com/android/repository/platform-tools-latest-linux.zip`.
Enable **Developer options → USB debugging** on the phone first, and accept the RSA
fingerprint prompt.

Leaf also registers as a viewer for the document types it reads, so it appears in the
"Open with" sheet for a PDF, a Word file, a spreadsheet, slides, ODT, RTF, plain text,
Markdown or CSV. EPUBs, videos, images, APKs and archives no longer offer Leaf; if one is
opened through an older shortcut anyway, it gets a clear message rather than a blank
screen.

## Layout

```
AndroidManifest.xml          no permissions, two activities
res/                         adaptive launcher icon only
src/io/herbert/leaf/
  core/                      pure Kotlin: magic bytes, chunking, Markdown, RTF, PalmDOC, search
  format/                    DOCX, XLSX, PPTX, ODT parsers (EPUB/MOBI parsers remain,
                             tested but no longer reachable from the reader)
  reader/                    page pager, text pager, HTML reader, page cache
  MainActivity.kt            home: picker + recents
  ReaderActivity.kt          reader chrome, search bar, menu, background loading
tests/Smoke.kt               desktop checks for the pure helpers
tests/Formats.kt             builds real EPUB/DOCX/XLSX/ODT/MOBI files and parses them
tests/stub/                  desktop XmlPullParser stand-in (kxml2)
```

## Known limits

- DOCX/ODT: floating text boxes keep their text but lose their position; SmartArt, charts,
  equations, footnotes and headers/footers are not drawn. Headers and footers are skipped
  on purpose so the page is the text body.
- PPTX: charts, tables, SmartArt, theme colour inheritance, gradients and shadows are not
  rendered. Text in ungrouped shapes without an explicit position, and shapes inside
  nested groups deeper than one level, may not appear. Most business slides work fine;
  highly designed decks may differ from the original.
- XLSX shows cell values, not formulas or number formats: a date cell appears as the
  serial number Excel stores, and cell colours/borders from the file are not painted.
  Column widths and bold cells are honoured.
- PDF pages cannot be searched; only formats with a text layer can.
- Only the first 12 MB of a plain-text file is loaded.
- Formats outside the supported list (EPUB, MOBI, CBZ, HTML, images, archives) are refused
  by design. Renaming one to a supported extension does not make it readable; the bytes are
  checked too, and the file is reported as damaged or renamed.

## How it was built

I built Leaf. The idea, the design, and the decisions about what it should and should not
do are mine — which formats it opens, how it looks, and where the line sits between a
reader and a document suite. The code and the logic were written with
[OpenCode](https://opencode.ai) assisting: it drafted, debugged and typed under my
direction, and I reviewed every change before it went in.
