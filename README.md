<img src="https://capsule-render.vercel.app/api?type=waving&height=180&color=0:00ff9c,100:0d1117&text=Leaf&fontColor=0d1117&fontSize=54&fontAlign=50&fontAlignY=38&desc=A%20minimal%2C%20fast%20document%20reader%20for%20Android&descSize=16&descAlignY=60" width="100%" alt="Leaf" />

<div align="center">

![Android](https://img.shields.io/badge/Android-0d1117?style=for-the-badge&logo=android&logoColor=00ff9c)
![Kotlin](https://img.shields.io/badge/Kotlin-0d1117?style=for-the-badge&logo=kotlin&logoColor=00ff9c)
![Dependencies](https://img.shields.io/badge/runtime_deps-0-00ff9c?style=for-the-badge&labelColor=0d1117)
![Permissions](https://img.shields.io/badge/permissions-none-00ff9c?style=for-the-badge&labelColor=0d1117)
![License](https://img.shields.io/badge/license-MIT-00ff9c?style=for-the-badge&labelColor=0d1117)

[![Download](https://img.shields.io/badge/Download-latest_APK-00ff9c?style=for-the-badge&logo=github&logoColor=0d1117&labelColor=00ff9c)](https://github.com/Herbertbuilds/leaf/releases/latest)

</div>

No toolbars you never use, no ads, no network access and no third-party libraries at runtime. Just your document, rendered as quickly as the phone can draw it.

<!-- Add a screenshot or GIF here:
<p align="center"><img src="docs/screenshot.png" width="320" alt="Leaf screenshot" /></p>
-->

## What it opens

| Format | How it is rendered |
|---|---|
| **PDF** | Platform `PdfRenderer`, one bitmap per page, LRU cached, pinch to zoom |
| **DOCX** | White page with pictures at real size, tables with shading and merged cells, alignment, indentation, spacing, lists, page breaks, and inline bold, italic, underline, colour and size |
| **XLSX** | Worksheet grid with column letters, row numbers, frozen headers, column widths, bold cells and right-aligned numbers |
| **PPTX** | Slides as white canvases: pictures, background and shape fills, text position, alignment and sizes |
| **ODT** | Same white-page treatment: pictures, tables, lists, and the paragraph and text styles the file declares |
| **RTF** | Control-word parser to plain text |
| **Markdown** | Converted to HTML (headings, lists, code, emphasis, links) |
| **TXT / CSV** | Chunked into 6 KB rows so layout stays instant |

That's the full list: `.pdf`, `.docx`, `.txt`, `.xlsx`, `.pptx`, `.odt`, `.rtf`, `.md` and `.csv`. EPUB, MOBI, comic archives, web pages, images and ordinary archives are refused with an explanation instead of a blank screen. Nothing is copied or parsed for them, and renaming a file doesn't fool it because the bytes are checked too. The file picker only offers the supported types, and recents saved for formats Leaf used to read are dropped the first time the home screen opens.

The first open copies the file into Leaf's cache and later opens reuse that copy. A recent entry therefore still works after a reboot, an app update, or Android's permission cleanup removing the original provider grant.

## Search

Open **⋮ → Search** and type. Leaf highlights the current match and scrolls it into view. The counter shows `3/27`, and `‹` / `›` walk the matches and wrap around.

Search works in everything with real text: TXT, CSV, Markdown, RTF, DOCX, ODT, PPTX and spreadsheets. PDFs have no text layer, so the menu entry is hidden there.

Text documents use Leaf's own matcher (`core/Finder.kt`, covered by the desktop tests). HTML documents use the `WebView`'s native find, which needs no JavaScript.

## Why it's fast

- **Zero runtime dependencies.** No AndroidX, no Compose, no Kotlin coroutines runtime. The APK is a couple of hundred KB plus the Kotlin standard library.
- **Nothing blocks the UI thread.** Copying from the picker, sniffing and parsing all run on one background thread. Only finished views are built on the main thread.
- **Bounded work per frame.** Text is chunked into 6 KB `TextView` rows, and pages render at view width into an LRU cache sized to `maxMemory / 6`.
- **One render thread.** Duplicate requests (fast flings recycle rows) are collapsed.
- **No JavaScript.** The `WebView` used for HTML-shaped formats runs with JS disabled.
- **No permissions.** There is no `INTERNET` permission, so remote images in documents fail silently instead of calling home.

## Install

**From Releases:** download the latest `Leaf-1.5.3.apk` from the [Releases page](https://github.com/Herbertbuilds/leaf/releases/latest) on your phone and open it.

**Sideload your own build (no computer needed)**

1. Copy `dist/Leaf-1.5.3.apk` to the phone (USB, cloud drive, `python3 -m http.server`, and so on).
2. Open it from the Files app.
3. When Android says unknown apps are blocked, tap **Settings** and allow installs for the app you opened it from (Files, Chrome, and so on).
4. Tap **Install**. Play Protect may warn about an unknown developer. That's expected for a self-signed personal build, so choose **Install anyway**.

**Over USB with adb**

```bash
adb install -r dist/Leaf-1.5.3.apk
```

`adb` ships in Android's platform-tools, not in this repo's toolchain. Install it with your distro package (`sudo pacman -S android-tools`) or download `https://dl.google.com/android/repository/platform-tools-latest-linux.zip`. Turn on **Developer options → USB debugging** on the phone first and accept the RSA fingerprint prompt.

Leaf also registers as a viewer for the types it reads, so it appears in the "Open with" sheet for a PDF, Word file, spreadsheet, slides, ODT, RTF, plain text, Markdown or CSV. EPUBs, videos, images, APKs and archives don't offer Leaf. If one is opened through an older shortcut anyway, it gets a clear message rather than a blank screen.

## Build it yourself

The build deliberately avoids Gradle. It needs a JRE 17, the Android build tools and platform jar, and the Kotlin compiler, and produces the APK with:

```text
aapt2 → kotlinc → d8 → zipalign → apksigner
```

**One-time toolchain setup** (about 300 MB, no root, no `sdkmanager`). Put it anywhere and point `LEAF_TOOLCHAIN` at it (default `~/.cache/leaf-toolchain`):

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

**Then build and test:**

```bash
./test.sh     # parser tests on the desktop JVM
./build.sh    # → dist/Leaf-1.5.3.apk
```

`test.sh` needs `tests/lib/kxml2-2.3.0.jar` (43 KB) to stand in for Android's Expat-backed `XmlPullParser`:

```bash
mkdir -p tests/lib
curl -L -o tests/lib/kxml2-2.3.0.jar \
  https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
```

`build.sh` generates `leaf-release.keystore` on first run (alias `leaf`, password `leaf-leaf-leaf`) with `keytool`, or with `openssl` if the JDK's `keytool` is missing.

> [!IMPORTANT]
> Keep that keystore file. Android only lets you update an installed app with an APK signed by the same key.

## Layout

```text
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

**DOCX / ODT**
- Floating text boxes keep their text but lose their position.
- SmartArt, charts, equations, footnotes and headers/footers are not drawn. Headers and footers are skipped on purpose so the page is the text body.

**PPTX**
- Charts, tables, SmartArt, theme colour inheritance, gradients and shadows are not rendered.
- Text in ungrouped shapes without an explicit position, and shapes inside groups nested more than one level, may not appear.
- Most business slides work fine, but highly designed decks may differ from the original.

**XLSX**
- Shows cell values, not formulas or number formats. A date cell appears as the serial number Excel stores.
- Cell colours and borders from the file are not painted. Column widths and bold cells are honoured.

**General**
- PDF pages can't be searched. Only formats with a text layer can.
- Only the first 12 MB of a plain-text file is loaded.

## How it was built

I built Leaf. The idea, the design and the decisions about what it should and should not do are mine: which formats it opens, how it looks, and where the line sits between a reader and a document suite. The code and the logic were written with [OpenCode](https://opencode.ai) assisting. It drafted, debugged and typed under my direction, and I reviewed every change before it went in.

## Licence

MIT. Fork it, change it, ship it. The only condition is keeping the copyright notice and the warranty disclaimer with the copy. See [LICENSE](LICENSE).

<div align="center">
<sub>Built by <a href="https://github.com/Herbertbuilds">Herbert Jantjies</a></sub>
</div>
