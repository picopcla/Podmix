from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from published_links import extract_published_links


class PublishedLinksTests(unittest.TestCase):
    def test_resolves_trance_empire_smart_link_to_media_pages(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.geturl.return_value = "https://lnk.to/TTE434"
        response.headers = {"Content-Type": "text/html; charset=utf-8"}
        response.read.return_value = b"""
          <a href="https://youtu.be/Be-upm4wCHs?src=Linkfire">YouTube</a>
          <a href="https://soundcloud.com/trance-empire/the-trance-empire-434-with">SoundCloud</a>
          <a href="https://podcasts.apple.com/example">Apple</a>
        """
        opener = Mock()
        opener.open.return_value = response
        with (
            patch("published_links.socket.getaddrinfo", return_value=[(None, None, None, None, ("93.184.216.34", 443))]),
            patch("published_links.build_opener", return_value=opener),
        ):
            links = extract_published_links(
                "<p>Choose your player <a href='https://lnk.to/TTE434'>TTE 434</a></p>"
            )
        self.assertEqual([
            "https://youtu.be/Be-upm4wCHs?src=Linkfire",
            "https://soundcloud.com/trance-empire/the-trance-empire-434-with",
        ], links)

    def test_ignores_unknown_description_links(self):
        self.assertEqual([], extract_published_links(
            "<a href='https://example.com/private'>Unrelated</a>"
        ))


if __name__ == "__main__":
    unittest.main()
