import requests
import json

# Recherches spécifiques pour différents aspects
searches = [
    ("audio fingerprinting podcast", "q=audio+fingerprinting+podcast+timestamp"),
    ("music detection in podcasts", "q=music+detection+podcast+speech+segmentation"),
    ("shazam alternative open source", "q=shazam+alternative+open+source+python"),
    ("acoustic fingerprinting", "q=acoustic+fingerprinting+music+recognition"),
    ("audio matching timestamp", "q=audio+matching+timestamp+recognition"),
]

all_results = []

for search_name, query in searches:
    print(f"\n=== RECHERCHE: {search_name} ===")
    
    url = f'https://api.github.com/search/repositories?{query}&sort=stars&order=desc&per_page=8'
    headers = {'Accept': 'application/vnd.github.v3+json'}
    
    try:
        r = requests.get(url, headers=headers)
        data = r.json()
        
        for item in data.get('items', []):
            # Éviter les doublons
            if item['html_url'] not in [r['html_url'] for r in all_results]:
                all_results.append({
                    'full_name': item['full_name'],
                    'stars': item['stargazers_count'],
                    'description': item.get('description', ''),
                    'url': item['html_url'],
                    'language': item.get('language', 'Unknown'),
                    'topics': item.get('topics', [])
                })
                
    except Exception as e:
        print(f"  Error: {e}")

# Trier par nombre d'étoiles
all_results.sort(key=lambda x: x['stars'], reverse=True)

print("\n" + "="*80)
print("TOP 20 PROJETS POUR PODCAST TIMESTAMPING ET FINGERPRINTING")
print("="*80)

for i, item in enumerate(all_results[:20]):
    print(f'\n{i+1}. {item["full_name"]}')
    print(f'   ⭐ {item["stars"]} stars | 💻 {item["language"]}')
    print(f'   📝 {item["description"][:150]}{"..." if len(item["description"]) > 150 else ""}')
    
    # Afficher les topics pertinents
    relevant_topics = [t for t in item["topics"] if any(keyword in t.lower() for keyword in 
                     ['audio', 'music', 'podcast', 'fingerprint', 'recognition', 'shazam', 'acoustic'])]
    if relevant_topics:
        print(f'   🏷️  {", ".join(relevant_topics[:3])}')
    
    print(f'   🔗 {item["url"]}')

# Recherche de projets académiques/universitaires
print("\n" + "="*80)
print("PROJETS ACADÉMIQUES ET RECHERCHE")
print("="*80)

academic_projects = [
    ("Columbia University - Audfprint", "dpwe/audfprint", "Audio fingerprinting with locality-sensitive hashing"),
    ("MIT - Music Information Retrieval", "mir-dataset-loaders/mirdata", "Datasets and tools for MIR"),
    ("University of London - Essentia", "MTG/essentia", "Audio analysis and MIR library"),
    ("Queen Mary University - librosa", "librosa/librosa", "Audio and music analysis in Python"),
    ("Stanford - CREPE", "marl/crepe", "Pitch tracking with deep learning"),
]

for name, repo, desc in academic_projects:
    print(f'\n• {name}')
    print(f'  Repository: {repo}')
    print(f'  Description: {desc}')

print("\n" + "="*80)
print("ARCHITECTURE RECOMMANDÉE POUR PODMIX")
print("="*80)

print("""
1. **Couche d'extraction audio** (déjà présente)
   - ffmpeg HTTP seek pour téléchargement partiel
   - yt-dlp pour YouTube
   - Support des URLs RSS podcasts

2. **Couche de prétraitement**
   - librosa pour analyse spectrale
   - pydub pour segmentation
   - Détection parole/musique

3. **Couche de fingerprinting**
   - Chromaprint (AcoustID) pour fingerprinting rapide
   - ShazamIO pour reconnaissance précise
   - Base de données locale pour cache

4. **Couche de matching**
   - Approximate Nearest Neighbors (Annoy/FAISS)
   - Recherche vectorielle pour fingerprints
   - Timestamp exact avec offset

5. **Couche d'intégration**
   - API REST (FastAPI) comme app.py v4
   - Cache SQLite pour résultats
   - WebSockets pour progression
""")