// Rendert icon.svg in PNG-Größen (Chromium über Playwright): node render.cjs icon.svg <ziel-präfix> 512 192 ...
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [svgFile, out, ...sizes] = process.argv.slice(2);
  const svg = fs.readFileSync(svgFile, 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const s of sizes.map(Number)) {
    await page.setViewportSize({ width: s, height: s });
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('width="512" height="512"', `width="${s}" height="${s}"`)}</body></html>`);
    await page.locator('svg').screenshot({ path: `${out}-${s}.png`, omitBackground: true });
  }
  await browser.close();
})();
