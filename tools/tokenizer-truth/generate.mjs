/**
 * generate.mjs — 产出会话搜索分词的 ground truth。
 *
 * 逐行复刻上游 lib/search/session-search-tokenizer.ts 的逻辑（含 CUSTOM_WORDS
 * 自定义词典），跑上游真正使用的 @node-rs/jieba，把结果写成 JSON。
 * Kotlin 侧的 SessionSearchTokenizer 用这份 JSON 做契约测试 —— 目的是让平板上
 * 搜出来的结果和电脑上一致，而不是"各自都能搜"。
 *
 *   node tools/tokenizer-truth/generate.mjs > core/src/test/resources/tokenizer-truth.json
 *
 * 依赖：npm install @node-rs/jieba（不进主工程，只是生成期工具）
 */
import { Jieba } from "@node-rs/jieba";
import { dict } from "@node-rs/jieba/dict.js";  // 上游经 Vite 打包，裸子路径可解析；Node ESM 下需显式扩展名，指向同一文件
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// ── 以下常量与函数逐字取自上游 session-search-tokenizer.ts ──
const CUSTOM_WORDS = [
  "session_search 1000 nz",
  "session 1000 eng",
  "SessionFile 1000 eng",
  "A2A通信 1000 nz",
  "聊天记录 1000 nz",
  "搜不到 1000 v",
  "Agent 1000 eng",
  "CodeX 1000 eng",
  "Claude 1000 eng",
  "OpenClaw 1000 eng",
  "Cherry 1000 eng",
  "Studio 1000 eng",
  "HANA_HOME 1000 eng",
  "Bridge 1000 eng",
  "MCP 1000 eng",
  "RC 1000 eng",
  "better-sqlite3 1000 eng",
];

const PUNCTUATION_RE = /^[\p{P}\p{S}\s]+$/u;
const ASCII_WORD_RE = /[a-z0-9_][a-z0-9_.-]*/giu;
const SPACED_TERM_RE = /[^\s]+/gu;

function normalizeSessionSearchText(value) {
  return String(value || "")
    .normalize("NFKC")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

function addToken(terms, token) {
  const value = normalizeSessionSearchText(token);
  if (!value || PUNCTUATION_RE.test(value)) return;
  if (value.length === 1 && /^[\p{Script=Han}]$/u.test(value)) return;
  terms.add(value);
}

const jieba = Jieba.withDict(dict);
jieba.loadDict(Buffer.from(CUSTOM_WORDS.join("\n"), "utf8"));

function tokenizeSessionSearchQuery(query) {
  const normalized = normalizeSessionSearchText(query);
  if (!normalized) return [];
  const terms = new Set();
  addToken(terms, normalized);
  for (const match of normalized.matchAll(SPACED_TERM_RE)) addToken(terms, match[0]);
  for (const match of normalized.matchAll(ASCII_WORD_RE)) addToken(terms, match[0]);
  for (const token of jieba.cutForSearch(normalized, true)) {
    addToken(terms, normalizeSessionSearchText(token));
  }
  return [...terms];
}
// ── 复刻结束 ──

const here = path.dirname(fileURLToPath(import.meta.url));
const corpus = JSON.parse(fs.readFileSync(path.join(here, "corpus.json"), "utf8"));

const cases = corpus.map((input) => ({
  input,
  terms: tokenizeSessionSearchQuery(input),
}));

process.stdout.write(JSON.stringify({
  note: "由 tools/tokenizer-truth/generate.mjs 从上游 @node-rs/jieba 生成，勿手工编辑",
  upstreamSource: "lib/search/session-search-tokenizer.ts",
  customWords: CUSTOM_WORDS,
  cases,
}, null, 2) + "\n");
