# FYH512 — voie voix stricte sous 0,95

## Statut

**Expérience exécutée puis refusée pour non-régression.** La voie automatique
figée ajoute correctement trois ancres sous 0,95 et porte le matching libre de
16 à 18/32, mais elle perd en même temps la référence 8. Conformément au
protocole, aucun retuning ni sous-ensemble choisi après lecture des erreurs de
référence n'a été tenté. Ce résultat n'est donc pas validé comme algorithme final.

- Branche : [`lab/vad-prototype-20261010`](https://github.com/picopcla/Podmix/tree/lab/vad-prototype-20261010)
- Code et preuves : [`lab/vad_prototype`](https://github.com/picopcla/Podmix/tree/lab/vad-prototype-20261010/lab/vad_prototype)
- Extraits privés : [`pont-messages/reponses/podmix-fyh512-voix-sous-seuil-extraits`](https://github.com/picopcla/pont-messages/tree/main/reponses/podmix-fyh512-voix-sous-seuil-extraits)
- Gel du protocole/code avant évaluation : commit `9c5c2750573dc7e231cacfc225ff015f6efab6f0`.
- Gel des sorties avant évaluation : commit `bfde2133d93d680a00cd1e7547e8938bcc0ea0cd`.

## Protocole figé et replay

La voie haute confiance `>= 0,95` reste inchangée. La voie alternative accepte
seulement `0,80 <= confiance < 0,95`, avec moyenne ASR `>= -0,55`, maximum
no-speech `<= 0,35`, titre principal exact et unique dans la tracklist non
minutée, score titre `>= 0,72`, marge `>= 0,12`, relation explicite au morceau
entrant, preuve artiste et désambiguïsation de la variante remix. Les marqueurs
récapitulatif, courant, précédent, chant/narration, jingle et remix ambigu
conduisent à l'abstention. La coupe reste la fin du segment INA, sans offset
preview, puis passe par l'optimiseur commun ordre/couloir/présence musicale.

L'exécution a rejoué automatiquement, dans le hook livré, les 10 événements ASR
de la passe complète gelée. Condition enregistrée dans la trace : expérience
d'interprétation seulement, **aucun nouveau VAD ou ASR prétendu**. Le média est
le fichier SHA-256 `47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69` ;
le même cache de 22 références a été utilisé. Le mode voix désactivé reproduit
exactement `e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208`.

## Audit des 10 événements

Les anciennes décisions sous 0,95 étaient des refus avant interprétation
textuelle. Le tableau ci-dessous décrit uniquement la nouvelle interprétation.

| ID | conf. | relation | score titre | marge | décision |
|---|---:|---:|---:|---:|---|
| d001 | 0,9086 | 0,0 | — | — | écartée : aucune relation entrante explicite |
| d002 | 0,9884 | voie haute | — | — | ancre existante acceptée, piste 1 |
| d003 | 0,8166 | 1,0 | 0,8500 | 0,4947 | nouvelle ancre acceptée par l'optimiseur, piste 7 à 1 389,16 s RSS |
| d004 | 0,7694 | — | — | — | écartée : sous le plancher alternatif, narration |
| d005 | 0,9864 | voie haute | — | — | ancre existante acceptée, piste 12 |
| d006 | 0,8435 | 1,0 | 0,8500 | 0,3016 | nouvelle ancre acceptée, piste 16 à 3 391,04 s RSS |
| d007 | 0,9269 | 0,8 | 0,8875 | 0,3840 | nouvelle ancre acceptée, piste 17 à 3 632,38 s RSS |
| d008 | 0,9698 | voie haute | — | — | ancre existante acceptée, piste 24 |
| d009 | 0,5177 | — | — | — | écartée : sous le plancher alternatif, narration |
| d010 | 0,9626 | voie haute | — | — | ancre existante acceptée, piste 32 |

Audit complet : [`CSV`](../lab/vad_prototype/results/fyh512-existing-engine-strict-under-threshold-candidate-audit.csv)
et [`JSON`](../lab/vad_prototype/results/fyh512-existing-engine-strict-under-threshold-candidate-audit.json).

## Évaluation post-gel

Même appariement libre glouton un-à-un par erreur absolue croissante, tolérance
`<= 30 s`. Les offsets sont RSS = 1001TL + 1,3455 s avant 3 565 s, puis
+1,7265 s. Les décimales sont celles du calcul et ne prétendent pas être la
précision de l'annotation publiée à la seconde.

- base moteur off : 12/32, 20 manques, 20 surplus ;
- base immédiate quatre ancres : 16/32, 16 manques, 16 surplus ;
- voie stricte brute : 18/32, 14 manques, 14 surplus ;
- gains libres contre 16/32 : références 7, 16 et 17 ;
- perte libre : référence 8 ;
- erreur absolue moyenne des 18 paires : 8,1644 s ; médiane : 1,5150 s ;
  dénominateur 18 ;
- distances par association artiste/titre/index sur les 32 pistes : `<60 s`
  22/32, `60–<120 s` 8/32, `120–<300 s` 2/32, `>=300 s` 0/32.

La perte est attribuable à d003 : l'ancre de la piste 7 déplace sa coupe de
+155,56 s et la coupe voisine de la piste 8 de +59,33 s. La piste 8 passe de
1,58 s à 60,91 s de sa référence et sort du matching libre. Le verdict est donc
`regression_stop`, même si d006 et d007 donnent deux gains propres. Rejeter d003
sur la base de cette erreur aurait constitué une sélection post-hoc assistée par
référence ; cette variante n'a pas été produite.

## 32 lignes 1001TL | ALGO

Association par même artiste/titre/index, distincte du matching libre ; toutes
les coupes algorithmiques réelles sont affichées.

```text
00:02:00 1001TL | 00:01:59 ALGO
00:05:37 1001TL | 00:05:38 ALGO
00:09:45 1001TL | 00:08:44 ALGO
00:13:00 1001TL | 00:13:02 ALGO
00:16:46 1001TL | 00:16:47 ALGO
00:19:02 1001TL | 00:18:33 ALGO
00:23:20 1001TL | 00:23:08 ALGO
00:25:54 1001TL | 00:26:55 ALGO
00:29:35 1001TL | 00:30:06 ALGO
00:32:35 1001TL | 00:32:06 ALGO
00:35:33 1001TL | 00:36:47 ALGO
00:39:26 1001TL | 00:39:24 ALGO
00:43:34 1001TL | 00:43:37 ALGO
00:47:05 1001TL | 00:47:06 ALGO
00:51:02 1001TL | 00:49:49 ALGO
00:56:30 1001TL | 00:56:30 ALGO
01:00:31 1001TL | 01:00:31 ALGO
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

Le détail avant/après avec écarts signés et distances absolues est dans le
[`CSV 32 pistes`](../lab/vad_prototype/results/fyh512-existing-engine-strict-under-threshold-comparison-32.csv).
Les paires, manques, surplus et le résumé restent séparés dans les fichiers du
même préfixe sous `results/`.

## Intégrité, tests et limites

- sortie brute : SHA-256 `6bde97219bc455b2ec08b2d9fbdef72afeef1485d0f864b3d3dc51439e220592` ;
- trace : `6d1c324f84d86de27d7d15f425dea3f946abb7a4ab4e0deee8cde0e237485958` ;
- moteur copié : `3f100064e13a870a052e6421dd590619ec80ff81279c8814a35fd331196b1421` ;
- runner : `e85bc276e0acc956521ffd2daca25a5a626f7933a8cf973fd86a33cde8d92c67` ;
- protocole : `758c3d2c244bc0bf0979490f1c021a17a5a8dca4f31dddf5367eec44a5f42df7` ;
- évaluateur : `9a8f719dfbf06178dbb08ae7aad2325681f22773a2f209d139540051ade7279b` ;
- 42 tests passent : titre unique, relation, remix ambigu, chant, voie haute
  inchangée, repli, conflits, cas 150/195 et conservation des non-annoncés.

La page 1001Tracklists fournie a servi de référence de provenance après le gel ;
une nouvelle lecture automatisée depuis cet environnement a été bloquée par
robots.txt/HTTP 403. L'évaluation utilise les 32 temps déjà vérifiés identiques
à la source officielle, sans les injecter dans le détecteur. Aucun changement
production/main/SQLite/API/service/Docker/APK ni au service pont, aucun merge et
aucune variante hors périmètre. Seul `pont-messages` privé reçoit les extraits
audio et le lien de livraison autorisés.
