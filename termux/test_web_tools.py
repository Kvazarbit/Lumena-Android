"""Offline network fixtures and real loopback framing regressions; no API keys needed."""
import gzip
import http.client
import importlib.util
import io
import json
import os
import socket
import tempfile
import threading
import unittest
import urllib.error
from email.message import Message
from pathlib import Path
from unittest.mock import patch, Mock


class Response(io.BytesIO):
    status = 200

    def __init__(
        self,
        body,
        kind="text/html; charset=utf-8",
        content_encoding=None,
    ):
        payload = body if isinstance(body, bytes) else body.encode()
        super().__init__(payload)
        self.headers = Message()
        self.headers["Content-Type"] = kind
        if content_encoding:
            self.headers["Content-Encoding"] = content_encoding


class WebToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-web-test-")
        self.env = patch.dict(os.environ, {"LUMENA_WORKSPACE": self.temp.name, "LUMENA_BRIDGE_TOKEN": "fixture-token",
                                          "LUMENA_BRAVE_API_KEY": "", "LUMENA_SEARXNG_URL": ""})
        self.env.start()
        spec = importlib.util.spec_from_file_location("web_test_bridge", Path(__file__).with_name("bridge.py"))
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.b.SEARCH_DIAGNOSTICS_FILE = Path(self.temp.name) / "web_search_diagnostics.json"
        self.b.BRIDGE_RUN_ID = "fixture-run"
        self.b.SEARCH_CACHE.clear()
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.env.stop)

    def payload(self, result):
        self.assertTrue(result["ok"], result)
        return json.loads(result["stdout"])

    def test_transport_artifacts_are_repaired_before_url_validation(self):
        with patch.object(
                self.b.socket,
                "getaddrinfo",
                return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))]
        ):
            self.assertEqual(
                "https://example.org/path",
                self.b._validated_public_https_url("ht\u2060tps://exa\ufeffmple.org/path")
            )
            self.assertEqual(
                "https://example.org/path",
                self.b._validated_public_https_url("https://exa\ufffdmple.org/path")
            )

    def test_transport_repair_cannot_bypass_network_security(self):
        def resolve(host, *args, **kwargs):
            address = "127.0.0.1" if host == "127.0.0.1" else "93.184.216.34"
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 443))]

        with patch.object(self.b.socket, "getaddrinfo", side_effect=resolve):
            with self.assertRaisesRegex(ValueError, "Non-public"):
                self.b._validated_public_https_url("https://127.0.0.\u20601")
            with self.assertRaisesRegex(ValueError, "HTTPS only"):
                self.b._validated_public_https_url("ht\u2060tp://example.org")
            with self.assertRaisesRegex(ValueError, "forbidden zero-width"):
                self.b._validated_public_https_url("https://exam\u200bple.org")
            with self.assertRaisesRegex(ValueError, "ambiguous transport corruption"):
                self.b._validated_public_https_url("https://b\ufffdücher.example")

    def test_202_distinguishes_challenge_without_claiming_search_success(self):
        for body, expected in [("<form id='challenge-form'>verify</form>", "human verification"),
                               ("Accepted", "no completed response")]:
            response = Response(body)
            response.status = 202
            with patch.object(self.b.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]), \
                    patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", return_value=response):
                with self.assertRaisesRegex(ValueError, expected):
                    self.b._web_fetch("https://example.org")

    def test_exhausted_search_provider_returns_one_failed_tool_result(self):
        with patch.object(
                self.b,
                "_search_provider",
                side_effect=ValueError("Upstream HTTP 202; human verification page detected; no usable evidence")
        ) as provider:
            result = self.b.execute_tool("web.search", {"query": "latest news"})

        self.assertFalse(result["ok"])
        self.assertEqual(1, result["exitCode"])
        self.assertEqual(3, provider.call_count)
        payload = json.loads(result["stdout"])
        self.assertEqual([], payload["results"])
        self.assertEqual(["duckduckgo-lite", "bing-rss", "duckduckgo"],
                         [attempt["provider"] for attempt in payload["attempts"]])
        self.assertIn("HTTP 202", payload["attempts"][0]["error"])
        self.assertIn("Search unavailable", result["error"])
        self.assertEqual("SEARCH_EXHAUSTED", result["errorCode"])
        self.assertEqual("DEPENDENCY_EXHAUSTED", result["failureClass"])
        self.assertFalse(result["retryable"])
        self.assertEqual("web.search", result["dependency"])

    def test_search_success_persists_safe_bounded_diagnostics(self):
        with patch.object(
                self.b,
                "_search_provider",
                return_value=[{
                    "url": "https://example.org/news",
                    "title": "Result",
                    "snippet": "Evidence body that must not be persisted verbatim",
                }],
        ):
            result = self.b.web_search(
                {"query": "private diagnostic query text", "limit": 3},
                request_id="req-search-success",
            )

        self.assertTrue(result["ok"])
        stored = json.loads(self.b.SEARCH_DIAGNOSTICS_FILE.read_text(encoding="utf-8"))
        self.assertEqual("success", stored["status"])
        self.assertEqual("complete", stored["stage"])
        self.assertEqual("duckduckgo-lite", stored["provider"])
        self.assertEqual("req-search-success", stored["request_id"])
        self.assertEqual("fixture-run", stored["bridge_run_id"])
        self.assertEqual(1, stored["result_count"])
        raw = self.b.SEARCH_DIAGNOSTICS_FILE.read_text(encoding="utf-8")
        self.assertNotIn("private diagnostic query text", raw)
        self.assertNotIn("Evidence body that must not be persisted verbatim", raw)

    def test_search_exhaustion_persists_dependency_failure_without_query(self):
        with patch.object(
                self.b,
                "_search_provider",
                side_effect=ValueError("fixture provider unavailable"),
        ):
            result = self.b.web_search(
                {"query": "sensitive search phrase"},
                request_id="req-search-fail",
            )

        self.assertFalse(result["ok"])
        self.assertEqual("SEARCH_EXHAUSTED", result["errorCode"])
        stored = json.loads(self.b.SEARCH_DIAGNOSTICS_FILE.read_text(encoding="utf-8"))
        self.assertEqual("dependency_exhausted", stored["status"])
        self.assertEqual("SEARCH_EXHAUSTED", stored["error_code"])
        self.assertEqual("req-search-fail", stored["request_id"])
        self.assertIn("fixture provider unavailable", stored["last_error"])
        self.assertNotIn(
            "sensitive search phrase",
            self.b.SEARCH_DIAGNOSTICS_FILE.read_text(encoding="utf-8"),
        )

    def test_previous_running_search_is_marked_interrupted_after_bridge_restart(self):
        previous = {
            "bridge_run_id": "old-run",
            "status": "running",
            "stage": "provider_request",
            "provider": "bing-rss",
            "request_id": "req-before-restart",
            "updated_at": "2026-09-23T00:00:00+02:00",
            "elapsed_ms": 1500,
            "result_count": None,
            "result_chars": None,
            "error_code": None,
            "last_error": None,
        }
        self.b.SEARCH_DIAGNOSTICS_FILE.write_text(
            json.dumps(previous),
            encoding="utf-8",
        )
        self.b.BRIDGE_RUN_ID = "new-run"

        self.b._mark_interrupted_search_from_previous_run()

        stored = json.loads(self.b.SEARCH_DIAGNOSTICS_FILE.read_text(encoding="utf-8"))
        self.assertEqual("interrupted_before_result", stored["status"])
        self.assertEqual("bridge_restart_observed", stored["stage"])
        self.assertEqual("SEARCH_INTERRUPTED", stored["error_code"])
        self.assertEqual("new-run", stored["detected_by_bridge_run_id"])
        self.assertIn("cause is unknown", stored["last_error"])

        health = self.b.execute_tool("health", {})
        self.assertTrue(health["ok"])
        self.assertIn(
            "last_web_search_status=interrupted_before_result",
            health["stdout"],
        )
        self.assertIn(
            "last_web_search_request_id=req-before-restart",
            health["stdout"],
        )

    def test_current_run_running_marker_is_not_mislabeled_interrupted(self):
        current = {
            "bridge_run_id": "fixture-run",
            "status": "running",
            "stage": "provider_request",
            "provider": "duckduckgo-lite",
            "request_id": "req-current",
        }
        self.b.SEARCH_DIAGNOSTICS_FILE.write_text(
            json.dumps(current),
            encoding="utf-8",
        )

        self.b._mark_interrupted_search_from_previous_run()

        stored = json.loads(self.b.SEARCH_DIAGNOSTICS_FILE.read_text(encoding="utf-8"))
        self.assertEqual("running", stored["status"])
        self.assertEqual("fixture-run", stored["bridge_run_id"])

    def test_search_extracts_real_urls_unwraps_deduplicates_and_caches(self):
        html = '''<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.example.org%2Fguide%3Futm_source%3Dx">Python <b>guide</b></a>
        <a class="result__snippet">Verified <b>documentation</b></a>
        <a class="result__a" href="https://docs.example.org/guide">Duplicate</a>
        <a class="result__a" href="http://127.0.0.1/private">Bad</a>'''
        with patch.object(self.b, "_web_fetch", return_value=("https://html.duckduckgo.com/html/", "text/html", html)) as fetch:
            data = self.payload(self.b.execute_tool("web.search", {"query": "Python guide"}))
            again = self.payload(self.b.web_search({"query": "Python guide"}))
        self.assertEqual(1, len(data["results"]))
        self.assertEqual("https://docs.example.org/guide", data["results"][0]["url"])
        self.assertEqual("Verified documentation", data["results"][0]["snippet"])
        self.assertTrue(again["cached"])
        self.assertEqual(1, fetch.call_count)

    def test_provider_failure_falls_back_without_exposing_key(self):
        with patch.dict(os.environ, {"LUMENA_BRAVE_API_KEY": "test-secret"}), \
                patch.object(self.b, "_search_provider", side_effect=[ValueError("test-secret denied"),
                             [{"url": "https://example.org/page", "title": "Found", "snippet": "source"}]]) as provider:
            result = self.b.web_search({"query": "actual query"})
        data = self.payload(result)
        self.assertEqual("duckduckgo-lite", data["provider"])
        self.assertNotIn("test-secret", result["stdout"])
        self.assertEqual(2, provider.call_count)

    def test_keyless_provider_chain_survives_duck_challenge_with_bing_rss(self):
        with patch.object(
                self.b,
                "_search_provider",
                side_effect=[
                    ValueError("human verification page detected"),
                    [{"url": "https://example.org/news", "title": "News", "snippet": "Evidence"}],
                ],
        ) as provider:
            data = self.payload(self.b.web_search({"query": "current news"}))

        self.assertEqual("bing-rss", data["provider"])
        self.assertEqual(2, provider.call_count)
        self.assertEqual("duckduckgo-lite", data["attempts"][0]["provider"])

    def test_bing_rss_is_keyless_and_parses_results(self):
        body = """<?xml version="1.0"?><rss><channel>
        <item><title>Example result</title><link>https://example.org/article</link>
        <description>Useful snippet</description><pubDate>Tue, 22 Sep 2026 10:00:00 GMT</pubDate></item>
        </channel></rss>"""
        with patch.object(
                self.b,
                "_web_fetch",
                return_value=("https://www.bing.com/search", "application/rss+xml", body),
        ) as fetch:
            results = self.b._search_provider("bing-rss", "test query", 3, "", "", "")

        self.assertEqual("Example result", results[0]["title"])
        self.assertEqual("https://example.org/article", results[0]["url"])
        self.assertEqual("Useful snippet", results[0]["snippet"])
        self.assertIn("format=rss", fetch.call_args.args[0])
        self.assertIn("q=test+query", fetch.call_args.args[0])

    def test_duckduckgo_lite_parser_accepts_lite_markup(self):
        html = """<a class="result-link" href="https://example.org/lite">Lite result</a>
        <td class="result-snippet">Lite snippet</td>"""
        with patch.object(
                self.b,
                "_web_fetch",
                return_value=("https://lite.duckduckgo.com/lite/", "text/html", html),
        ):
            results = self.b._search_provider("duckduckgo-lite", "test query", 3, "", "", "")

        self.assertEqual("Lite result", results[0]["title"])
        self.assertEqual("Lite snippet", results[0]["snippet"])

    def test_documented_api_adapters_send_filters_and_parse_results(self):
        body = json.dumps({"web": {"results": [{"title": "Docs", "url": "https://example.org/doc", "description": "Description"}]}})
        with patch.object(self.b, "_web_fetch", return_value=("https://api.search.brave.com", "application/json", body)) as fetch:
            results = self.b._search_provider("brave", "test query", 3, "week", "fixture-key", "")
        self.assertEqual("Description", results[0]["snippet"])
        self.assertIn("freshness=pw", fetch.call_args.args[0])
        self.assertEqual(0, fetch.call_args.kwargs["redirects"])
        self.assertEqual("fixture-key", fetch.call_args.kwargs["headers"]["X-Subscription-Token"])
        body = json.dumps({"results": [{"title": "Docs", "url": "https://example.org/doc", "content": "Snippet"}]})
        with patch.object(self.b, "_web_fetch", return_value=("https://search.example.org", "application/json", body)) as fetch:
            results = self.b._search_provider("searxng", "test query", 3, "month", "", "https://search.example.org")
        self.assertEqual("Snippet", results[0]["snippet"])
        self.assertIn("format=json", fetch.call_args.args[0])
        self.assertIn("time_range=month", fetch.call_args.args[0])

    def test_challenge_and_empty_search_are_failures_not_fabricated_results(self):
        for html in ('<form id="challenge-form">human?</form>', "<html>No results</html>"):
            with patch.object(self.b, "_web_fetch", return_value=("https://html.duckduckgo.com/", "text/html", html)):
                result = self.b.web_search({"query": "query"})
            self.assertFalse(result["ok"])
            self.assertEqual([], json.loads(result["stdout"])["results"])

    def test_search_cache_is_bounded_and_expires(self):
        with patch.object(self.b, "_search_provider", return_value=[{"url": "https://example.org/page", "title": "Found"}]) as provider, \
                patch.object(self.b.time, "monotonic", return_value=100):
            for i in range(18):
                self.b.web_search({"query": f"query {i}"})
            self.assertEqual(16, len(self.b.SEARCH_CACHE))
            with patch.object(self.b.time, "monotonic", return_value=191):
                data = self.payload(self.b.web_search({"query": "query 17"}))
            self.assertFalse(data["cached"])
            self.assertEqual(19, provider.call_count)

    def test_gzip_json_provider_response_is_decoded_before_parsing(self):
        payload = {"query": {"pages": []}}
        compressed = gzip.compress(json.dumps(payload).encode("utf-8"))
        response = Response(
            compressed,
            "application/json; charset=utf-8",
            content_encoding="gzip",
        )
        request = urllib.request.Request("https://example.org/api")
        with patch.object(
                self.b.PUBLIC_HTTPS_OPENER,
                "open",
                return_value=response,
        ):
            decoded = self.b._read_json_response(
                request,
                5,
                "fixture",
            )

        self.assertEqual(payload, decoded)

    def test_gzip_web_response_is_decoded_before_html_parsing(self):
        html = (
            "<html><head><title>Python Blogs</title></head>"
            "<body><main><h1>Python news</h1><p>"
            + ("Release notes and community updates. " * 20)
            + "</p></main></body></html>"
        )
        compressed = gzip.compress(html.encode("utf-8"))
        response = Response(
            compressed,
            "text/html; charset=utf-8",
            content_encoding="gzip",
        )
        with patch.object(
                self.b,
                "_validated_public_https_url",
                side_effect=lambda url: url,
        ), patch.object(
                self.b.PUBLIC_HTTPS_OPENER,
                "open",
                return_value=response,
        ):
            data = self.payload(
                self.b.web_read({"url": "https://www.python.org/blogs/"})
            )

        self.assertEqual("Python Blogs", data["title"])
        self.assertIn("Python news", data["text"])
        self.assertIn("Release notes and community updates", data["text"])
        self.assertNotIn("\ufffd", data["text"])

    def test_gzip_magic_is_decoded_even_without_content_encoding_header(self):
        html = "<main><p>" + ("Readable Python source. " * 20) + "</p></main>"
        response = Response(gzip.compress(html.encode("utf-8")))
        with patch.object(
                self.b,
                "_validated_public_https_url",
                side_effect=lambda url: url,
        ), patch.object(
                self.b.PUBLIC_HTTPS_OPENER,
                "open",
                return_value=response,
        ):
            url, kind, body = self.b._web_fetch("https://example.org/article")

        self.assertEqual("https://example.org/article", url)
        self.assertEqual("text/html", kind)
        self.assertIn("Readable Python source", body)

    def test_gzip_decoded_size_limit_blocks_decompression_bomb(self):
        compressed = gzip.compress(b"x" * (self.b.MAX_HTTP_JSON + 1024))
        response = Response(
            compressed,
            "text/html; charset=utf-8",
            content_encoding="gzip",
        )
        with patch.object(
                self.b,
                "_validated_public_https_url",
                side_effect=lambda url: url,
        ), patch.object(
                self.b.PUBLIC_HTTPS_OPENER,
                "open",
                return_value=response,
        ):
            with self.assertRaisesRegex(ValueError, "decoded response exceeds"):
                self.b._web_fetch("https://example.org/huge")

    def test_read_extracts_article_limits_output_and_preserves_source_metadata(self):
        html = '<html><head><title>Actual title</title><meta property="article:published_time" content="2026-09-01"></head>' \
               '<body><nav>MENU</nav><script>DO_NOT_EXECUTE</script><main><h1>Research</h1><p>' + 'Evidence. ' * 400 + '</p></main><footer>FOOTER</footer></body></html>'
        with patch.object(self.b, "_web_fetch", return_value=("https://example.org/article", "text/html", html)):
            data = self.payload(self.b.execute_tool("web.read", {"url": "https://example.org", "max_chars": "500"}))
        self.assertEqual("Actual title", data["title"])
        self.assertEqual("2026-09-01", data["published"])
        self.assertEqual("https://example.org/article", data["url"])
        self.assertEqual(500, len(data["text"]))
        self.assertTrue(data["truncated"])
        for unwanted in ("MENU", "DO_NOT_EXECUTE", "FOOTER"):
            self.assertNotIn(unwanted, data["text"])

    def test_web_read_retries_timeout_once_then_succeeds(self):
        html = "<main><h1>Recovered</h1><p>" + ("Evidence. " * 40) + "</p></main>"
        with patch.object(
                self.b,
                "_web_fetch",
                side_effect=[
                    ValueError("Public HTTPS transport failed (TimeoutError)"),
                    ("https://example.org/article", "text/html", html),
                ],
        ) as fetch:
            data = self.payload(
                self.b.web_read({"url": "https://example.org/article"})
            )

        self.assertEqual("https://example.org/article", data["url"])
        self.assertEqual(2, fetch.call_count)
        self.assertEqual(
            12,
            fetch.call_args_list[1].kwargs["timeout_seconds"],
        )

    def test_web_read_does_not_retry_human_verification(self):
        with patch.object(
                self.b,
                "_web_fetch",
                side_effect=ValueError(
                    "Page requires human verification; use another source"
                ),
        ) as fetch:
            with self.assertRaisesRegex(ValueError, "human verification"):
                self.b.web_read({"url": "https://example.org/article"})

        self.assertEqual(1, fetch.call_count)

    def test_read_does_not_report_empty_javascript_shell_as_success(self):
        with patch.object(self.b, "_web_fetch", return_value=("https://example.org", "text/html", "<script>content()</script>")):
            with self.assertRaisesRegex(ValueError, "too little"):
                self.b.web_read({"url": "https://example.org"})

    def test_redirect_revalidates_and_never_opens_private_destination(self):
        headers = Message()
        headers["Location"] = "https://127.0.0.1/private"
        error = urllib.error.HTTPError("https://example.org", 302, "Redirect", headers, io.BytesIO())
        def resolve(host, *args, **kwargs):
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1" if host == "127.0.0.1" else "93.184.216.34", 443))]
        with patch.object(self.b.socket, "getaddrinfo", side_effect=resolve), \
                patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", side_effect=error) as opener:
            with self.assertRaisesRegex(ValueError, "Non-public"):
                self.b._web_fetch("https://example.org")
        self.assertEqual(1, opener.call_count)

    def test_public_redirect_succeeds_and_strips_credentials(self):
        headers = Message()
        headers["Location"] = "https://other.example/article"
        error = urllib.error.HTTPError("https://example.org", 302, "Redirect", headers, io.BytesIO())
        with patch.object(self.b, "_validated_public_https_url", side_effect=lambda url: url) as validate, \
                patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", side_effect=[error, Response("public " * 30)]) as opener:
            url, _, _ = self.b._web_fetch("https://example.org", headers={"X-Subscription-Token": "secret"})
        self.assertEqual("https://other.example/article", url)
        self.assertEqual(2, validate.call_count)
        self.assertNotIn("X-subscription-token", opener.call_args_list[1].args[0].headers)

    def test_redirect_loop_and_http_downgrade_are_rejected(self):
        for target in ("https://example.org", "http://example.org/insecure"):
            headers = Message()
            headers["Location"] = target
            error = urllib.error.HTTPError("https://example.org", 302, "Redirect", headers, io.BytesIO())
            with patch.object(self.b.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]), \
                    patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", side_effect=error) as opener:
                with self.assertRaises(ValueError):
                    self.b._web_fetch("https://example.org")
            self.assertEqual(1, opener.call_count)

    def test_connected_ip_is_pinned_and_tls_keeps_original_hostname(self):
        connection = self.b.PublicHTTPSConnection("example.org", timeout=1)
        fake_socket, context = Mock(), Mock()
        connection._context = context
        with patch.object(self.b.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]), \
                patch.object(self.b.socket, "socket", return_value=fake_socket):
            connection.connect()
        fake_socket.connect.assert_called_once_with(("93.184.216.34", 443))
        context.wrap_socket.assert_called_once_with(fake_socket, server_hostname="example.org")

    def test_dns_rebinding_and_mixed_public_private_answers_cannot_connect(self):
        connection = self.b.PublicHTTPSConnection("example.org", timeout=1)
        with patch.object(self.b.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443)), (2, 1, 6, "", ("127.0.0.1", 443))]), \
                patch.object(self.b.socket, "socket") as sock:
            with self.assertRaisesRegex(ValueError, "Non-public"):
                connection.connect()
        sock.assert_not_called()

    def test_oversize_or_non_text_response_is_rejected(self):
        for response in (Response("x" * (self.b.MAX_HTTP_JSON + 1)), Response("pdf", "application/pdf")):
            with patch.object(self.b, "_validated_public_https_url", side_effect=lambda url: url), \
                    patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", return_value=response):
                with self.assertRaises(ValueError):
                    self.b._web_fetch("https://example.org")

    def test_real_http_errors_have_complete_json_and_next_request_works(self):
        server = self.b.ThreadingHTTPServer(("127.0.0.1", 0), self.b.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            for body, expected in ((b"{broken", 400), (json.dumps({"tool": "health", "args": {}}).encode(), 200)):
                connection.request("POST", "/tool", body, {"Authorization": "Bearer fixture-token"})
                response = connection.getresponse()
                raw = response.read()
                self.assertEqual(expected, response.status)
                self.assertEqual(str(len(raw)), response.getheader("Content-Length"))
                self.assertEqual("close", response.getheader("Connection"))
                self.assertEqual(expected == 200, json.loads(raw)["ok"])
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
