/**
 * generate.mjs — 从上游导出浏览器搜索的页内提取脚本。
 *
 * 这段脚本是整条搜索链路里最脆弱的部分：它写死了 Bing / Google / DuckDuckGo 的
 * DOM 选择器、CAPTCHA 信号和多语言"无结果"文案。搜索引擎一改版它就失效。
 *
 * 所以不在 Kotlin 里重写选择器，而是把上游产出的脚本**原样**存成资产，
 * Android 侧用 WebView.evaluateJavascript 直接喂进去。这样以后能直接
 * 跟上游 diff 同步，而不用逐条对照选择器。
 *
 *   node tools/search-extractors/generate.mjs <上游仓库路径>
 */
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const upstreamRoot = process.argv[2];
if (!upstreamRoot) {
  console.error("用法: node tools/search-extractors/generate.mjs <上游仓库路径>");
  process.exit(1);
}

const require = createRequire(import.meta.url);
const mod = require(path.join(upstreamRoot, "lib/browser/browser-search-extractors.cjs"));

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(here, "../../core/src/main/resources/assets/search");
fs.mkdirSync(outDir, { recursive: true });

// 用一个固定的 limit 生成，再把那一行换成占位符。只替换 `const maxResults = 5;`
// 这一处，脚本正文里其它出现 5 的地方不受影响。
const SENTINEL_LIMIT = 5;
const LIMIT_LINE = `const maxResults = ${SENTINEL_LIMIT};`;

for (const provider of mod.BROWSER_SEARCH_PROVIDER_IDS) {
  const script = mod.buildBrowserSearchExtractionScript(provider, SENTINEL_LIMIT);
  if (!script.includes(LIMIT_LINE)) {
    console.error(`${provider}: 找不到 ${LIMIT_LINE} —— 上游模板变了，需要更新本脚本`);
    process.exit(1);
  }
  const templated = script.replace(LIMIT_LINE, "const maxResults = __MAX_RESULTS__;");
  const outFile = path.join(outDir, `${provider}.js`);
  fs.writeFileSync(outFile, templated + "\n");
  console.error(`写出 ${path.relative(process.cwd(), outFile)}（${templated.length} 字节）`);
}

// URL 构造与 locale 预设也一并导出成数据，供 Kotlin 端对照测试
const providers = {};
for (const provider of mod.BROWSER_SEARCH_PROVIDER_IDS) {
  providers[provider] = {};
  for (const locale of ["zh-CN", "zh-TW", "ja", "ko", "en-US", ""]) {
    providers[provider][locale || "(none)"] = {
      url: mod.buildBrowserSearchUrl(provider, "记忆传送带 memory", 5, { locale }),
      loadOptions: mod.buildBrowserSearchLoadOptions(provider, { locale }),
    };
  }
}
fs.writeFileSync(
  path.resolve(here, "../../core/src/test/resources/search-url-truth.json"),
  JSON.stringify({ note: "由 tools/search-extractors/generate.mjs 生成，勿手工编辑", providers }, null, 2) + "\n",
);
console.error("写出 core/src/test/resources/search-url-truth.json");
