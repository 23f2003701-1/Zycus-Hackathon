/*
 * Server-renders the console's components against live backend payloads.
 *
 * The point is not snapshot testing - it is that a console assembled from real API shapes cannot
 * be trusted until something has actually rendered it. This catches the class of bug that a build
 * cannot: a field named differently than assumed, a null cost price reaching a formatter, an empty
 * suggestion list hitting code that expects one.
 *
 * Run with the backend up:  npm run smoke     (from the frontend directory)
 */

import { createServer } from "vite";
import { fileURLToPath } from "node:url";
import path from "node:path";
// React is CommonJS, so it is imported through Node's interop rather than Vite's SSR evaluator,
// which would otherwise try to inline it and fail on the missing `module` global.
import React from "react";
import { renderToString } from "react-dom/server";

const API = process.env.API_BASE ?? "http://localhost:8080";
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const get = async (p) => {
  const response = await fetch(`${API}${p}`);
  if (!response.ok) throw new Error(`GET ${p} -> ${response.status}`);
  return response.json();
};

const vite = await createServer({
  root: frontendRoot,
  server: { middlewareMode: true },
  appType: "custom",
  logLevel: "warn",
  // Keep React out of the SSR transform; only our own JSX needs compiling.
  ssr: { external: ["react", "react-dom", "react-dom/server"] },
});

let failures = 0;

function check(label, fn) {
  try {
    const html = fn();
    if (!html || html.length === 0) throw new Error("rendered nothing");
    console.log(`  ok    ${label} (${html.length} chars)`);
    return html;
  } catch (err) {
    failures += 1;
    console.log(`  FAIL  ${label}: ${err.message}`);
    return "";
  }
}

try {
  const [products, pricing, reorder, strategy] = await Promise.all([
    get("/products"),
    get("/pricing-suggestions"),
    get("/reorder-suggestions"),
    get("/admin/commerce-strategy"),
  ]);

  console.log(
    `\nLive data: ${products.length} products, ${pricing.length} pricing, ${reorder.length} reorder suggestions\n`
  );

  const { ActionQueue } = await vite.ssrLoadModule("/src/components/ActionQueue.jsx");
  const { CatalogBoard } = await vite.ssrLoadModule("/src/components/CatalogBoard.jsx");
  const { Header } = await vite.ssrLoadModule("/src/components/Header.jsx");
  const { SuggestionCard } = await vite.ssrLoadModule("/src/components/SuggestionCard.jsx");

  const el = React.createElement;
  const render = (component, props) => renderToString(el(component, props));

  const pendingPricing = pricing.filter((s) => s.status === "PENDING");
  const pendingReorder = reorder.filter((s) => s.status === "PENDING");
  const pendingByProduct = new Map();
  for (const s of [...pendingPricing, ...pendingReorder]) {
    pendingByProduct.set(s.productId, (pendingByProduct.get(s.productId) ?? 0) + 1);
  }

  const groups = products
    .filter((p) => pendingByProduct.has(p.id) || p.status === "PRICE_REVIEW_PENDING")
    .map((product) => ({
      product,
      pricing: pendingPricing.filter((s) => s.productId === product.id),
      reorder: pendingReorder.filter((s) => s.productId === product.id),
    }));

  const noop = () => {};

  const queueHtml = check("ActionQueue with live pending suggestions", () =>
    render(ActionQueue, { groups, busyKey: null, onDecide: noop, onRequest: noop })
  );

  check("ActionQueue empty state", () =>
    render(ActionQueue, { groups: [], busyKey: null, onDecide: noop, onRequest: noop })
  );

  const catalogProps = {
    pricingSuggestions: pricing,
    pendingByProduct,
    busyKey: null,
    streaming: false,
    onSell: noop,
    onRestock: noop,
    onStreamPricing: noop,
  };

  const catalogHtml = check("CatalogBoard with all products", () =>
    render(CatalogBoard, { ...catalogProps, products })
  );

  check("CatalogBoard with no matching products", () =>
    render(CatalogBoard, {
      ...catalogProps,
      products: [],
      pricingSuggestions: [],
      pendingByProduct: new Map(),
    })
  );

  check("CatalogBoard with a product missing cost price", () =>
    render(CatalogBoard, {
      ...catalogProps,
      products: [{ ...products[0], costPrice: null, marginFloor: null, demandVelocity: 0 }],
      pricingSuggestions: [],
      pendingByProduct: new Map(),
    })
  );

  check("Header connected", () =>
    render(Header, {
      strategy,
      pendingCount: pendingPricing.length + pendingReorder.length,
      loading: false,
      error: null,
      lastUpdated: new Date(),
      paused: false,
      onTogglePause: noop,
      onRefresh: noop,
      onStrategyChange: noop,
      busy: false,
    })
  );

  check("Header while disconnected, before any strategy is known", () =>
    render(Header, {
      strategy: null,
      pendingCount: 0,
      loading: false,
      error: "Cannot reach the API",
      lastUpdated: null,
      paused: false,
      onTogglePause: noop,
      onRefresh: noop,
      onStrategyChange: noop,
      busy: false,
    })
  );

  if (pendingPricing.length > 0) {
    check("SuggestionCard in its busy state", () =>
      render(SuggestionCard, {
        suggestion: pendingPricing[0],
        kind: "pricing",
        busy: true,
        onDecide: noop,
      })
    );
  }

  /*
   * Synthetic fixtures for the permutations the seeded state does not happen to contain. These are
   * built rather than provoked by writing to the running app, so the check is deterministic and
   * leaves no trace: every trigger, direction, and source combination gets rendered regardless of
   * what the live database currently holds.
   */
  const basePricing = {
    id: 9001,
    productId: products[0].id,
    productSku: products[0].sku,
    productName: products[0].name,
    currentPrice: 24.99,
    recommendedPrice: 27.49,
    changePct: 10,
    direction: "INCREASE",
    confidence: 0.82,
    reasoning: "Synthetic fixture reasoning for the render check.",
    status: "PENDING",
    triggerReason: "INVENTORY_LOW",
    autoTriggered: true,
    generatedBy: "aiAdvisor",
    createdAt: new Date().toISOString(),
    decidedAt: null,
  };

  const permutations = [
    ["INVENTORY_LOW", true, "aiAdvisor", "INCREASE"],
    ["DEMAND_SPIKE", true, "aiAdvisor", "INCREASE"],
    ["INVENTORY_LOW", true, "ruleBased", "DECREASE"],
    ["MANUAL", false, "ruleBased", "HOLD"],
    ["INITIAL", false, "aiAdvisor", "HOLD"],
  ];

  const permutationHtml = permutations
    .map(([triggerReason, autoTriggered, generatedBy, direction]) =>
      check(`SuggestionCard ${triggerReason} / ${generatedBy} / ${direction}`, () =>
        render(SuggestionCard, {
          suggestion: { ...basePricing, triggerReason, autoTriggered, generatedBy, direction },
          kind: "pricing",
          busy: false,
          onDecide: noop,
        })
      )
    )
    .join("");

  check("SuggestionCard with zero confidence and a long reasoning body", () =>
    render(SuggestionCard, {
      suggestion: { ...basePricing, confidence: 0, reasoning: "x".repeat(600) },
      kind: "pricing",
      busy: false,
      onDecide: noop,
    })
  );

  check("Reorder SuggestionCard", () =>
    render(SuggestionCard, {
      suggestion: {
        ...basePricing,
        currentStock: 7,
        recommendedQuantity: 84,
        suggestedLeadTimeDays: 7,
        triggerReason: "DEMAND_SPIKE",
      },
      kind: "reorder",
      busy: false,
      onDecide: noop,
    })
  );

  const { ReasoningStream } = await vite.ssrLoadModule("/src/components/ReasoningStream.jsx");
  check("ReasoningStream while tokens are arriving", () =>
    render(ReasoningStream, {
      stream: {
        product: products[0],
        phase: "reasoning",
        advisor: "aiAdvisor",
        reasoning: "Cover is under a day, so a 10% rise slows depletion.",
        fallback: null,
        suggestion: null,
        error: null,
      },
      busy: false,
      onDecide: noop,
      onClose: noop,
    })
  );
  check("ReasoningStream after a fallback", () =>
    render(ReasoningStream, {
      stream: {
        product: products[0],
        phase: "done",
        advisor: "ruleBased",
        reasoning: "Abandoned mid-stream.",
        fallback: "no API key configured",
        suggestion: basePricing,
        error: null,
      },
      busy: false,
      onDecide: noop,
      onClose: noop,
    })
  );

  // Content assertions - rendering without throwing is not the same as showing the right thing.
  console.log("");
  const expectations = [
    ["queue shows AI or Rules provenance", /">(AI|Rules)</.test(queueHtml)],
    ["queue shows reasoning text", /class="reasoning"/.test(queueHtml)],
    ["queue shows a confidence meter", /confidence-fill/.test(queueHtml)],
    ["queue offers accept and reject", /Reject/.test(queueHtml) && /Accept/.test(queueHtml)],
    ["catalog shows a stock heat bar", /stockbar-fill heat-/.test(catalogHtml)],
    ["catalog shows simulate-sale controls", /Sell 1/.test(catalogHtml)],
    ["catalog offers live streaming price ask", /Ask live/.test(catalogHtml)],
    ["catalog shows a status pill", /class="pill pill-/.test(catalogHtml)],
  ];

  // Asserted against the fixtures, so this holds no matter what the live database contains.
  expectations.push(
    ["auto-triggered badges render solid", /badge-solid/.test(permutationHtml)],
    ["inventory-low badge is distinct from demand-spike", /badge-amber/.test(permutationHtml) && /badge-violet/.test(permutationHtml)],
    ["a requested suggestion is not styled as auto-triggered", /badge-slate/.test(permutationHtml)],
    ["both AI and rule-based provenance render", /">AI</.test(permutationHtml) && /">Rules</.test(permutationHtml)],
    ["price increases and decreases are styled differently", /delta-increase/.test(permutationHtml) && /delta-decrease/.test(permutationHtml)]
  );

  for (const [label, pass] of expectations) {
    if (!pass) failures += 1;
    console.log(`  ${pass ? "ok  " : "FAIL"}  ${label}`);
  }
} catch (err) {
  failures += 1;
  console.error(`\nfatal: ${err.message}`);
} finally {
  await vite.close();
}

console.log(failures === 0 ? "\nAll render checks passed.\n" : `\n${failures} check(s) failed.\n`);
// Set the code rather than calling process.exit, which can race Vite's socket teardown on Windows.
process.exitCode = failures === 0 ? 0 : 1;
