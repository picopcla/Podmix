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

L’application peut ouvrir un fichier audio local pour la lecture, la waveform et
l’édition manuelle. Ce fichier reste dans l’application : il n’est jamais envoyé
au backend.

Le VPS ne télécharge, ne décode et n’analyse aucun média. Ses jobs durables
enchaînent uniquement la description RSS, les chapitres et commentaires publiés,
les smart-links publiés (`lnk.to`, `linktr.ee`, `podlink.to`), MixesDB,
1001Tracklists, la recherche Web puis la consolidation IA via Nous
Portal. Leur état et leurs résultats sont conservés dans
`server/data/podmix.sqlite3`, et leur progression est envoyée par Server-Sent
Events. Une clé par épisode évite les doublons.
Le détail et les garanties sont dans
[`docs/AUTOMATIC_PIPELINE.md`](docs/AUTOMATIC_PIPELINE.md).

Les favoris peuvent être partagés sous la forme d’un lien Podmix temporaire.
Celui-ci ne conserve que le titre, sa provenance et les timestamps : aucun
extrait audio n’est créé ni hébergé. La page lue par le destinataire utilise
l’URL publiée par l’éditeur, ou le redirige vers la publication d’origine.

L’ordre des bases de tracklists se règle avec
`PODMIX_TRACKLIST_SOURCES=mixesdb,1001tracklists`.

Quand `NOUS_PORTAL_API` est définie, les textes collectés pendant les recherches
Web sont automatiquement décodés et consolidés via l'API Nous Portal
compatible OpenAI. Le backend lit `local.properties` ou
`android/local.properties` avec la clé `NOUS_PORTAL_API` ou `nousportalApi`.
Le parseur local reste utilisé comme repli si l'appel échoue ou si le résultat
est rejeté par les contrôles de cohérence. `PODMIX_OPENAI_TIMESTAMPING=0`
désactive explicitement ces appels et `PODMIX_NOUS_MODEL` permet de choisir le
modèle Nous.

Après la recherche, une tracklist peut être collée dans l’inspecteur. Les formats
`00:00 Artiste - Titre` et `1. Artiste - Titre` sont reconnus. Les titres sont
horodatés conservent leurs timestamps publiés. Aucun timestamp n’est inventé
lorsque la source n’en fournit pas.

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
```

Le plan de migration est dans `docs/TRANSITION_PLAN.md`.
Le contrat de l’API est dans `docs/openapi.yaml`.
Le workflow Android est dans `docs/ANDROID.md`.
