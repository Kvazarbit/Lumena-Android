"""Offline network fixtures and real loopback framing regressions; no API keys needed."""
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

    def __init__(self, body, kind="text/html; charset=utf-8"):
        super().__init__(body.encode())
        self.headers = Message()
        self.headers["Content-Type"] = kind


class WebToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-web-test-")
        self.env = patch.dict(os.environ, {"LUMENA_WORKSPACE": self.temp.name, "LUMENA_BRIDGE_TOKEN": "fixture-token",
                                          "LUMENA_BRAVE_API_KEY": "", "LUMENA_SEARXNG_URL": ""})
        self.env.start()
        spec = importlib.util.spec_from_file_location("web_test_bridge", Path(__file__).with_name("bridge.py"))
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.env.stop)

    def payload(self, result):
        self.assertTrue(result["ok"], result)
        return json.loads(result["stdout"])

    def test_202_distinguishes_challenge_without_claiming_search_success(self):
        for body, expected in [("<form id='challenge-form'>verify</form>", "human verification"),
                               ("Accepted", "no completed response")]:
            response = Response(body)
            response.status = 202
            with patch.object(self.b.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]), \
                    patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", return_value=response):
                with self.assertRaisesRegex(ValueError, expected):
                    self.b._web_fetch("https://example.org")

    def test_http_202_becomes_terminal_search_failure_contract(self):
        response = Response("Accepted")
        response.status = 202
        with patch.object(self.b.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]), \
                patch.object(self.b.PUBLIC_HTTPS_OPENER, "open", return_value=response):
            result = self.b.web_search({"query": "latest world news"})

        self.assertFalse(result["ok"])
        payload = json.loads(result["stdout"])
        self.assertEqual([], payload["results"])
        self.assertEqual("duckduckgo", payload["attempts"][0]["provider"])
        self.assertIn("HTTP 202", payload["attempts"][0]["error"])
        self.assertIn("Search unavailable", result["error"])

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
        self.assertEqual("duckduckgo", data["provider"])
        self.assertNotIn("test-secret", result["stdout"])
        self.assertEqual(2, provider.call_count)

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
