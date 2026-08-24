/**
 * generate.mjs — 产出 FactStore 检索文本与 FTS 查询的 ground truth。
 *
 * 逐字复刻上游 lib/memory/fact-store.ts 里的纯函数。与会话搜索分词不同，
 * 这条链路不依赖 jieba：它用 NFKC 归一 + CJK 2/3-gram，完全确定性 ——
 * 所以理论上两端可以做到逐字一致，这份 truth 就是用来钉死这一点的。
 *
 *   node tools/factstore-truth/generate.mjs > core/src/test/resources/factstore-truth.json
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// ── 以下逐字取自上游 lib/memory/fact-store.ts ──
const CJK_RUN_RE = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]+/gu;

function normalizeSearchText(text) {
  return String(text || "").normalize("NFKC").trim();
}

function cjkNgrams(text) {
  const tokens = [];
  CJK_RUN_RE.lastIndex = 0;
  for (const match of normalizeSearchText(text).matchAll(CJK_RUN_RE)) {
    const chars = Array.from(match[0]);
    for (const size of [2, 3]) {
      if (chars.length < size) continue;
      for (let i = 0; i <= chars.length - size; i++) {
        tokens.push(chars.slice(i, i + size).join(""));
      }
    }
  }
  return tokens;
}

function uniqueTokens(tokens) {
  const seen = new Set();
  const out = [];
  for (const token of tokens) {
    const normalized = normalizeSearchText(token);
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    out.push(normalized);
  }
  return out;
}

function buildFactSearchText(fact, tags = []) {
  const base = [fact, ...tags].map(normalizeSearchText).filter(Boolean).join(" ");
  const grams = cjkNgrams(base);
  return uniqueTokens([base, ...grams]).join(" ");
}

function buildFtsQuery(query) {
  const normalized = normalizeSearchText(query);
  if (!normalized) return "";
  const lexicalTokens = normalized.split(/\s+/);
  const grams = cjkNgrams(normalized);
  return uniqueTokens([...lexicalTokens, ...grams])
    .map((w) => `"${w.replace(/"/g, '""')}"`)
    .join(" OR ");
}

function hasCjk(text) {
  CJK_RUN_RE.lastIndex = 0;
  return CJK_RUN_RE.test(normalizeSearchText(text));
}
// ── 复刻结束 ──

const here = path.dirname(fileURLToPath(import.meta.url));
const corpus = JSON.parse(fs.readFileSync(path.join(here, "corpus.json"), "utf8"));

process.stdout.write(JSON.stringify({
  note: "由 tools/factstore-truth/generate.mjs 生成，勿手工编辑",
  upstreamSource: "lib/memory/fact-store.ts",
  searchText: corpus.facts.map(({ fact, tags }) => ({
    fact,
    tags,
    searchText: buildFactSearchText(fact, tags),
  })),
  ftsQuery: corpus.queries.map((query) => ({
    query,
    ftsQuery: buildFtsQuery(query),
    hasCjk: hasCjk(query),
  })),
}, null, 2) + "\n");
