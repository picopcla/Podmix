# Design : Ajout par URL directe — Radio

**Date :** 2026-04-12  
**Scope :** `AddRadioViewModel` + `AddRadioScreen` uniquement  
**Statut :** Approuvé

---

## Contexte

- **Podcasts/Émissions** : déjà implémenté — `AddPodcastViewModel.isRssUrl()` + `addByRssUrl()` + bouton dans `AddPodcastScreen` (lignes 107-122).
- **Radios** : pas de support URL directe. Seule la recherche RadioBrowser existe.

---

## Objectif

Permettre à l'utilisateur de coller une URL de flux audio direct (ex : `http://streaming.radio-classique.fr/radio-classique`) dans le champ de recherche radio et de l'ajouter en un clic, sans passer par RadioBrowser.

---

## Flux logique

**Input :** URL HTTP(S) collée dans le champ de recherche  
**Process :**
1. `isStreamUrl()` détecte le préfixe `http` → affiche le bouton "Ajouter ce flux"
2. Click → `addByStreamUrl()` extrait `URI(url).host` comme nom provisoire
3. `repository.addRadio(name, streamUrl, logoUrl=null)` persiste en base
4. `_addedEvent.emit(Unit)` → navigation retour

**Output :** La radio apparaît dans la liste avec le hostname comme nom, modifiable ensuite

---

## Changements

### `AddRadioViewModel.kt`

Deux nouvelles fonctions :

```kotlin
fun isStreamUrl(text: String): Boolean =
    text.trimStart().startsWith("http", ignoreCase = true)

fun addByStreamUrl(url: String) {
    val name = try { URI(url).host ?: url } catch (_: Exception) { url }
    viewModelScope.launch {
        repository.addRadio(name = name, streamUrl = url.trim(), logoUrl = null)
        _addedEvent.emit(Unit)
    }
}
```

Le debounce est filtré pour ne pas chercher sur RadioBrowser quand c'est une URL :

```kotlin
.filter { it.length >= 2 && !isStreamUrl(it) }
```

### `AddRadioScreen.kt`

Bloc inséré entre le `TextField` et le `if (isLoading)` existant :

```kotlin
if (viewModel.isStreamUrl(query)) {
    Button(
        onClick = { viewModel.addByStreamUrl(query.trim()) },
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text("Ajouter ce flux", color = Color.White)
    }
    Spacer(Modifier.height(8.dp))
}
```

Le spinner de recherche devient conditionnel :

```kotlin
if (isLoading && !viewModel.isStreamUrl(query)) { ... }
```

---

## Fichiers touchés

| Fichier | Changement |
|---------|-----------|
| `ui/screens/radio/AddRadioViewModel.kt` | +`isStreamUrl()`, +`addByStreamUrl()`, filtre debounce |
| `ui/screens/radio/AddRadioScreen.kt` | +bloc bouton URL, spinner conditionnel |

Aucun autre fichier modifié. Pas de migration DB. Pas de nouveau route.

---

## Hors scope

- Validation que l'URL est un flux audio jouable (HEAD request, content-type check) — ajout futur si nécessaire
- Édition du nom après ajout — déjà possible via l'écran détail existant
