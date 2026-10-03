# Pipeline automatique sans traitement audio VPS

Le serveur ne reçoit aucun fichier audio, ne télécharge aucun épisode et
n’exécute aucun décodeur média. La lecture, le téléchargement hors ligne et
l’édition d’une waveform appartiennent uniquement à l’application Android.

Pour chaque épisode, le job durable suit cet ordre :

1. lire la description et les éventuels timestamps du RSS ;
2. extraire les liens média ou tracklist publiés dans cette description ;
3. résoudre les smart-links connus (`lnk.to`, `linktr.ee`, `podlink.to`) vers
   YouTube, SoundCloud, Mixcloud ou 1001Tracklists ;
4. lire les chapitres, descriptions ou commentaires publiés par ces pages ;
5. interroger MixesDB et 1001Tracklists ;
6. effectuer une recherche Web multi-sources ;
7. faire consolider les textes collectés par Nous Portal lorsque la clé est
   configurée ;
8. si nécessaire, confier au navigateur Android la consultation de
   1001Tracklists ;
9. conserver les titres sans position tant qu’aucune source ne fournit de
   timestamp explicite.

Les timestamps RSS et Web ne sont jamais recalés sur le signal. Une tracklist
sans horaires reste au statut `pending` : le serveur n’invente pas une
répartition dans la durée. Si le RSS n’horodate qu’une partie des titres, les
timestamps présents sont conservés mais les valeurs manquantes déclenchent la
recherche externe.

Les jobs sont stockés dans SQLite et repris après redémarrage. La migration
retire leur ancienne relation avec la table d’uploads. Les anciens jobs audio
`queued` ou `running` sont arrêtés ; les résultats déjà terminés restent
consultables.

Nous Portal est activé avec `NOUS_PORTAL_API`. Le serveur peut lire cette clé
depuis l’environnement, `local.properties` ou `android/local.properties`.
Aucune clé n’est intégrée au frontend ou à l’APK.
