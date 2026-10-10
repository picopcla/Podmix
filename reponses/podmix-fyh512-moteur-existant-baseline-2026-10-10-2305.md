# FYH512 — baseline du moteur Podmix existant

Statut : **terminé et vérifié**. Le moteur de production demandé a réellement tourné sans modification de son calcul. Résultat principal : **12/32 appariements à ±30 s**, 20 manques et 20 surplus. Il s'agit d'une **reconnaissance assistée par références connues**, pas d'une identification indépendante. Le test n'est pas présenté comme aveugle : la référence était historiquement connue, mais ses temps n'ont été chargés qu'après gel des sorties.

## Périmètre et version effectivement exécutée

- branche : `lab/vad-prototype-20261010` ; aucun changement sur `main` ni en production ;
- moteur : `server/audio_fallback.py::analyze_known_tracklist()` ; appel reproduit depuis `server/dev_server.py::run_research_job()` / `audioFallbackPending` ;
- commit moteur imposé et vérifié : `eebd70c45e88e7a632992b0255f1b1d55501c9c2` (`feat: sync Podmix 1.0.208`) ;
- SHA-256 du moteur exécuté : `03e23eba207aa06966d2e715220d68036654dbb6a95980145d54934f7f47501d` ; le fichier de la branche labo était octet pour octet identique au blob de ce commit ;
- audio RSS gelé : 288 429 012 octets, SHA-256 `47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69` ; durée décodée réellement utilisée par le moteur : `7216.927375 s` ;
- entrée runtime : exactement 32 objets ne contenant que `artist` et `title`, dans l'ordre officiel ; SHA-256 du fichier source artist/title `a525ef8565aab0d12282fa0e81d93eefae780c21b91ad9cdf510a3faedf6b8dc` ;
- paramètres d'appel : `audio_url` RSS Podbean, `source_url` page Podbean, même page dans `source_urls`, `duration_seconds=7210.0` ; le moteur remplace normalement cette durée indicative par la durée décodée ;
- le cache local du MP3 vérifié par hash a seulement remplacé le transport réseau de `_download_source`; le décodage, les références publiques, les landmarks, les présences, `detect_constrained_transitions()`, les corrections adjacentes et la cohérence sont restés ceux du moteur ;
- aucun timestamp, `providedTime`, offset YouTube/RSS, ancre voix ou `boundary_anchor` n'a été fourni au calcul ; aucun AudD, secret, service payant, nouveau modèle ou méthode de substitution.

Le runner exécuté (SHA-256 `db431a24c5f57953b0b798154857380e1d7c969df1b912b4a89950f8b835776c`) et les sorties ont été gelés avant l'évaluation au commit `e8f6cba52121cb8bf1dbb1174b9fa4fda3c194c6`. Le runner livré (SHA-256 `56e131a105bde972dc6a41c7b86775de76f8f05ecbc5fe8823d885adb0329c77`) ne diffère que par la suppression des paramètres signés temporaires avant sérialisation. Cette sanitation périphérique ne touche pas le calcul ; le JSON de résultats est resté inchangé et son hash est `e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208`.

## Références réellement obtenues ou absentes

Le moteur a retourné 32 URL pour 23 pistes, téléchargé 22 fichiers et construit au moins une référence décodable pour 19 pistes.

- aucune URL retournée : pistes 1, 3, 7, 17, 18, 20, 22, 27 et 29 ;
- URL retournée mais aucun fichier obtenu : pistes 10, 12, 19 et 32 ;
- succès partiel (Deezer obtenu, SoundCloud DRM rejeté) : pistes 9, 11, 14, 21, 28 et 30 ;
- au moins une référence obtenue : pistes 2, 4, 5, 6, 8, 9, 11, 13, 14, 15, 16, 21, 23, 24, 25, 26, 28, 30 et 31 ;
- les 10 échecs sont tous des réponses SoundCloud « DRM protected », absorbées par le fallback comme prévu ;
- références SoundCloud effectivement obtenues : [Save Me](https://soundcloud.com/zerothree-music/against-all-odds-save-me), [Palm Of Your Hands](https://soundcloud.com/housextechno/palm_of_your_hands_adriatique_x_falling_slow_kasbo), [FSU](https://soundcloud.com/nightdiver-nightdiver/victor-ruiz-kura-fsu-southmind), [The Right Place](https://soundcloud.com/fathommusicofficial/maxtage-fathom-eg-the-right) et [Through The Looking Glass](https://soundcloud.com/theviicrew/john-askew-through-the-looking-glass-faders-wilder-remix) ;
- les références Deezer sont les aperçus publics renvoyés par `catalog.search_deezer`; leurs hôtes, chemins, tailles et SHA-256 sont conservés dans la trace, sans paramètres de signature temporaires.

La [trace exhaustive](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-trace-frozen.json) donne, piste par piste, URL publique normalisée, succès/échec, taille et hash. Ces références sont externes au mix cible : aucune ne vient de la vidéo YouTube utilisée ensuite pour mesurer.

## Présences, corrections et fallback

Onze présences ont été retenues par le maillage ordonné : pistes 2, 5, 6, 9, 11, 13, 15, 16, 25, 30 et 31. Le moteur a appliqué trois corrections parce que les deux pistes adjacentes étaient présentes :

- frontière piste 6 : `1128.45 → 1114.49 s` RSS, confiance `high` ;
- frontière piste 16 : `3255.36 → 3338.08 s` RSS, confiance `medium` ;
- frontière piste 31 : `6831.04 → 6694.46 s` RSS, confiance `low`.

La chaîne corrigée a été acceptée par `boundaries_are_coherent()` ; aucune correction proposée n'a donc été rejetée. Il ne s'agit pas d'un fallback pur sans présence (`pure_fallback_no_presence=false`). Les autres frontières restent les choix de `detect_constrained_transitions()` dans les couloirs locaux calculés avec les présences disponibles. À titre descriptif, la correction de la piste 6 dégrade l'écart publié de ~14,90 s à ~28,86 s, et les deux autres corrections restent hors tolérance : la présence catalogue ne garantit donc pas ici une meilleure frontière.

## Évaluation après gel

Référence : [description de la vidéo officielle Andrew Rayel](https://www.youtube.com/watch?v=5JhO52xYwf0). Règle : appariement libre glouton un-à-un, erreurs absolues croissantes, tolérance `≤30 s`. Normalisation : `RSS = YT + 1.3455 s` avant `3565 s`, puis `RSS = YT + 1.7265 s`. Limites : offsets estimés et timestamps YouTube publiés à la seconde.

- appariées : **12/32** ; manques : **20/32** ; surplus : **20/32** ;
- erreur absolue moyenne : **10.886 s sur 12 appariements seulement** ; médiane : **2.7445 s sur 12** ;
- `<1 s` : **3/12** ; `<5 s` : **7/12** ; `<10 s` : **8/12** ; `<30 s` : **12/12** ;
- surplus, sous la forme `numéro sortie:temps ALGO ramené en horloge YT` : `1:-1.35`, `3:524.09`, `7:1232.25`, `9:1805.82`, `11:2206.65`, `12:2489.27`, `15:2983.29`, `16:3336.73`, `17:3562.75`, `18:3905.66`, `19:4156.09`, `20:4434.43`, `21:4661.44`, `22:4924.48`, `23:5223.68`, `25:5733.05`, `27:5983.43`, `30:6498.43`, `31:6692.73`, `32:7011.52`.

Les 32 lignes demandées, arrondies à la seconde, en horloge YouTube (`YT|ALGO`) :

```text
120|non trouvé
337|338
585|non trouvé
780|782
1006|1007
1142|1113
1400|non trouvé
1554|1556
1775|non trouvé
1955|1926
2133|non trouvé
2366|non trouvé
2614|2617
2825|2826
3062|non trouvé
3390|non trouvé
3631|non trouvé
3849|non trouvé
4086|non trouvé
4310|non trouvé
4589|non trouvé
4825|non trouvé
5059|non trouvé
5348|5374
5576|non trouvé
5827|5856
5951|non trouvé
6185|6186
6383|6389
6587|non trouvé
6730|non trouvé
6974|non trouvé
```

## Artefacts vérifiables

- [runner exact](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/run_fyh512_existing_engine.py), [évaluateur](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/evaluate_fyh512_existing_engine.py), [test glouton](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/test_fyh512_existing_engine.py) ;
- [manifeste de gel](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-freeze-manifest.json), [sorties brutes](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-results-frozen.json), [résumé d'évaluation](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-evaluation-summary.json) ;
- [comparaison 32 lignes structurée](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-comparison-32.json), [paires](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-pairs.json), [manques](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-missing.json), [surplus](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-surplus.json).

## Extraits privés

Les quatre MP3 sont livrés uniquement dans `picopcla/pont-messages/reponses/podmix-fyh512-moteur-existant-baseline-extraits/`, avec un `manifest.json` contenant SHA-256, début, fin et positions relatives examinées : bon calage piste 8, raté piste 3 sans référence, rôle des deux présences adjacentes sur la correction piste 6, et raté piste 32 avec référence SoundCloud indisponible. Aucun média n'est versionné dans Podmix.

## Bilan factuel

Fonctionne : moteur existant complet exécuté, version et paramètres attestés, références publiques tracées, 11 présences et 3 corrections observées, sorties gelées avant comparaison, évaluation 32 lignes et extraits privés produits, tests passants. Reste impossible : obtenir les 9 références non trouvées et les 10 fichiers SoundCloud DRM sans changer de source/méthode ; cela n'a pas été contourné. Aucune voix DJ n'a été ajoutée et aucune règle du moteur n'a été modifiée.
