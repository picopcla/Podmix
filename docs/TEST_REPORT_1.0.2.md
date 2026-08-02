# Rapport de recette — Podmix 1.0.2

Date : 25 juillet 2026

## Verdict

Toutes les fonctions vérifiables automatiquement sur le VPS passent. Les
fonctions qui nécessitent un périphérique physique ou des identifiants externes
sont isolées dans la section « À valider sur matériel » et ne sont pas déclarées
comme validées.

## Résultats

- Serveur : 7 tests unitaires réussis.
- API : santé, recherche/import RSS, Radio Browser, protection SSRF, upload WAV,
  analyse, progression SSE, alignement de tracklist, MusicBrainz, chroma/DTW et
  recherche/import SoundCloud vérifiés.
- Frontend : 4 parcours Playwright réussis sur Chromium desktop et mobile.
- PWA : manifeste, service worker et build de production vérifiés.
- Android : 4 tests instrumentés réussis sur émulateur API 35.
- Media3 : podcast MP3/HTTPS réel, radio AAC/HTTP réelle et file suivante
  vérifiés.
- Release : compilation et lint Android réussis.
- Signature : APK Signature Scheme v2 valide, certificat de mise à jour stable.
- Dépendances de production npm : aucune vulnérabilité connue.

## Corrections incluses

- Lecture directe du premier épisode depuis une carte podcast.
- Remontée des erreurs Media3 dans l'interface.
- Compatibilité des anciens flux audio HTTP dans l'APK.
- Recherche DJ priorisant SoundCloud lorsque YouTube bloque le VPS.
- Gestion sûre et inscriptible du fichier de cookies yt-dlp.
- CORS ajouté au manifeste de mise à jour et aux origines de prévisualisation.
- Navigation mobile accessible et accès aux réglages depuis l'avatar.

## À valider sur matériel ou avec des comptes externes

- Google Cast avec un récepteur réel.
- Bose SoundTouch sur le réseau local.
- Android Auto sur véhicule ou Desktop Head Unit.
- Notifications, écran verrouillé et écoute prolongée sur téléphone réel.
- Téléchargement hors connexion de longue durée et pression de stockage.
- Flux HLS réels.
- AcoustID et Spotify avec les clés du propriétaire.
- YouTube avec une session valide lorsque l'anti-bot est actif.

Le chemin SoundCloud fonctionne sans cookie YouTube. L'absence de clé AcoustID
est gérée proprement par l'API.

## Artefact

- Version : `1.0.2` (`versionCode` 10002)
- Package : `com.podmix.next`
- Android minimum : API 24
- SHA-256 :
  `bab9d0905f80b95196fbd84eabfdec4ad09801c925c02fc89d87e88b9318c683`
- Certificat SHA-256 :
  `b27d46c092c63432e7e274d227c0e465cf91ab4656f67c1a0b511402505c8b8c`
