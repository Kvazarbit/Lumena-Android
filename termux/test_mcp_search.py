"""Offline regressions for Lumena's external MCP search broker."""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class McpSearchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumena-mcp-search-test-")
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
            "mcp_search_test_bridge",
            Path(__file__).with_name("bridge.py"),
        )
        self.b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.b)
        self.b.MCP_SEARCH_CONFIG_FILE = Path(self.temp.name) / "mcp_search_providers.json"
        self.addCleanup(self.env.stop)
        self.addCleanup(self.temp.cleanup)

    def payload(self, result):
        self.assertTrue(result["ok"], result)
        return json.loads(result["stdout"])

    def test_unconfigured_mcp_search_fails_closed_with_explicit_code(self):
        result = self.b.mcp_search({"query": "serwisant Legionowo"})
        self.assertFalse(result["ok"])
        self.assertEqual("MCP_NOT_CONFIGURED", result["errorCode"])
        self.assertEqual("mcp.search", result["dependency"])
        self.assertIn("web.search", result["error"])

    def test_selector_requires_read_only_hint_and_maps_supported_args(self):
        tools = [
            {
                "name": "dangerous_search_and_message",
                "description": "search listings and send a message",
                "annotations": {"readOnlyHint": False},
                "inputSchema": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
            {
                "name": "search_jobs",
                "description": "Search job listings",
                "annotations": {"readOnlyHint": True},
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "location": {"type": "string"},
                        "limit": {"type": "integer"},
                    },
                    "required": ["query"],
                },
            },
            {
                "name": "find_without_annotation",
                "description": "Find things",
                "inputSchema": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
        ]

        selected = self.b._mcp_select_search_tool(
            tools,
            configured_name="",
            query="serwisant",
            location="Legionowo",
            category="jobs",
            limit=7,
        )
        self.assertIsNotNone(selected)
        tool, args = selected
        self.assertEqual("search_jobs", tool["name"])
        self.assertEqual(
            {"query": "serwisant", "location": "Legionowo", "limit": 7},
            args,
        )

    def test_selector_rejects_unknown_required_arguments(self):
        selected = self.b._mcp_select_search_tool(
            [
                {
                    "name": "search_jobs",
                    "description": "Search job listings",
                    "annotations": {"readOnlyHint": True},
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "query": {"type": "string"},
                            "tenant_id": {"type": "string"},
                        },
                        "required": ["query", "tenant_id"],
                    },
                }
            ],
            configured_name="",
            query="serwisant",
            location="Legionowo",
            category="jobs",
            limit=5,
        )
        self.assertIsNone(selected)

    def test_mcp_search_returns_provider_provenance_and_content(self):
        providers = [
            {
                "id": "jobs-fixture",
                "url": "https://mcp.example.org/mcp",
                "search_tool": "",
                "auth_env": "",
            }
        ]
        provider_result = {
            "provider": "jobs-fixture",
            "tool": "search_jobs",
            "protocol_version": self.b.MCP_PROTOCOL_VERSION,
            "arguments": {
                "query": "serwisant",
                "location": "Legionowo",
                "limit": 3,
            },
            "content": ["Technik serwisu — Legionowo"],
            "structured_content": {
                "items": [{"id": "job-1", "title": "Technik serwisu"}]
            },
            "is_error": False,
        }
        with patch.object(
            self.b,
            "_load_mcp_search_providers",
            return_value=providers,
        ), patch.object(
            self.b,
            "_mcp_search_provider",
            return_value=provider_result,
        ) as call:
            data = self.payload(
                self.b.mcp_search(
                    {
                        "query": "serwisant",
                        "location": "Legionowo",
                        "limit": "3",
                    },
                    request_id="req-mcp-1",
                )
            )

        self.assertEqual("mcp", data["mode"])
        self.assertEqual("jobs-fixture", data["provider"])
        self.assertEqual("search_jobs", data["tool"])
        self.assertEqual(["Technik serwisu — Legionowo"], data["content"])
        self.assertEqual("job-1", data["structured_content"]["items"][0]["id"])
        self.assertEqual("req-mcp-1", data["request_id"])
        call.assert_called_once()

    def test_config_never_embeds_bearer_token_and_uses_env_reference(self):
        self.b.MCP_SEARCH_CONFIG_FILE.write_text(
            json.dumps(
                {
                    "providers": [
                        {
                            "id": "secure",
                            "url": "https://mcp.example.org/mcp",
                            "auth_env": "MCP_SECURE_TOKEN",
                            "search_tool": "search",
                        }
                    ]
                }
            ),
            encoding="utf-8",
        )
        with patch.object(
            self.b,
            "_validated_public_https_url",
            side_effect=lambda value: value,
        ):
            providers = self.b._load_mcp_search_providers()

        self.assertEqual("MCP_SECURE_TOKEN", providers[0]["auth_env"])
        self.assertNotIn("token", providers[0])


if __name__ == "__main__":
    unittest.main()
