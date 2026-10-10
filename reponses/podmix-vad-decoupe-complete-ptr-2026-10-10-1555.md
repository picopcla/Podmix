# Podmix VAD — découpe complète de PTR492 et PTR493

## Statut

**Expérience de laboratoire terminée sur les deux épisodes, sans activation ni correction.** Les médias RSS complets ont été segmentés par INA puis Silero sur CPU. Les deux candidats historiques `PTR492 +0,740 s` et `PTR493 +3,080 s` réapparaissent comme sorties brutes de l'algorithme gelé, mais ils restent **non approuvés et non appliqués**. Aucune écoute d'Emmanuel n'est interprétée comme une validation.

Aucune production, base SQLite, donnée applicative, chapitre, API, dépendance de production, service, conteneur, APK, configuration ou déploiement n'a été modifié. Aucun audio n'est ajouté au dépôt.

## Méthode gelée avant comparaison

Le protocole a été figé le `2026-10-10T15:56:43+02:00` dans `lab/vad_prototype/full_episode_protocol.json`, avant la génération du premier tableau d'écarts. Le détecteur ne lit aucun temps CueNation. Il analyse chaque épisode par fenêtres de 600 s, recouvertes de 60 s; seul le cœur de chaque fenêtre est conservé, ce qui produit une segmentation globale continue.

Paramètres fixes :

- INA `sm`, sans genre, batch 32, ratio énergie 0.03;
- Silero 16 kHz, seuil 0.5, seuil de sortie 0.35, parole minimale 250 ms, silence minimal 100 ms et marge 30 ms;
- candidat = fin d'une plage INA de parole d'au moins 0.75 s, immédiatement suivie d'au moins 5.0 s de musique, avec recouvrement Silero ≥ 0.25 s et fins des deux modèles séparées de ≤ 2.0 s;
- candidats distants de ≤ 15.0 s regroupés en gardant la confiance la plus forte;
- confiance de 0 à 1 : continuité musicale INA (35 %), accord des fins INA/Silero (35 %), recouvrement de parole (20 %) et durée de parole INA (10 %). Cette valeur est un score interne, pas une probabilité calibrée.

La comparaison est postérieure : pour chaque temps CueNation croissant, la détection non encore utilisée la plus proche est retenue si elle est à 20 s ou moins. L'écart signé vaut `temps_algorithme - temps_cuesheet`. Une détection non appariée est seulement une fausse détection **potentielle**, faute d'annotation humaine de la voix et des transitions.

## Résultats synthétiques

| Épisode | Références | Détections | Appariées | Non détectées | Détections sans équivalent | Médiane abs. | Moyenne abs. | < 1 s | < 3 s | < 10 s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| PTR492 | 22 | 25 | 11 | 11 | 14 | 4.720 s | 6.064 s | 27.3 % | 45.5 % | 72.7 % |
| PTR493 | 20 | 18 | 9 | 11 | 9 | 4.140 s | 4.973 s | 0.0 % | 11.1 % | 100.0 % |

Les parts et les moyennes utilisent seulement les frontières appariées : 11 pour PTR492 et 9 pour PTR493. Les non-détections ne sont donc pas transformées artificiellement en erreurs de 20 s.

## Toutes les frontières détectées

### PTR492

| ID | Temps absolu | Secondes | Confiance | Équivalent CueNation |
|---|---:|---:|---:|---|
| d001 | 00:01:22.740 | 82.740 | 0.9962 | piste 2, écart +0.740 s |
| d002 | 00:05:25.280 | 325.280 | 0.9163 | aucun à ±20 s (potentielle fausse détection) |
| d003 | 00:06:27.760 | 387.760 | 0.8204 | piste 3, écart -0.240 s |
| d004 | 00:10:38.080 | 638.080 | 0.6875 | aucun à ±20 s (potentielle fausse détection) |
| d005 | 00:10:53.280 | 653.280 | 0.8754 | piste 4, écart -4.720 s |
| d006 | 00:16:16.740 | 976.740 | 0.8386 | aucun à ±20 s (potentielle fausse détection) |
| d007 | 00:17:02.900 | 1022.900 | 0.8003 | piste 5, écart -11.100 s |
| d008 | 00:27:59.960 | 1679.960 | 0.9255 | aucun à ±20 s (potentielle fausse détection) |
| d009 | 00:29:23.680 | 1763.680 | 0.9948 | aucun à ±20 s (potentielle fausse détection) |
| d010 | 00:32:58.720 | 1978.720 | 0.9612 | aucun à ±20 s (potentielle fausse détection) |
| d011 | 00:43:11.560 | 2591.560 | 0.9794 | aucun à ±20 s (potentielle fausse détection) |
| d012 | 00:48:27.820 | 2907.820 | 0.8866 | aucun à ±20 s (potentielle fausse détection) |
| d013 | 00:52:28.920 | 3148.920 | 0.9542 | aucun à ±20 s (potentielle fausse détection) |
| d014 | 00:53:17.580 | 3197.580 | 0.6326 | piste 12, écart -12.420 s |
| d015 | 01:00:46.020 | 3646.020 | 0.9928 | piste 13, écart -2.980 s |
| d016 | 01:04:45.420 | 3885.420 | 0.9696 | aucun à ±20 s (potentielle fausse détection) |
| d017 | 01:05:21.820 | 3921.820 | 0.7745 | aucun à ±20 s (potentielle fausse détection) |
| d018 | 01:05:52.900 | 3952.900 | 0.8300 | piste 14, écart -0.100 s |
| d019 | 01:19:49.380 | 4789.380 | 0.8331 | piste 17, écart -6.620 s |
| d020 | 01:26:21.880 | 5181.880 | 0.7698 | aucun à ±20 s (potentielle fausse détection) |
| d021 | 01:26:37.920 | 5197.920 | 0.7449 | aucun à ±20 s (potentielle fausse détection) |
| d022 | 01:27:02.980 | 5222.980 | 0.9550 | piste 18, écart -9.020 s |
| d023 | 01:33:01.660 | 5581.660 | 0.8295 | piste 19, écart -17.340 s |
| d024 | 01:50:47.580 | 6647.580 | 0.8541 | piste 22, écart -1.420 s |
| d025 | 01:56:17.920 | 6977.920 | 0.8479 | aucun à ±20 s (potentielle fausse détection) |

### PTR493

| ID | Temps absolu | Secondes | Confiance | Équivalent CueNation |
|---|---:|---:|---:|---|
| d001 | 00:00:40.340 | 40.340 | 0.7016 | aucun à ±20 s (potentielle fausse détection) |
| d002 | 00:01:06.680 | 66.680 | 0.9462 | aucun à ±20 s (potentielle fausse détection) |
| d003 | 00:01:55.140 | 115.140 | 0.8500 | piste 2, écart +4.140 s |
| d004 | 00:07:27.940 | 447.940 | 0.6001 | aucun à ±20 s (potentielle fausse détection) |
| d005 | 00:19:07.060 | 1147.060 | 0.8374 | aucun à ±20 s (potentielle fausse détection) |
| d006 | 00:23:55.860 | 1435.860 | 0.8134 | piste 6, écart -5.140 s |
| d007 | 00:28:47.740 | 1727.740 | 0.9884 | aucun à ±20 s (potentielle fausse détection) |
| d008 | 00:29:20.180 | 1760.180 | 0.7978 | piste 7, écart +3.180 s |
| d009 | 00:32:25.060 | 1945.060 | 0.8060 | aucun à ±20 s (potentielle fausse détection) |
| d010 | 00:39:13.080 | 2353.080 | 0.8925 | piste 9, écart +3.080 s |
| d011 | 00:45:27.580 | 2727.580 | 0.7531 | piste 10, écart -6.420 s |
| d012 | 00:55:39.960 | 3339.960 | 0.9765 | piste 12, écart -4.040 s |
| d013 | 01:09:06.780 | 4146.780 | 0.8821 | piste 14, écart +2.780 s |
| d014 | 01:13:12.640 | 4392.640 | 0.7549 | piste 15, écart -8.360 s |
| d015 | 01:28:44.000 | 5324.000 | 0.7204 | aucun à ±20 s (potentielle fausse détection) |
| d016 | 01:33:16.400 | 5596.400 | 0.5587 | aucun à ±20 s (potentielle fausse détection) |
| d017 | 01:33:56.000 | 5636.000 | 0.8541 | aucun à ±20 s (potentielle fausse détection) |
| d018 | 01:43:55.380 | 6235.380 | 0.9530 | piste 21, écart -7.620 s |

## Comparaison frontière par frontière

### PTR492

| Piste | Titre | CueNation | Algorithme | Confiance | Écart signé |
|---:|---|---:|---:|---:|---:|
| 2 | Midnight Evolution - Dreams | 00:01:22.000 (82.000 s) | 00:01:22.740 (82.740 s) | 0.9962 | +0.740 s |
| 3 | metakomplex & Orkidea - Dream of You | 00:06:28.000 (388.000 s) | 00:06:27.760 (387.760 s) | 0.8204 | -0.240 s |
| 4 | Emran Badalov - Scorpio Rider (Hazem Beltagui Remix) | 00:10:58.000 (658.000 s) | 00:10:53.280 (653.280 s) | 0.8754 | -4.720 s |
| 5 | Soul Alt Delete - Copycat | 00:17:14.000 (1034.000 s) | 00:17:02.900 (1022.900 s) | 0.8003 | -11.100 s |
| 6 | Allende - The Weight | 00:23:00.000 (1380.000 s) | non détectée | — | — |
| 7 | Protoculture - Telemetry | 00:28:30.000 (1710.000 s) | non détectée | — | — |
| 8 | Dusky - Lab | 00:34:00.000 (2040.000 s) | non détectée | — | — |
| 9 | ARCHERY - KEEP IT GOIN' | 00:38:43.000 (2323.000 s) | non détectée | — | — |
| 10 | Peter Steele - Summer Breeze | 00:44:39.000 (2679.000 s) | non détectée | — | — |
| 11 | FKN & Tom Bro feat. Emily Orchard - Fading Blue | 00:49:34.000 (2974.000 s) | non détectée | — | — |
| 12 | Thrillseekers pres. Hydra - Amber (Asteroid Remix) | 00:53:30.000 (3210.000 s) | 00:53:17.580 (3197.580 s) | 0.6326 | -12.420 s |
| 13 | Bryan Kearney & John O'Callaghan pres. Key4050 - Final Memory | 01:00:49.000 (3649.000 s) | 01:00:46.020 (3646.020 s) | 0.9928 | -2.980 s |
| 14 | Asteroid - Spectra | 01:05:53.000 (3953.000 s) | 01:05:52.900 (3952.900 s) | 0.8300 | -0.100 s |
| 15 | C-Systems - Pillars of Light | 01:10:12.000 (4212.000 s) | non détectée | — | — |
| 16 | Ferkingge & Emma Wang feat. Adria Du & Nini - Blooming | 01:14:08.000 (4448.000 s) | non détectée | — | — |
| 17 | Solarstone vs. Sirocco - Destination (Effen Remix) | 01:19:56.000 (4796.000 s) | 01:19:49.380 (4789.380 s) | 0.8331 | -6.620 s |
| 18 | Stoneface & Terminal with Susanne Teutenberg - High As The Sun | 01:27:12.000 (5232.000 s) | 01:27:02.980 (5222.980 s) | 0.9550 | -9.020 s |
| 19 | Blackromeo - Ashanti | 01:33:19.000 (5599.000 s) | 01:33:01.660 (5581.660 s) | 0.8295 | -17.340 s |
| 20 | Above & Beyond with Zoe Johnston - Quicksand (Ciaran McAuley Remix) | 01:39:41.000 (5981.000 s) | non détectée | — | — |
| 21 | Armin van Buuren feat. Sharon Den Adel - In And Out of Love (Ben Hemsley Remix) | 01:44:35.000 (6275.000 s) | non détectée | — | — |
| 22 | Super-Frog Saves Tokyo - Jitterbug | 01:50:49.000 (6649.000 s) | 01:50:47.580 (6647.580 s) | 0.8541 | -1.420 s |
| 23 | Vangelis - West Across The Ocean Sea | 01:56:41.000 (7001.000 s) | non détectée | — | — |

### PTR493

| Piste | Titre | CueNation | Algorithme | Confiance | Écart signé |
|---:|---|---:|---:|---:|---:|
| 2 | Solarstone - Solarcoaster (Deestopia Remix) | 00:01:51.000 (111.000 s) | 00:01:55.140 (115.140 s) | 0.8500 | +4.140 s |
| 3 | Nordfold - Tidal Shift | 00:08:54.000 (534.000 s) | non détectée | — | — |
| 4 | Exotek - Embrace | 00:13:42.000 (822.000 s) | non détectée | — | — |
| 5 | Kyau & Albert - Halo | 00:20:12.000 (1212.000 s) | non détectée | — | — |
| 6 | PARAFRAME - 3 Worlds | 00:24:01.000 (1441.000 s) | 00:23:55.860 (1435.860 s) | 0.8134 | -5.140 s |
| 7 | Josh Caffe - Velvet Skin | 00:29:17.000 (1757.000 s) | 00:29:20.180 (1760.180 s) | 0.7978 | +3.180 s |
| 8 | Tre Turner - Archaos (CLOSE PROXIMITY Progressive Mix) | 00:33:21.000 (2001.000 s) | non détectée | — | — |
| 9 | Private Taste - First (Ashtrax Rerub) | 00:39:10.000 (2350.000 s) | 00:39:13.080 (2353.080 s) | 0.8925 | +3.080 s |
| 10 | SONIN x Orkidea - Avril | 00:45:34.000 (2734.000 s) | 00:45:27.580 (2727.580 s) | 0.7531 | -6.420 s |
| 11 | Push - Open The Night | 00:50:21.000 (3021.000 s) | non détectée | — | — |
| 12 | Factor B feat. Cat Martin - Crashing Over (Lost Minds Remix) | 00:55:44.000 (3344.000 s) | 00:55:39.960 (3339.960 s) | 0.9765 | -4.040 s |
| 13 | St. John & Scott Ramsay pres. ApexLOOP - Inspire | 01:02:27.000 (3747.000 s) | non détectée | — | — |
| 14 | Factoria + Ross Baker - River of Light | 01:09:04.000 (4144.000 s) | 01:09:06.780 (4146.780 s) | 0.8821 | +2.780 s |
| 15 | Sequence Six & Zara Taylor - Above | 01:13:21.000 (4401.000 s) | 01:13:12.640 (4392.640 s) | 0.7549 | -8.360 s |
| 16 | Super8 & Tab feat. Julie Thompson - My Enemy (CVMRN Club Mix) | 01:18:16.000 (4696.000 s) | non détectée | — | — |
| 17 | Technology - Electronicly Entertained | 01:23:55.000 (5035.000 s) | non détectée | — | — |
| 18 | Ruben De Ronde pres. NRG2000 x York x Angel City - Slip Away | 01:29:10.000 (5350.000 s) | non détectée | — | — |
| 19 | Above & Beyond with Zoe Johnston - Quicksand (Don't Go) (Mark Sherry Remix) | 01:34:23.000 (5663.000 s) | non détectée | — | — |
| 20 | David Forbes x Lostly - Echo Burn | 01:39:50.000 (5990.000 s) | non détectée | — | — |
| 21 | FKN & Tom Bro feat. Emily Orchard - Fading Blue (Ambient Mix) | 01:44:03.000 (6243.000 s) | 01:43:55.380 (6235.380 s) | 0.9530 | -7.620 s |

## Décalage constant et dérive

- **PTR492** : écart signé moyen -5.929 s, IC 95 % [-9.427; -2.431] — décalage constant exploratoire détecté. Pente -2.821 s/h, IC 95 % [-8.528; +2.887], R² 0.094 — dérive non détectée.
- **PTR493** : écart signé moyen -2.044 s, IC 95 % [-5.461; +1.372] — décalage constant exploratoire non détecté. Pente -5.906 s/h, IC 95 % [-12.161; +0.349], R² 0.328 — dérive non détectée.

Ces tests sont exploratoires, utilisent seulement les frontières détectées et des intervalles normaux malgré de petits effectifs. Pour PTR492, le biais moyen négatif est détectable dans les 11 appariements, mais la pente n'est pas significative; il ne prouve ni un décalage du RSS ni une erreur de CueNation. Pour PTR493, ni décalage constant ni dérive ne sont détectés.

## Réserve d'alignement et interprétation

L'alignement entre la cuesheet et l'enclosure RSS reste **non vérifié**. La durée RSS dépasse la durée CueNation d'environ 2,6 s pour PTR492 et 3,8 s pour PTR493. Un écart au temps CueNation ne peut donc pas être présenté sans réserve comme une erreur de l'algorithme. En outre, INA et Silero peuvent confondre voix DJ, chant, rap, jingle ou voice-over : les 14 et 9 détections sans équivalent sont potentielles, pas des faux positifs confirmés.

Le détecteur retrouve notamment `82,740 s` sur PTR492 et `2 353,080 s` sur PTR493. Ces résultats reproduisent les deux candidats explicitement non approuvés; ils ne constituent ni une validation humaine ni une autorisation de modifier des chapitres.

## Reproductibilité et livrables

- `lab/vad_prototype/full_episode_protocol.json` : protocole gelé;
- `lab/vad_prototype/run_full_episode.py` : segmentation complète, séquentielle et CPU;
- `lab/vad_prototype/analyze_full_episode.py` : détection, appariement, statistiques et rapport;
- `lab/vad_prototype/results/full-episode-detections.{csv,json}` : toutes les détections;
- `lab/vad_prototype/results/full-episode-comparison.{csv,json}` : les 42 références;
- `lab/vad_prototype/results/full-episode-summary.json` : synthèse et régressions.

Les checkpoints complets restent dans `lab/vad_prototype/output/full_episode/`, ignoré par Git avec les médias. Les exécutions ont été séquentielles sous `nice -n 10`, CPU seul, avec deux threads. PTR492 : Silero 29,533 s et INA 260,744 s mur; PTR493 : Silero 26,076 s et INA 223,893 s mur. Aucun nouvel extrait audio n'a été nécessaire.

## Conclusion

La découpe globale est techniquement reproductible mais insuffisante pour une activation : elle apparie 11/22 frontières de PTR492 et 9/20 de PTR493 à ±20 s, avec respectivement 14 et 9 détections supplémentaires potentielles. Le laboratoire reste désactivé et aucune correction n'est appliquée.
