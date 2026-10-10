# Podmix — complément de la méthode de base par la voix DJ

## Verdict

Le prototype combiné demandé est exécuté **dans le laboratoire uniquement** sur
PTR492 et PTR493. Il complète la base existante sans la remplacer : les 42
frontières, leurs 42 titres et leur ordre sont conservés. Le VAD ne crée ni ne
supprime aucune frontière.

Avec le protocole conservateur figé avant cette nouvelle évaluation :

- 1 frontière sur 42 est ajustée automatiquement : PTR492 piste 2,
  `82,000 → 82,740 s` (`+0,740 s`, confiance interne `0,9962`) ;
- 41 frontières sur 42 restent **exactement** à leur temps de base ;
- les abstentions se répartissent en 31 sans détection dans la fenêtre locale,
  6 avec seulement une détection hors de l'intervalle positif admissible et 4
  avec une confiance inférieure à `0,95` ;
- aucun cas ambigu, conflit de détection ou risque d'inversion n'apparaît dans
  ces deux épisodes, mais ces cas sont bloqués par le code et couverts par les
  tests.

Ce résultat démontre la conservation de la méthode de base et le fonctionnement
du garde-fou local. Il **ne démontre pas** la précision audio du complément : la
base et la référence d'évaluation sont la même source CueNation. La mesure est
donc circulaire et décrit seulement la concordance à la source.

## Périmètre et sauvegarde

- Branche : `lab/vad-prototype-20261010` ; aucun merge vers `main`.
- État initial sauvegardé avant édition : commit
  `cdf71b2879563c22372f489901fd7b1414dfdcd6` dans
  `/home/debian/pont-task-workspaces/c8369f8f24bf7a0cb0c08768/backup/podmix-lab-prechange-20261010-1656`
  (49 Mio observés).
- Copie de production `/home/debian/projects/podmix` consultée en lecture seule,
  au commit `eebd70c`.
- Les empreintes de `server/tracklist.py` et `server/feeds.py` sont identiques
  entre la production en lecture seule et la branche labo avant les ajouts.
- Aucun média, extrait audio, secret ou donnée applicative n'est ajouté au
  dépôt. Aucun audio n'a été relu ou recalculé.
- Aucune écriture production, SQLite, chapitre réel, API, dépendance, service,
  conteneur, APK, configuration ou déploiement n'a été effectuée.

## Méthode de base réellement reproduite

### Code courant

Le pipeline actuel recherche d'abord des horaires publiés :

1. `server/feeds.py::import_feed` lit les descriptions RSS et ne construit une
   tracklist que si des timestamps explicites existent ;
2. `server/dev_server.py::_automatic_tracklist` orchestre les sources RSS,
   pages publiées, MixesDB, 1001Tracklists et recherche Web ;
3. `server/tracklist.py::parse_tracklist` convertit uniquement les horaires
   explicitement écrits en `providedTime` ;
4. `server/tracklist.py::apply_external_timestamps` peut recopier par index les
   horaires externes sur une liste de titres déjà établie, après contrôles de
   longueur et de première piste ;
5. `server/tracklist.py::align_tracklist` arrondit un horaire publié à deux
   décimales. Un horaire absent reste `pending`; aucun espacement dans la durée
   n'est inventé. Un horaire existant manuel est conservé.

Le pipeline contient aussi
`server/audio_fallback.py::analyze_known_tracklist`, appelé par
`server/dev_server.py::run_research_job` lorsqu'un fallback audio a été demandé
faute d'horaires externes vérifiés. Ce fallback n'est pas la provenance des 42
frontières PTR disponibles dans le laboratoire et aucun résultat PTR de ce
fallback n'est disponible dans la branche. Il n'a donc pas été substitué à la
base réelle.

### Provenance PTR492/PTR493

Pour ces deux épisodes, `lab/vad_prototype/episodes.json` donne explicitement
les deux URLs CueNation et les horaires déjà utilisés par les analyses
antérieures :

- PTR492 : 22 frontières, pistes 2 à 23 ;
- PTR493 : 20 frontières, pistes 2 à 21.

Le générateur relit ces listes dans leur ordre de fichier et reproduit
identifiant, numéro, titre et temps. Il ne trie pas les titres autrement, ne
complète aucune piste et n'utilise pas les détections pour produire la base.

Il n'existe aucun recalage de la cuesheet sur l'enclosure RSS : pas d'offset,
pas de changement d'échelle, pas de DTW et pas d'ancre audio. La durée annoncée
par CueNation diffère d'environ `+2,61 s` pour PTR492 (RSS `7 174,61 s` contre
cuesheet `7 172 s`) et `+3,83 s` pour PTR493 (RSS `6 473,83 s` contre cuesheet
`6 470 s`). Cette différence n'a pas été corrigée, car son origine et
l'alignement des deux médias ne sont pas vérifiés.

## Protocole combiné figé avant évaluation

`lab/vad_prototype/combined_protocol.json` a été figé à
`2026-10-10T16:59:00+02:00`, avant la génération des nouveaux tableaux. Les
choix reposent sur la sémantique du prototype antérieur — fin d'une plage de
voix INA confirmée par Silero, immédiatement suivie de musique — et non sur une
optimisation contre CueNation :

- fenêtre de proximité observée : `[-5 s ; +5 s]` autour de la base ;
- déplacement admissible : uniquement après la base, de `+0,5 s` à `+5 s` ;
- confiance interne minimale : `0,95` ;
- une seule détection admissible : plusieurs candidats impliquent une
  abstention ;
- les détections sont celles déjà dédupliquées à 15 s par le protocole global
  du commit `6ada6c1`; le complément ne relance pas ni ne redéduplique le
  détecteur ;
- un identifiant ou temps dupliqué est rejeté ; une détection revendiquée par
  plusieurs frontières entraîne une abstention ;
- le temps proposé doit rester strictement entre la frontière combinée
  précédente et la frontière de base suivante ;
- sans candidat unique satisfaisant toutes les règles, le nombre flottant du
  temps de base est recopié sans modification.

La direction positive n'affirme pas que toute voix de DJ se termine après le
repère publié. Elle formalise uniquement le cas que ce complément sait traiter :
une cuesheet donne déjà la frontière et une fin de voix très proche peut en
retarder légèrement la lecture. Les fins de voix antérieures ne sont pas
utilisées pour déplacer rétrospectivement la base.

La valeur de confiance n'est pas une probabilité calibrée. INA+Silero établit
un accord entre deux segmentations sur une fin de voix et un retour à la
musique; cela ne prouve pas l'identité DJ. Chant, rap, jingle et voice-over
peuvent produire le même motif. Ce risque justifie l'abstention par défaut.

Les seuils n'ont pas été réglés sur les deux corrections connues. Ces deux cas
ciblés ne constituent ni une annotation exhaustive ni un jeu de test aveugle.

## Détections proches et décisions

Les 43 détections INA+Silero versionnées sont relues depuis
`results/full-episode-detections.json`; aucun modèle n'est exécuté à nouveau.
Onze frontières ont au moins une détection dans `±5 s` :

| Frontière | Base | Détection | Delta | Confiance | Décision |
|---|---:|---:|---:|---:|---|
| PTR492 t02 | 82,000 | 82,740 | +0,740 | 0,9962 | ajustée automatiquement |
| PTR492 t03 | 388,000 | 387,760 | -0,240 | 0,8204 | abstention : pas après +0,5 s |
| PTR492 t04 | 658,000 | 653,280 | -4,720 | 0,8754 | abstention : détection antérieure |
| PTR492 t13 | 3 649,000 | 3 646,020 | -2,980 | 0,9928 | abstention : détection antérieure |
| PTR492 t14 | 3 953,000 | 3 952,900 | -0,100 | 0,8300 | abstention : pas après +0,5 s |
| PTR492 t22 | 6 649,000 | 6 647,580 | -1,420 | 0,8541 | abstention : détection antérieure |
| PTR493 t02 | 111,000 | 115,140 | +4,140 | 0,8500 | abstention : confiance < 0,95 |
| PTR493 t07 | 1 757,000 | 1 760,180 | +3,180 | 0,7978 | abstention : confiance < 0,95 |
| PTR493 t09 | 2 350,000 | 2 353,080 | +3,080 | 0,8925 | abstention automatique : confiance < 0,95 |
| PTR493 t12 | 3 344,000 | 3 339,960 | -4,040 | 0,9765 | abstention : détection antérieure |
| PTR493 t14 | 4 144,000 | 4 146,780 | +2,780 | 0,8821 | abstention : confiance < 0,95 |

Les 31 autres frontières n'ont aucune détection dans la fenêtre locale et
restent exactement à la base. La liste exhaustive, titres compris, est dans le
CSV et le JSON par frontière.

## Évaluation à la même CueNation

L'appariement est identique pour tous les scénarios : par référence CueNation
croissante, frontière libre la plus proche du même épisode dans `±20 s`, puis
temps et identifiant pour départager. Les frontières et la référence restent
les mêmes. Les moyennes et proportions portent donc sur 22 frontières pour
PTR492, 20 pour PTR493 et 42 au total.

### Base brute contre CueNation

| Épisode | Appariées | Manquantes | Sans équivalent | Médiane abs. | Moyenne abs. | < 1 s | < 3 s | < 10 s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| PTR492 | 22 | 0 | 0 | 0,000 s | 0,000 s | 100 % | 100 % | 100 % |
| PTR493 | 20 | 0 | 0 | 0,000 s | 0,000 s | 100 % | 100 % | 100 % |
| Total | 42 | 0 | 0 | 0,000 s | 0,000 s | 100 % | 100 % | 100 % |

Tous les écarts signés sont `0,000 s`. Ce score parfait n'est pas masqué : il
est attendu, car les temps de base ont été recopiés depuis la référence.

### Base + complément automatique contre CueNation

| Épisode | Appariées | Manquantes | Sans équivalent | Médiane abs. | Moyenne abs. | < 1 s | < 3 s | < 10 s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| PTR492 | 22 | 0 | 0 | 0,000 s | 0,033636 s | 100 % | 100 % | 100 % |
| PTR493 | 20 | 0 | 0 | 0,000 s | 0,000000 s | 100 % | 100 % | 100 % |
| Total | 42 | 0 | 0 | 0,000 s | 0,017619 s | 100 % | 100 % | 100 % |

Les écarts signés combinés sont `+0,740 s` pour PTR492 t02 et `0,000 s` pour
les 41 autres frontières. En concordance à la source, 1 ligne est donc marquée
`degraded`, 41 `unchanged` et 0 `improved`. Le mot `degraded` ne décrit pas la
justesse audio : PTR492 t02 est précisément un cas confirmé à l'écoute comme
correction labo.

Décalage et dérive exploratoires :

- PTR492 : décalage signé moyen `+0,033636 s`, IC 95 %
  `[-0,032291 ; +0,099564]`, pas de décalage détecté ; pente
  `-0,095424 s/h`, IC 95 % `[-0,2052 ; +0,0144]`, pas de dérive détectée ;
- PTR493 : décalage et pente nuls, car aucune frontière automatique ne change ;
- total : décalage signé moyen `+0,017619 s`, IC 95 %
  `[-0,016914 ; +0,052152]`; pente `-0,052859 s/h`, IC 95 %
  `[-0,1152 ; +0,0072]`; ni décalage constant ni dérive détectés.

Ces régressions sur une référence circulaire n'apportent aucune validation
audio; elles documentent seulement l'effet mécanique des déplacements.

## Corrections humaines existantes, isolées

Le « Oui » d'Emmanuel à 16:07 portait explicitement sur l'application au labo
des deux corrections déjà connues :

| Frontière | Base brute | Correction humaine labo | Couche automatique actuelle |
|---|---:|---:|---|
| PTR492 t02 | 82,000 s | 82,740 s | sélectionne indépendamment 82,740 s |
| PTR493 t09 | 2 350,000 s | 2 353,080 s | s'abstient, confiance 0,8925 |

Elles restent présentes dans deux champs séparés : `existing_human_time_seconds`
et `human_only_time_seconds`. La vue `automatic_plus_human_time_seconds` donne
priorité à la validation humaine et enregistre la source
`existing_human_correction` pour les deux lignes. Aucun gain humain n'est donc
attribué au VAD.

Contre la CueNation d'origine, la couche humaine seule — identique ici à la vue
automatique + humaine — obtient une moyenne absolue de `0,090952 s`, une médiane
nulle, `97,619 %` sous 1 s, `97,619 %` sous 3 s et `100 %` sous 10 s. Cela
mesure uniquement son écart à la source : `+0,740 s` et `+3,080 s`. Les deux
écoutes confirment deux cas connus, pas les 40 autres frontières.

## Validité et limites

La base brute vient de CueNation et elle est évaluée contre les mêmes temps
CueNation. Les métriques sont donc une **mesure de concordance à la source**,
pas une précision audio, un rappel de voix DJ ou une preuve d'amélioration ou
de dégradation réelle.

Il manque toujours :

- une vérification de l'alignement temporel entre chaque cuesheet CueNation et
  l'enclosure RSS effectivement segmentée ;
- une référence humaine indépendante, exhaustive et aveugle pour les 42
  frontières ;
- une annotation séparant parole DJ, chant, rap, jingle et voice-over ;
- assez d'épisodes annotés pour calibrer la confiance et mesurer faux
  déplacements, rappel et généralisation.

Le fait que le DJ ne parle pas nécessairement entre les morceaux est pris en
compte : l'absence de voix conserve la frontière de base. Le VAD n'est jamais
utilisé pour créer une frontière manquante, supprimer une frontière publiée ou
remplacer la tracklist.

## Tests et reproductibilité

Commande exécutée depuis `lab/vad_prototype` :

```text
python3 -m unittest -v test_combine_with_vad.py
nice -n 10 python3 combine_with_vad.py
```

Résultat : `10/10` tests réussis. Les tests couvrent :

- protocole gelé ;
- reproduction des 42 frontières et de leur ordre ;
- conservation bit à bit du nombre flottant de base sans voix admissible ;
- refus d'une confiance insuffisante, d'un déplacement négatif/trop grand et
  de plusieurs candidats ;
- rejet des détections dupliquées ;
- cardinalité, titres, ordre strict et conservation des abstentions ;
- couche humaine séparée ;
- visibilité de la baseline CueNation parfaite.

Livrables :

- `lab/vad_prototype/combined_protocol.json` : protocole figé ;
- `lab/vad_prototype/combine_with_vad.py` : complément et évaluation ;
- `lab/vad_prototype/test_combine_with_vad.py` : tests automatiques ;
- `lab/vad_prototype/results/combined-boundaries.csv` : une ligne par
  frontière avec provenance, base, détection, confiance, décision, temps
  combiné, déplacement, référence, écarts et effet de concordance ;
- `lab/vad_prototype/results/combined-boundaries.json` : même détail structuré ;
- `lab/vad_prototype/results/combined-summary.json` : métriques par épisode et
  globales pour base, automatique, humain seul et automatique + humain ;
- `reponses/podmix-vad-combine-base-2026-10-10-1656.md` : présent rapport.

## Conclusion franche

La correction de cap est respectée : la méthode de base demeure entière et la
voix n'est qu'un complément local avec abstention par défaut. Le laboratoire
démontre que l'implémentation préserve toutes les frontières sans voix fiable
et tous les invariants structurels. Elle sélectionne un seul déplacement,
déjà parmi les deux cas connus et déjà validé humainement.

Il reste impossible d'affirmer que ce complément améliore le chapitrage audio
en général. La référence indépendante et l'alignement CueNation/RSS manquent,
INA+Silero ne prouve pas l'identité DJ, et les deux validations ciblées ne sont
pas un jeu de test aveugle. Aucune autorisation production n'a été donnée ni
interprétée.
