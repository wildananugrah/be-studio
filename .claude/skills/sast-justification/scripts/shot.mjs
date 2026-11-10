// Renders every evidence page to PNG: node shot.mjs OUT_DIR/sast-evidence
// Reads OUT_DIR/sast-evidence/html/*.html, writes OUT_DIR/sast-evidence/<name>.png (2x, cropped to the content).
// Needs playwright-core (npm install --prefix <skill>/scripts playwright-core) and a Chromium: CHROME_PATH, a
// Playwright browser in ~/Library/Caches/ms-playwright or ~/.cache/ms-playwright, or Google Chrome.
import { chromium } from 'playwright-core';
import { readdirSync, existsSync } from 'fs';
import { join, resolve } from 'path';
import { pathToFileURL } from 'url';
import { homedir } from 'os';

function browser() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH;
  for (const base of [join(homedir(), 'Library/Caches/ms-playwright'), join(homedir(), '.cache/ms-playwright')]) {
    if (!existsSync(base)) continue;
    for (const d of readdirSync(base).filter(x => x.startsWith('chromium')).sort().reverse()) {
      for (const sub of readdirSync(join(base, d))) {
        for (const exe of ['chrome-headless-shell', 'Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing', 'chrome',
          'Chromium.app/Contents/MacOS/Chromium']) {
          const p = join(base, d, sub, exe);
          if (existsSync(p)) return p;
        }
      }
    }
  }
  for (const p of ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium',
    'C:/Program Files/Google/Chrome/Application/chrome.exe']) if (existsSync(p)) return p;
  return undefined; // playwright's own download, if installed
}

const out = resolve(process.argv[2]);
const src = join(out, 'html');
const b = await chromium.launch({ executablePath: browser() });
const p = await b.newPage({ viewport: { width: 1232, height: 800 }, deviceScaleFactor: 2 });
const files = readdirSync(src).filter(x => x.endsWith('.html')).sort();
for (const f of files) {
  await p.goto(pathToFileURL(join(src, f)).href);
  await (await p.$('.wrap')).screenshot({ path: join(out, f.replace(/\.html$/, '.png')) });
}
await b.close();
console.log(`${files.length} screenshots in ${out}`);
