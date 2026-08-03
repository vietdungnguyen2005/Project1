import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, unlinkSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

function findPlaywrightChrome() {
  const localAppData = process.env.LOCALAPPDATA;

  if (!localAppData) {
    return null;
  }

  const browserRoot = join(localAppData, "ms-playwright");

  if (!existsSync(browserRoot)) {
    return null;
  }

  const candidates = readdirSync(browserRoot)
    .filter((entry) => entry.startsWith("chromium-"))
    .map((entry) => join(browserRoot, entry, "chrome-win64", "chrome.exe"))
    .filter(existsSync)
    .sort()
    .reverse();

  return candidates[0] ?? null;
}

const url = process.env.LIGHTHOUSE_URL ?? "http://127.0.0.1:3010";
const outputPath = resolve("reports/lighthouse.json");
mkdirSync(dirname(outputPath), { recursive: true });
if (existsSync(outputPath)) {
  unlinkSync(outputPath);
}
const chromePath = process.env.CHROME_PATH ?? findPlaywrightChrome();
const env = chromePath ? { ...process.env, CHROME_PATH: chromePath } : process.env;

const result = spawnSync(
  process.execPath,
  [
    resolve("node_modules/lighthouse/cli/index.js"),
    url,
    "--quiet",
    "--chrome-flags=--headless=new --no-sandbox --disable-gpu",
    "--only-categories=performance,accessibility,best-practices,seo",
    "--output=json",
    `--output-path=${outputPath}`
  ],
  {
    encoding: "utf8",
    shell: false,
    env
  }
);

if (result.status !== 0 && !existsSync(outputPath)) {
  if (result.error) {
    console.error(result.error);
  }

  if (result.stdout) {
    console.error(result.stdout);
  }

  if (result.stderr) {
    console.error(result.stderr);
  }

  process.exit(result.status ?? 1);
}

if (result.status !== 0) {
  console.warn("Lighthouse produced a report but exited non-zero after Chrome cleanup.");
}

const report = JSON.parse(readFileSync(outputPath, "utf8")) as {
  categories: Record<string, { score: number }>;
};

const scores = Object.fromEntries(
  Object.entries(report.categories).map(([key, category]) => [
    key,
    Math.round(category.score * 100)
  ])
);

console.log(JSON.stringify(scores, null, 2));

if ((scores.performance ?? 0) < 95 || (scores.seo ?? 0) < 95) {
  throw new Error("Lighthouse score below 95 target");
}
