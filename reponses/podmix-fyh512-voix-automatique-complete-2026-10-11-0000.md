# FYH512 — passe voix automatique complète

## Statut

**Terminé et validé sans régression, laboratoire uniquement.** La copie du
moteur existant a exécuté une nouvelle passe locale complète VAD puis ASR sur
l'audio FYH512 exact, avant matching, landmarks, maillage et calage. Elle n'a
chargé ni rejoué `fyh512-reliable-voices-asr-frozen.json`, ni injecté YouTube,
ni codé les identifiants ou temps d002/d005/d008/d010.

- Branche : [`lab/vad-prototype-20261010`](https://github.com/picopcla/Podmix/tree/lab/vad-prototype-20261010)
- Code et résultats : [`lab/vad_prototype`](https://github.com/picopcla/Podmix/tree/lab/vad-prototype-20261010/lab/vad_prototype)
- Extraits privés : [`pont-messages/reponses/podmix-fyh512-voix-automatique-complete-extraits`](https://github.com/picopcla/pont-messages/tree/main/reponses/podmix-fyh512-voix-automatique-complete-extraits)
- Audio source : SHA-256 `47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69`, jamais ajouté à Podmix.

## Exécution réelle et couverture

Paramètres figés avant évaluation dans
[`fyh512_complete_voice_protocol.json`](../lab/vad_prototype/fyh512_complete_voice_protocol.json).

- couverture : `00:00:00.000` à `02:00:16.927`, soit `7 216,927375 s` ;
- fenêtrage : 600 s, recouvrement 60 s, règle de cœur des recouvrements ;
- INA 0.8.0 CPU : 14/14 fenêtres, 251,8625 s murales ;
- Silero VAD 6.2.3 via ONNX Runtime 1.31.0 CPU : 14/14 fenêtres, 29,4807 s murales ;
- ASR : faster-whisper 1.2.1, modèle `small`, CPU int8, 2 threads, anglais,
  beam 5, sans VAD ASR ni contexte précédent ;
- 10 candidats produits automatiquement, 10 transcrits ;
- 4 candidats interprétés comme morceau suivant, 4 ancres acceptées par
  l'optimiseur réel ; 6 abstentions sous le seuil inchangé de 0,95 ;
- ordre effectif : INA, Silero, ASR, interprétation, landmarks, validation par
  l'optimiseur, maillage/calage ; un seul CPU lourd à la fois.

La preuve complète — fenêtres, paramètres, versions, commandes utilisant la
copie média temporaire du moteur, candidats, transcriptions, confiances, décisions, anchors et
hashes — est dans
[`fyh512-existing-engine-complete-voice-trace-frozen.json`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-trace-frozen.json)
et le tableau lisible dans
[`fyh512-existing-engine-complete-voice-candidate-audit.csv`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-candidate-audit.csv).
Les fichiers exacts des trois modèles et leurs SHA-256 sont consignés dans
[`fyh512-existing-engine-complete-voice-model-manifest.json`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-model-manifest.json).

## Candidats voix et décisions

| ID | début–fin RSS | conf. VAD | transcrit | interprétation | ancre |
|---|---:|---:|---|---|---|
| d001 | 00:00:00.000–00:00:04.580 | 0,9086 | oui | jingle/identification, abstention sous seuil | non soumise |
| d002 | 00:01:17.940–00:02:00.000 | 0,9884 | oui | suivant : piste 1 | acceptée à 120,00 s |
| d003 | 00:23:03.180–00:23:09.160 | 0,8166 | oui | annonce suivante audible, abstention sous seuil | non soumise |
| d004 | 00:26:54.920–00:27:31.520 | 0,7694 | oui | voix narrative/ambiguë, abstention sous seuil | non soumise |
| d005 | 00:39:12.340–00:39:25.680 | 0,9864 | oui | suivant : piste 12 | acceptée à 2 365,68 s |
| **d006** | **00:56:17.180–00:56:31.040** | **0,8435** | **oui** | **clôture heure/récap, refus sous seuil 0,95** | **non soumise à l'optimiseur** |
| d007 | 00:59:42.460–01:00:32.380 | 0,9269 | oui | actuel/première, abstention sous seuil | non soumise |
| d008 | 01:29:05.280–01:29:09.380 | 0,9698 | oui | suivant : piste 24 | acceptée à 5 349,38 s |
| d009 | 01:50:45.280–01:50:57.160 | 0,5177 | oui | narration/ambiguë, abstention sous seuil | non soumise |
| d010 | 01:55:52.620–01:56:15.720 | 0,9626 | oui | suivant : piste 32 | acceptée à 6 975,72 s |

d006 a donc bien une **nouvelle candidate**, est **transcrite**, puis
**interprétée comme abstention** avant création d'ancre : sa confiance 0,8435
reste sous 0,95. Le seuil n'a pas été abaissé et aucun gain n'est promis.

d008 annonce la piste algorithmique 24. Son extrait prouve cette annonce ; il
ne doit pas être présenté comme écoute du gain de la référence 23, située
environ 261 secondes plus tôt.

## Comparaison stricte avec la base immédiate 16/32

Évaluateur inchangé sur le fond : appariement libre glouton un-à-un par erreur
absolue croissante, tolérance `<= 30 s`, offsets YT/RSS 1,3455 s avant 3 565 s
et 1,7265 s après. Les sorties moteur ont été figées avant de charger les 32
références officielles.

- base immédiate quatre annonces gelées + correctif : **16/32**, manques 16,
  surplus 16, SHA sortie `199c10e9c35552206a81ddf782e689986e7f36628415b5a18ae1bd033f37070a` ;
- nouvelle passe locale complète : **16/32**, manques 16, surplus 16, même SHA ;
- gains contre 16/32 : `[]` ; pertes : `[]` ;
- erreurs sur 16 appariements : moyenne 8,4816 s, médiane 1,6250 s ;
  `<1 s` 5/16, `<5 s` 11/16, `<10 s` 12/16, `<=30 s` 16/16 ;
- comparaison à la vraie base moteur désactivé 12/32 : gains historiques
  1/12/23/32, aucune perte, mais ce n'est pas un gain nouveau de cette passe.

Sorties détaillées :
[`comparaison CSV`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-comparison-32.csv),
[`paires`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-pairs.csv),
[`manques`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-missing.csv),
[`surplus`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-surplus.csv)
et [`résumé`](../lab/vad_prototype/results/fyh512-existing-engine-complete-voice-evaluation-summary.json).

```text
00:02:00 YT | 00:01:59 ALGO
00:05:37 YT | 00:05:38 ALGO
00:09:45 YT | non trouvé
00:13:00 YT | 00:13:02 ALGO
00:16:46 YT | 00:16:47 ALGO
00:19:02 YT | 00:18:33 ALGO
00:23:20 YT | non trouvé
00:25:54 YT | 00:25:56 ALGO
00:29:35 YT | non trouvé
00:32:35 YT | 00:32:06 ALGO
00:35:33 YT | non trouvé
00:39:26 YT | 00:39:24 ALGO
00:43:34 YT | 00:43:37 ALGO
00:47:05 YT | 00:47:06 ALGO
00:51:02 YT | non trouvé
00:56:30 YT | non trouvé
01:00:31 YT | non trouvé
01:04:09 YT | non trouvé
01:08:06 YT | non trouvé
01:11:50 YT | non trouvé
01:16:29 YT | non trouvé
01:20:25 YT | non trouvé
01:24:19 YT | 01:24:47 ALGO
01:29:08 YT | 01:29:08 ALGO
01:32:56 YT | non trouvé
01:37:07 YT | 01:37:36 ALGO
01:39:11 YT | non trouvé
01:43:05 YT | 01:43:06 ALGO
01:46:23 YT | 01:46:29 ALGO
01:49:47 YT | non trouvé
01:52:10 YT | non trouvé
01:56:14 YT | 01:56:14 ALGO
```

## Intégrité, tests et comportement moteur

- sortie voix désactivée reproduite exactement : SHA
  `e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208` ;
- sortie nouvelle passe : SHA
  `199c10e9c35552206a81ddf782e689986e7f36628415b5a18ae1bd033f37070a` ;
- trace nouvelle passe : SHA
  `d1a80c35149ad33f8720644499830c2297306ad3fe67cf7d71f42007f250a91f` ;
- détecteur : SHA
  `240575241bca33c4bb3cd32e0c942c22a4593ddc45e0397967dafeec03415958` ;
- runner : SHA
  `3c3da8ab1ddb10ea882970b637fec402e42ef82dc9d52ca06d461bc0863f9538` ;
- protocole : SHA
  `873b5b3d9465880fa3921eec3affb7e2386a8651dd7dbc0815679b28721456e8` ;
- 39 tests du laboratoire passent, dont les 10 tests voix existants, le cas
  exact 150/195 s sur 4 pistes, le conflit ciblé réellement prouvé par
  l'optimiseur, le repli exact toutes ancres rejetées, la conservation des
  titres non annoncés, l'appel INA/Silero/ASR depuis le hook et la couverture
  0–7 216,927375 s.

Le canal `boundary_anchors` reste séparé des `presences` landmarks. Aucun
offset de preview n'est appliqué aux voix. Le premier début peut être non nul.
Les conflits provoquent une abstention ciblée, sans masquer les autres erreurs ;
si toutes les ancres sont refusées, la sortie redevient exactement le mode off.

Diff livré contre `78375c0` : 26 fichiers, 5 397 insertions et 2 suppressions ;
les volumes viennent principalement des traces et tableaux gelés. Le code
ciblé se limite au runner voix, au détecteur, à l'évaluateur, à l'option
`--output-root`, au protocole et aux tests, tous sous `lab/vad_prototype`.
Contre le `main` original `eebd70c`, la branche compte 230 fichiers et 66 326
insertions parce qu'elle conserve tout l'historique antérieur du laboratoire ;
la présente étape n'ajoute rien hors `lab/vad_prototype` et ce rapport.

## Limites

Ce test n'est ni indépendant des titres/artistes connus, ni aveugle : la
tracklist non minutée aide l'interprétation et les 32 temps YouTube servent à
l'évaluation après gel. Il ne démontre ni l'identité indépendante des titres,
ni l'impossibilité de trouver les 16 manques. Un résultat identique sur FYH512
ne prouve pas la généralisation à d'autres émissions, accents, jingles ou
mixages. Aucun réglage n'a été fait pour améliorer le score YouTube. Aucun
changement production/main/SQLite/API/service/Docker/APK/pont et aucune
promesse de production.
