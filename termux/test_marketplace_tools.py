"""Offline regressions for Polish marketplace search/watch tools."""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class MarketplaceToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-marketplace-test-")
        self.env = patch.dict(
            os.environ,
            {
                "LUMENA_WORKSPACE": self.temp.name,
                "LUMENA_BRIDGE_TOKEN": "fixture-token",
                "LUMENA_BRAVE_API_KEY": "",
                "LUMENA_SEARXNG_URL": "",
            },
        )
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "marketplace_test_bridge",
            Path(__file__).with_name("bridge.py"),
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.b.MARKETPLACE_WATCHES_FILE = Path(self.temp.name) / "marketplace_watches.json"
        self.b.SEARCH_DIAGNOSTICS_FILE = Path(self.temp.name) / "web_search_diagnostics.json"
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def payload(self, result):
        self.assertTrue(result["ok"], result)
        return json.loads(result["stdout"])

    def search_result(self, rows):
        return {
            "ok": True,
            "exitCode": 0,
            "stdout": json.dumps(
                {
                    "provider": "fixture",
                    "results": rows,
                },
                ensure_ascii=False,
            ),
            "stderr": "",
            "error": None,
        }

    def test_olx_detail_identity_rejects_other_hosts_and_strips_query(self):
        self.assertIsNone(
            self.b._olx_pl_listing_identity(
                "https://example.org/d/oferta/job-IDabc.html"
            )
        )
        identity = self.b._olx_pl_listing_identity(
            "https://www.olx.pl/d/oferta/serwisant-legionowo-ID1AbC.html?reason=search"
        )
        self.assertEqual("olx-pl:1AbC", identity[0])
        self.assertEqual(
            "https://www.olx.pl/d/oferta/serwisant-legionowo-ID1AbC.html",
            identity[1],
        )

    def test_search_keeps_matching_olx_detail_and_marks_indexed_evidence(self):
        rows = [
            {
                "title": "Serwisant techniczny - Legionowo",
                "url": "https://www.olx.pl/d/oferta/serwisant-techniczny-legionowo-IDabc123.html",
                "snippet": "Praca dla serwisanta w Legionowie",
                "published": None,
            },
            {
                "title": "Unrelated result",
                "url": "https://example.org/job",
                "snippet": "not OLX",
                "published": None,
            },
            {
                "title": "OLX category",
                "url": "https://www.olx.pl/praca/legionowo/",
                "snippet": "Praca Legionowo",
                "published": None,
            },
        ]
        with patch.object(
            self.b,
            "web_search",
            return_value=self.search_result(rows),
        ):
            data = self.payload(
                self.b.marketplace_search(
                    {
                        "query": "serwisant",
                        "location": "Legionowo",
                        "category": "jobs",
                    }
                )
            )

        self.assertEqual("olx-pl", data["provider"])
        self.assertEqual("PL", data["market"])
        self.assertEqual("search-index-fallback", data["mode"])
        self.assertFalse(data["direct_olx_access"])
        self.assertEqual(1, data["item_count"])
        self.assertEqual("olx-pl:abc123", data["items"][0]["id"])
        self.assertEqual(1, len(data["discovery_pages"]))
        self.assertIn("first seen", data["evidence"])

    def test_watch_baselines_existing_items_then_reports_only_first_seen_new_id(self):
        baseline = [
            {
                "title": "Serwisant A - Legionowo",
                "url": "https://www.olx.pl/d/oferta/serwisant-a-legionowo-IDaaa.html",
                "snippet": "Praca serwisant Legionowo",
                "published": None,
            }
        ]
        later = baseline + [
            {
                "title": "Serwisant B - Legionowo",
                "url": "https://www.olx.pl/d/oferta/serwisant-b-legionowo-IDbbb.html",
                "snippet": "Nowa praca serwisant Legionowo",
                "published": None,
            }
        ]

        with patch.object(
            self.b,
            "web_search",
            return_value=self.search_result(baseline),
        ), patch.object(self.b.shutil, "which", return_value=None):
            created = self.payload(
                self.b.marketplace_watch_create(
                    {
                        "query": "serwisant",
                        "location": "Legionowo",
                        "category": "jobs",
                        "interval_minutes": "60",
                        "notify": "true",
                    }
                )
            )

        watch_id = created["created"]["id"]
        self.assertEqual(1, created["baseline_item_count"])
        self.assertEqual(0, created["created"]["last_new_count"])

        with patch.object(
            self.b,
            "web_search",
            return_value=self.search_result(later),
        ), patch.object(self.b.shutil, "which", return_value=None):
            polled = self.payload(
                self.b.marketplace_watch_poll({"watch_id": watch_id})
            )

        self.assertEqual(1, polled["new_count"])
        self.assertEqual("olx-pl:bbb", polled["new_items"][0]["id"])
        listed = self.payload(self.b.marketplace_watch_list({}))
        self.assertEqual(1, listed["watch_count"])
        self.assertEqual(2, listed["watches"][0]["seen_count"])
        self.assertEqual(1, listed["watches"][0]["last_new_count"])
        self.assertEqual(
            "termux-notification-unavailable",
            listed["watches"][0]["notification_status"],
        )


if __name__ == "__main__":
    unittest.main()
