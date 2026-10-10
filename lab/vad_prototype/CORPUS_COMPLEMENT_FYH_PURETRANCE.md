# Complément corpus FYH / Pure Trance Radio — 10 octobre 2026

> **Mise à jour 16:07 — laboratoire uniquement.** Ce rapport conserve l'état
> de l'expérience avant revue humaine. Emmanuel a depuis validé PTR492 piste 2
> (`82,000 → 82,740 s`) et PTR493 piste 9 (`2 350,000 → 2 353,080 s`). Les
> deux lignes de consensus correspondantes sont désormais marquées
> `correction_validee_labo`; les autres candidats restent non validés. Voir
> `reponses/podmix-vad-complement-corrections-lab-2026-10-10-1610.md`.

## Statut et lien avec le premier essai

**Complément terminé, hors production, sans activation.** Il complète le
[rapport du premier essai](REPORT.md) dans le même laboratoire et conserve ses
25 frontières et sorties. Deux épisodes de **Pure Trance Radio** ont réellement
été ajoutés : 42 nouvelles frontières ont été traitées par
**inaSpeechSegmenter et Silero**, l'un après l'autre sur CPU. FYH a été identifié
et ses épisodes/tracklists ont été vérifiés, mais il n'a pas été soumis aux
modèles faute de temps de transition publiés fiables sur le même audio RSS.

Aucune suggestion n'a été appliquée. Aucune API, base SQLite, dépendance,
configuration, donnée, chapitre ou média de Podmix n'a été modifié. Aucun
service, conteneur, APK, déploiement ou fusion vers `main` n'a été lancé.

## Sauvegarde et identité du catalogue

Avant la première modification du laboratoire, son contenu complet a été
sauvegardé dans
`/home/debian/backups/pont-tasks/2ad337a1ccf01aff3152867c/pre-vad-corpus-complement-20261010T1420/vad_prototype-before-complement.tar.gz`.
Taille : 48 786 736 octets. SHA-256 :
`ceed6d119e4c809b757a28357c5375371c477fa3d2582e8036f4678a2ef1910c`.

L'annuaire lu par l'API Podmix locale en lecture seule a donné :

| Recherche | Nom exact retenu | Identifiant catalogue | Flux exact |
|---|---|---|---|
| `fyh` | aucun résultat FYH direct | — | — |
| `Find Your Harmony` | `Find Your Harmony Radioshow` | `884562443` | `https://feed.podbean.com/andrewrayel/feed.xml` |
| `Pure Trance Radio` | `Pure Trance Radio Podcast with Solarstone` | `270338166` | `https://feed.podbean.com/richve/feed.xml` |

`Find Your Harmony` n'est pas retenu par simple supposition : une capture
locale existante de l'interface Podmix affiche `Find Your Harmony Radioshow`,
et les tests locaux Podmix associent explicitement `FYH496` à
`Find Your Harmony Radio Episode #496`. La requête littérale `fyh` de l'annuaire
iTunes ne retrouve toutefois pas ce flux ; cette limite est conservée dans
`episodes.json`.

La recherche `Pure Trance Radio` renvoie aussi `Shades of Addiction | Pure
Trance Radio podcast` (`385487737`). Il n'a pas été retenu, car le catalogue
local existant affiche précisément la source Solarstone.

## Épisodes et référence de tracklist

### FYH vérifié mais non testé

Le flux FYH est accessible et expose 500 épisodes. Les épisodes 511 et 512 ont
des tracklists dans leur description RSS, mais sans temps de début. Les albums
Apple Music FYH511/FYH512 fournissent des durées de plages, mais correspondent
à des éditions d'environ 1 h 27 / 1 h 26, contre 2 h 02 / 2 h 00 pour les
podcasts RSS, avec un nombre de pistes différent. Ces durées n'ont donc pas été
substituées silencieusement à des temps de frontière du podcast.

### Pure Trance Radio testé

Les enclosures officielles RSS ont été comparées aux cuesheets CueNation. Les
durées concordent à 2–3 secondes près sur l'épisode complet :

| Épisode | GUID Podbean | Audio RSS | Cuesheet | Frontières |
|---|---|---:|---:|---:|
| Pure Trance Radio Podcast 493 | `richve.podbean.com/9e0ff222-1a34-3f72-a19e-59f4ca256561` | 6 473,83 s | 6 470 s | 20 |
| Pure Trance Radio Podcast 492 | `richve.podbean.com/e630acd0-fd71-3b0d-a022-18856604ed36` | 7 174,61 s | 7 172 s | 22 |

SHA-256 des médias locaux de laboratoire :

- PTR 493 : `262b843a806172873e83fcbfb6737d840f922183c3eec20d556f3296d4db724f` ;
- PTR 492 : `6925c5d6e8d5f51653f9b05cc572d5b0c936851a2ddca6f1b05602385eade4cd`.

Temps originaux CueNation testés :

- PTR 493 : `111, 534, 822, 1212, 1441, 1757, 2001, 2350, 2734,
  3021, 3344, 3747, 4144, 4401, 4696, 5035, 5350, 5663, 5990, 6243 s` ;
- PTR 492 : `82, 388, 658, 1034, 1380, 1710, 2040, 2323, 2679, 2974,
  3210, 3649, 3953, 4212, 4448, 4796, 5232, 5599, 5981, 6275, 6649,
  7001 s`.

Chaque fenêtre exacte est `[temps original - 45 s, temps original + 45 s]`.
Les bornes numériques et les titres sont dans `results/boundary-results.csv`
et `episodes.json`.

## Protocole et versions

Le protocole du premier essai est inchangé : même fenêtre de 90 s, mêmes
paramètres gelés, `nice -n 10`, deux threads, `CUDA_VISIBLE_DEVICES=-1`, un seul
modèle à la fois, et checkpoints par frontière. Les 25 anciennes frontières ont
été reprises sans recalcul.

- Python 3.13.5 ; inaSpeechSegmenter 0.8.0 ; TensorFlow CPU 2.21.0 ;
- Silero VAD 6.2.3 ; ONNX Runtime 1.31.0 forcé sur `CPUExecutionProvider` ;
- NumPy 2.3.5 ; scikit-image 0.26.0 ; SoundFile 0.14.0 ;
  imageio-ffmpeg 0.6.0 / FFmpeg 7.0.2 ;
- aucune distribution CUDA, cuDNN, NVIDIA ou `onnxruntime-gpu` installée.

La règle de publication est désormais explicite dans l'agrégateur : même si
les deux modèles signalent une parole suivie de musique, le résultat reste un
**candidat automatique en abstention** tant qu'une écoute humaine ne confirme
pas une vraie prise de parole et n'exclut pas chant, rap, jingle et voice-over.
Le candidat INA historique JOC à 300,000 s reste intact dans les sorties brutes,
mais l'agrégat courant le requalifie lui aussi en candidat automatique avec
abstention. `results/summary.json` compte donc 6 candidats INA au total : 1
historique et 5 sur le nouveau corpus.

## Résultats du complément

Sur les 42 nouvelles frontières :

- INA : 5 candidats automatiques, **0 suggestion publiée**, 42 abstentions ;
- Silero : 0 suggestion possible, 42 abstentions ;
- consensus INA/Silero : 2 candidats automatiques, **0 suggestion publiée**,
  42 abstentions.

Les cinq déplacements automatiques INA, tous retenus comme faux déplacements
potentiels faute d'écoute, sont :

| Frontière | Temps original | Fenêtre | Candidat INA | Delta | Consensus et motif |
|---|---:|---:|---:|---:|---|
| PTR492 piste 2, Midnight Evolution — Dreams | 82,000 s | 37–127 s | 82,740 s | +0,740 s | candidat automatique ; abstention sans validation humaine |
| PTR493 piste 2, Solarstone — Solarcoaster | 111,000 s | 66–156 s | 115,140 s | +4,140 s | abstention ; fins INA/Silero en désaccord de plus de 2 s |
| PTR493 piste 3, Nordfold — Tidal Shift | 534,000 s | 489–579 s | 535,160 s | +1,160 s | abstention ; Silero ne confirme pas la parole englobante |
| PTR493 piste 9, Private Taste — First | 2 350,000 s | 2 305–2 395 s | 2 353,080 s | +3,080 s | candidat automatique ; abstention sans validation humaine |
| PTR493 piste 14, Factoria + Ross Baker — River of Light | 4 144,000 s | 4 099–4 189 s | 4 146,780 s | +2,780 s | abstention ; Silero ne confirme pas la parole englobante |

Pour les deux candidats de consensus :

- PTR492 piste 2 : INA classe `37,000–82,740 s` en parole puis
  `82,740–126,980 s` en musique ; Silero finit sa plage englobante à
  `83,014 s` ;
- PTR493 piste 9 : INA classe `2 336,700–2 353,080 s` en parole puis
  `2 353,080–2 394,980 s` en musique ; Silero finit sa plage englobante à
  `2 353,926 s`.

Ces concordances automatiques ne démontrent pas la nature de la voix. Les deux
temps restent donc inchangés.

Motifs des 42 abstentions de consensus :

- 21 : aucun modèle ne voit de parole englobante et le retour musique INA est
  insuffisant ;
- 15 : INA ne voit pas de parole englobante et le retour musique INA est
  insuffisant ;
- 2 : candidat automatique complet, mais pas de validation humaine ;
- 2 : Silero ne confirme pas la parole englobante ;
- 1 : Silero ne confirme pas la parole et le delta INA est hors seuil ;
- 1 : fins de parole INA/Silero en désaccord de plus de 2 s.

Accord INA/Silero sur la présence de parole au temps original : 24/42
(57,1 %) : 3 parole/parole, 21 absence/absence, 3 INA seul, 15 Silero seul.
Il s'agit d'un accord inter-modèles, pas d'une précision.

## Précision et faux déplacements

La référence temporelle est la cuesheet publiée ; elle indique les frontières
à examiner, pas la vérité « présentation parlée puis musique ». La référence
de classe nécessaire à la précision devrait être une écoute humaine aveugle
des 84 extraits avant/après, avec annotation séparée des prises de parole,
chants, raps, jingles et voice-overs.

Cette annotation n'existe pas dans l'exécution : précision, rappel et nombre de
faux déplacements confirmés sont **non mesurables** (`0/42` frontières annotées
humainement). Les 5 candidats INA et les 2 candidats de consensus sont donc
qualifiés de faux déplacements potentiels et aucun n'est appliqué. L'effet réel
sur les temps Podmix est 0 déplacement sur 42.

## Performances et impact

| Modèle | Nouvelles frontières | Mur | CPU | Pic RAM |
|---|---:|---:|---:|---:|
| Silero | 42 | 19,637 s | 21,544 s | 244 836 Kio (~239,1 Mio) |
| INA | 42 | 203,723 s | 187,164 s | 837 344 Kio (~817,7 Mio) |
| Total séquentiel | 84 passages | 223,360 s | 208,708 s | 837 344 Kio max |

Occupation liée au laboratoire : médias téléchargés 545 937 873 octets
(~520,6 Mio), sorties brutes 161 962 octets, 134 extraits de vérification
7 875 171 octets, cache de modèles 46 741 117 octets, venv 2 065 220 638
octets. Les médias et extraits sont ignorés par Git et restent locaux.

Les services `podmix-api-watchdog`, `podmix-minipc-tunnel` et
`cloudflared-podmix-minipc` étaient `active` avant et après. Le SQLite de
production a conservé exactement taille (53 248 octets), mtime et SHA-256
`51530765ec3c738b0b9b6f4bb2ffdbcce6374e4092dac1c491e0992babf0f230`.
La charge 1 minute observée est passée de 1,38 avant à 1,59 après ; aucune
latence applicative n'a été instrumentée.

## Artefacts et reproduction

- `episodes.json` : identités catalogue, feeds, GUID, SHA médias, 42 temps et
  protocole gelé ;
- `results/corpus-complement-summary.json` : synthèse machine du complément ;
- `results/boundary-results.json` et `.csv` : 201 lignes, dont les 126 lignes
  INA/Silero/consensus du nouveau corpus ;
- `output/raw/{ina,silero}` : 67 checkpoints chacun, dont 42 nouveaux ;
- `output/excerpts` : 134 extraits locaux avant/après ;
- `output/metrics-{ina,silero}-all.json` : temps CPU/mur et pic RAM du
  complément avec 25 reprises ;
- `results/environment-audit.json` : versions et preuves CPU.

Les commandes de reproduction restent celles de [README.md](README.md), avec
`--resume`. Les enclosures RSS doivent être placées aux chemins `local_media`
documentés dans `episodes.json`. Aucun secret n'est requis.

## Conclusion

**Pure Trance Radio a réellement été testé sur deux épisodes et les deux
modèles ont tourné. FYH a été vérifié mais non testé faute de frontières
horodatées fiables sur le même média.** Aucun candidat n'est assez qualifié
pour activation sans écoute humaine. Le prototype reste désactivé et hors
production.
