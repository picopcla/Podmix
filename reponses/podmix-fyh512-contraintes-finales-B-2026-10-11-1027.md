# FYH512 B — corrections finales sous contraintes locales

## Verdict

**B est validé sans régression de matching, avec un gain d’accuracy nul.** La
sortie finale conserve **16/32** en appariement libre un-à-un à `≤ 30 s`, sans
gain ni perte contre A. Le baseline reste **12/32**. La correction est donc
structurelle : elle empêche trois corrections post-DP de contourner les
contraintes et l’objectif, sans revendiquer de nouvelle exactitude.

Le contrôle voix désactivée reproduit exactement la sortie gelée demandée :
`e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208`.
La sortie B vaut
`2b0a597bc0b0b9fbf5d59db2b722d286e0965040fd2bef6422dad849a1003dfa`.

## Choix générique figé avant évaluation

Le choix documenté dans
[`fyh512_joint_selection_b_protocol.json`](../lab/vad_prototype/fyh512_joint_selection_b_protocol.json)
est d’intégrer chaque correction adjacente comme **candidat ordinaire du même
DP**, avec sa novelty réelle et sans bonus. Il n’y a plus d’écrasement de coupe
après le DP. Une proposition n’est acceptée que si elle gagne l’objectif global
et appartient au chemin final revérifié.

Un unique objet `TransitionProblem` calcule et porte : maillage théorique,
rayons des couloirs, minimums locaux 70–110 s, candidats, maximum global,
frontières voix fixes et propositions de correction. Le solveur, l’audit des
propositions et la vérification finale consomment ce même objet; aucun seuil
approximatif parallèle n’intervient sur la route B.

Le contrôle landmark-only sans voix reste volontairement le chemin A gelé,
hors sélection B, afin de respecter le SHA off imposé. Il ne sert ni au choix
ni au réglage de B.

## Résultat des corrections réelles

Le DP sans forçage garde le même objectif avant/après ajout des candidats :
`263,84302856`. Les trois propositions sont refusées et ne sont jamais
présentées comme acceptées :

| Frontière (index 0) | Avant A / proposition | Après B | Couloir | Min. local | Décision |
|---:|---:|---:|---:|---:|---|
| 5 | 1114,49 s | 1128,45 s | 1054,84–1144,84 s | 70,00 s | refusée : objectif forcé `262,29198706`, inférieur |
| 15 | 3338,08 s | 3255,36 s | 3220,136–3430,136 s | 107,1514 s | refusée : objectif forcé `262,87998057`, inférieur |
| 30 | 6694,46 s | 6831,04 s | 6711,3908–6875,4572 s | 75,2026 s | refusée : hors couloir local |

Le lot complet n’est pas faisable parce qu’il contient déjà la troisième
proposition individuellement invalide; il est donc tracé comme
`correction_constraint_conflict`, et non comme conflit conjoint. Le contre-cas
synthétique où deux propositions sont individuellement faisables mais
incompatibles ensemble reste couvert séparément.

Les quatre frontières voix sont fixes et inchangées à `±0,01 s` : indices
`0=120,00`, `11=2365,68`, `23=5349,38`, `31=6975,72`. Aucun offset preview
n’est appliqué aux annonces. Les 32 artistes/titres et leur ordre sont
strictement conservés.

Trace complète :
[`fyh512-joint-selection-b-trace-frozen.json`](../lab/vad_prototype/results/fyh512-joint-selection-b-trace-frozen.json).

## Tests et chemin réel

Les 17 tests de
[`test_joint_selection_b.py`](../lab/vad_prototype/test_joint_selection_b.py)
passent. Ils couvrent notamment :

- correction dans les gaps globaux mais hors couloir local ;
- correction respectant 45 s mais violant le minimum local ;
- correction faisable mais moins bonne selon l’objectif ;
- corrections individuellement faisables mais conjointement incompatibles ;
- frontière voix fixe impossible à écraser ;
- recherche conjointe A non gloutonne et exception explicite à 100000 chaînes ;
- cas 150/195, fallback landmarks et conservation des 32 titres.

La route réelle a été rejouée avec le média au SHA-256
`47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69`,
les 22 fichiers du cache original vérifiés par URL normalisée/SHA/taille et
sans repli réseau, ainsi que les 10 événements ASR gelés au SHA
`d1a80c35149ad33f8720644499830c2297306ad3fe67cf7d71f42007f250a91f`.
Aucun nouveau VAD/ASR n’est revendiqué.

Commandes exécutées :

```text
/home/debian/projects/podmix/.venv/bin/python -m unittest -v \
  lab/vad_prototype/test_joint_selection_b.py

PODMIX_FFMPEG=<binaire local compatible> \
  /home/debian/projects/podmix/.venv/bin/python \
  lab/vad_prototype/run_fyh512_joint_selection_b.py \
  --media <média privé vérifié> --vad-python /bin/false --asr-python /bin/false \
  --replay-trace lab/vad_prototype/results/fyh512-existing-engine-complete-voice-trace-frozen.json

/home/debian/projects/podmix/.venv/bin/python \
  lab/vad_prototype/evaluate_fyh512_joint_selection_b.py
```

## Évaluation gelée

| Sortie | Matchs | Manques | Surplus | Moyenne abs. appariée | Médiane abs. appariée | Dénominateur |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 12/32 | 20 | 20 | — | — | 12 |
| A validé | 16/32 | 16 | 16 | 8,4816 s | 1,6250 s | 16 |
| B final | 16/32 | 16 | 16 | 7,6091 s | 1,6250 s | 16 |

Contre A : gains `[]`, pertes `[]`. Contre le baseline 12/32 : gains
`[1, 12, 23, 32]`, pertes `[]`. Le gain de nombre de matchs contre A est donc
zéro, honnêtement rapporté.

Sur l’association par index des 32 titres : moyenne signée `+7,5775 s`,
médiane signée `+1,4745 s`, moyenne absolue `48,0718 s`, médiane absolue
`30,1600 s`, dénominateur `32`. Distribution absolue : 19 sous 60 s, 9 entre
60 et 120 s, 4 entre 120 et 300 s, aucune à 300 s ou plus.

Le CSV demandé contient pour chaque index la référence, le temps A avant, le
temps B après, le déplacement signé, l’écart signé, le statut libre
gain/perte/conservé/hors seuil et les colonnes missing/surplus associées via
les fichiers dédiés :

- [`comparaison 32`](../lab/vad_prototype/results/fyh512-joint-selection-b-comparison-32.csv) ;
- [`paires`](../lab/vad_prototype/results/fyh512-joint-selection-b-pairs.csv) ;
- [`manques`](../lab/vad_prototype/results/fyh512-joint-selection-b-missing.csv) ;
- [`surplus`](../lab/vad_prototype/results/fyh512-joint-selection-b-surplus.csv) ;
- [`résumé`](../lab/vad_prototype/results/fyh512-joint-selection-b-evaluation-summary.json).

## Tableau 32 — temps réels 1001TL et algorithme

Les conversions utilisent les offsets RSS gelés : `+1,3455 s` avant 3565 s,
puis `+1,7265 s`. La page 1001Tracklists n’a pas été relue en direct dans B;
les temps sont ceux gelés et déjà vérifiés avant A, annoncés identiques à
YouTube :
[source 1001Tracklists FYH512](https://www.1001tracklists.com/tracklist/m84xvnt/andrew-rayel-find-your-harmony-radioshow-512-2026-09-23.html).

```text
00:02:00 1001TL | 00:01:59 ALGO
00:05:37 1001TL | 00:05:38 ALGO
00:09:45 1001TL | 00:08:44 ALGO
00:13:00 1001TL | 00:13:02 ALGO
00:16:46 1001TL | 00:16:47 ALGO
00:19:02 1001TL | 00:18:47 ALGO
00:23:20 1001TL | 00:20:32 ALGO
00:25:54 1001TL | 00:25:56 ALGO
00:29:35 1001TL | 00:30:06 ALGO
00:32:35 1001TL | 00:32:06 ALGO
00:35:33 1001TL | 00:36:47 ALGO
00:39:26 1001TL | 00:39:24 ALGO
00:43:34 1001TL | 00:43:37 ALGO
00:47:05 1001TL | 00:47:06 ALGO
00:51:02 1001TL | 00:49:43 ALGO
00:56:30 1001TL | 00:54:14 ALGO
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
01:52:10 1001TL | 01:53:49 ALGO
01:56:14 1001TL | 01:56:14 ALGO
```

## Hashes et reproductibilité

- base A : commit `3b52f08f9c35a18b088c308f979b9fe1730d5f09` ;
- moteur A de départ : `a290559d2d9b55068581e5c1f956abdeb33ab179e3ea3f7400fcf6a62f33d47a` ;
- moteur B : `264bfeb7dcfad8852dc73cfcfcfc71351628a79116f0d18d345fac112fbd9eb3` ;
- runner : `ed7eb748fd8a83de2bcbc7cb4b89ce01cddf4764c03b5084099022821fe7aa2f` ;
- évaluateur : `f9db90fb4254d9fd91860ff7ca8b0f79863f4c716def995f0a10cf42d197e32f` ;
- protocole : `2a9cff2565a81ffd9ef0f00b6be02618af690d1f59d5abe60baabdfebf79f5ae` ;
- tests : `f5a2fae94a00241dfc7a56810c5436da6925127c2f2310188a353cdc14254292` ;
- trace B : `5de52b5722a877a52c278c04b6f0af9edaf005d73865cd8af176d340fd4642b0` ;
- CSV 32 : `338329474dc78a3451338666d49a41f4dcf206885029ce8822106cd75bcba82d` ;
- résumé : `677d8a7f6481ef64a6804185f266f9768a727d4fdf62f68907a3502d82b42f5d` ;
- manifeste de gel : `a715019f4f7be5262767ccf9846e517fac4615dadc4b9fb6a75ced9181d51064` ;
- manifeste d’évaluation : `339feab38c827e6e8018be196c487038ea50461fe9b983498333c8aec74ab91c`.

Le manifeste de gel contient également les hashes des entrées, de
`full_episode_protocol.json`, de la provenance du cache, du détecteur, des
quatre sorties gelées et l’inventaire exact des 22 références. Aucun secret,
jeton, mot de passe, clé, MP3 privé ou cache audio n’est versionné.

## Limites factuelles

B sécurise la route conjointe de laboratoire seulement. Aucun changement n’a
été fait dans la production, `main`, SQLite, API, services, Docker, APK,
canaux, traduction ou route sous seuil. Aucun nouvel extrait MP3 ni nouvelle
ancre n’a été créé. Le résultat mesuré reste 16/32 : la faiblesse structurelle
est corrigée, mais aucune amélioration de nombre de matchs n’est démontrée.
