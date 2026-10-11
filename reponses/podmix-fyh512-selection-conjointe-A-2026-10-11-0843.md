# FYH512 A — sélection conjointe voix et landmarks

## Verdict

**Correction structurelle validée, amélioration mesurée nulle.** Le replay A
produit exactement la sortie de la base voix gelée : **16/32**, sans gain et
sans perte. Il n'y a donc aucune régression d'un match existant, mais aucun
nouveau match à revendiquer. Le correctif B n'a pas été réalisé.

Le mode voix désactivé reproduit exactement le baseline 12/32 :
`e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208`.
La sortie A vaut
`199c10e9c35552206a81ddf782e689986e7f36628415b5a18ae1bd033f37070a`,
identique à la base 16/32.

## Isolation et protocole figé

- base : `1391d7ab63d04e118c0d8df84fbc4c07199654a6` ;
- copie moteur avant modification : SHA-256
  `72e478f2c7fbeb208d1861320411f8e5630aaad95df81fedfa5c6feccf08ec0e` ;
- module A isolé :
  [`audio_fallback_joint_a.py`](../lab/vad_prototype/audio_fallback_joint_a.py) ;
- protocole figé avant évaluation :
  [`fyh512_joint_selection_a_protocol.json`](../lab/vad_prototype/fyh512_joint_selection_a_protocol.json) ;
- runner :
  [`run_fyh512_joint_selection_a.py`](../lab/vad_prototype/run_fyh512_joint_selection_a.py) ;
- diff moteur contre `1391d7a` :
  [`fyh512-joint-selection-a-vs-1391d7a.patch`](../lab/vad_prototype/results/fyh512-joint-selection-a-vs-1391d7a.patch),
  SHA-256 `880d2a8fed70eb9c46facda143d31df2fe158ba15b060125addbdf9be082a406`.

Les preuves de la route sous seuil refusée restent dans le dépôt. A repart de
l'interprétation de `1391d7a` : seuil voix `>= 0,95`, quatre annonces
candidates, aucun changement de texte, de seuil ou d'offset preview. Les trois
annonces sous seuil sont hors scope et A seul ne les récupère pas.

## Règle de sélection A

La recherche est exhaustive sur les sous-ensembles des annonces candidates,
donc l'ordre chronologique ne verrouille plus le premier candidat. Pour chaque
sous-ensemble, les candidats landmarks en conflit direct sont retirés avant
le DP ordonné existant. La faisabilité finale appelle exactement
`detect_constrained_transitions` avec la même novelty, les mêmes présences,
couloirs, candidats et contraintes DP que la sortie finale.

Objectif lexicographique figé :

1. maximiser le nombre de présences landmarks fiables ;
2. maximiser `Σ log1p(score landmark) + Σ confiance voix` ;
3. maximiser le nombre d'ancres voix ;
4. départage stable indépendant de l'ordre d'entrée.

Une annonce ne peut donc pas réduire la couverture landmarks. Entre deux
chaînes de même couverture, sa confiance est mise en balance avec la force des
empreintes : la voix ne gagne pas automatiquement sur une empreinte fiable.
Une incompatibilité réelle conserve l'abstention.

Sur FYH512, les 16 sous-ensembles des quatre annonces ont été examinés. Le
meilleur ensemble retient d002, d005, d008 et d010, ainsi que 11 présences
landmarks. Les alternatives, conflits, objectif et choix figurent dans la
[`trace gelée`](../lab/vad_prototype/results/fyh512-joint-selection-a-trace-frozen.json).

## Entrées et reproductibilité

- média RSS : SHA-256
  `47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69` ;
- dix événements ASR gelés rejoués automatiquement ; aucun nouveau VAD/ASR
  n'est revendiqué ;
- mêmes 22 références et cache ; les échecs DRM SoundCloud restent des
  abstentions de téléchargement, comme dans la base ;
- aucune heure 1001TL/YouTube n'est chargée pendant la sélection ;
- manifeste :
  [`fyh512-joint-selection-a-freeze-manifest.json`](../lab/vad_prototype/results/fyh512-joint-selection-a-freeze-manifest.json).

Commande exécutée (avec le FFmpeg déjà présent dans l'environnement Python) :

```text
PODMIX_FFMPEG=<binaire local> python run_fyh512_joint_selection_a.py \
  --media <média vérifié> --vad-python /bin/false --asr-python /bin/false \
  --replay-trace results/fyh512-existing-engine-complete-voice-trace-frozen.json
```

Le replay ne consulte pas les exécutables VAD/ASR fournis : ils sont
volontairement inertes et les dix événements gelés sont injectés par le hook.

## Tests synthétiques

[`test_joint_selection_a.py`](../lab/vad_prototype/test_joint_selection_a.py)
contient huit tests, tous passés :

- interprétation gelée inchangée à 0,95 et quatre candidats ;
- première annonce bloquant un meilleur ensemble ;
- deux chaînes landmarks, dont une compatible voix ;
- conflit réel donnant une abstention ;
- invariance à l'ordre des candidats ;
- fallback landmarks sans voix ;
- maintien déterministe du cas 150/195 ;
- conservation des titres non annoncés.

## Évaluation libre 1-à-1 à 30 s

| Sortie | Matchs | Manques | Surplus | Moyenne absolue | Médiane absolue | Dénominateur |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 12/32 | 20 | 20 | — | — | 12 |
| Base voix `1391d7a` | 16/32 | 16 | 16 | — | — | 16 |
| A | 16/32 | 16 | 16 | 8,4816 s | 1,6250 s | 16 |

Gains contre 16/32 : aucun. Pertes contre 16/32 : aucune. Verdict automatisé :
`validated_no_regression`.

Les fichiers d'évaluation séparée sont :

- [`résumé`](../lab/vad_prototype/results/fyh512-joint-selection-a-evaluation-summary.json) ;
- [`32 lignes CSV avec écarts signés et déplacements avant/après`](../lab/vad_prototype/results/fyh512-joint-selection-a-comparison-32.csv) ;
- [`paires libres`](../lab/vad_prototype/results/fyh512-joint-selection-a-pairs.csv) ;
- [`manques`](../lab/vad_prototype/results/fyh512-joint-selection-a-missing.csv) ;
- [`surplus`](../lab/vad_prototype/results/fyh512-joint-selection-a-surplus.csv) ;
- [`audit des dix candidats`](../lab/vad_prototype/results/fyh512-joint-selection-a-candidate-audit.csv).

## Tableau 32 — temps réels 1001TL et algorithme

Les temps algorithme sont convertis du RSS vers 1001TL avec l'offset gelé :
`RSS = 1001TL + 1,3455 s` avant 3565 s, puis `+ 1,7265 s` après. L'association
ci-dessous reste celle du même index/artiste/titre ; elle est distincte de
l'appariement libre utilisé pour le score.

```text
00:02:00 1001TL | 00:01:59 ALGO
00:05:37 1001TL | 00:05:38 ALGO
00:09:45 1001TL | 00:08:44 ALGO
00:13:00 1001TL | 00:13:02 ALGO
00:16:46 1001TL | 00:16:47 ALGO
00:19:02 1001TL | 00:18:33 ALGO
00:23:20 1001TL | 00:20:32 ALGO
00:25:54 1001TL | 00:25:56 ALGO
00:29:35 1001TL | 00:30:06 ALGO
00:32:35 1001TL | 00:32:06 ALGO
00:35:33 1001TL | 00:36:47 ALGO
00:39:26 1001TL | 00:39:24 ALGO
00:43:34 1001TL | 00:43:37 ALGO
00:47:05 1001TL | 00:47:06 ALGO
00:51:02 1001TL | 00:49:43 ALGO
00:56:30 1001TL | 00:55:37 ALGO
01:00:31 1001TL | 00:59:23 ALGO
01:04:09 1001TL | 01:05:06 ALGO
01:08:06 1001TL | 01:09:16 ALGO
01:11:50 1001TL | 01:13:54 ALGO
01:16:29 1001TL | 01:17:41 ALGO
01:20:25 1001TL | 01:22:04 ALGO
01:24:19 1001TL | 01:24:47 ALGO
01:29:08 1001TL | 01:29:08 ALGO
01:32:56 1001TL | 01:35:33 ALGO
01:37:07 1001TL | 01:37:36 ALGO
01:39:11 1001TL | 01:39:43 ALGO
01:43:05 1001TL | 01:43:06 ALGO
01:46:23 1001TL | 01:46:29 ALGO
01:49:47 1001TL | 01:48:18 ALGO
01:52:10 1001TL | 01:51:33 ALGO
01:56:14 1001TL | 01:56:14 ALGO
```

Provenance :
[1001Tracklists FYH512](https://www.1001tracklists.com/tracklist/m84xvnt/andrew-rayel-find-your-harmony-radioshow-512-2026-09-23.html).
La page était lisible en direct au moment du contrôle (titre de page conforme),
mais son HTML public ne rendait pas les 32 timestamps exploitables sans le
JavaScript du site. Les 32 temps restent donc ceux déjà vérifiés et gelés dans
`fyh512_reference.json`, identiques à YouTube comme établi avant A.

## Hashes principaux

- sortie off : `e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208` ;
- sortie A : `199c10e9c35552206a81ddf782e689986e7f36628415b5a18ae1bd033f37070a` ;
- trace A : `9749f76226fcf55d8f57030193b357a67235db55928edf7c2ca19a48f86aceaf` ;
- CSV 32 : `2d8d23317236f86beaeb5a747199afa88d758113938f06d9b0f1fffcad3cc85f` ;
- résumé : `5c30e83047d488c4f606f1714b44e7db709eb07b770ad081541136021d224985`.

## Limites factuelles

A corrige la dépendance d'ordre et trace les alternatives. Sur ce média, les
quatre ancres de la base étaient déjà compatibles avec la chaîne choisie : la
nouvelle recherche conjointe n'a donc déplacé aucune sortie. Les trois annonces
sous seuil restent exclues, aucune nouvelle ancre ni aucun MP3 privé n'a été
créé, et les corrections finales B restent entièrement hors scope.
