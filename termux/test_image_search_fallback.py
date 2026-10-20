"""Image provider read timeouts are local failures; never make real network calls."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch


class FakeResponse:
    def __init__(self, payload=None, timeout=False):
        self.body = json.dumps(payload or {}).encode("utf-8")
        self.timeout = timeout
        self.headers = {"Content-Type": "application/json"}

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        return False

    def read(self, size):
        if self.timeout:
            raise TimeoutError("The read operation timed out")
        return self.body


class ImageSearchFallbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("lumena_image_bridge", Path(__file__).with_name("bridge.py"))
        cls.bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.bridge)

    def test_wikimedia_read_timeout_uses_openverse(self):
        calls = []
        payload = {"results": [{"id": "abc-123", "title": "Portrait",
            "thumbnail": "https://api.openverse.org/v1/images/abc-123/thumb/",
            "filetype": "jpeg", "width": 640, "height": 480}]}

        class Opener:
            def open(self, request, timeout):
                calls.append(request.full_url)
                if "commons.wikimedia.org" in request.full_url:
                    return FakeResponse(timeout=True)
                return FakeResponse(payload)

        with patch.object(self.bridge, "PUBLIC_HTTPS_OPENER", Opener()):
            result = self.bridge.image_search({"query": "portrait", "limit": "2"})
        self.assertTrue(result["ok"])
        data = json.loads(result["stdout"])
        self.assertEqual(["Openverse"], data["providers_used"])
        self.assertTrue(any("commons.wikimedia.org" in url for url in calls))
        self.assertTrue(any("api.openverse.org" in url for url in calls))

    def test_two_provider_read_timeouts_report_bounded_failure(self):
        class Opener:
            def open(self, request, timeout):
                return FakeResponse(timeout=True)

        with patch.object(self.bridge, "PUBLIC_HTTPS_OPENER", Opener()):
            with self.assertRaisesRegex(ValueError, "No displayable images"):
                self.bridge.image_search({"query": "portrait", "limit": 1})

    def test_curated_public_cdn_previews_without_private_or_spoofed_urls(self):
        thumbnails = [
            "https://images.unsplash.com/photo-123?auto=format",
            "https://live.staticflickr.com/123/456.jpg",
            "https://images.unsplash.com.evil.example/photo.jpg",
            "http://images.unsplash.com/photo.jpg",
            "https://127.0.0.1/secret.jpg",
            "https://user:secret@images.unsplash.com/secret.jpg",
            "https://images.unsplash.com:8443/private.jpg",
        ]
        payload = {"results": [
            {"id": str(i), "title": "Portrait", "thumbnail": url, "filetype": "jpeg"}
            for i, url in enumerate(thumbnails)
        ]}

        class Opener:
            def open(self, request, timeout):
                if "commons.wikimedia.org" in request.full_url:
                    return FakeResponse()
                return FakeResponse(payload)

        with patch.object(self.bridge, "PUBLIC_HTTPS_OPENER", Opener()):
            result = self.bridge.image_search({"query": "portrait", "limit": 8})
        data = json.loads(result["stdout"])
        self.assertEqual(thumbnails[:2], [item["thumbnail_url"] for item in data["images"]])

    def test_wikimedia_lookalike_domain_cannot_supply_preview(self):
        payload = {"query": {"pages": [{
            "title": "File:Portrait.jpg",
            "imageinfo": [{
                "thumburl": "https://evilwikimedia.org/private.jpg",
                "url": "https://evilwikimedia.org/private.jpg",
                "descriptionurl": "https://evilwikimedia.org/Portrait",
                "mime": "image/jpeg",
            }],
        }]}}

        class Opener:
            def open(self, request, timeout):
                return FakeResponse(payload)

        with patch.object(self.bridge, "PUBLIC_HTTPS_OPENER", Opener()):
            self.assertEqual([], self.bridge._wikimedia_image_search("portrait", 4, 2))

