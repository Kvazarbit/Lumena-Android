# Web research — Bridge 0.19 / Android build 24

## Follow-up and HTTP 202 regression fix

Standalone `повтори` / `retry` / `continue` now retain the previous task goal,
so intent routing sees the actual research request. The new task still has fresh
execution counters and approvals; historical observations do not prove completion.
A single registered tool key such as `{"web.search":{"query":"news"}}` is
normalized through the existing registry and permission checks. Ambiguous or
unknown dotted tool-key JSON requests protocol correction instead of finishing
as a successful plain-text answer.

The reported phone diagnostic showed only DuckDuckGo was attempted and returned
HTTP 202. That status alone does not establish CAPTCHA or loss of connectivity.
Bridge 0.19 inspects a bounded textual body for known challenge markers and
reports either human verification or an uncompleted response. Neither is search
evidence. This is a diagnostic repair, not a claim that DuckDuckGo now works on
the affected phone. Configure a supported provider below when it remains blocked.
Offline regressions cover both 202 cases; live phone/provider verification is
still required. APK signing continuity remains a separate release prerequisite.

`web.search` discovers source URLs. `web.read` retrieves readable evidence. Raw
`http.get` and `http.json` remain available for compatibility; their redirects
remain blocked. Use `web.read` for safely followed public-page redirects.

```json
{"tool":"web.search","args":{"query":"Python unittest official documentation","limit":"5","time_range":"month"}}
{"tool":"web.read","args":{"url":"https://docs.python.org/3/library/unittest.html","max_chars":"6000"}}
```

The query is required (1–400 characters); limit is 1–8; optional time_range is
day/week/month/year. Provider date filters are requests to the provider, not
independently verified publication dates. Keep queries focused; use `site:` for
known official sources. Explicit web goals without a supplied URL get a search
preflight so small models need not guess a homepage. The heuristic may need
refinement for ambiguous requests; it does not understand all natural language.

Results include provider, original query, source IDs, titles, URLs, bounded
snippets, publication metadata when supplied, retrieval time, and preceding
provider errors. Tracking query parameters and fragments are removed. Duplicate
URLs are omitted. Provider ranking is retained; no fabricated reliability score.
At most 6000 characters of result objects enter the payload. Successful searches
use a process-local LRU of 16 entries for 90 seconds, with cache age disclosed.
Errors are never cached. A cached response is not another independent observation.

`web.read` removes scripts/styles/navigation/forms and prefers main/article text.
Default output is 6000 characters; max_chars accepts 500–12000. It reports the
final URL, retrieval timestamp, source-supplied publication metadata and truncation.
The existing model-context budget can select less text than the raw tool result.
No JavaScript execution, browser login, CAPTCHA bypass or PDF extraction is included.
Some complex pages need their documented API or a different source. A nonempty
page or a search snippet alone does not establish factual accuracy.

## Providers

Order: configured Brave, configured SearXNG, then DuckDuckGo HTML. Each provider
is attempted once; empty/unreadable responses, access restrictions and failures
are disclosed. No repeated identical failed search loop is built into the tool.
Fallback sends the same query to the next provider in this order.

* Default: DuckDuckGo's public non-JavaScript HTML interface, without an API key.
  This is a best-effort HTML integration, not a guaranteed search API. Its markup
  or automated-access policy can change. Human-verification responses are failures.
* Optional: `LUMENA_BRAVE_API_KEY`, supplied to Bridge's environment, never tool args.
  Uses Brave's documented web search endpoint and X-Subscription-Token header.
  Requires a user-provided API subscription with suitable permitted use; no
  subscription or cost is created automatically. The adapter rejects redirects.
* Optional: `LUMENA_SEARXNG_URL=https://your-public-instance.example`, a user-selected
  public HTTPS base URL. The instance must enable JSON format. Authentication,
  nonstandard ports and local/private instances are not supported by this public
  web-fetch tool. Do not assume every public SearXNG instance supports the API.

Example optional Brave setup in Termux, without writing the secret into history:

```bash
read -rsp 'Brave API key: ' LUMENA_BRAVE_API_KEY
export LUMENA_BRAVE_API_KEY
python ~/.lumena/bridge.py
```

Restart an already-running Bridge for new environment settings. A manually
started shell's environment is not inherited by Android auto-start; persistent
provider configuration and a settings UI are future work. Default search requires
no extra Python packages or keys.

## Transport and evidence boundaries

Bridge returns explicit JSON Content-Length and Connection: close on success and
error, rejects incomplete/chunked/ambiguous request bodies, and closes each local
connection. Android also requests Connection: close. Implicit OkHttp retries and
redirects remain disabled. There is exactly one explicit retry for lost transport
responses to registry-confirmed READ_ONLY tools. A known HTTP tool error is not
replayed. Mutating/executable/unknown tools retain outcomeUnknown and stop.

An IOException mentioning `127.0.0.1:8765` is evidence of a failed local Bridge
response, not proof of a remote site's bot protection. A complete upstream 403
is different from a local EOF. The screenshots alone cannot establish the precise
cause of the reported disconnect; framing and replay regressions test the fixes.
Final read transport failure is negative execution evidence (no usable result),
not proof that the external source itself is unreliable.

Public fetches have an 8-second socket timeout, at most three redirects, and a
2 MiB response cap. These are per-socket limits, not a hard end-to-end deadline:
DNS resolution and multiple public addresses can add latency. Each hop validates
HTTPS, port 443, no URL credentials and public IPs. Connections pin a checked IP
while preserving TLS SNI/hostname verification, preventing a second DNS lookup
from redirecting the socket to an unchecked address. Custom headers are removed
across redirects; the Brave adapter disallows redirects entirely.

Web text is untrusted data, not instructions. Model prompts require cited source
URLs, distinguishing snippets/facts/inference and honest partial completion.
This release does not implement a semantic claim-to-citation validator. It cannot
guarantee weak-model answer correctness; that is a separate roadmap stage.

## Validation and remaining checks

Automated fixtures cover provider fallback, URL extraction/deduplication, cache
expiry/bounds, blocked challenges, article extraction, output/response limits,
public/private redirects, DNS pinning, TLS hostname preservation and real local
HTTP error framing. Android loopback socket tests cover one lost-read retry,
unknown mutations without replay and known tool errors without retries.

The development shell could not resolve the live search provider during this
change (`Temporary failure in name resolution`). Consequently live provider
availability and phone-specific network behavior are not claimed as verified.
After installation, run one focused search and open two returned sources. Check
offline behavior by disabling the network. Build both APK and Bridge from this
release; an older Bridge does not implement the new tools.

Primary provider references checked for this change:

* [Brave Web Search documentation](https://api-dashboard.search.brave.com/documentation/services/web-search)
* [SearXNG Search API](https://docs.searxng.org/dev/search_api.html)
* [DuckDuckGo non-JavaScript interfaces](https://duckduckgo.com/duckduckgo-help-pages/features/non-javascript)
