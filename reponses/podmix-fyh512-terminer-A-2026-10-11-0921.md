# FYH512 — mission A terminée, B non réalisée

## Verdict

La correction structurelle A est complète et la comparaison contrôlée est
désormais valide. La sortie reste exactement celle de la base voix : **16/32**,
**zéro gain** et **zéro perte**. Il s'agit donc d'une correction de recherche,
pas d'une amélioration d'accuracy mesurée sur FYH512.

- sortie voix désactivée :
  `e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208` ;
- sortie A :
  `199c10e9c35552206a81ddf782e689986e7f36628415b5a18ae1bd033f37070a` ;
- base en vigueur : `1391d7ab63d04e118c0d8df84fbc4c07199654a6` ;
- média :
  `47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69` ;
- interprétation voix inchangée : seuil `>= 0,95`, quatre ancres retenues ;
- voie sous seuil, retuning, corrections B et nouveau VAD/ASR : non réalisés.

## Cache de références rétabli et provenance des cinq divergences

Le cache original complet a été retrouvé et vérifié : **22/22 fichiers** ont
exactement le SHA-256 et la taille du manifest de `1391d7ab`. Il a été restauré
avant le replay final. Le runner refuse maintenant le réseau et bloque avant
l'analyse si l'un de ces 22 fichiers manque ou diffère.

Les 17 fichiers Deezer étaient déjà identiques. Les cinq URL SoundCloud sont
également inchangées, mais deux téléchargements successifs avaient produit des
conteneurs différents :

- base : MP4 plat `ftyp + moov + free + mdat` ;
- manifest A initial : MP4 fragmenté `ftyp + moov`, puis répétitions
  `styp/sidx/moof/mdat` ;
- dans les cinq cas, le SHA-256 du PCM décodé par FFmpeg 7.0.2 est identique
  entre l'ancien et le nouveau fichier.

| Référence | Base SHA / octets | A initial SHA / octets | PCM SHA identique |
|---|---|---|---|
| The Right Place | `ffc6f1ac…` / 5 127 986 | `0bc50bbd…` / 5 133 393 | `c81649b7…` |
| Palm Of Your Hands | `3052bef6…` / 10 301 020 | `f575ea2c…` / 10 310 531 | `49d28798…` |
| FSU | `10cf8e1b…` / 7 070 521 | `6d782953…` / 7 077 084 | `0c5cf9b1…` |
| Through The Looking Glass | `c8d66d0f…` / 5 500 333 | `b5989509…` / 5 505 839 | `7d61aba5…` |
| Save Me | `905d37b7…` / 7 051 283 | `7986a26d…` / 7 059 246 | `1d2e950b…` |

La divergence est donc prouvée comme un **remux/conteneur fragmenté**, sans
différence de PCM décodé. La comparaison finale n'utilise néanmoins pas cette
équivalence comme substitut : elle utilise bien les octets originaux de la
base. La preuve complète, avec URL, SHA, tailles et hashes PCM, est dans
[`fyh512-reference-cache-provenance.json`](../lab/vad_prototype/results/fyh512-reference-cache-provenance.json).

## Recherche conjointe A corrigée

L'ancien correctif testait tous les sous-ensembles voix, mais une seule chaîne
landmarks issue du DP local. Une chaîne rejetée après correction adjacente ou
par le DP final condamnait donc trop tôt le sous-ensemble voix.

Le nouveau chemin, dans
[`audio_fallback_joint_a.py`](../lab/vad_prototype/audio_fallback_joint_a.py),
fait pour chaque sous-ensemble voix :

1. filtrage des conflits directs voix/présence ;
2. énumération de toutes les chaînes ordonnées de présences landmarks fiables ;
3. rejet chaîne par chaîne des conflits de correction adjacente ;
4. classement selon l'objectif conjoint figé ;
5. appel du vrai `detect_constrained_transitions()` jusqu'à la première chaîne
   faisable — qui est alors l'optimum exact du sous-ensemble ;
6. comparaison des optima des sous-ensembles voix avec le même objectif.

Objectif lexicographique inchangé : couverture de présences fiables, puis
`Σ log1p(score landmark) + Σ confiance voix`, puis nombre d'ancres voix, puis
identité stable. L'ordre d'entrée des candidats voix ou landmarks n'intervient
pas.

Les candidats sans présence fiable sont éliminés par dominance : ils
n'affectent ni objectif, ni correction, ni couloir théorique, ni DP final, et
ne peuvent qu'ajouter une restriction d'ordre. Cette élimination ne supprime
donc aucune solution effective.

La limite publiée est de **100 000 chaînes complètes par sous-ensemble voix**.
Si elle est atteinte avant épuisement, toute la sélection lève une abstention
explicite ; aucun résultat tronqué n'est rendu comme « exhaustif ». Sur FYH512,
les 16 sous-ensembles ont terminé : 516 480 chaînes examinées au total, maximum
32 280 pour un sous-ensemble, donc aucune troncature. Le protocole figé est
[`fyh512_joint_selection_a_protocol.json`](../lab/vad_prototype/fyh512_joint_selection_a_protocol.json).

## Replay réel et tests

Le runner réel
[`run_fyh512_joint_selection_a.py`](../lab/vad_prototype/run_fyh512_joint_selection_a.py)
a été exécuté avec le média vérifié et le cache historique. Son hook rejoue
automatiquement les **10 événements ASR gelés** ; les exécutables VAD et ASR
étaient `/bin/false`, donc aucun nouveau VAD/ASR n'est revendiqué.

Treize tests ciblés passent : les douze tests A plus le témoin d'appariement
glouton. Ils couvrent notamment :

- chaîne localement gagnante rejetée par correction adjacente, puis chaîne
  alternative compatible ;
- chaîne localement gagnante impossible dans le vrai DP, puis alternative
  faisable ;
- invariance à l'ordre des candidats landmarks et voix ;
- conflit réel et abstention ;
- limite de recherche donnant une abstention explicite ;
- témoin non glouton sur les annonces, cas 150/195, fallback landmarks,
  conservation des titres non annoncés et appariement libre glouton.

La découverte complète du labo passe également : **54/54 tests**.

Sur le média réel, aucune chaîne n'a été rejetée par correction adjacente ou
DP : ces deux régressions sont donc démontrées par les tests construits, pas
présentées comme des événements observés sur FYH512.

## Évaluation 32 titres

Règle : appariement libre glouton un-à-un par erreur absolue croissante,
tolérance `<= 30 s`.

| Sortie | Matchs | Manques | Surplus | Moyenne absolue | Médiane absolue | Dénominateur |
|---|---:|---:|---:|---:|---:|---:|
| Baseline voix désactivée | 12/32 | 20 | 20 | — | — | 12 |
| Base voix `1391d7ab` | 16/32 | 16 | 16 | — | — | 16 |
| A terminée | 16/32 | 16 | 16 | 8,4816 s | 1,6250 s | 16 |

Gains face à 16/32 : aucun. Pertes face à 16/32 : aucune. Le CSV
[`fyh512-joint-selection-a-comparison-32.csv`](../lab/vad_prototype/results/fyh512-joint-selection-a-comparison-32.csv)
contient pour chaque titre le temps réel, le temps A, l'écart signé, le temps
avant A, le déplacement signé avant/après et le statut d'appariement libre.

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

Source :
[1001Tracklists FYH512](https://www.1001tracklists.com/tracklist/m84xvnt/andrew-rayel-find-your-harmony-radioshow-512-2026-09-23.html).
La page n'a pas été relue en direct pendant ce correctif. Les 32 temps ont été
chargés seulement après gel depuis `fyh512_reference.json` ; ce sont les temps
déjà vérifiés comme identiques à YouTube. Ils n'interviennent jamais dans la
sélection.

## Hashes et preuves

- moteur A : `a290559d2d9b55068581e5c1f956abdeb33ab179e3ea3f7400fcf6a62f33d47a` ;
- runner : `2d66a2a47dd9b140cdd8d06f13f3ea8ef0c91f92cfd05c79e57b107097978cdc` ;
- évaluateur : `dc069f903fb4d540d71c4ef55d8bfccd26f840f1d732ea5778079e283554fb31` ;
- tests A : `92ddac01ccef456d8c83fb383114647ac81924ebdc5bdecb457e6ec5180a9169` ;
- protocole : `044a171e9401e3bc8f5bb09838847db34bef90328456efc15b4138516b0d258f` ;
- manifest de gel : `d53b3e7d3d7e5a07872e328b13019478dceebcd5516e47d7262c7de8f7f7d81e` ;
- trace A : `df213c69ede6ecf636317ffb07a0950066c226249bbbba620e54d5c91116debb` ;
- CSV 32 : `2d8d23317236f86beaeb5a747199afa88d758113938f06d9b0f1fffcad3cc85f` ;
- résumé : `5c30e83047d488c4f606f1714b44e7db709eb07b770ad081541136021d224985` ;
- preuve cache : `bb61755c4b7f4e40a56c86d8bcf0b100396e4363304f2879895679a4e9d4a8e6` ;
- manifest d'évaluation : `a10e48dd76ff3532764914f6450fdbf5f55bdea9abefc772ba5d04c9278848ef`.

Le
[`manifest d'évaluation`](../lab/vad_prototype/results/fyh512-joint-selection-a-evaluation-manifest.json)
réunit les hashes du code, runner, évaluateur, tests, protocoles, entrées,
inventaire cache, sorties gelées, traces et sorties d'évaluation.

## Bilan factuel

Fonctionne : cache original 22/22 restauré et verrouillé, provenance des cinq
divergences prouvée, vraie recherche des chaînes alternatives après conflits,
limite et abstention explicites, invariance d'ordre, replay réel des 10
événements, 13 tests ciblés et 54 tests labo passants, sorties off/A exactes,
32 lignes et CSV signés,
16/32 sans perte.

Reste impossible dans A : obtenir un gain d'accuracy sur FYH512 — le gain
mesuré est zéro. Les voies sous seuil, corrections finales B, retuning,
nouvelles ancres, services, production, API, SQLite, Docker et APK restent
hors scope et n'ont pas été touchés.
