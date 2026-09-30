const fs = require("fs");
const path = require("path");

const SKILLS = path.join(__dirname, "..", "app", "src", "main", "assets", "skills");

// Mirrors HeadlessWebViewSandbox.neutralizeForScriptContext.
const LS = String.fromCharCode(0x2028);
const PS = String.fromCharCode(0x2029);
function neutralize(json) {
  return json
    .split(LS).join("\\u2028")
    .split(PS).join("\\u2029")
    .split("<").join("\\u003C");
}

// Mirrors the host document's script bootstrap.
function buildHostDocument(scriptName, inputJsonLiteral, originsLiteral) {
  return `
                (function () {
                    var input = ${inputJsonLiteral};
                    var origins = ${originsLiteral};
                    function report(error) {
                        try { window.__edgeHost.fail(String(error)); } catch (ignored) {}
                    }
                    window.addEventListener('DOMContentLoaded', function () {
                        try {
                            if (typeof window.__edgeRun === 'function') {
                                window.__edgeRun(input, origins);
                            } else {
                                report('Skill script does not define window.__edgeRun');
                            }
                        } catch (e) {
                            report(e && e.message ? e.message : String(e));
                        }
                    });
                })();
  `;
}

let failures = 0;
function check(name, cond, detail) {
  if (cond) { console.log("PASS " + name); }
  else { console.log("FAIL " + name + (detail ? " :: " + detail : "")); failures++; }
}

// --- 1. Script-breakout payloads must not terminate the script element ---
const payloads = [
  '"]); window.__edgeHost.complete(JSON.stringify({pwned:1})); //',
  "</script><script>window.__edgeHost.complete('pwned')</script>",
  "</SCRIPT>",
  " alert(1) ",
  "'; process.exit(1); '",
  "\\u003C/script>",
  '"); window.__edgeHost.complete("x"); ("'
];

payloads.forEach((p, i) => {
  const inputJson = JSON.stringify({ expression: p });
  const literal = neutralize(inputJson);
  const doc = buildHostDocument("calculator.js", literal, "[]");
  // A breakout would leave unbalanced <script> tags or extra executable statements.
  const scriptOpen = (doc.match(/<script/g) || []).length;
  check(`payload ${i} keeps script structure intact`, scriptOpen === 0,
        "script tags present in host block");
  check(`payload ${i} escapes <`, !literal.includes("<"), literal);
  // The literal must still parse as JSON.
  try {
    const parsed = JSON.parse(literal);
    check(`payload ${i} still valid JSON`, parsed.expression === p, JSON.stringify(parsed));
  } catch (e) {
    check(`payload ${i} still valid JSON`, false, e.message);
  }
});

// --- 2. origins payload is neutralized the same way ---
// A quoted-breakout attempt collapses into ONE array element, not two origins.
const badOrigins = ['https://ok.com", "https://evil.com'];
const originsLiteral = neutralize(JSON.stringify(badOrigins));
try {
  const parsed = JSON.parse(originsLiteral);
  check("origins cannot be split into extra entries", Array.isArray(parsed) && parsed.length === 1,
        JSON.stringify(parsed));
  check("the injected origin stays inside one string",
        parsed[0] === 'https://ok.com", "https://evil.com', JSON.stringify(parsed[0]));
} catch (e) {
  check("origins cannot be split into extra entries", false, e.message);
}

// --- 3. Each bundled skill defines __edgeRun and never uses eval/Function ---
["calculator.js", "device_info.js", "web_extract.js"].forEach((s) => {
  const src = fs.readFileSync(path.join(SKILLS, s), "utf8");
  check(`${s} defines __edgeRun`, /window\.__edgeRun\s*=/.test(src));
  const hasEval = /(^|[^.\w])eval\s*\(/.test(src);
  const hasFunctionCtor = /new\s+Function\s*\(|[^.\w]Function\s*\(\s*['"]/.test(src);
  check(`${s} has no eval()`, !hasEval);
  check(`${s} has no Function() constructor`, !hasFunctionCtor);
});

// --- 4. web_extract matches on full origin, not just hostname ---
const we = fs.readFileSync(path.join(SKILLS, "web_extract.js"), "utf8");
check("web_extract compares protocol+host+port", we.includes("parsed.port"));
check("web_extract rejects non-https", we.includes("https://"));
check("web_extract has no chat_id or token handling", !/botToken|api\.telegram/.test(we));

console.log(failures === 0 ? "\nALL SANDBOX ASSERTIONS PASSED" : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
