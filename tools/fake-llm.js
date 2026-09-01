/*
 * A stand-in for an OpenAI-compatible LLM, so the AI path can be exercised end to end without a
 * real API key. It echoes back which prompt it received, which is how we verify that the
 * inventory-low and demand-spike prompts really are different documents on the wire.
 *
 *   node tools/fake-llm.js
 *
 * Then point the app at it:
 *   llm.provider=ollama  llm.base-url=http://localhost:11434
 *
 * Pass a mode to exercise the failure paths:
 *   node tools/fake-llm.js prose      -> replies with no JSON at all
 *   node tools/fake-llm.js absurd     -> replies with a wildly out-of-bounds price
 *   node tools/fake-llm.js fenced     -> wraps valid JSON in markdown fences
 *   node tools/fake-llm.js slow       -> hangs, to exercise the read timeout
 *   node tools/fake-llm.js error      -> replies 429, as a quota failure would
 *   node tools/fake-llm.js truncated  -> streams half a document, then stops mid-sentence
 *
 * Requests with "stream": true get an SSE response chunked into small deltas with a delay
 * between them, which is the only way to see whether the console really renders tokens as they
 * land rather than buffering the lot and revealing it at the end.
 */

const http = require("http");

const mode = process.argv[2] || "ok";
// Overridable so two modes can run side by side - useful when comparing a healthy stream against
// a truncated one without restarting anything.
const PORT = Number(process.env.FAKE_LLM_PORT) || 11434;

function reply(prompt) {
  const isPricing = prompt.includes("recommendedPrice");
  const trigger =
    prompt.includes("INVENTORY RUNNING LOW") || prompt.includes("STOCK BELOW REORDER THRESHOLD")
      ? "INVENTORY_LOW"
      : prompt.includes("DEMAND SPIKE") || prompt.includes("REPLENISHING INTO A DEMAND SPIKE")
        ? "DEMAND_SPIKE"
        : "ROUTINE";

  console.log(`  -> ${isPricing ? "pricing" : "reorder"} prompt, situation ${trigger}, ${prompt.length} chars`);

  if (mode === "prose") {
    return "I'm sorry, I can't make pricing decisions without more market context.";
  }
  if (mode === "absurd") {
    return JSON.stringify({
      recommendedPrice: 999999,
      confidence: 0.99,
      reasoning: "Scarcity implies unlimited pricing power.",
    });
  }

  // Pull the current price out of the fact sheet so the answer is inside the guardrails.
  const priceMatch = prompt.match(/Current price\s*:\s*\$([0-9.]+)/);
  const current = priceMatch ? parseFloat(priceMatch[1]) : 20;

  const body = isPricing
    ? {
        recommendedPrice: Number((current * (trigger === "DEMAND_SPIKE" ? 1.08 : 1.12)).toFixed(2)),
        direction: "INCREASE",
        confidence: 0.78,
        reasoning:
          trigger === "DEMAND_SPIKE"
            ? "Velocity is running well ahead of category peers while cover is thin, so an 8% rise captures the elevated willingness to pay without the double-digit move that would visibly punish a trending item. I rejected holding because the spike window is short."
            : "With under a day of cover left and a healthy margin above cost, a 12% rise slows depletion and protects the remaining units until resupply. I rejected discounting to clear because demand is still strong, so stranded inventory is not the risk here.",
      }
    : {
        recommendedQuantity: 120,
        suggestedLeadTimeDays: 7,
        confidence: 0.71,
        reasoning:
          "Sizing to cover the lead time at roughly the current rate, with a modest buffer for continued elevated demand.",
      };

  const json = JSON.stringify(body);
  return mode === "fenced" ? "Here you go:\n```json\n" + json + "\n```\nHope that helps." : json;
}

/*
 * Real providers do not split on anything convenient, so neither does this: fixed-width slices
 * land mid-word and mid-escape, which is exactly the case the reasoning filter has to survive.
 */
function chunksOf(text, size) {
  const chunks = [];
  for (let i = 0; i < text.length; i += size) {
    chunks.push(text.slice(i, i + size));
  }
  return chunks;
}

function streamReply(res, prompt) {
  const full = reply(prompt);
  // Truncated mode cuts the document off mid-reasoning: the tokens look fine on screen, and only
  // the parse at the end reveals there is no usable recommendation.
  const text = mode === "truncated" ? full.slice(0, Math.floor(full.length * 0.6)) : full;
  const chunks = chunksOf(text, 7);

  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
  });

  let index = 0;
  const timer = setInterval(() => {
    if (index >= chunks.length) {
      clearInterval(timer);
      res.write("data: [DONE]\n\n");
      console.log(`  -> streamed ${chunks.length} chunks${mode === "truncated" ? " (truncated)" : ""}`);
      return res.end();
    }
    const frame = { choices: [{ delta: { content: chunks[index++] } }] };
    res.write(`data: ${JSON.stringify(frame)}\n\n`);
  }, 45);

  res.on("close", () => clearInterval(timer));
}

http
  .createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      if (mode === "error") {
        res.writeHead(429, { "Content-Type": "application/json" });
        return res.end(JSON.stringify({ error: { message: "rate limit exceeded" } }));
      }
      if (mode === "slow") {
        console.log("  -> hanging to trigger the client read timeout");
        return; // never respond
      }

      const request = JSON.parse(raw);
      const prompt = request.messages[0].content;

      if (request.stream) {
        return streamReply(res, prompt);
      }

      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          choices: [{ message: { role: "assistant", content: reply(prompt) } }],
        })
      );
    });
  })
  .listen(PORT, () => console.log(`fake LLM listening on :${PORT} in "${mode}" mode`));
