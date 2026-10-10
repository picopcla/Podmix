# Podmix VAD — application labo des deux corrections validées

## Statut

**Terminé dans le laboratoire uniquement.** La branche
`lab/vad-prototype-20261010` marque maintenant comme validées par écoute
humaine les deux corrections suivantes :

| Frontière | Temps original | Temps corrigé | Delta | Validation |
|---|---:|---:|---:|---|
| PTR492 piste 2 — Midnight Evolution - Dreams | 82,000 s | 82,740 s | +0,740 s | Emmanuel, 10/10/2026 16:07 Europe/Paris |
| PTR493 piste 9 — Private Taste - First (Ashtrax Rerub) | 2 350,000 s | 2 353,080 s | +3,080 s | Emmanuel, 10/10/2026 16:07 Europe/Paris |

La portée est `lab_only`. Aucun temps de chapitre réel n'est changé.

## Sauvegarde préalable

Avant le clone de travail et avant toute édition, la branche distante a été
sauvegardée sous forme de dépôt Git nu dans :

`/home/debian/pont-task-workspaces/4ec670c80325786261bd0fd3/backup-podmix-lab-pre-corrections-20261010-1610.git`

- commit sauvegardé : `6ada6c121a3397dc26055d9f3886efcda3bc6b3b` ;
- taille observée : 17 Mio ;
- contrôle `git fsck --no-dangling` : réussi.

## État avant / après

Avant la réponse d'Emmanuel, les deux lignes `consensus_ina_silero` étaient en
`abstention`, avec `new_time_seconds = null`, et le résumé comptait 67
abstentions de consensus. Les temps candidats existaient déjà, respectivement
`82.740` et `2353.080`, mais sans validation humaine enregistrée.

Après application au laboratoire :

- les deux lignes de consensus sont en `correction_validee_labo` ;
- `original_time_seconds`, `candidate_time_seconds`, `new_time_seconds`, les
  deltas, la source, l'horodatage et la portée sont conservés côte à côte ;
- le résumé compte 65 abstentions et 2 corrections validées pour le consensus ;
- les sorties INA et Silero restent des preuves algorithmiques : elles ne sont
  pas transformées en corrections autonomes ;
- `results/consensus-results.{json,csv}` fournit une vue dédiée des 67 lignes
  de consensus ;
- la précision globale reste non mesurable, car deux validations ciblées ne
  constituent pas une annotation exhaustive des 67 frontières.

## Comparaison avec et sans corrections

Les détections et appariements de l'analyse pleine durée ne changent pas. Le
scénario « avec » substitue uniquement les deux références validées aux temps
CueNation d'origine.

| Épisode | Frontière | Écart sans | Écart avec | Erreur abs. moyenne sans | Erreur abs. moyenne avec | < 1 s sans / avec | < 3 s sans / avec |
|---|---|---:|---:|---:|---:|---:|---:|
| PTR492 | piste 2 | +0,740 s | +0,000 s | 6,064 s | 5,996 s | 27,3 % / 27,3 % | 45,5 % / 45,5 % |
| PTR493 | piste 9 | +3,080 s | +0,000 s | 4,973 s | 4,631 s | 0,0 % / 11,1 % | 11,1 % / 22,2 % |

Les métriques utilisent seulement les frontières appariées (11 pour PTR492,
9 pour PTR493). L'alignement entre la cuesheet CueNation et l'audio de
l'enclosure RSS reste **NON VÉRIFIÉ** ; ces chiffres sont donc deux vues de
laboratoire, pas la preuve d'une référence temporelle absolue.

## Candidats restant non validés

Les candidats suivants demeurent explicitement non validés et sans temps
corrigé appliqué :

- PTR493 piste 2 : `+4,140 s` ;
- PTR493 piste 3 : `+1,160 s` ;
- PTR493 piste 14 : `+2,780 s` ;
- Joint Operations Centre piste 1 : `+2,500 s`.

## Fichiers mis à jour

- `lab/vad_prototype/results/boundary-results.{json,csv}` ;
- `lab/vad_prototype/results/consensus-results.{json,csv}` ;
- `lab/vad_prototype/results/summary.json` et
  `corpus-complement-summary.json` ;
- `lab/vad_prototype/results/full-episode-comparison.{json,csv}` et
  `full-episode-summary.json` ;
- le protocole, l'agrégateur et le générateur du rapport, afin que l'état soit
  reproductible ;
- le rapport principal
  `reponses/podmix-vad-decoupe-complete-ptr-2026-10-10-1555.md`.

## Isolation confirmée

Aucune modification n'a été faite dans `/home/debian/projects/podmix`, sur
`main`, dans SQLite, les chapitres réels, l'API, l'APK, la configuration ou le
déploiement. Aucun merge n'a été réalisé et aucun média n'est versionné.
