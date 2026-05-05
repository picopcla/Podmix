#!/usr/bin/env python3
"""
Serveur HTTP minimal pour le timestamping audio avec 1001TL.
Utilise les scripts Python existants du projet PodMix.
"""

import http.server
import socketserver
import json
import urllib.parse
import urllib.request
import subprocess
import sys
import os
import re
from pathlib import Path
import logging

try:
    import yt_dlp
    YT_DLP_AVAILABLE = True
except ImportError:
    YT_DLP_AVAILABLE = False

# Configuration
PORT = 8099
HOST = "0.0.0.0"
DATA_DIR = Path("audio_data")
DATA_DIR.mkdir(exist_ok=True)

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class AudioTimestampHandler(http.server.BaseHTTPRequestHandler):
    """Handler pour les requêtes de timestamping audio."""
    
    def do_GET(self):
        """Gère les requêtes GET."""
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        
        if path == "/":
            self.send_response(200)
            self.send_header("Content-type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "service": "Audio Timestamp Server",
                "version": "1.0",
                "endpoints": {
                    "GET /tracklist": "Recherche tracklist 1001TL",
                    "POST /analyze": "Analyse audio avec 1001TL",
                    "GET /health": "Health check"
                }
            }).encode())
            
        elif path == "/health":
            self.send_response(200)
            self.send_header("Content-type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "healthy"}).encode())
            
        elif path == "/tracklist":
            query = urllib.parse.parse_qs(parsed.query)
            q = query.get("q", [""])[0]
            
            if not q:
                self.send_error(400, "Missing query parameter 'q'")
                return
                
            # Utiliser le scraper 1001TL existant
            try:
                # Importer dynamiquement pour éviter les problèmes de chemin
                import sys
                import importlib.util
                
                scraper_path = "OLD_PWA/podcast-app/backend/app/services/tracklists_1001.py"
                spec = importlib.util.spec_from_file_location("tracklists_1001", scraper_path)
                module = importlib.util.module_from_spec(spec)
                sys.modules["tracklists_1001"] = module
                spec.loader.exec_module(module)
                TracklistsScraper = module.TracklistsScraper
                
                import asyncio
                
                async def search():
                    scraper = TracklistsScraper()
                    try:
                        return await scraper.search_artists(q)
                    finally:
                        await scraper.close()
                
                results = asyncio.run(search())
                
                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(results).encode())
                
            except Exception as e:
                logger.error(f"Error searching 1001TL: {e}")
                self.send_error(500, f"Internal server error: {e}")
                
        elif path == "/comments":
            query_params = urllib.parse.parse_qs(parsed.query)
            video_id = query_params.get("video_id", [""])[0]
            if not video_id:
                self.send_error(400, "Missing 'video_id' parameter")
                return
            try:
                tracks = []
                if YT_DLP_AVAILABLE:
                    import re as _re
                    timestamp_re = _re.compile(r'\[?\(?\d{1,2}:\d{2}(?::\d{2})?\)?\]?')
                    ydl_opts = {
                        'quiet': True,
                        'skip_download': True,
                        'no_warnings': True,
                        'getcomments': True,
                        'extractor_args': {
                            'youtube': {
                                'max_comments': ['200'],
                                'comment_sort': ['top'],
                            }
                        },
                        'js_runtimes': {'node': {}},
                        'remote_components': ['ejs:github'],
                    }
                    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                        info = ydl.extract_info(
                            f"https://www.youtube.com/watch?v={video_id}",
                            download=False
                        )
                        comments = info.get('comments') or []

                    # Find comment with most timestamp lines
                    best_comment = ""
                    best_count = 0
                    for c in comments:
                        text = c.get('text', '')
                        count = len(timestamp_re.findall(text))
                        if count > best_count:
                            best_count = count
                            best_comment = text

                    # Parse timestamped lines
                    if best_count >= 3:
                        for line in best_comment.splitlines():
                            line = line.strip()
                            if not line:
                                continue
                            m = _re.search(r'(\d{1,2}):(\d{2})(?::(\d{2}))?', line)
                            if not m:
                                continue
                            h = int(m.group(1)) if m.group(3) else 0
                            mins = int(m.group(2)) if m.group(3) else int(m.group(1))
                            secs = int(m.group(3)) if m.group(3) else int(m.group(2))
                            start_sec = h * 3600 + mins * 60 + secs
                            track_text = _re.sub(r'\[?\(?\d{1,2}:\d{2}(?::\d{2})?\)?\]?', '', line).strip(' -–—.')
                            if not track_text:
                                continue
                            if ' - ' in track_text:
                                parts = track_text.split(' - ', 1)
                                artist, title = parts[0].strip(), parts[1].strip()
                            else:
                                artist, title = '', track_text
                            if title:
                                tracks.append({"artist": artist, "title": title, "startTimeSec": start_sec})

                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"tracks": tracks}).encode())
            except Exception as e:
                logger.error(f"Error fetching comments: {e}")
                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"tracks": []}).encode())

        elif path == "/chapters":
            query_params = urllib.parse.parse_qs(parsed.query)
            video_id = query_params.get("video_id", [""])[0]
            if not video_id:
                self.send_error(400, "Missing 'video_id' parameter")
                return
            try:
                tracks = []
                if YT_DLP_AVAILABLE:
                    ydl_opts = {
                        'quiet': True,
                        'skip_download': True,
                        'no_warnings': True,
                    }
                    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                        info = ydl.extract_info(
                            f"https://www.youtube.com/watch?v={video_id}",
                            download=False
                        )
                        chapters = info.get('chapters') or []
                        tracks = [
                            {"title": c.get("title", ""), "startTimeSec": int(c.get("start_time", 0))}
                            for c in chapters
                        ]
                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"tracks": tracks}).encode())
            except Exception as e:
                logger.error(f"Error fetching chapters: {e}")
                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"tracks": []}).encode())

        elif path == "/mixesdb":
            query_params = urllib.parse.parse_qs(parsed.query)
            q = query_params.get("q", [""])[0]
            if not q:
                self.send_error(400, "Missing 'q' parameter")
                return
            try:
                tracks = []

                headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}

                # 1. Search for the page
                search_url = (
                    "https://www.mixesdb.com/api.php?action=query&list=search"
                    f"&srsearch={urllib.parse.quote(q)}&format=json&srlimit=1"
                )
                req = urllib.request.Request(search_url, headers=headers)
                with urllib.request.urlopen(req, timeout=5) as resp:
                    search_data = json.loads(resp.read().decode())
                results = search_data.get("query", {}).get("search", [])
                if not results:
                    raise ValueError("No MixesDB page found")

                page_id = results[0]["pageid"]

                # 2. Fetch wikitext
                parse_url = (
                    f"https://www.mixesdb.com/api.php?action=parse&pageid={page_id}"
                    "&prop=wikitext&format=json"
                )
                req = urllib.request.Request(parse_url, headers=headers)
                with urllib.request.urlopen(req, timeout=5) as resp:
                    parse_data = json.loads(resp.read().decode())
                wikitext = parse_data.get("parse", {}).get("wikitext", {}).get("*", "")

                # 3. Parse tracklist rows (defensive — format not officially documented)
                for line in wikitext.splitlines():
                    line = line.strip()
                    cells = [c.strip() for c in line.split("||")]
                    if len(cells) >= 3:
                        artist = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', cells[1])
                        title = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', cells[2])
                        if artist and title:
                            tracks.append({"artist": artist, "title": title, "startTimeSec": 0})
                    elif len(cells) == 2 and " - " in cells[1]:
                        parts = cells[1].split(" - ", 1)
                        artist = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', parts[0])
                        title = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', parts[1])
                        if artist and title:
                            tracks.append({"artist": artist, "title": title, "startTimeSec": 0})

                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"tracks": tracks}).encode())
            except Exception as e:
                logger.warning(f"MixesDB: {e}")
                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"tracks": []}).encode())

        elif path == "/analyze":
            # GET fallback — Retrofit sends GET /analyze?url=...
            query_params = urllib.parse.parse_qs(parsed.query)
            url = query_params.get("url", [""])[0]
            if not url:
                self.send_error(400, "Missing 'url' parameter")
                return
            self._handle_analyze(url)

        else:
            self.send_error(404, f"Endpoint not found: {path}")

    def _handle_analyze(self, url: str):
        """Analyse audio via Shazam — actuellement retourne vide (pipeline non implémenté)."""
        logger.info(f"analyze requested for: {url[:80]}")
        self.send_response(200)
        self.send_header("Content-type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"tracks": [], "source": "shazam", "error": "not implemented"}).encode())

    def do_POST(self):
        """Gère les requêtes POST."""
        if self.path == "/analyze":
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            
            try:
                data = json.loads(post_data.decode())
                url = data.get("url")
                query = data.get("query", "")
                
                if not url:
                    self.send_error(400, "Missing 'url' parameter")
                    return
                    
                # Pour l'instant, simulation d'analyse
                # TODO: Intégrer le vrai pipeline de traitement
                result = {
                    "status": "processing",
                    "url": url,
                    "tracklist_found": True,
                    "estimated_tracks": 15,
                    "message": "Analysis queued. Use GET /tracklist?q=... for manual search."
                }
                
                self.send_response(200)
                self.send_header("Content-type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(result).encode())
                
            except json.JSONDecodeError:
                self.send_error(400, "Invalid JSON")
            except Exception as e:
                logger.error(f"Error processing analyze request: {e}")
                self.send_error(500, f"Internal server error: {e}")
                
        else:
            self.send_error(404, f"Endpoint not found: {self.path}")
    
    def log_message(self, format, *args):
        """Override pour utiliser notre logger."""
        logger.info("%s - %s", self.address_string(), format % args)

def main():
    """Lance le serveur HTTP."""
    handler = AudioTimestampHandler
    
    with socketserver.TCPServer((HOST, PORT), handler) as httpd:
        logger.info(f"Server starting on {HOST}:{PORT}")
        logger.info(f"API endpoints:")
        logger.info(f"  GET  http://{HOST}:{PORT}/ - Service info")
        logger.info(f"  GET  http://{HOST}:{PORT}/health - Health check")
        logger.info(f"  GET  http://{HOST}:{PORT}/tracklist?q=QUERY - Search 1001TL")
        logger.info(f"  POST http://{HOST}:{PORT}/analyze - Analyze audio URL")
        logger.info(f"  GET  http://{HOST}:{PORT}/chapters?video_id=ID - YouTube chapters via yt-dlp")
        logger.info(f"  GET  http://{HOST}:{PORT}/mixesdb?q=QUERY - MixesDB tracklist")
        
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            logger.info("Server shutting down...")
            httpd.shutdown()

if __name__ == "__main__":
    # Ajouter le chemin du backend Python
    backend_path = Path("OLD_PWA/podcast-app/backend")
    if backend_path.exists():
        sys.path.insert(0, str(backend_path))
        sys.path.insert(0, str(backend_path / "app"))
    
    main()