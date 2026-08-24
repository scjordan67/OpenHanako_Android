(() => {
    const engine = "bing";
    const maxResults = __MAX_RESULTS__;

    function textOf(el) {
      return (el && (el.innerText || el.textContent) || "").replace(/\s+/g, " ").trim();
    }

    function firstText(root, selectors) {
      for (const selector of selectors) {
        const el = root.querySelector(selector);
        const text = textOf(el);
        if (text) return text;
      }
      return "";
    }

    function firstAnchor(root, selectors) {
      for (const selector of selectors) {
        const el = root.querySelector(selector);
        if (el && el.href) return el;
      }
      return null;
    }

    function cleanUrl(raw) {
      if (!raw) return "";
      let url;
      try {
        url = new URL(raw, location.href);
      } catch {
        return "";
      }

      if (url.hostname.endsWith("google.com") && url.pathname === "/url" && url.searchParams.get("q")) {
        try { url = new URL(url.searchParams.get("q")); } catch {}
      }
      if (url.hostname.endsWith("duckduckgo.com") && url.pathname.startsWith("/l/") && url.searchParams.get("uddg")) {
        try { url = new URL(url.searchParams.get("uddg")); } catch {}
      }
      if (!["http:", "https:"].includes(url.protocol)) return "";
      return url.href;
    }

    function displayUrl(url) {
      try {
        const parsed = new URL(url);
        return parsed.hostname.replace(/^www\./, "") + parsed.pathname.replace(/\/$/, "");
      } catch {
        return "";
      }
    }

    function hasCaptchaSignals() {
      const bodyText = textOf(document.body).toLowerCase();
      const href = location.href.toLowerCase();
      return (
        href.includes("/sorry/") ||
        href.includes("captcha") ||
        bodyText.includes("unusual traffic") ||
        bodyText.includes("verify you are human") ||
        bodyText.includes("detected unusual traffic") ||
        bodyText.includes("our systems have detected") ||
        bodyText.includes("enter the characters you see below")
      );
    }

    function blockedReason() {
      if (hasCaptchaSignals()) return "Search page requires verification or CAPTCHA.";
      const bodyText = textOf(document.body).toLowerCase();
      if (bodyText.includes("enable javascript")) return "Search page requires JavaScript.";
      if (bodyText.includes("consent") && bodyText.includes("privacy")) return "Search page is blocked by a consent interstitial.";
      return "";
    }

    function hasNoResultsSignals() {
      const bodyText = textOf(document.body).toLowerCase();
      if (engine === "bing") {
        return (
          bodyText.includes("there are no results for") ||
          bodyText.includes("we did not find any results") ||
          bodyText.includes("没有与此相关的结果") ||
          bodyText.includes("找不到与") && bodyText.includes("相关的结果")
        );
      }
      if (engine === "google") {
        return (
          bodyText.includes("did not match any documents") ||
          bodyText.includes("没有找到和您的查询相符的内容")
        );
      }
      if (engine === "duckduckgo") {
        return (
          bodyText.includes("no results found") ||
          bodyText.includes("没有结果")
        );
      }
      return false;
    }

    function resultFrom(root, anchor, title, snippet, rank) {
      const url = cleanUrl(anchor && anchor.href);
      if (!title || !url) return null;
      return {
        title,
        url,
        content: snippet || "",
        rank,
        score: null,
        metadata: {
          display_url: displayUrl(url),
          engine,
        },
      };
    }

    function bingResults() {
      const items = Array.from(document.querySelectorAll("li.b_algo, .b_algo"));
      return items.map((item, idx) => {
        const anchor = firstAnchor(item, ["h2 a", "a"]);
        const title = firstText(item, ["h2", "a"]);
        const snippet = firstText(item, [".b_caption p", ".b_snippet", "p"]);
        return resultFrom(item, anchor, title, snippet, idx + 1);
      }).filter(Boolean);
    }

    function googleResults() {
      const items = Array.from(document.querySelectorAll("div.g, div.MjjYud, div[data-sokoban-container]"));
      return items.map((item, idx) => {
        const anchor = firstAnchor(item, ["a:has(h3)", "a"]);
        const title = firstText(item, ["h3"]);
        const snippet = firstText(item, [".VwiC3b", ".IsZvec", "[data-sncf]", ".kb0PBd"]);
        return resultFrom(item, anchor, title, snippet, idx + 1);
      }).filter(Boolean);
    }

    function duckduckgoResults() {
      const items = Array.from(document.querySelectorAll("article[data-testid='result'], .result, .web-result"));
      return items.map((item, idx) => {
        const anchor = firstAnchor(item, ["a[data-testid='result-title-a']", "a.result__a", "h2 a", "a"]);
        const title = textOf(anchor) || firstText(item, ["h2", ".result__title"]);
        const snippet = firstText(item, ["[data-result='snippet']", ".result__snippet", ".result__body"]);
        return resultFrom(item, anchor, title, snippet, idx + 1);
      }).filter(Boolean);
    }

    let reason = blockedReason();
    const blocked = !!reason;
    let results = [];
    if (!blocked) {
      if (engine === "bing") results = bingResults();
      else if (engine === "google") results = googleResults();
      else if (engine === "duckduckgo") results = duckduckgoResults();
    }
    let status = "ok";
    if (blocked) status = "blocked";
    else if (results.length === 0 && hasNoResultsSignals()) {
      status = "no_results";
      reason = "Search page returned no results.";
    } else if (results.length === 0) {
      status = "extraction_failed";
      reason = "Search results could not be extracted from " + engine + " page.";
    }

    return {
      title: document.title || "",
      final_url: location.href,
      status,
      blocked,
      captcha: hasCaptchaSignals(),
      reason,
      results: results.slice(0, maxResults).map((item, idx) => ({ ...item, rank: idx + 1 })),
    };
  })()
