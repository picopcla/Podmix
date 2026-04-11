#!/usr/bin/env python3
"""Test du serveur de timestamping audio."""

import subprocess
import time
import requests
import json

def test_server():
    """Test les endpoints du serveur."""
    
    # URL de base
    base_url = "http://localhost:8099"
    
    print("=== Test du serveur Audio Timestamp ===")
    
    # Test 1: Endpoint racine
    print("\n1. Test endpoint racine...")
    try:
        response = requests.get(f"{base_url}/")
        print(f"   Status: {response.status_code}")
        print(f"   Response: {response.json()}")
    except Exception as e:
        print(f"   Error: {e}")
    
    # Test 2: Health check
    print("\n2. Test health check...")
    try:
        response = requests.get(f"{base_url}/health")
        print(f"   Status: {response.status_code}")
        print(f"   Response: {response.json()}")
    except Exception as e:
        print(f"   Error: {e}")
    
    # Test 3: Recherche 1001TL (simplifiée)
    print("\n3. Test recherche 1001TL...")
    try:
        response = requests.get(f"{base_url}/tracklist?q=test")
        print(f"   Status: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"   Found {len(data) if isinstance(data, list) else '?'} results")
            if data and len(data) > 0:
                print(f"   First result: {data[0]}")
        else:
            print(f"   Response: {response.text[:200]}")
    except Exception as e:
        print(f"   Error: {e}")
    
    # Test 4: Analyse audio (simulation)
    print("\n4. Test analyse audio...")
    try:
        data = {
            "url": "https://example.com/audio.mp3",
            "query": "techno mix"
        }
        response = requests.post(f"{base_url}/analyze", json=data)
        print(f"   Status: {response.status_code}")
        print(f"   Response: {response.json()}")
    except Exception as e:
        print(f"   Error: {e}")
    
    print("\n=== Test terminé ===")

if __name__ == "__main__":
    # Vérifier si le serveur est en cours d'exécution
    try:
        response = requests.get("http://localhost:8099/health", timeout=2)
        if response.status_code == 200:
            print("Serveur déjà en cours d'exécution.")
            test_server()
        else:
            print(f"Le serveur répond mais avec un code d'erreur: {response.status_code}")
    except requests.exceptions.ConnectionError:
        print("Le serveur n'est pas en cours d'exécution.")
        print("Pour démarrer le serveur, exécutez:")
        print("  python audio_timestamp_server.py")
        print("\nOu dans un terminal séparé:")
        print("  start cmd /k python audio_timestamp_server.py")