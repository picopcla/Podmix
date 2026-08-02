# Pipeline automatique et reprise

## Comportement actuel

L’analyse d’un épisode est exécutée sur le VPS par un worker unique et
persistée dans SQLite.

1. planification immédiate et persistante du téléchargement ;
2. téléchargement de l’audio par le worker serveur ;
3. détection des transitions ;
4. extraction d’une tracklist structurée depuis la description ;
5. à défaut, lecture des chapitres et métadonnées YouTube, SoundCloud ou
   Mixcloud ;
6. à défaut pour les émissions, DJ sets et podcasts marqués musicaux,
   recherche MixesDB puis 1001Tracklists ;
7. alignement de la tracklist sur les transitions ;
8. validation acoustique chroma/DTW avec les aperçus disponibles ;
9. recalage sur un temps fort BeatNet, ou sur la grille Librosa de repli ;
10. conservation des résultats et de leur provenance.

Les jobs `running` retrouvés au démarrage sont replacés en état `queued`. Le
worker les reprend dans l’ordre de création. Une interruption du frontend ou un
redémarrage du téléphone n’arrête donc pas l’analyse serveur.

Quand l’analyse automatique est activée, le dernier nouvel épisode d’une
émission musicale ou d’un podcast explicitement marqué musical est planifié
lors du rafraîchissement RSS. Un DJ set ajouté est planifié immédiatement ; un
import groupé planifie au maximum trois sets à la fois. Le worker les traite
ensuite séquentiellement afin de limiter le CPU, le trafic externe et les
risques de blocage des sources.

Le frontend conserve pour chaque épisode le `jobId`, l’étape, le pourcentage et
l’erreur éventuelle. Lorsqu’un épisode est rouvert, il se rattache au job
persisté au lieu d’en créer un nouveau.

## Règles de confiance

- Une transition spectrale seule n’est pas publiée comme morceau identifié.
- Un timestamp publié par la source est prioritaire et peut être recalé sur une
  transition proche.
- Une tracklist sans timestamps garde explicitement la preuve
  `Ordre de la tracklist`.
- Un échec de MixesDB, de 1001Tracklists ou du raffinage acoustique ne fait pas perdre les
  résultats déjà obtenus.
- L’IA ne doit pas inventer de timestamp. Elle pourra uniquement normaliser les
  métadonnées et classer des candidats avec leurs preuves.

## Livré dans la priorité 3

- Validation acoustique explicite avec score catalogue, score audio et preuve.
- Adaptateur BeatNet optionnel et grille Librosa de repli.
- Podcasts marqués musicaux inclus dans la politique automatique.
- Corpus synthétique et benchmark reproductible.
- Fallback MixesDB via l’API MediaWiki avant 1001Tracklists.
- Évaluation documentée d’Olaf et Panako.

Le déplacement du catalogue IndexedDB vers SQLite et les outils MCP restent des
chantiers séparés de la priorité 3.
