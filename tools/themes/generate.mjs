#!/usr/bin/env node
/**
 * 从上游导出 11 套主题的设计 token。
 *
 * 用法：node tools/themes/generate.mjs <上游仓库路径>
 *
 * 和搜索提取脚本、人格模板一样，主题按**资产**处理而不是手抄：这个移植版存在的
 * 理由就是「喜欢它的设计」，配色是那个设计的一部分，手抄一遍必然漂移，而且漂移了
 * 也没人看得出来 —— 只会觉得"好像哪里不太一样"。
 *
 * CSS 的层叠模型是：styles.css 的 :root 给出 81 个基线 token，每个主题文件只覆盖
 * 其中一部分。所以这里两边都导出，由 Kotlin 侧做 baseline ⊕ overrides 的合并。
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";

const upstream = process.argv[2];
if (!upstream) {
  console.error("用法: node tools/themes/generate.mjs <上游仓库路径>");
  process.exit(1);
}

const registry = JSON.parse(
  readFileSync(join(upstream, "desktop/src/shared/theme-registry-data.json"), "utf8"),
);

/** 抽出一段 CSS 里的所有自定义属性声明，按出现顺序（后者覆盖前者）。 */
function extractTokens(css) {
  const tokens = {};
  // 只取 `--name: value;` 形式；值里可能有逗号、括号、引号，一路吃到分号
  const re = /(--[a-z0-9-]+)\s*:\s*([^;]+);/gi;
  let match;
  while ((match = re.exec(css)) !== null) {
    tokens[match[1]] = match[2].trim();
  }
  return tokens;
}

// 基线：styles.css 的第一个 :root 块
const stylesCss = readFileSync(join(upstream, "desktop/src/styles.css"), "utf8");
const rootBlock = stylesCss.match(/:root\s*\{([\s\S]*?)\n\}/);
if (!rootBlock) {
  console.error("在 styles.css 里找不到 :root 块 —— 上游结构变了，先看一眼再改这个脚本");
  process.exit(1);
}
const baseline = extractTokens(rootBlock[1]);

/**
 * 亮/暗由背景色亮度推出来，不是上游声明的。
 *
 * registry 里的 i18nMode 是翻译键（settings.appearance.midnightMode），不带亮暗信息；
 * autoLightDefault / autoDarkDefault 只点名了两个默认值。用相对亮度判断对这 11 套
 * 是稳的（两套 midnight 的背景明显偏暗，其余都是浅色纸面）。
 */
function isDark(hex) {
  const value = hex.replace("#", "");
  const r = parseInt(value.slice(0, 2), 16) / 255;
  const g = parseInt(value.slice(2, 4), 16) / 255;
  const b = parseInt(value.slice(4, 6), 16) / 255;
  const channel = (c) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  const luminance = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  return luminance < 0.5;
}

const themes = {};
for (const [id, meta] of Object.entries(registry.themes)) {
  const css = readFileSync(join(upstream, "desktop/src", meta.cssPath), "utf8");
  themes[id] = {
    backgroundColor: meta.backgroundColor,
    dark: isDark(meta.backgroundColor),
    i18nName: meta.i18nName,
    tokens: extractTokens(css),
  };
}

const output = {
  _comment: "从上游 desktop/src/themes/*.css 与 styles.css 导出，勿手改。重跑 tools/themes/generate.mjs",
  defaultTheme: registry.defaultTheme,
  autoLightDefault: registry.autoLightDefault,
  autoDarkDefault: registry.autoDarkDefault,
  baseline,
  themes,
};

const target = join(
  dirname(new URL(import.meta.url).pathname),
  "../../core/src/main/resources/assets/theme/themes.json",
);
mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, JSON.stringify(output, null, 2) + "\n", "utf8");

console.error(`导出 ${Object.keys(themes).length} 套主题，基线 token ${Object.keys(baseline).length} 个`);
for (const [id, t] of Object.entries(themes)) {
  console.error(`  ${id.padEnd(20)} ${t.dark ? "暗" : "亮"}  覆盖 ${Object.keys(t.tokens).length} 个 token`);
}
