# Pitch brochure

`MeshMind_Pitch_v5.html` is the current 27-page source. It extends the Light Show page with the implemented Conductor: USB laptop control, eight spatial scenes, groups/positions, twelve-cue plans, blackout/restore and a finale. v3 and v4 remain intact as earlier editions. Existing research and phone-test claims retain their original evidence labels. The new conductor is JVM/browser-tested; Android USB and actual display alignment remain unmeasured.

To render the new PDF headlessly, use Node.js with Playwright available and an installed Chrome/Edge executable:

```powershell
$env:CHROMIUM_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
node docs/pitch/render_brochure.cjs
```

If Playwright is supplied by the Codex runtime, set `NODE_PATH` to its returned `dependencies/node/node_modules` directory. Otherwise install Playwright in your normal tooling environment. Optional arguments are HTML input and PDF output paths. The renderer waits for fonts and scripted diagrams, checks all pages for content overflow, prints A4 with backgrounds and no browser headers/footers, and saves three QA screenshots under `tmp/pdfs/`.

The source uses Google Fonts, so allow network access during rendering for the intended typography. Inspect the renderer's font report and the PDF with Poppler before distributing a changed version. Light Show's physical display synchronization and crowd scale are not yet measured.
