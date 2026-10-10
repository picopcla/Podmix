# FYH512 — moteur Podmix existant + annonces DJ

Statut : **terminé, gelé et vérifié** sur la branche laboratoire
`lab/vad-prototype-20261010`. Verdict : **gain**, de **12/32 à 16/32**
appariements à ±30 s, sans début précédemment apparié perdu. Il reste 16 manques
et 16 surplus. Il s'agit du moteur existant assisté par la tracklist connue et
par l'interprétation automatique d'annonces, jamais d'une reconnaissance
musicale indépendante. Le test est déjà connu et n'est pas présenté comme aveugle.

## Préservation du moteur existant

- original attesté : commit `eebd70c45e88e7a632992b0255f1b1d55501c9c2`,
  `server/audio_fallback.py`, SHA-256
  `03e23eba207aa06966d2e715220d68036654dbb6a95980145d54934f7f47501d` ;
- baseline gelé attesté : SHA-256
  `e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208` ;
- copie labo modifiée :
  [`audio_fallback_voice.py`](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/audio_fallback_voice.py),
  SHA-256 `274d4cad5cfd47af83918f1dd09212401f2292107daea58ff4d66c286fdb2905` ;
- aucun fichier de production, API, SQLite, Docker, APK ou `main` modifié ;
- mode voix désactivé réellement exécuté avec le même audio et les mêmes
  références courantes : JSON **octet pour octet identique** au baseline,
  SHA-256 `e0200bae…` ;
- le diff est limité à la copie labo : types/règles voix, hook immédiatement
  après décodage, validation des conflits, passage des ancres au maillage et
  support d'un premier début non nul. Les landmarks, présences, novelty,
  références, corrections adjacentes et optimiseur restent ceux du moteur.

## Pré-étape voix réellement exécutée

Le runner rejoue les sorties gelées du VAD+ASR local CPU déjà produites pour
FYH512, dont le hash est enregistré, puis exécute **automatiquement** la nouvelle
interprétation. Il ne rejoue aucune décision manuelle `d002→piste 1` : le code
cherche le dernier marqueur explicite de morceau suivant, rapproche uniquement
la proposition qui le suit des artistes/titres connus et exige une correspondance
unique. Le récapitulatif « coming up tonight » de d002 est donc ignoré tandis que
« kicking things off with … Keep On Running » est admissible.

Règles figées avant comparaison : confiance VAD/ASR `≥0,95`, score titre
`≥0,72`, marge d'unicité `≥0,12`, conflit présence/correction `>90 s`, espacement
minimal historique `45 s`. Une présence vocale, un jingle, une liste, un titre
ambigu, une relation actuelle/précédente/récapitulative ou un conflit d'ordre,
fenêtre ou présence produit une abstention tracée. Le canal `boundary_anchors`
est distinct de `LandmarkPresence` et ne reçoit jamais l'offset Deezer de 30 s.

Ancres automatiques acceptées :

- piste 1, `120,00 s` RSS, confiance interne `0,9143`, d002 ; l'intro reste
  une intro et aucun morceau fictif n'est créé ;
- piste 12, `2365,68 s`, `0,8184`, d005 ;
- piste 24, `5349,38 s`, `0,8243`, d008 ;
- piste 32, `6975,72 s`, `0,8182`, d010.

Les débuts effectivement modifiés dans le moteur sont : piste 1 `0→120,00`,
piste 12 `2490,62→2365,68`, piste 23 `5225,41→5088,45` (effet du nouveau
couloir, piste non annoncée conservée), piste 24 `5375,74→5349,38` et piste 32
`7013,25→6975,72`. Il n'y a aucun collage de points après calcul.

## Références publiques et limite de comparaison

Les deux modes de cette expérience ont utilisé le même cache courant de 22
fichiers. Les 17 aperçus Deezer ont les mêmes hashes que le baseline. Cinq
fichiers SoundCloud ont changé de hash depuis l'exécution baseline (Save Me,
Palm Of Your Hands, FSU, The Right Place et Through The Looking Glass) ; cette
différence externe est explicitement conservée dans le résumé. Elle n'est pas
masquée ni attribuée à la voix. Malgré elle, le mode désactivé produit le
baseline exact ; la comparaison désactivé/voix, effectuée avec le même cache,
isole donc le hook voix pour cette exécution.

## Évaluation après gel

Référence officielle : [vidéo Andrew Rayel](https://www.youtube.com/watch?v=5JhO52xYwf0).
Appariement libre glouton un-à-un par erreur absolue croissante, tolérance
`≤30 s`. Conversion : `RSS=YT+1,3455 s` avant `3565 s`, puis `+1,7265 s`.

- baseline : **12/32**, 20 manques, 20 surplus ;
- moteur existant + voix : **16/32**, 16 manques, 16 surplus ;
- gagnés : références 1, 12, 23 et 32 ; perdus : **aucun** ;
- erreur absolue moyenne : **8,4816 s sur 16 appariements** ; médiane :
  **1,6250 s sur 16** ;
- `<1 s` : **5/16** ; `<5 s` : **11/16** ; `<10 s` : **12/16** ;
  `<30 s` : **16/16**.

Tableau simple 32 lignes, horloge YouTube arrondie (`YT|ALGO`) :

```text
120|119
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
2366|2364
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
5059|5087
5348|5348
5576|non trouvé
5827|5856
5951|non trouvé
6185|6186
6383|6389
6587|non trouvé
6730|non trouvé
6974|6974
```

Surplus séparés, `numéro sortie:temps RSS` : `3:525,44`, `7:1233,60`,
`9:1807,17`, `11:2208,00`, `15:2984,64`, `16:3338,08`, `17:3564,10`,
`18:3907,39`, `19:4157,82`, `20:4436,16`, `21:4663,17`, `22:4926,21`,
`25:5734,78`, `27:5985,16`, `30:6500,16`, `31:6694,46`.

## Code, protocole, tests et sorties

- [runner réellement exécuté](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/run_fyh512_existing_engine_plus_voice.py),
  [évaluateur après gel](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/evaluate_fyh512_existing_engine_plus_voice.py),
  [tests](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/test_existing_engine_plus_voice.py) ;
- [manifeste de gel](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-freeze-manifest.json),
  [sortie voix](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-frozen.json),
  [trace](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-trace-frozen.json),
  [résumé](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-evaluation-summary.json),
  [comparaison 32 lignes](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-comparison-32.json),
  [paires](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-pairs.json),
  [manques](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-missing.json),
  [surplus](https://github.com/picopcla/Podmix/blob/lab/vad-prototype-20261010/lab/vad_prototype/results/fyh512-existing-engine-plus-voice-surplus.json).

Tests : 9/9 passants, couvrant reproduction baseline, ancres internes, premier
début non nul, récap/jingle/ambiguïté, conflits ordre/présence, absence d'offset
preview et conservation des morceaux non annoncés. Une seule charge CPU lourde
a tourné à la fois.

## Extraits privés

Six MP3 sont livrés uniquement dans
`picopcla/pont-messages/reponses/podmix-fyh512-moteur-existant-plus-voix-extraits/`.
Le fichier `index.json` fournit points d'écoute, intervalles et SHA-256 : quatre
bonnes ancres, intro/premier début, ancre d008 modifiant utilement le couloir,
une annonce sous seuil écartée et une présence vocale seule écartée.

## Bilan factuel

Fonctionne : copie labo minimale du moteur exact, hook voix avant matching,
quatre ancres automatiques traçables, intro à 120 s, maillage existant conservé,
baseline désactivé reproduit, sorties gelées avant évaluation, gain net de
4 débuts sans perte, tests et extraits privés livrés.

Reste impossible dans ce périmètre : récupérer les 16 manques restants sans
annonce admissible/référence suffisante ; résoudre
les DRM SoundCloud ; garantir que les fichiers publics externes conserveront
leurs hashes. Aucun nouveau service, moteur, base musicale ni retuning YouTube
n'a été utilisé.
