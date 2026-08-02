# Parité fonctionnelle avec Podmix Android

Ce document est la référence de migration. Une fonction n'est considérée comme
remplacée que lorsqu'elle fonctionne dans l'APK hybride sur un appareil réel.

## Catalogue et navigation

| Fonction historique | Cible | État |
| --- | --- | --- |
| Podcasts, émissions, radios et DJ sets | React + API | Opérationnels, types podcast/émission distincts |
| Ajout par recherche, URL ou flux RSS | React + API | RSS/iTunes, Radio Browser, URL DJ et recherche multi-sets opérationnels |
| Détails podcast et DJ, liste d'épisodes | React + API | Fiches source et épisode, tracklist et actions opérationnelles |
| Recherche globale | React local puis API fédérée | Recherche locale sources et épisodes opérationnelle |
| Favoris et historique | Stockage local + préférences Android | Morceaux contextualisés par épisode, regroupement par podcast, lecture globale, liens Deezer/Spotify et synchronisation Android Auto opérationnels ; favoris de sources ajoutés dans la nouvelle app |
| Réglages | React + préférences locales/natives | Persistants ; lecture, limites catalogue, serveur, détection, Cast, Bose et données |
| Actualisation périodique | Worker Android | Abonnements synchronisés, contrôle réseau toutes les 6 h et notification |
| Retour système Android | Capacitor App | Ferme recherche, modales et fiches avant de réduire l'application |

## Audio

| Fonction historique | Cible | État |
| --- | --- | --- |
| Lecteur et mini-lecteur | React + Media3 | Implémenté |
| Arrière-plan et notification | Media3 Android | Implémenté |
| Téléchargements hors connexion | Android natif + index React | Implémenté, bibliothèque et suppression incluses |
| HLS et radios en direct | Media3 | Radio AAC/HTTP réelle validée sur émulateur ; HLS reste à valider sur appareil |
| File de lecture | Media3 | Implémentée et passage au morceau suivant validé |
| Android Auto | MediaLibraryService | Hiérarchie Podcast → Épisode → Morceaux, logos, dossier Favoris, lecture continue des favoris et bouton cœur implémentés ; navigation et files validées sur Galaxy S23, essai écran véhicule requis |
| Google Cast | Bridge Cast Android | Sélection, connexion et diffusion implémentées ; essai appareil requis |
| Bose SoundTouch | Bridge Android dédié | Lecture, commandes et volume implémentés sur réseau privé ; essai appareil requis |

## Tracklists et détection

| Fonction historique | Cible | État |
| --- | --- | --- |
| Timestamping manuel | PWA | Implémenté |
| Détection de transitions | Worker Python | Implémenté |
| Import de tracklist et alignement | API + PWA | Implémenté |
| YouTube, SoundCloud, Mixcloud | API serveur | Découverte de métadonnées implémentée |
| Bases de tracklists | API serveur | MixesDB puis 1001Tracklists, import sécurisé et alignement implémentés |
| Spotify et Deezer | API serveur | Deezer opérationnel ; Spotify activable avec identifiants serveur ; pochettes de morceaux récupérées automatiquement avec repli sur le logo du podcast |
| MusicBrainz | API serveur | Implémenté |
| Analyse chroma et DTW | Worker Python | Validation acoustique, preuves et corpus reproductible implémentés |
| Frontières musicales | BeatNet/Librosa | BeatNet optionnel, repli Librosa et recalage sur temps forts implémentés |
| AAC et M4A | FFmpeg + Librosa | Décodage et analyse testés |
| Empreinte AcoustID/Chromaprint | Worker Python | Implémentée avec clé serveur facultative |

## Ordre d'exécution

1. Navigation et modèles communs.
2. Catalogue RSS/radio/DJ et écrans de détail.
3. Lecteur global, mini-lecteur, file et téléchargements.
4. Favoris, historique et reprise de lecture.
5. Sources de tracklists et pipeline avancé.
6. Cast, Android Auto et Bose.
7. Migration des données Room et tests de non-régression.

## Design

Le frontend actuel reste inchangé comme demandé. Trois directions distinctes
sont proposées dans `DESIGN_PROPOSALS.md`; aucune n’est appliquée sans choix.

## Validation Android

L'APK a été testée sur un émulateur Android API 35 et sur un Galaxy S23.
Les tests instrumentés valident le contexte applicatif, la préparation d'un
podcast MP3 réel, la lecture d'une radio AAC/HTTP réelle, la navigation
Podcast → Épisode → Morceaux, les logos, le dossier Favoris et les files
Media3 de morceaux et de favoris. Les parcours navigateur desktop et mobile
couvrent recherche, import, lecture, persistance, bibliothèque, réglages, mise
à jour, Studio et installation PWA. Cast, Bose, HLS, l'affichage sur écran
véhicule et les téléchargements longs restent à valider sur leur matériel.
