import type { CatalogSource, Episode } from './domain'

export function mergeEpisode(previous: Episode | undefined, fresh: Episode): Episode {
  if (!previous) return fresh
  return {
    ...previous,
    ...fresh,
    audioUrl: fresh.audioUrl || previous.audioUrl,
    sourceUrl: fresh.sourceUrl || previous.sourceUrl,
    tracks: fresh.tracks?.length ? fresh.tracks : previous.tracks,
    analysis: fresh.analysis ?? previous.analysis,
  }
}

export function mergeCatalogSource(previous: CatalogSource, fresh: CatalogSource): CatalogSource {
  const previousEpisodes = new Map(previous.episodes.map((episode) => [episode.id, episode]))
  return {
    ...previous,
    ...fresh,
    id: previous.id,
    episodes: fresh.episodes.map((episode) => mergeEpisode(previousEpisodes.get(episode.id), episode)),
  }
}
