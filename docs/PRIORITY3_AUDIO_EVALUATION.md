# Évaluation audio — priorité 3

Date : 28 juillet 2026

## Décision

Le worker de production utilise :

- les aperçus Deezer et le chroma/DTW pour valider acoustiquement un titre ;
- BeatNet lorsqu’il est installé explicitement sur un worker compatible ;
- Librosa comme grille musicale de repli, déjà incluse dans Podmix ;
- MixesDB, puis 1001Tracklists, pour la recherche externe de tracklists.

Olaf et Panako ne sont pas embarqués dans Podmix. Ils restent des moteurs
externes optionnels pour un futur index de références audio maîtrisé.

## Préflight des moteurs

### Olaf

- Projet actif, version 2.0.2 publiée en mars 2026.
- Léger et adapté à l’indexation de références locales.
- Licence AGPL-3.0.
- Le build C de secours échoue actuellement avec GCC/C11 sur `strdup`; le build
  recommandé exige Zig, absent du VPS.

Verdict : intéressant pour un service d’empreintes séparé, mais pas à intégrer
dans le worker Podmix actuel.

### Panako

- Robuste aux changements de hauteur et de vitesse.
- Licence AGPL-3.0, Java/LMDB et chaîne Gradle plus lourde.
- Le Gradle 7.2 du dépôt ne compile pas directement avec le Java 21 du VPS
  (`Unsupported class file major version 65`).

Verdict : surdimensionné pour comparer un mix à quelques aperçus de catalogue.

### BeatNet

- Fournit beats et temps forts avec un modèle CRNN.
- L’installation PyPI résout une pile PyTorch/CUDA très lourde.
- La documentation officielle signale les incompatibilités de Madmom avec
  Python 3.10+ et NumPy moderne ; le worker Podmix utilise Python 3.13.

Verdict : adaptateur optionnel conservé, sans imposer cette pile au worker
standard. Le repli Librosa recale déjà les transitions sur une grille musicale.

## Corpus reproductible

`server/benchmarks/reference_corpus.json` décrit un mix synthétique libre de
droits, composé de trois segments harmoniques, de transitions connues et d’une
grille à 120 BPM.

Exécution :

```bash
.venv/bin/python server/benchmarks/benchmark_priority3.py
```

Le rapport mesure :

- l’erreur des transitions spectrales ;
- l’erreur après recalage sur la grille musicale ;
- la localisation des aperçus par chroma/DTW ;
- la disponibilité des moteurs externes Olaf et Panako.

Résultat de référence du 28 juillet 2026 :

- transitions spectrales : erreur moyenne de 0,025 s ;
- recalage sur la grille Librosa : erreur moyenne de 0,025 s ;
- localisation chroma/DTW : erreur moyenne de 0,113 s ;
- seuils de non-régression : validés.

## Sources techniques

- [Olaf](https://github.com/JorenSix/Olaf)
- [Panako](https://github.com/JorenSix/Panako)
- [BeatNet](https://github.com/mjhydri/BeatNet)
- [MixesDB](https://www.mixesdb.com/)
