# Rapport du prototype VAD Podmix — 10 octobre 2026

> **Mise à jour 16:07 — laboratoire uniquement.** Après cet essai initial,
> Emmanuel a validé à l'écoute PTR492 piste 2 (`82,000 → 82,740 s`) et PTR493
> piste 9 (`2 350,000 → 2 353,080 s`). Les affirmations ci-dessous sur
> l'absence totale de validation décrivent l'état initial avant cette réponse.
> L'état courant est détaillé dans
> `reponses/podmix-vad-complement-corrections-lab-2026-10-10-1610.md` et dans
> `results/summary.json`. Aucune production n'a été modifiée.

Complément corpus demandé :
[FYH / Pure Trance Radio](CORPUS_COMPLEMENT_FYH_PURETRANCE.md).

## Statut

**Partiel, exploitable comme essai hors production.** Les deux modeles ont
effectivement tourne sur CPU, de bout en bout, sur les memes extraits. Les
resultats, les points de reprise et 50 extraits de verification ont ete
produits. La precision vraie et les faux deplacements ne sont pas mesurables,
car cette execution ne disposait d'aucune capacite d'ecoute humaine. Le corpus
verifiable est limite a deux episodes au lieu des trois a cinq souhaites.

Aucune suggestion n'a ete appliquee. Aucune API, base SQLite, configuration,
dependance, donnee, chapitre ou media de Podmix n'a ete modifie. Aucun service,
conteneur, APK, deploiement ou fusion vers `main` n'a ete lance.

## Isolation et sauvegarde

- Sauvegarde prealable :
  `/home/debian/backups/pont-tasks/c4e2c7fcfebf45c154a4b8fb/pre-vad-prototype-20261010T1412/workspace-before-local-changes.tar.gz`
  (SHA-256 `87433c35fa002f97a005a0a3c56fc8c49ed365d094b7c7376cbafa3fb7e3b05c`).
- Clone de laboratoire : branche `lab/vad-prototype-20261010`, distincte du
  repertoire `/home/debian/projects/podmix` en lecture seule.
- Venv : `../.venv-vad`, Python 3.13.5, 2,065,220,638 octets.
- Sorties et caches propres au labo : `lab/vad_prototype/output` et
  `lab/vad_prototype/cache`; ils ne sont pas suivis par Git.
- Analyse sequentielle, un seul modele a la fois, `nice -n 10`, deux threads,
  `CUDA_VISIBLE_DEVICES=-1`.
- La reprise a ete verifiee : 25/25 frontieres sautees sans recalcul pour
  chacun des deux modeles avec `--resume`.

## Audit des dependances CPU

Les metadonnees amont d'inaSpeechSegmenter 0.8.0 exigent bien
`tensorflow[and-cuda]` et `onnxruntime-gpu`. Un premier `pip --dry-run` a
commence a mettre ces archives dans le cache du labo : le processus a ete
interrompu avant installation et le cache de 1,6 Gio a ete purge. La variante
retenue installe INA avec `--no-deps`, puis remplace explicitement ces deux
dependances par `tensorflow-cpu==2.21.0` et `onnxruntime==1.31.0`.

Validation effective :

- TensorFlow : `is_cuda_build=false`, `is_rocm_build=false`, aucun peripherique
  GPU;
- ONNX Runtime : session Silero forcee sur `CPUExecutionProvider`;
- aucune distribution contenant `cuda`, `cudnn`, `nvidia` ou
  `onnxruntime-gpu` installee;
- inaSpeechSegmenter 0.8.0; Silero VAD 6.2.3; NumPy 2.3.5;
  scikit-image 0.26.0; SoundFile 0.14.0; imageio-ffmpeg 0.6.0 avec FFmpeg 7.0.2.

Le detail machine et versions est dans `results/environment-audit.json`.

## Corpus et protocole gele

1. **Key4050 — Transmission Bangkok 2024**, fichier local de 59 min 26 s,
   tracklist horodatee publique SoundCloud/set79 : 13 frontieres analysees,
   dont 5 de bon fonctionnement et 8 d'evaluation.
2. **Joint Operations Centre — Subculture Festival Melbourne 2022**, fichier
   local et tracklist horodatee publique SoundCloud/set79 : 12 frontieres
   d'evaluation.

Le fichier local `b3b.m4a` a ete exclu : aucune provenance, aucun manifeste et
aucun temps de tracklist verifiable n'ont ete retrouves. Rien n'a ete invente.
Le corpus couvre des plages instrumentales et des titres vocaux d'apres les
metadonnees de tracklist. La presence exacte de presentation, rap, jingle ou
voix sur fond musical n'est pas certifiee sans ecoute.

Pour chacune des 25 frontieres : meme fenetre `[temps original - 45 s,
temps original + 45 s]`. Parametres fixes avant l'evaluation :

- INA : moteur `sm`, parole/musique, sans genre, batch 32, ratio energie 0,03;
- Silero : 16 kHz, seuil 0,50, seuil de sortie 0,35, parole minimale 250 ms,
  silence minimal 100 ms, marge 30 ms, blocs de 512 echantillons;
- suggestion de consensus seulement si le temps original est dans une plage
  de parole pour les deux modeles, si leurs fins de parole different de 2 s au
  plus, si INA confirme ensuite au moins 5 s de musique et si le deplacement
  positif est compris entre 0,5 et 15 s.

Les 5 premieres frontieres Key4050 ont seulement valide le fonctionnement et
n'ont provoque aucun ajustement. L'evaluation distincte porte sur 20
frontieres.

## Resultats

- **INA seul** : 1 suggestion, 24 abstentions.
- **Silero seul** : 25 abstentions. Silero ne distinguant pas musique et
  non-parole, il n'est jamais autorise seul a confirmer un retour a la musique.
- **Consensus INA + Silero** : 0 suggestion, 25 abstentions.

Le seul deplacement INA est la frontiere 1 du set Joint Operations Centre :
temps original `300,000 s`, proposition INA `302,500 s`, delta `+2,500 s`.
INA classe `297,740–302,500 s` en parole puis le reste en musique. Silero ne
detecte aucune parole dans cette fenetre (probabilite maximale 0,037582), donc
le consensus s'abstient. Sans ecoute, il s'agit d'un **faux deplacement
potentiel**, pas d'un faux positif confirme.

Motifs des abstentions de consensus :

- 24/25 : aucun des deux modeles ne confirme une parole englobant la frontiere
  et INA ne confirme donc pas la sequence exigee;
- 1/25 : INA voit parole puis musique, mais Silero ne confirme pas la parole.

Accord inter-modeles sur la presence/absence de parole au temps original :
24/25, soit 96 %. Ce chiffre est un accord automatique, **pas une precision**.
Precision humaine : non mesurable, denominateur annote `0/25`. Aucun vrai ou
faux deplacement ne peut etre affirme. Effet sur les temps existants : aucun;
25/25 restent inchanges dans la sortie de consensus et dans Podmix.

## Performances et impact observe

| Modele | Frontieres | Mur | CPU | Pic RAM |
|---|---:|---:|---:|---:|
| Silero | 25 | 7,620 s | 13,122 s | 102 160 Kio (~99,8 Mio) |
| INA | 25 | 84,155 s | 112,898 s | 814 836 Kio (~795,7 Mio) |
| Total sequentiel | 50 passages | 91,775 s | 126,019 s | 814 836 Kio max |

Occupation finale : venv 2,1 Gio; cache reproductible 45 Mio (modele INA 5,7
Mio et wheels 39 Mio); sorties brutes/extraits 3,1 Mio; resultats consolides
164 Kio. Le cache `pip` de 477 Mio de l'installation CPU a ete purge apres
validation.

Avant/apres l'evaluation, `podmix-api-watchdog`, `podmix-minipc-tunnel` et
`cloudflared-podmix-minipc` sont restes `active/running`. Les trois fichiers de
production controles (SQLite et deux uploads) conservent exactement tailles,
dates et SHA-256. La charge 1 minute est passee de 1,85 a 2,33 pendant la
fenetre mesuree; aucune latence applicative n'a ete instrumentee. Aucun
redemarrage n'a ete observe ou demande.

## Sorties et reproduction

- `episodes.json` : sources, temps originaux, fenetres et parametres geles;
- `run_vad.py` : execution CPU sequentielle avec checkpoints par frontiere;
- `aggregate.py` : politique de suggestion/abstention;
- `make_excerpts.py` : 50 extraits locaux de 12 s avant/apres;
- `results/boundary-results.json` et `.csv` : 75 lignes detaillees (INA,
  Silero et consensus), segments, motifs, incertitude et references d'extrait;
- `results/summary.json` et `results/environment-audit.json` : synthese et
  preuve CPU.

Les commandes sans secret sont documentees dans `README.md`. Les extraits
restent uniquement sur le mini-PC sous `output/excerpts` et ne sont pas pousses
sur GitHub.

## Conclusion

**Aucun candidat a activer dans Podmix.** INA fournit la distinction
parole/musique requise, mais produit ici une suggestion non confirmee par
Silero. Silero est nettement plus rapide et leger, mais un VAD seul ne peut pas
prouver le retour a la musique et ne doit pas deplacer une frontiere. La
prochaine etape possible est une ecoute aveugle des 50 extraits, avec annotation
des 25 frontieres (presentation, chant/rap, jingle, voix sur musique, vraie
transition), puis l'ajout d'au moins un troisieme episode verifiable avant de
recalculer precision, rappel et faux deplacements. Le prototype reste desactive
et hors production.
