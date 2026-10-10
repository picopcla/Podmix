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
