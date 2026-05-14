# F-Droid metadata images — placeholder

These images are intentionally absent in the Phase 12 commit. Before tagging
v0.1.0 the maintainer must drop in:

- `icon.png` — 512×512 launcher icon (the in-app `mipmap/ic_launcher.png`
  is the obvious source; render at 512×512 for F-Droid's index).
- `featureGraphic.png` — 1024×500 banner shown on F-Droid's app page.
- `phoneScreenshots/1.png` … `phoneScreenshots/N.png` — at least three
  in-context screenshots: command row + persona selector, AI rewrite
  preview strip, sticker picker. F-Droid recommends 1080×1920 or higher.

F-Droid's `fdroidserver` validates these at build time. The repo will not
fail to compile without them; the F-Droid index page just won't render
properly.
