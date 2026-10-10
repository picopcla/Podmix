# Prototype VAD Podmix hors production

Ce laboratoire compare inaSpeechSegmenter et Silero sur les memes fenetres de
90 secondes centrees sur les temps de tracklist existants. Il ne contient
aucun raccord a l'API Podmix et n'ecrit jamais dans les medias ou la base de
production.

Le [rapport initial](REPORT.md) est complété par le
[rapport corpus FYH / Pure Trance Radio](CORPUS_COMPLEMENT_FYH_PURETRANCE.md).
Les médias RSS de Pure Trance Radio restent dans `output/media/`, ignoré par
Git ; leurs URL, SHA-256 et chemins attendus sont consignés dans
`episodes.json`.

## Isolation CPU

Creer le venv a cote du clone, puis installer explicitement les variantes CPU.
INA doit etre installe avec `--no-deps`, car ses metadonnees amont 0.8.0
reclament `tensorflow[and-cuda]` et `onnxruntime-gpu`.

```bash
python3 -m venv ../.venv-vad
../.venv-vad/bin/pip install tensorflow-cpu==2.21.0 onnxruntime==1.31.0 \
  numpy==2.3.5 pandas==3.0.6 scikit-image==0.26.0 pyannote.core==6.0.1 \
  matplotlib==3.11.2 Pyro4==4.82 pytextgrid==0.1.4 soundfile==0.14.0 \
  imageio-ffmpeg==0.6.0
../.venv-vad/bin/pip install --no-deps inaSpeechSegmenter==0.8.0 silero-vad==6.2.3
```

Variables communes a chaque commande d'analyse :

```bash
export CUDA_VISIBLE_DEVICES=-1
export TF_CPP_MIN_LOG_LEVEL=2
export KERAS_HOME="$PWD/lab/vad_prototype/cache/keras"
export OMP_NUM_THREADS=2
export TF_NUM_INTRAOP_THREADS=2
export TF_NUM_INTEROP_THREADS=1
```

Executer un seul modele a la fois. `--resume` saute les frontieres deja ecrites.

```bash
../.venv-vad/bin/python lab/vad_prototype/audit_environment.py
nice -n 10 ../.venv-vad/bin/python lab/vad_prototype/run_vad.py --method silero --split all --resume
nice -n 10 ../.venv-vad/bin/python lab/vad_prototype/run_vad.py --method ina --split all --resume
../.venv-vad/bin/python lab/vad_prototype/make_excerpts.py
../.venv-vad/bin/python lab/vad_prototype/aggregate.py
```

Les fichiers bruts et les extraits restent dans `output/`, ignore par Git. Les
sorties consolidees sans media sont versionnees dans `results/`. Une suggestion
reste une proposition a verifier : elle n'est jamais appliquee a Podmix.
Sans confirmation humaine d'une vraie prise de parole suivie d'un retour à la
musique, l'agrégateur conserve désormais le temps/delta comme candidat
automatique mais produit une abstention. Chant, rap, jingle et voice-over ne
sont pas des confirmations suffisantes.

Mise a jour du 10 octobre 2026 a 16:07 : Emmanuel a valide par ecoute deux
frontieres, exclusivement pour les sorties du laboratoire : PTR492 piste 2
(`82,000 -> 82,740 s`) et PTR493 piste 9 (`2 350,000 -> 2 353,080 s`). Les
temps originaux, corriges, la source et l'horodatage restent cote a cote dans
`results/boundary-results.*`, `results/consensus-results.*` et
`results/summary.json`. Les candidats PTR493 `+4,140`, `+1,160`, `+2,780 s`
et JOC `+2,500 s` restent non valides. Cette mise a jour ne raccorde toujours
pas le prototype a Podmix et ne modifie aucun chapitre reel.

## Segmentation globale PTR492 / PTR493

Le protocole indépendant des temps CueNation est gelé dans
`full_episode_protocol.json`. Il analyse l'épisode entier par fenêtres
recouvrantes de 600 secondes et conserve des checkpoints dans
`output/full_episode/`.

Exécuter strictement un modèle et un épisode à la fois, avec les mêmes
variables CPU que ci-dessus :

```bash
nice -n 10 ../.venv-vad/bin/python lab/vad_prototype/run_full_episode.py \
  --method silero --episode pure-trance-radio-492 --resume
nice -n 10 ../.venv-vad/bin/python lab/vad_prototype/run_full_episode.py \
  --method ina --episode pure-trance-radio-492 --resume
# Répéter sans changer les paramètres pour pure-trance-radio-493.
../.venv-vad/bin/python lab/vad_prototype/analyze_full_episode.py
```

La dernière commande produit les CSV/JSON `results/full-episode-*` et le
rapport français dans `reponses/`. Elle compare après coup les détections aux
temps CueNation avec une tolérance maximale de 20 secondes. Elle ne modifie
aucun temps Podmix. Si les segmentations globales ignorees par Git ne sont plus
presentes, elle reutilise les detections versionnees et recalcule seulement la
comparaison ainsi que les vues avec/sans corrections validees. L'alignement
cuesheet/audio RSS reste non verifie dans les deux vues.

## Complément conservateur de la base existante

`combined_protocol.json` fige une couche locale qui conserve les 42 frontières
CueNation de PTR492/PTR493 et ne les ajuste que devant une fin de voix unique,
postérieure de 0,5 à 5 secondes et de confiance interne au moins égale à 0,95.
Sans cette preuve technique, le temps de base est recopié exactement. Le VAD ne
crée, ne supprime et ne réordonne jamais une frontière. INA+Silero ne prouve pas
l'identité DJ : chant, rap, jingle et voice-over restent possibles.

Les détections globales déjà versionnées sont réutilisées sans relire l'audio :

```bash
python3 -m unittest -v test_combine_with_vad.py
nice -n 10 python3 combine_with_vad.py
```

Les sorties `results/combined-*` séparent la base brute, le complément
automatique et les deux corrections humaines existantes. Leur comparaison à
CueNation est explicitement une concordance circulaire à la source, pas une
mesure de précision audio.

## FYH 511 : absence de base temporelle

Le test du 10 octobre 2026 sur `Find Your Harmony Episode #511` réutilise sans
retouche les paramètres de `full_episode_protocol.json` et
`combined_protocol.json`. Le RSS Podbean publie 33 titres mais aucun timestamp
de piste et aucune balise de chapitres. Le code courant de Podmix ne crée donc
aucune position : `feeds.py::import_feed` n'appelle l'alignement que si au
moins un timestamp RSS est présent, et `tracklist.py::align_tracklist` ne sait
conserver que des temps explicites ou préexistants.

L'audio reste hors du dépôt. Avec un fichier privé déjà téléchargé, les deux
modèles s'exécutent strictement l'un après l'autre :

```bash
nice -n 10 ../.venv-vad/bin/python lab/vad_prototype/run_external_full_episode.py \
  --method silero --episode find-your-harmony-511 \
  --title "Find Your Harmony Episode #511" --media /chemin/prive/fyh511.mp3 \
  --duration 7347 --resume
nice -n 10 ../.venv-vad/bin/python lab/vad_prototype/run_external_full_episode.py \
  --method ina --episode find-your-harmony-511 \
  --title "Find Your Harmony Episode #511" --media /chemin/prive/fyh511.mp3 \
  --duration 7347 --resume
python lab/vad_prototype/analyze_fyh511.py --feed /chemin/prive/feed.xml
```

`results/fyh511-boundaries.*` matérialise les 33 abstentions avec des temps
vides; `results/fyh511-suggestions.*` publie séparément les fins de voix à
écouter. Aucune suggestion n'est un chapitre : sans frontière temporelle de
base, le protocole combiné s'arrête avant tout appariement ou déplacement.

## FYH 512 : gel avant référence

`fyh512_protocol.json` sépare strictement la production algorithmique de son
évaluation. `analyze_fyh512_frozen.py` ne charge aucun timestamp publié. Il
réutilise le détecteur de voix gelé et le détecteur spectral existant de
`server/audio_fallback.py`. Les 31 temps de transition sont dérivés de l'audio,
mais leur cardinalité est assistée par le décompte RSS de 32 titres.

L'identification AudD n'est pas exécutée sans jeton et la méthode de landmarks
qui reçoit les titres connus en entrée est classée comme assistée, pas comme
identification indépendante. Les titres indépendants restent donc vides avec
une abstention explicite. Les sorties `*-frozen.*` doivent être commitées avant
de lancer le script d'évaluation qui charge la tracklist minutée officielle.

Exécution, après les deux modèles globaux et avec les chemins privés adaptés :

```bash
python lab/vad_prototype/analyze_fyh512_frozen.py --media /media/prive/fyh512.mp3
# Commit obligatoire des sorties *-frozen.* avant la commande suivante.
python lab/vad_prototype/evaluate_fyh512.py
python lab/vad_prototype/make_fyh512_excerpts.py \
  --media /media/prive/fyh512.mp3 --output /sortie/privee --duration 7216.927375
```

La comparaison applique l'alignement audio documenté dans
`fyh512_reference.json`. Les extraits MP3 restent privés et ne doivent jamais
être ajoutés à ce dépôt.

## FYH 512 : variante séquentielle par intervalles voix

`fyh512_sequential_protocol.json` corrige la limite de la première variante :
les quatre fins de voix à confiance interne >= 0,95 découpent réellement cinq
grands intervalles continus. L'identification (indépendante, ASR, puis landmarks
assistés) est tentée avant la détection spectrale locale dans chacun de ces
intervalles. Le nombre de transitions n'est pas imposé.

Le protocole et le code doivent être committés avant le calcul, puis toutes les
sorties `fyh512-sequential-*-frozen.*` doivent être committées avant d'exécuter
l'évaluation qui charge les temps officiels. AudD est seulement contrôlé par
présence de configuration et n'est jamais appelé dans cette expérience.
