# Distribution Android

## Adresse publique

- Page : `https://vps-43451133.vps.ovh.net/podmix/`
- APK : `https://vps-43451133.vps.ovh.net/podmix/podmix-1.0.39.apk`
- Manifeste : `https://vps-43451133.vps.ovh.net/podmix/update.json`
- API : `https://vps-43451133.vps.ovh.net/podmix-api/`

L'APK publique est un build `release` signé. Android accepte une mise à jour
installée par-dessus uniquement si le `applicationId` et cette clé restent
identiques.

## Publier une version suivante

1. Augmenter `versionCode` et `versionName` dans `android/app/build.gradle`.
2. Mettre la même version dans `.env.production`.
3. Lancer `npm run android:sync`, puis `cd android && ./gradlew assembleRelease`.
4. Copier l'APK sous `/var/www/podmix/podmix-X.Y.Z.apk`.
5. Calculer son SHA-256 et modifier `/var/www/podmix/update.json`.
6. Modifier le lien de la page de téléchargement.

L'application vérifie ce manifeste au démarrage. Une version dont le
`versionCode` est supérieur apparaît dans Réglages → Mises à jour et ouvre
l'APK publique pour installation.

## Clé de signature

Les fichiers locaux `android/podmix-release.keystore` et
`android/release-signing.properties` sont exclus de Git. Une copie privée est
conservée dans `/home/debian/.podmix-signing/`. Perdre cette clé empêcherait
toute mise à jour des installations existantes : elle doit être sauvegardée
hors du VPS sans jamais être publiée avec l'APK.

## Serveur

Le backend tourne avec Docker Compose et conserve ses données dans le volume
`podmix-next_podmix-data`. Caddy termine HTTPS et route uniquement le préfixe
`/podmix-api/` vers le port local `8099`. Le reste du domaine continue d'être
servi par Authentik.
