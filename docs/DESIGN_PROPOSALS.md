# Propositions de design — sans modification du frontend actuel

Ces pistes restent volontairement séparées de l’application fonctionnelle.
Elles pourront être prototypées après choix, sans remplacer le design actuel.

## A — Signal Observatory

Une interface sombre et spatiale, construite autour du signal :

- waveform comme horizon central ;
- pistes représentées par des constellations colorées ;
- halos indiquant le niveau de confiance ;
- navigation circulaire entre podcast, épisode et tracklist ;
- lecteur réduit à une ligne lumineuse persistante ;
- typographie condensée pour les titres, monospace pour les temps.

Palette : noir bleuté, orange spectral, cyan et ivoire.

Intérêt : identité très forte pour la détection et le timestamping.
Risque : demande une excellente accessibilité pour ne pas devenir décorative.

## B — Radio Cartography

Une bibliothèque inspirée des cartes de fréquences et des plans de métro :

- chaque source devient une ligne ;
- les épisodes sont des stations chronologiques ;
- les correspondances de morceaux relient plusieurs émissions ;
- radios en direct affichées comme des balises animées ;
- hors connexion matérialisé par une zone locale distincte ;
- Studio présenté comme une table de montage éditoriale.

Palette : papier chaud, encre noire, rouge signal, bleu radio.

Intérêt : rend les relations entre émissions, artistes et morceaux immédiatement
lisibles.
Risque : moins adapté aux catalogues très volumineux sans filtres solides.

## C — Modular Hi‑Fi

Une approche inspirée des chaînes hi-fi modulaires, tactile et mobile :

- chaque fonction est un module : source, transport, file, détection, sortie ;
- gros contrôles physiques avec retours d’état nets ;
- Cast et Bose intégrés dans un module « Sortie » ;
- téléchargements représentés comme des cartouches locales ;
- Studio en double panneau : platine/waveform puis fiches de morceaux ;
- thèmes clair et sombre partageant les mêmes volumes et espacements.

Palette : graphite, aluminium, vert VU-mètre, ambre.

Intérêt : excellente compréhension sur téléphone et en voiture.
Risque : moins dense sur ordinateur que le design Studio actuel.

## Recommandation

Conserver le design actuel pour l’atelier professionnel et prototyper
**Modular Hi‑Fi** pour l’APK mobile. Des éléments de **Signal Observatory**
peuvent être réservés à la vue de détection avancée. Cette combinaison offre
une identité originale sans sacrifier la lisibilité ni la parité fonctionnelle.
