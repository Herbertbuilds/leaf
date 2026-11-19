# Page by page — design

2026-11-19 · Leaf 1.6 · small change

## Intent

Some documents are read, not scanned: novels, reports, EPUBs. Free scrolling makes it easy
to drift and lose your place. Requested: an option so one gesture moves exactly one screen.

## Goals

- A checked menu entry, **⋮ → Page by page**, that turns free scrolling into discrete pages.
- The choice sticks between documents and between launches (`Prefs.paged`).
- One screenful per gesture: a vertical swipe up/down turns a page, however fast or slow.
  Nothing else scrolls the content while the mode is on.
- A page bar with the page number and a slider, plus **Go to page…** — the same controls a
  PDF already has.
- Toggling never loses the reading position.

## Non-goals

- Horizontal page flipping or a page-curl animation.
- Reflowing HTML into true pages; HTML pages are viewport steps.
- Changing PDF and comic reading: they are already one page per screen.
- Paging slide decks: two-finger pinch zoom would fight the gesture.
- Replacing the chapter strip in EPUBs: chapters move to the Contents menu while paging.

## Anti-goals

- A page that skips content, or a document that scrolls freely "a little" in page mode.
- A layout change when the mode is turned on.
- A visible stall: turning the mode on must not freeze the UI.

## Constraints

- No AndroidX, no JavaScript, `minSdk` 26, one WebView, everything must stay fast.
- Plain text keeps its 12 MB read cap; page layout is linear work, so paging is offered up
  to 1.5 million characters (roughly a 1,500-page book) and the menu entry is disabled with
  a reason above that.

## Approaches

**A. Screen-step paging** — every scrolling reader moves one viewport per gesture.
Works everywhere, no reflow, instant; an HTML page can slice a line at the screen edge.

**B. Clean book pages, text only** — `StaticLayout` pages for plain text, no slicing, but
EPUB/DOCX are left out.

**Chosen: A**, with B's line handling folded in for plain text, since it costs little and
text is the format where sliced lines are most noticeable. HTML accepts the sliced line.

## Design

### Reader surface

`Reader` gains three members with defaults, so existing readers are untouched:

```kotlin
val canPage: Boolean get() = false
val pageBlocked: String? get() = null   // shown disabled in the menu, with the reason
val paged: Boolean get() = false
fun setPaged(on: Boolean) {}
```

`paged` mode also makes `pager`, `pageCount`, `currentPage` and `goToPage()` meaningful, so
the existing **Go to page…** dialog and the file-info page count work unchanged.

### The switch

`ReaderActivity` reads `Prefs.paged(this)` and calls `setPaged` on every new reader. The menu
adds a checkable "Page by page" right after "Find in document", when `canPage` is true. The
item toggles the preference and calls `setPaged` on the live reader — no reload, so the
position survives.

### Shared pieces (new files)

- `reader/PageBar.kt` — the counter-plus-slider strip, extracted from `PagerReader` and used
  by all three paged readers, so a PDF, a novel and an EPUB look the same at the bottom.
  Hidden when a document has one page.
- `reader/PageTurns.kt` — a `FrameLayout` wrapper that claims a touch only when the gesture
  is predominantly vertical and passes a single page turn up; taps, text selection and
  horizontal panning still reach the child.
- `core/Pages.kt` — pure pagination: given per-chunk line starts and heights, produce page
  boundaries as character offsets into the concatenated text. JVM-testable.

### Plain text

`TextReader` becomes a thin host: its view is a `FrameLayout` holding either today's
`ListView` (free scroll) or a single `TextView` showing one page. Page boundaries come from
`Pages.split` fed by `StaticLayout` measurements of each 6 KB chunk, so pages break at whole
lines and never clip one. Font-size changes re-measure and keep the page's first character.
Search keeps working: `Finder` hits are character offsets, mapped to pages by binary search,
and the hit is highlighted inside the page.

### HTML

`HtmlReader` wraps the WebView in `PageTurns`. While paging, `currentPage` is
`scrollY / viewportHeight`; `goToPage(n)` scrolls to `n * viewportHeight`; the page count is
`ceil(contentHeight * scale / viewportHeight)` after load. Native find still scrolls to a hit,
and the scroll listener keeps the bar honest. Turning the mode off restores the WebView's own
scrolling. The EPUB chapter strip gives way to the `PageBar` while paging; chapters remain in
**Contents**.

### Everything else

PDF, CBZ: `canPage` false, unchanged. PPTX decks: `canPage` false (pinch zoom), unchanged.
XLSX and DOCX: paging applies; horizontal panning of wide sheets and tables still works
because only vertical gestures are claimed.

## What is left to the builder

Exact touch slop and fling thresholds, whether the bar shows for a two-page document, and the
wording of the "too large" reason.
