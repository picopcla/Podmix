# Application Android Capacitor

## Identité transitoire

- Nom : Podmix Studio
- Application ID : `com.podmix.next`
- Minimum Android : API 24
- Cible : API 36

L’identifiant diffère volontairement de l’application historique `com.podmix`.
Les deux APK peuvent ainsi être installées simultanément pendant la transition.
La reprise future de `com.podmix` demandera la clé de signature historique.

## Développement

Lancer l’API :

```bash
npm run api
```

Sur l’émulateur Android, `10.0.2.2:8099` désigne la machine hôte. Le trafic HTTP
est autorisé uniquement par le manifeste `debug`. Une version release doit
recevoir une URL HTTPS dans `VITE_API_URL`.

Synchroniser l’application web :

```bash
npm run android:sync
```

Construire l’APK debug :

```bash
npm run android:build:debug
```

APK attendu :

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Prérequis de compilation

- JDK 21
- Android SDK avec API 36
- Android Build Tools
- `ANDROID_HOME` et `JAVA_HOME` configurés

La machine actuelle possède OpenJDK 21, Android SDK API 36, Build Tools 36.0.0
et Platform Tools. Le SDK est installé dans `/home/debian/Android/Sdk`.

Le build debug a été produit et vérifié le 25 juillet 2026. Il est signé avec
la clé Android debug locale et peut être installé pour les tests.

## Lecture native Media3

Le frontend communique avec un plugin Capacitor `PodmixPlayer` via un contrat
stable comprenant notamment :

- `load`
- `play`
- `pause`
- `seekTo`
- `download`
- `getDownload`
- `removeDownload`
- `getState`
- `setQueue`, `next`, `previous`
- `syncLibrary`, `getStorage`
- `openCastPicker`, `cast`
- `bosePlay`, `boseSetVolume`, `boseGetState`
- événement `stateChanged`

Le plugin pilote un `ExoPlayer` hébergé dans un `MediaSessionService`. Cela
assure la lecture en arrière-plan, la session média Android et les contrôles
système. Dans un navigateur, le même contrat TypeScript utilise un élément
`HTMLAudioElement`.

Les téléchargements sont gérés nativement par Android et stockés dans le
répertoire privé externe de l’application. Leur état est conservé dans les
préférences du plugin. Aucune permission générale d’accès au stockage n’est
nécessaire. La demande de notification est déclenchée au premier usage du
lecteur ou d’un téléchargement sur Android 13 et plus.

La bibliothèque React est synchronisée vers le `MediaLibraryService` pour
Android Auto. Les URL de DJ sets, temporaires, sont renouvelées avant lecture ou
téléchargement ; seuls les DJ sets réellement téléchargés sont publiés dans la
bibliothèque Android Auto persistante.

Les abonnements RSS sont également synchronisés vers un `WorkManager` Android.
Il contrôle les flux toutes les six heures lorsque le réseau est disponible,
amorce silencieusement son état au premier passage puis notifie les nouveaux
épisodes. Le rafraîchissement interactif du catalogue reste effectué par le
frontend au lancement et au retour du réseau.
