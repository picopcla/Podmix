# Plan de transition Podmix

## Architecture cible

Podmix devient un produit web-first composé de trois couches :

1. `podmix-next` : frontend React/TypeScript commun à la PWA et à l’APK Capacitor.
2. `podmix-api` : API, authentification, catalogue, synchronisation et orchestration.
3. `podmix-workers` : traitements audio Python, extraction et détection asynchrone.

L’APK conserve une couche Kotlin réduite pour Media3, la lecture en arrière-plan,
Android Auto, Cast, les téléchargements et les fonctions peu fiables en WebView.

## Principes

- Le projet Android historique reste fonctionnel pendant toute la transition.
- L’API est définie avant le déplacement des repositories.
- Les secrets et scrapers quittent l’APK.
- Le timestamping manuel fonctionne sans backend.
- La détection lourde devient un job serveur reprenable et observable.
- Les données synchronisées utilisent de nouveaux identifiants stables.

## Lots

### Lot 0 — Inventaire et contrat

- Cartographier écrans, modèles, repositories, services et permissions Android.
- Définir Episode, AudioSource, Track, Marker et DetectionJob.
- Écrire le contrat OpenAPI et la stratégie d’authentification.
- Séparer données locales et données synchronisées.

Critère de sortie : un épisode et sa tracklist sont décrits sans dépendance Kotlin.

### Lot 1 — Atelier vertical

- Import audio local.
- Waveform, lecture, seek et marqueurs.
- Édition artiste/titre et score de confiance.
- Simulation puis branchement d’un job de détection.
- Sauvegarde locale IndexedDB.

Critère de sortie : une tracklist peut être créée et corrigée dans le navigateur.

### Lot 2 — Backend de détection

- Création et annulation de jobs.
- File d’attente et workers Python.
- Stockage des peaks, segments, candidats et preuves.
- Progression par Server-Sent Events.
- Adaptateurs de sources côté serveur.

Critère de sortie : une analyse survit à la fermeture du navigateur.

### Lot 3 — Catalogue PWA

- Podcasts, émissions, radios et DJ sets.
- Recherche, favoris et historique.
- Lecteur web, Media Session API et mode hors ligne limité.
- Synchronisation des tracklists et préférences.

Critère de sortie : la PWA couvre le parcours quotidien hors fonctions Android avancées.

### Lot 4 — APK Capacitor

- Conteneur Capacitor.
- Bridge TypeScript/Kotlin vers Media3.
- Service au premier plan, notification et contrôles système.
- Téléchargements, Cast et Android Auto.
- Migration progressive des données Room.

Critère de sortie : l’APK hybride remplace l’interface Compose sans régression audio.

### Lot 5 — Bascule

- Tests sur appareils Android modestes.
- Mesures batterie, mémoire, reprise réseau et durée de traitement.
- Migration des utilisateurs et stratégie de retour arrière.
- Retrait progressif des écrans Compose remplacés.

## Premier incrément

L’atelier de timestamping valide le risque principal — audio long et interaction
timeline — avant la migration du catalogue et de la navigation complète.

## État au 25 juillet 2026

- [x] Base React/TypeScript responsive
- [x] Manifeste PWA et cache de l’application
- [x] Import audio et waveform
- [x] Marqueurs et édition de tracklist
- [x] Persistance IndexedDB
- [x] Contrat OpenAPI v1
- [x] Cycle de recherche durable et progression SSE
- [x] Suppression des uploads, téléchargements et traitements audio du VPS
- [x] Parsing des tracklists horodatées et numérotées
- [x] Conservation exclusive des timestamps explicites avec preuves
- [x] Validation humaine des propositions dans l’atelier
- [x] Découverte par URL et métadonnées YouTube, SoundCloud et Mixcloud
- [x] Protection SSRF par HTTPS et liste blanche de domaines
- [x] Recherche automatique d’une URL depuis le titre de l’épisode
- [x] Validation artiste/titre dans MusicBrainz avec cache et limitation de débit
- [x] Conteneur Capacitor Android isolé sous `com.podmix.next`
- [x] Synchronisation automatisée PWA vers Android
- [x] Compilation et vérification de l’APK debug
- [x] Bridge Capacitor Media3 et service de lecture en arrière-plan
- [x] Téléchargements audio natifs persistants pour l’écoute hors connexion
- [x] Raccordement des commandes et téléchargements natifs à l’interface
- [x] File de lecture, Android Auto et Cast
- [ ] Authentification et synchronisation multi-appareils

## Timestamping sans média VPS

- [x] RSS en première source
- [x] Fallback MixesDB et 1001Tracklists
- [x] Recherche Web et commentaires publics
- [x] Consolidation Nous Portal
- [x] Aucun timestamp inventé quand la source n’en publie pas
