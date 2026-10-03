---
name: podmix-release-publish
description: Build, publish, and present a signed Podmix Android update when the user asks for an APK link, a public update, or a release containing fixes.
---

# Podmix Release Publish

Use this skill for a downloadable/public Podmix Android update. It is not needed for a local source edit or a device-only install.

## Release contract

- A downloadable link must point to an APK built after the requested fixes. Never present an older APK as containing new corrections.
- In every handoff involving an installation or download, explicitly state the new version number (for example `1.0.148-ng`) and the main corrections it contains. Do this even for a direct S23 installation with no public link.
- Do not call a workspace file link a public download. The handoff is complete only after the public presentation page and its APK button are live.
- The delivery must be usable without context: it needs a visible download page, the direct APK link, the displayed version, concise release highlights, and Android installation guidance.
- The `ng` flavor is the installed transition app: package `com.podmix.next.ng`; build it with `npm run android:build:ng`.
- Before a release, inspect the existing version in `package.json`, `android/app/build.gradle`, and `deploy/update.json`. Bump exactly once if the new APK needs to supersede the published one.
- Validate the Web build and the Android release build. Calculate SHA-256 from `android/app/build/outputs/apk/ng/release/app-ng-release.apk`.
- If the change touches `android/` (Java, manifest, resources, or Capacitor plugin), require a successful native Gradle build. An asset-only APK repackaging is not an acceptable substitute.

## Publish

1. Copy the signed APK to the public Podmix document root as `podmix-<version>-ng.apk`.
2. Update `deploy/update.json`: `versionCode`, `versionName`, `apkUrl`, `publishedAt`, `sha256`, and concise user-facing notes.
3. Update `deploy/index.html` with the current version and its APK link.
4. Update `deploy/download.html` as the focused presentation page: current version, one-sentence benefit, direct download button, three or fewer user-facing highlights, and the Android installation reminder.
5. Keep the release notes consistent across `update.json`, `index.html`, and `download.html`; do not expose build internals, keys, or local paths.
6. Deploy the APK and all updated static files with the project’s available VPS access. Verify the public APK URL, manifest, and presentation page with HTTP before giving the link to the user.

Publishing is an external write. Do it only when the user explicitly asks for a downloadable/public update. If VPS access or an Android build is unavailable, state the exact blocker and do not alter the public manifest to reference a missing APK.

After a successful public release, lead with the presentation/download URL, then include the direct APK link and version. If a connected Android device is in scope, install that same APK only after the public artifact is verified.

For a device-only build, state the installed version in the final confirmation before listing its corrections.

## Handoff checklist

Before replying with a link, confirm all of the following:

1. The public APK reports the intended package, version code, version name, alignment, and signature.
2. `update.json` points to that exact public APK and SHA-256.
3. `download.html` displays the same version and its button targets that APK.
4. The public `download.html`, `update.json`, and APK URL each return successfully over HTTP.

If one of these checks is unavailable, say that the release is not published yet; do not present a speculative link.
