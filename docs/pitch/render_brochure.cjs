// Headless, reproducible A4 export. Supply a Chrome/Edge binary with CHROMIUM_PATH.
const { chromium } = require('playwright');
const path = require('node:path');
const fs = require('node:fs/promises');
const { pathToFileURL } = require('node:url');

(async () => {
  const input = path.resolve(process.argv[2] || path.join(__dirname, 'MeshMind_Pitch_v5.html'));
  const output = path.resolve(process.argv[3] || input.replace(/\.html$/i, '.pdf'));
  if (input === output) throw new Error('Output must differ from HTML input');
  const browser = await chromium.launch({ headless: true,
    ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 794, height: 1123 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(pathToFileURL(input).href, { waitUntil: 'networkidle', timeout: 60000 });
    await page.evaluate(() => document.fonts.ready);
    const report = await page.evaluate(() => {
      return [...document.querySelectorAll('.page')].map((section, index) => {
        const bounds = section.getBoundingClientRect();
        const clipped = [...section.querySelectorAll('h1,h2,h3,p,table,.card,.foot')].filter(element => {
          const r = element.getBoundingClientRect();
          return r.bottom > bounds.bottom + 1 || r.right > bounds.right + 1 || r.left < bounds.left - 1;
        }).map(element => element.textContent.trim().slice(0, 100));
        return { page: index + 1, id: section.id, clipped, scrollOverflow: section.scrollHeight > section.clientHeight + 1 };
      });
    });
    if (errors.length || report.some(p => p.clipped.length || p.scrollOverflow)) {
      throw new Error(JSON.stringify({ errors, overflowingPages: report.filter(p => p.clipped.length || p.scrollOverflow) }, null, 2));
    }
    await fs.mkdir(path.dirname(output), { recursive: true });
    await page.pdf({ path: output, preferCSSPageSize: true, printBackground: true, displayHeaderFooter: false });
    const qaDir = path.resolve(__dirname, '../../tmp/pdfs');
    await fs.mkdir(qaDir, { recursive: true });
    for (const id of ['p1', 'p-light-show', 'p24']) {
      await page.locator(`#${id}`).screenshot({ path: path.join(qaDir, `brochure-${id}.png`) });
    }
    console.log(JSON.stringify({ input, output, pages: report.length, fonts: await page.evaluate(() => ({
      sans: document.fonts.check('600 16px "Space Grotesk"'), serif: document.fonts.check('16px "Instrument Serif"'),
      mono: document.fonts.check('16px "JetBrains Mono"') })) }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
