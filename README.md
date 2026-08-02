# Podmix Next

Nouvelle base web-first de Podmix, isolée de l’application Android historique.

## Démarrer

Terminal 1 :

```bash
npm install
npm run dev
```

Terminal 2 :

```bash
npm run setup:api
npm run api
```

`setup:api` n’est nécessaire que lors de la première installation ou après une
modification de `server/requirements.txt`.

Le prototype permet d’importer un fichier audio local, de lire ou scruter la
waveform, d’ajouter des marqueurs et d’éditer une piste. Les sessions sont
conservées dans IndexedDB.

Le moteur avancé accepte WAV, MP3, FLAC, OGG, AAC et M4A. Il combine nouveauté spectrale,
énergie, texture et changement chromatique pour proposer les transitions. Si les
dépendances avancées sont absentes, un moteur WAV PCM 16 bits reste disponible.
Les uploads et les jobs sont conservés dans `server/data/podmix.sqlite3`, et leur
progression est envoyée par Server-Sent Events.

Les jobs sont traités par une file serveur durable. Après un redémarrage du VPS,
une analyse interrompue est replacée en attente puis reprise automatiquement.
Pour un épisode RSS ou un DJ set, le pipeline peut enchaîner description,
chapitres/métadonnées, MixesDB, 1001Tracklists, alignement audio, validation
chroma/DTW et recalage sur une grille musicale. Un podcast peut être marqué
explicitement comme musical depuis sa fiche pour rejoindre cette politique.
Une clé par épisode évite les doublons, et le téléchargement distant fait
lui-même partie du job durable.
Le détail et les garanties sont dans
[`docs/AUTOMATIC_PIPELINE.md`](docs/AUTOMATIC_PIPELINE.md).

BeatNet est détecté automatiquement lorsqu’il est installé sur un worker
compatible. Le worker standard utilise Librosa sans dépendance lourde
supplémentaire. `PODMIX_BEAT_ENGINE=librosa` force le repli ;
`PODMIX_BEAT_ENGINE=beatnet` exige explicitement BeatNet. L’ordre des bases de
tracklists se règle avec
`PODMIX_TRACKLIST_SOURCES=mixesdb,1001tracklists`.

Après l’analyse, une tracklist peut être collée dans l’inspecteur. Les formats
`00:00 Artiste - Titre` et `1. Artiste - Titre` sont reconnus. Les titres sont
alignés sur les transitions audio avec un score et des preuves, puis restent à
valider par l’utilisateur.

Une URL YouTube, SoundCloud ou Mixcloud peut également être explorée. YouTube
peut exiger une session pour son contrôle anti-bot. Dans ce cas, un fichier de
cookies Netscape peut être fourni explicitement avant le lancement :

```bash
YTDLP_COOKIE_FILE=/chemin/cookies.txt npm run api
```

Podmix ne tente pas de contourner les restrictions de la plateforme.

Chaque titre proposé peut être recherché dans MusicBrainz depuis l’inspecteur.
Le MBID, le score de recherche et la correspondance trouvée sont ajoutés aux
preuves. Cette étape valide la cohérence des métadonnées, pas la présence
acoustique du titre dans le mix ; la validation humaine reste donc séparée.

La même validation enrichit le titre avec Deezer. Spotify reprend la cascade de
recherche de l'application Android historique : artiste et titre stricts, texte
libre, puis nouvelle tentative avec le premier artiste. Le service peut être
activé avec des identifiants fournis exclusivement au serveur :

```bash
export SPOTIFY_CLIENT_ID="..."
export SPOTIFY_CLIENT_SECRET="..."
npm run api
```

Ces valeurs ne doivent jamais être intégrées au frontend ou à l’APK.

Une clé AcoustID personnelle facultative active l’identification Chromaprint :

```bash
export ACOUSTID_API_KEY="..."
npm run api
```

Le binaire `fpcalc` est fourni par le paquet Debian `libchromaprint-tools`.

## APK et serveur

La version publique téléchargeable et la procédure de mise à jour sont
documentées dans [`docs/DISTRIBUTION.md`](docs/DISTRIBUTION.md).

L’APK utilise Media3 pour la lecture en arrière-plan, les téléchargements,
Android Auto et les contrôles système. Cast et Bose SoundTouch sont accessibles
dans les réglages. L’adresse du backend se configure et se teste directement
dans l’application ; une version de production doit utiliser HTTPS.

Le backend peut être lancé dans un conteneur :

```bash
cp .env.example .env
docker compose up -d --build
```

Le port `8099` reste lié à localhost pour être publié derrière un reverse proxy
HTTPS. `PODMIX_ALLOWED_ORIGINS` doit contenir l’origine exacte de la PWA.

## Vérifier

```bash
npm run test:server
npm run lint
npm run build
npm run android:build:debug
.venv/bin/python server/benchmarks/benchmark_priority3.py
```

Le plan de migration est dans `docs/TRANSITION_PLAN.md`.
Le contrat de l’API est dans `docs/openapi.yaml`.
Le workflow Android est dans `docs/ANDROID.md`.
