import type { CatalogSource, Episode } from './domain'

export function mergeEpisode(previous: Episode | undefined, fresh: Episode): Episode {
  if (!previous) return fresh
  return {
    ...previous,
    ...fresh,
    audioUrl: fresh.audioUrl || previous.audioUrl,
    sourceUrl: fresh.sourceUrl || previous.sourceUrl,
    webTracklistUrl: fresh.webTracklistUrl || previous.webTracklistUrl,
    tracks: fresh.tracks?.length ? fresh.tracks : previous.tracks,
    analysis: fresh.analysis ?? previous.analysis,
  }
}

function sameText(left: string | undefined, right: string | undefined) {
  return Boolean(left && right && left.trim().toLocaleLowerCase('fr') === right.trim().toLocaleLowerCase('fr'))
}

function matchingPreviousEpisode(previousEpisodes: Episode[], fresh: Episode): Episode | undefined {
  const sameId = previousEpisodes.filter((episode) => episode.id === fresh.id)
  // A formerly truncated RSS ID can belong to many episodes. In that case
  // match stable feed fields instead of transferring one episode's metadata
  // (tracks, timestamps, progress) to every other episode.
  if (sameId.length === 1) return sameId[0]
  return previousEpisodes.find((episode) => Boolean(episode.audioUrl) && episode.audioUrl === fresh.audioUrl)
    ?? previousEpisodes.find((episode) => sameText(episode.title, fresh.title) && sameText(episode.publishedAt, fresh.publishedAt))
}

/** Épisodes réellement entrés depuis la dernière lecture du flux. */
export function newEpisodesFromFeed(previous: CatalogSource, fresh: CatalogSource): Episode[] {
  return fresh.episodes.filter((episode) => !matchingPreviousEpisode(previous.episodes, episode))
}

export function mergeCatalogSource(previous: CatalogSource, fresh: CatalogSource): CatalogSource {
  return {
    ...previous,
    ...fresh,
    id: previous.id,
    episodes: fresh.episodes.map((episode) => mergeEpisode(matchingPreviousEpisode(previous.episodes, episode), episode)),
  }
}
