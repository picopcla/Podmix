# Podmix V2 - Instructions de developpement pour Codex VPS

Ce document sert de brief de prise de relais pour Codex execute sur le VPS. Il fixe la direction Podmix V2, les frontieres APK/VPS, l'arbre des processus metier, les regles de code, et l'ordre de developpement.

## Decision principale

Podmix V2 doit etre reconstruit proprement autour des processus metier.

On ne recode pas d'abord des ecrans. On recode des actions isolees, testables et reutilisables. Les ecrans appellent ensuite ces actions.

Architecture cible:

```text
Podmix APK = client leger local-first
Podmix VPS = moteur d'analyse, enrichissement, matching et timestamping
```

Raison:

- La V1 melange UI, repositories, scraping, analyse, player, download, reparation de donnees et appels externes.
- Le telephone chauffait et vidait la batterie parce que trop de traitements lourds tournaient dans l'APK.
- La V2 doit sortir du telephone tout ce qui est fragile, lent, CPU-heavy, reseau-heavy, ou dependant de sites externes.

## Regles non negociables

- Aucun scraping dans l'APK.
- Aucune IA dans l'APK.
- Aucun `yt-dlp` dans l'APK.
- Aucune analyse audio lourde dans l'APK.
- Aucune recherche externe massive dans l'APK.
- Aucun traitement automatique qui chauffe le telephone.
- Aucun ViewModel ne contient de logique metier profonde.
- Aucun ecran ne parle directement a un DAO, scraper, resolver externe ou service lourd.
- Le player ne connait pas 1001TL, YouTube comments, MixesDB, IA, jobs, ou timestamping.
- Pas de reparation de donnees cachee dans le player, le download manager ou le startup.
- Pas d'IP VPS codee en dur.
- Pas de `fallbackToDestructiveMigration` dans la V2.
- Room est la source d'affichage cote APK.
- Les reponses VPS sont stockees localement avant affichage.
- Chaque processus important a un input, un process, un output, un owner, des erreurs typpees et des tests.

## Frontiere APK / VPS

### APK Android

L'APK garde:

```text
- UI Compose
- navigation
- Room
- cache local
- mode offline
- ExoPlayer
- progression de lecture
- favoris de contenus
- tracks sauvegardees
- telechargement a la demande
- settings
- WorkManager leger
- polling des jobs VPS
- stockage des resultats d'analyse
```

L'APK ne doit pas faire:

```text
- scraping 1001TL
- WebView invisible pour extraction
- IA
- yt-dlp
- analyse audio lourde
- chroma refinement
- extraction YouTube massive
- parsing externe fragile
- matching complexe
```

### VPS FastAPI

Le VPS prend:

```text
- analyse URL
- recherche 1001TL
- recherche YouTube
- recuperation descriptions / chapters / comments
- IA pour extraction et matching
- validation de candidats
- timestamping podcast/liveset
- yt-dlp
- jobs longs
- cache serveur des analyses
- enrichissement Spotify / Deezer pour tracks sauvegardees
```

Regle simple:

```text
Si ca chauffe le telephone ou depend d'un site externe fragile, ca va sur le VPS.
```

## Arbre metier Podmix V2

```text
Podmix
├── Podcast
│   ├── Importer
│   ├── Rafraichir
│   ├── Analyser tracklist
│   ├── Timestamper
│   ├── Telecharger si demande
│   └── Lire
│
├── Liveset
│   ├── Importer
│   ├── Rafraichir
│   ├── Analyser tracklist
│   ├── Timestamper
│   ├── Telecharger si demande
│   └── Lire
│
├── Emission
│   ├── Importer
│   ├── Rafraichir
│   ├── Telecharger si demande
│   └── Lire
│
├── Radio
│   ├── Importer
│   └── Lire live
│
├── Favoris
│   ├── Episodes podcast favoris
│   └── Livesets favoris
│
├── Tracks sauvegardees
│   ├── Tracks issues de podcasts
│   ├── Tracks issues de livesets
│   ├── Lire depuis timestamp source
│   ├── Rechercher Spotify / Deezer
│   └── Ouvrir Spotify / Deezer
│
├── Player
│   ├── Preparer media
│   ├── Lire
│   ├── Pause / reprise
│   ├── Seek
│   ├── Lire depuis timestamp
│   ├── Sauvegarder progression
│   └── Marquer ecoute
│
└── Maintenance
    ├── Migrations Room explicites
    ├── Nettoyage explicite
    └── Logs diagnostic
```

## Regles metier par type de contenu

```text
Podcast
- tracklist possible
- timestamp possible
- analyse VPS autorisee
- telechargement sur demande
- peut etre favori en tant que contenu
- ses tracks peuvent etre sauvegardees

Liveset
- tracklist centrale
- timestamp central
- analyse VPS par defaut
- telechargement sur demande
- peut etre favori en tant que contenu
- ses tracks peuvent etre sauvegardees

Emission
- jamais de tracklist
- jamais de timestamp tracklist
- pas de favori
- lecture et telechargement seulement

Radio
- jamais de tracklist persistante
- pas de favori
- lecture live seulement
```

## Favoris et tracks sauvegardees

Ne pas melanger ces deux concepts.

### Favoris

Les favoris sont des contenus longs a reecouter:

```text
- episodes podcast favoris
- livesets favoris
```

Pas de favoris pour:

```text
- emissions
- radios
```

### Tracks sauvegardees

Les tracks sauvegardees sont des morceaux precis extraits d'une tracklist podcast/liveset:

```text
- track issue d'un podcast
- track issue d'un liveset
- lecture depuis son timestamp dans le contenu source
- enrichissement Spotify / Deezer
```

Spotify / Deezer concerne uniquement les tracks sauvegardees, pas les episodes/livesets favoris.

## Pipeline podcast: tracklist et timestamp

Objectif: identifier une tracklist fiable et des timestamps fiables pour certains episodes podcast.

Le VPS ne doit pas simplement demander a l'IA de deviner depuis la description RSS. Il doit utiliser l'IA comme moteur de comparaison et d'extraction, avec preuves.

Flux:

```text
APK importe episode RSS
-> APK stocke Episode local dans Room
-> APK envoie au VPS:
   - podcastName
   - episodeTitle
   - description RSS
   - audioUrl
   - durationSeconds
   - firstMentionedTrack si detecte
-> VPS extrait des indices RSS
-> VPS cherche candidats 1001TL
-> VPS cherche candidats YouTube
-> VPS recupere preuves candidates
-> IA compare RSS vs candidats
-> VPS accepte seulement si score suffisant
-> VPS extrait tracklist et timestamps
-> APK stocke Tracklist + tracks + timestamps dans Room
```

Indices RSS utiles:

```text
- titre episode
- nom podcast
- date episode
- duree
- liens externes
- premiers artistes mentionnes
- premier track mentionne si present
```

Attention: `firstMentionedTrack` est un indice fort, pas une verite absolue.

Acceptation candidat podcast:

```text
Accepter si:
- titre proche + premier track trouve
OU
- titre proche + duree proche + plusieurs tracks communes
OU
- YouTube chapters explicites + titre proche

Refuser si:
- seul le nom du DJ/artiste match
- seulement des mots generiques matchent
- duree tres differente
- page 1001TL incoherente
- score faible
```

Resultats possibles:

```text
- tracklist + timestamps fiables
- tracklist + timestamps partiels
- tracklist sans timestamps
- aucune tracklist fiable
```

Interdit:

```text
- inventer des timestamps
- utiliser des timestamps uniformes comme verite
- marquer une estimation comme fiable
```

## Pipeline liveset: tracklist et timestamp

Les livesets sont plus exigeants que les podcasts: tracklist et timestamp sont au coeur de la valeur produit.

Inputs possibles:

```text
- URL YouTube
- URL Mixcloud
- URL SoundCloud
- URL 1001Tracklists
- nom DJ/artiste
- liveset selectionne depuis recherche
```

Flux:

```text
APK cree LiveSet local status=pending
-> APK envoie source au VPS
-> VPS identifie la plateforme
-> VPS recupere metadata
-> VPS cherche sources associees
-> VPS recupere 1001TL / YouTube / Mixcloud / MixesDB si utile
-> IA compare candidats
-> VPS extrait tracklist
-> VPS extrait timestamps
-> VPS score confiance
-> APK stocke resultat en Room
```

Priorite timestamp liveset:

```text
1. YouTube chapters
2. 1001TL timestamps
3. YouTube description timestamps
4. YouTube comments timestamps
5. Mixcloud sections
6. MixesDB timestamps si presents
7. Analyse audio VPS seulement en dernier recours
```

Acceptation candidat liveset:

```text
Accepter si:
- URL 1001TL directe et page coherente
OU
- titre + artiste + date/evenement matchent
OU
- YouTube chapters explicites coherents
OU
- plusieurs tracks communes entre sources
OU
- Mixcloud sections disponibles

Refuser si:
- seul le nom du DJ match
- evenement / ville / date incoherents
- duree tres differente
- pageTitle 1001TL incoherent
- match base seulement sur "live", "set", "mix", "radio"
```

## Usage de l'IA

L'IA est autorisee uniquement sur le VPS.

Elle sert a:

```text
- extraire des tracks depuis texte sale
- normaliser artist/title/remix
- comparer candidats
- expliquer les preuves
- scorer la confiance
- detecter timestamps explicites
```

Elle ne doit pas:

```text
- inventer des tracks
- inventer des timestamps
- accepter un match sans preuves
- remplacer les validations metier
```

L'IA doit toujours retourner du JSON structure avec:

```text
- decision
- matchScore
- evidence
- rejectedReasons
- tracks
- timestampQuality
- source
```

## Structure Android cible

```text
app/src/main/java/com/podmix/
  core/
    config/
    database/
    network/
    result/
    logging/

  domain/
    model/
    repository/
    usecase/
      podcast/
      liveset/
      emission/
      radio/
      playback/
      favorites/
      savedtracks/
      download/

  data/
    local/
      dao/
      entity/
      mapper/
    remote/
      api/
      dto/
    repository/

  features/
    podcast/
    liveset/
    emission/
    radio/
    player/
    favorites/
    savedtracks/
    settings/

  playback/
  sync/
```

Regle de dependance:

```text
UI -> ViewModel -> UseCase -> Repository interface -> DataSource
```

## Use cases obligatoires

Podcast:

```text
ImportPodcastUseCase
RefreshPodcastUseCase
AnalyzePodcastTracklistUseCase
TimestampPodcastUseCase
DownloadPodcastEpisodeUseCase
PlayPodcastEpisodeUseCase
```

Liveset:

```text
ImportLiveSetUseCase
RefreshLiveSetUseCase
SearchLiveSetsUseCase
AnalyzeLiveSetTracklistUseCase
TimestampLiveSetUseCase
DownloadLiveSetUseCase
PlayLiveSetUseCase
```

Emission:

```text
ImportEmissionUseCase
RefreshEmissionUseCase
DownloadEmissionEpisodeUseCase
PlayEmissionEpisodeUseCase
```

Radio:

```text
ImportRadioUseCase
PlayRadioUseCase
```

Favoris:

```text
ToggleFavoriteContentUseCase
GetFavoriteContentsUseCase
PlayFavoriteContentUseCase
RemoveFavoriteContentUseCase
```

Tracks sauvegardees:

```text
SaveTrackUseCase
GetSavedTracksUseCase
PlaySavedTrackUseCase
RemoveSavedTrackUseCase
EnrichSavedTrackLinksUseCase
OpenSavedTrackExternalLinkUseCase
```

Playback:

```text
ResolvePlayableMediaUseCase
PlayMediaUseCase
PersistPlaybackProgressUseCase
SyncCurrentTrackUseCase
MarkContentListenedUseCase
```

## Structure backend cible

```text
backend/
  app/
    main.py
    core/
      config.py
      errors.py
      logging.py
    podmix/
      routes.py
      schemas.py
      use_cases.py
      repository.py
      adapters/
        search_1001tl.py
        search_youtube.py
        search_mixesdb.py
        ai_matcher.py
        tracklist_extractor.py
        timestamp_extractor.py
        spotify.py
        deezer.py
    jobs/
      models.py
      repository.py
      runner.py
    storage/
      database.py
      migrations/
    tests/
```

Endpoints minimum:

```text
GET  /health
POST /api/podmix/podcasts/analyze
POST /api/podmix/livesets/analyze
GET  /api/podmix/jobs/{job_id}
GET  /api/podmix/analyses/{analysis_id}
POST /api/podmix/saved-tracks/enrich
```

## GUI cible

La V2 doit avoir une interface originale, pas une liste generique de podcasts.

Direction:

```text
Console d'ecoute musicale
```

Principes:

```text
- timeline tracklist centrale
- statut d'analyse visible
- distinction claire Podcast / Liveset / Emission / Radio
- mini-player permanent
- espace separe pour Favoris
- espace separe pour Tracks sauvegardees
- affichage clair des timestamps fiables / partiels / absents
```

Chaque contenu long doit repondre a ces questions en quelques secondes:

```text
1. Est-ce que je peux l'ecouter ?
2. Est-ce qu'il a une tracklist ?
3. Est-ce que les timestamps sont fiables ?
```

## Ordre de developpement recommande

1. Ecrire les docs d'architecture V2.
2. Definir les modeles domaine.
3. Definir les interfaces repositories.
4. Definir les use cases.
5. Creer backend FastAPI minimal.
6. Creer endpoints analyse podcast/liveset en mode stub.
7. Brancher APK sur backend avec un vertical slice simple.
8. Implementer pipeline podcast IA + 1001TL/YT.
9. Implementer pipeline liveset IA + 1001TL/YT/Mixcloud.
10. Implementer tracks sauvegardees + enrichissement Spotify/Deezer.
11. Refondre GUI autour des processus.
12. Supprimer progressivement la logique V1.

## Definition of done V2

La V2 est acceptable quand:

```text
- le telephone ne lance plus d'analyse lourde
- l'APK fonctionne offline pour les donnees deja connues
- Room est la source d'affichage
- podcasts/livesets ont pipelines d'analyse VPS separes
- emissions n'entrent jamais dans le pipeline tracklist
- radios n'ont pas de favoris ni tracklist persistante
- favoris et tracks sauvegardees sont deux modules distincts
- Spotify/Deezer enrichit seulement les tracks sauvegardees
- le player ne fait que lire un PlayableMedia
- aucune IP VPS n'est codee en dur
- chaque action principale est un use case testable
```

## Point de vigilance majeur

Ne pas reproduire la V1 dans une nouvelle arborescence.

Si un nouveau fichier commence a melanger plusieurs responsabilites, stopper et redefinir le processus metier avant de coder.

Le but n'est pas d'avoir plus de dossiers. Le but est que chaque action Podmix soit modifiable sans perturber la mecanique generale.
