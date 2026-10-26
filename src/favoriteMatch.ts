// Un favori est identifie par sa cle exacte episode::track::id::index.
// Les anciennes versions stockaient seulement l'identifiant numerique du morceau : l'affichage les accepte aussi.
// Cette fonction unique sert a l'affichage ET a la recherche de liens, pour que les deux restent alignes.
export function isFavoriteTrack(favoriteIds: readonly string[], key: string, trackId: number | string): boolean {
  return favoriteIds.includes(key) || favoriteIds.includes(String(trackId))
}
