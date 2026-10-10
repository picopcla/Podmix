# FYH512 — algorithme initial complété par les voix

## Statut

Terminé, périmètre laboratoire uniquement. La comparaison porte sur
`analyze_fyh512_frozen.py` et ses 32 points globaux gelés, puis sur ces mêmes
points complétés uniquement avec les annonces voix déjà détectées. Les
pipelines séquentiel et landmarks ne sont ni appelés ni repris.

Le baseline est un détecteur spectral sur l'audio entier assisté par le nombre
de 32 titres. Ce n'est pas un pipeline complet de reconnaissance et aucun titre
n'a été identifié indépendamment.

## Règle gelée avant évaluation

- SHA256 du baseline vérifié :
  `d8369ba170b98fd96c933520b0519b7f1fc0eefd75ebd0c770f0c26772de3378`.
- Seuil voix original conservé : confiance interne `>= 0,95`.
- Une voix n'est utilisable que si la transcription déjà gelée contient une
  annonce explicite de démarrage/reprise de la musique suivante. Les quatre
  cas admissibles sont d002, d005, d008 et d010.
- L'identité du DJ n'est pas vérifiée. Une présence vocale seule n'est pas une
  preuve de début de piste. Les six détections sous seuil sont tracées et
  ignorées.
- Rapprochement : si exactement un point spectral hors départ audio est à
  `<=45 s`, rayon repris du gap minimal du baseline, la borne voix remplace ce
  point. Sans voisin, elle est ajoutée. Avec plusieurs voisins, abstention.
- Aucune reselection spectrale, aucune cible de cardinalité après complément,
  aucun fingerprint, catalogue, AudD, modèle ou service supplémentaire.

Trace des quatre rapprochements :

- d002 `120,000 s` remplace S002 `162,690 s`, écart `42,690 s` ;
- d005 `2365,680 s` remplace S011 `2409,730 s`, écart `44,050 s` ;
- d008 `5349,380 s` remplace S023 `5375,740 s`, écart `26,360 s` ;
- d010 `6975,720 s` remplace S032 `7013,250 s`, écart `37,530 s`.

Le résultat contient 28 points spectraux conservés et quatre points voix
rapprochés. Il reste à 32 points par conséquence de la règle, pas parce qu'une
cardinalité a été réimposée.

## Évaluation commune

Appariement glouton libre un-à-un par erreur absolue croissante, tolérance
`<=30 s`, sans ordre forcé. Horloge RSS alignée vers YouTube avec les offsets
normalisés `1,3455 s` avant 3565 s et `1,7265 s` après.

| Variante | Appariés | Manques | Surplus | Erreur moyenne | Médiane | <1 s | <3 s | <10 s | Dénominateur |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Initial | 9 | 23 | 23 | 8,3918 s | 2,2745 s | 2 | 5 | 6 | 9 paires |
| Initial + voix | 12 | 20 | 20 | 4,4064 s | 1,4745 s | 4 | 9 | 10 | 12 paires |

Verdict : **gain** de trois appariements, trois manques en moins et trois
surplus en moins. Les statistiques ne portent que sur les paires indiquées et
ne signifient pas qu'un titre a été reconnu.

## 32 lignes YouTube

Temps arrondis à la seconde, même horloge YouTube :

```text
YT | ALGO
00:02:00 | 00:01:59
00:05:37 | 00:05:38
00:09:45 | non trouve
00:13:00 | 00:13:02
00:16:46 | 00:16:47
00:19:02 | non trouve
00:23:20 | non trouve
00:25:54 | 00:25:56
00:29:35 | non trouve
00:32:35 | non trouve
00:35:33 | non trouve
00:39:26 | 00:39:24
00:43:34 | 00:43:37
00:47:05 | non trouve
00:51:02 | non trouve
00:56:30 | non trouve
01:00:31 | non trouve
01:04:09 | non trouve
01:08:06 | non trouve
01:11:50 | non trouve
01:16:29 | non trouve
01:20:25 | non trouve
01:24:19 | non trouve
01:29:08 | 01:29:08
01:32:56 | 01:33:06
01:37:07 | 01:37:36
01:39:11 | non trouve
01:43:05 | 01:43:06
01:46:23 | non trouve
01:49:47 | non trouve
01:52:10 | non trouve
01:56:14 | 01:56:14
```

## Surplus de la variante complétée

Horloge YouTube, arrondie à la seconde :

```text
S001 00:00:00
S004 00:08:44
S007 00:20:32
S009 00:30:06
S010 00:36:47
S012 00:41:59
S014 00:48:38
S015 00:49:43
S016 00:53:03
S017 01:05:06
S018 01:07:26
S019 01:09:16
S020 01:13:54
S021 01:17:41
S022 01:19:01
S025 01:34:01
S026 01:35:33
S028 01:41:45
S030 01:47:13
S031 01:50:37
```

## Reproductibilité

- Protocole : `fyh512_initial_plus_voice_protocol.json`.
- Complément exact : `analyze_fyh512_initial_plus_voice.py`.
- Évaluation : `evaluate_fyh512_initial_plus_voice.py`.
- Extraits : `make_fyh512_initial_plus_voice_excerpts.py`.
- Tests : `test_fyh512_initial_plus_voice.py` et
  `test_evaluate_fyh512_initial_plus_voice.py` (`4` tests réussis).
- Sorties gelées et traces : fichiers
  `results/fyh512-initial-plus-voice-*` et baseline libre
  `results/fyh512-initial-baseline-*`.

Les MP3 ne sont pas stockés dans Podmix : ils sont publiés uniquement dans le
dépôt privé `picopcla/pont-messages`, avec index, positions d'écoute et hashes.
