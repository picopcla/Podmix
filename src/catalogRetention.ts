import type { CatalogSource, Episode } from './domain'

type Reference = { id: string; url?: string }
export type RetentionContext = {
  favoriteTrackIds: string[]
  history: Reference[]
  listeningSessions: Reference[]
  offlineEpisodes: Array<{ id: string; remoteUrl?: string }>
  completedEpisodeIds: string[]
  activeEpisodeIds?: string[]
  nativeReferencesReady: boolean
}

// The limit is for replaceable feed entries, never a deletion request.
// Until native references have loaded successfully, preserve all old episodes.
export function protectedEpisodeIds(catalog: CatalogSource[], context: RetentionContext): Set<string> {
  const episodes = catalog.flatMap(source => source.episodes)
  if (!context.nativeReferencesReady) return new Set(episodes.map(episode => episode.id))
  const references = [...context.history, ...context.listeningSessions]
  const ids = new Set([
    ...references.map(item => item.id), ...context.completedEpisodeIds,
    ...context.offlineEpisodes.map(item => item.id), ...(context.activeEpisodeIds ?? []),
  ])
  const urls = new Set([
    ...references.flatMap(item => item.url ? [item.url] : []),
    ...context.offlineEpisodes.flatMap(item => item.remoteUrl ? [item.remoteUrl] : []),
  ])
  const favorites = new Set(context.favoriteTrackIds)
  for (const episode of episodes) {
    const favorite = context.favoriteTrackIds.some(id => id.startsWith(`${episode.id}::track::`)) || (episode.tracks ?? []).some((track, index) =>
      favorites.has(`${episode.id}::track::${track.id}::${index}`) || favorites.has(String(track.id)))
    // Analysis, tracklists, manual timestamps and evidence are not disposable cache.
    if (favorite || urls.has(episode.audioUrl) || episode.tracks?.length || episode.analysis
      || episode.webTracklistUrl || episode.liveSetTracklistUrl) ids.add(episode.id)
  }
  return ids
}

export function retainSourceEpisodes(source: CatalogSource, limit: number, protectedIds: Set<string>): CatalogSource {
  if (!Number.isFinite(limit) || source.episodes.length <= limit) return source
  const recent = new Set(source.episodes.map((episode, index) => ({episode, index}))
    .sort((a,b) => (Date.parse(b.episode.publishedAt) || 0) - (Date.parse(a.episode.publishedAt) || 0) || a.index-b.index)
    .slice(0, Math.max(0, limit)).map(item => item.episode))
  const episodes = source.episodes.filter(episode => recent.has(episode) || protectedIds.has(episode.id))
  return episodes.length === source.episodes.length ? source : {...source, episodes}
}

export function retainReferencedFeedEpisodes(previous: CatalogSource, merged: CatalogSource,
  protectedIds: Set<string>): CatalogSource {
  // Ambiguous legacy IDs cannot be safely rebound to a different episode.
  const counts = new Map<string, number>()
  for (const item of previous.episodes) counts.set(item.id, (counts.get(item.id) ?? 0) + 1)
  if ([...counts].some(([id, count]) => count > 1 && protectedIds.has(id))) {
    const oldIds = new Set(previous.episodes.map(item => item.id))
    return {...merged, episodes:[...previous.episodes, ...merged.episodes.filter(item => !oldIds.has(item.id))]}
  }
  const retained = new Set<Episode>()
  const episodes = merged.episodes.map(episode => {
    const idMatches = previous.episodes.filter(item => item.id === episode.id)
    const urlMatches = previous.episodes.filter(item => Boolean(item.audioUrl) && item.audioUrl === episode.audioUrl)
    const old = idMatches.length === 1 ? idMatches[0] : urlMatches.length === 1 ? urlMatches[0] : undefined
    if (!old || !protectedIds.has(old.id)) return episode
    retained.add(old)
    // Favorites include the old ID and track index: do not invalidate them on refresh.
    return {...episode, id:old.id, tracks:old.tracks?.length ? old.tracks : episode.tracks,
      analysis:old.analysis ?? episode.analysis,
      webTracklistUrl:old.webTracklistUrl || episode.webTracklistUrl,
      liveSetTracklistUrl:old.liveSetTracklistUrl || episode.liveSetTracklistUrl,
      liveSetTracklistOrigin:old.liveSetTracklistOrigin || episode.liveSetTracklistOrigin}
  })
  const ids = new Set(episodes.map(item => item.id))
  for (const old of previous.episodes) {
    if (protectedIds.has(old.id) && !retained.has(old) && !ids.has(old.id)) {
      episodes.push(old); ids.add(old.id)
    }
  }
  return {...merged, episodes}
}
