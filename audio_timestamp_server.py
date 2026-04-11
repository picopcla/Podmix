#!/usr/bin/env python3
"""
Serveur HTTP minimal pour le timestamping audio avec 1001TL.
Utilise les scripts Python existants du projet PodMix.
"""

import http.server
import socketserver
import json
import urllib.parse
import subprocess
import sys
import os
from pathlib import Path
import logging

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
                
        else:
            self.send_error(404, f"Endpoint not found: {path}")
    
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