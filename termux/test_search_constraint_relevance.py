"""Relevance checks: retrieval success is not user-goal evidence."""

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class ExplicitSearchConstraintTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-search-constraint-")
        self.env = patch.dict(os.environ, {
            "LUMENA_WORKSPACE": self.temp.name,
            "LUMENA_BRIDGE_TOKEN": "test-token",
            "LUMENA_BRAVE_API_KEY": "",
            "LUMENA_SEARXNG_URL": ""
        })
        self.env.start()
        spec = importlib.util.spec_from_file_location(
            "search_constraint_bridge", Path(__file__).with_name("bridge.py")
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.b.SEARCH_CACHE.clear()
        self.b.SEARCH_DIAGNOSTICS_FILE = Path(self.temp.name) / "search_diag.json"
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def test_unrelated_provider_results_fail_explicit_quoted_query(self):
        unrelated = {
            "url": "https://example.org/delivery",
            "title": "Package tracking",
            "snippet": "Track your parcels",
        }
        with patch.object(self.b, "_search_provider", return_value=[unrelated]) as provider:
            result = self.b.web_search({"query": '"Amanda Lopez" "Edgar Mattey"'})
        self.assertFalse(result["ok"])
        self.assertEqual("SEARCH_NO_MATCH", result["errorCode"])
        self.assertEqual("NO_RELEVANT_RESULTS", result["failureClass"])
        self.assertEqual(3, provider.call_count)
        self.assertEqual([], json.loads(result["stdout"])["results"])

    def test_ignores_provider_results_missing_site_query_keyword(self):
        unrelated = {
            "url": "https://www.example.org/galleries/another-model/",
            "title": "Beautiful portraits",
            "snippet": "Portrait photography gallery",
        }
        with patch.object(self.b, "_search_provider", return_value=[unrelated]):
            result = self.b.web_search({
                "query": "site:example.org/galleries/ Wandy met-art"
            })
        self.assertFalse(result["ok"])
        self.assertEqual("SEARCH_NO_MATCH", result["errorCode"])
        self.assertEqual("NO_RELEVANT_RESULTS", result["failureClass"])

    def test_relevant_site_and_name_candidate_passes_as_untrusted_lead(self):
        candidate = {
            "url": "https://www.example.org/galleries/wandy-pan-111/",
            "title": "Wandy Pan photo series",
            "snippet": "Photo set of Wandy Pan",
        }
        with patch.object(self.b, "_search_provider", return_value=[candidate]):
            result = self.b.web_search({
                "query": 'site:example.org/galleries/ "Wandy Pan"'
            })
        self.assertTrue(result["ok"])
        payload = json.loads(result["stdout"])
        self.assertEqual(1, len(payload["results"]))
        self.assertIn("Untrusted search snippets", payload["evidence"])

    def test_regular_semantic_search_keeps_previous_behavior(self):
        candidate = {
            "url": "https://example.org/topic",
            "title": "General reference",
            "snippet": "Maybe relevant",
        }
        with patch.object(self.b, "_search_provider", return_value=[candidate]):
            result = self.b.web_search({"query": "find an unusual topic"})
        self.assertTrue(result["ok"])


if __name__ == "__main__":
    unittest.main()
