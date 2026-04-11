import requests
import json

url = 'https://api.github.com/search/repositories'
params = {
    'q': 'podcast music detection timestamp OR fingerprinting',
    'sort': 'stars',
    'order': 'desc',
    'per_page': 15
}
headers = {'Accept': 'application/vnd.github.v3+json'}

try:
    r = requests.get(url, params=params, headers=headers)
    data = r.json()
    
    print("=== RECHERCHE GITHUB POUR PODCAST TIMESTAMPING ===\n")
    
    for i, item in enumerate(data.get('items', [])):
        print(f'{i+1}. {item["full_name"]}')
        print(f'   ⭐ Stars: {item["stargazers_count"]}')
        print(f'   📝 Description: {item.get("description", "No description")}')
        print(f'   🔗 URL: {item["html_url"]}')
        
        # Vérifier les topics
        topics = item.get('topics', [])
        if topics:
            print(f'   🏷️  Topics: {", ".join(topics[:5])}')
        
        # Langage principal
        language = item.get('language', 'Unknown')
        print(f'   💻 Language: {language}')
        print()
        
except Exception as e:
    print(f'Error: {e}')

# Recherche supplémentaire pour des outils spécifiques
print("\n=== OUTILS SPÉCIFIQUES POUR FINGERPRINTING AUDIO ===\n")

specific_tools = [
    ("AcoustID/chromaprint", "Audio fingerprinting library used by MusicBrainz"),
    ("worldveil/dejavu", "Audio fingerprinting and recognition in Python"),
    ("dpwe/audfprint", "Audio fingerprinting system by Dan Ellis"),
    ("addictedcs/soundfingerprinting", ".NET audio fingerprinting library"),
    ("spotify/annoy", "Approximate Nearest Neighbors for audio search"),
    ("microsoft/SPTAG", "Space Partition Tree And Graph for vector search"),
    ("willdrevo/audiorecognizer", "Shazam-like audio recognition"),
    ("itsmehemant123/Hear-Here", "Audio fingerprinting and music recognition")
]

for name, desc in specific_tools:
    print(f'• {name}: {desc}')