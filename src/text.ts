import { decode } from 'html-entities'
import type {
  CatalogSource,
  DetectionJob,
  DjSearchResult,
  Episode,
  LiveSetDetails,
  LiveSetSearchResult,
  LiveSetTrack,
  OfflineEpisode,
  PodcastSearchResult,
  Track,
} from './domain'

/**
 * Decode complete HTML character references without parsing the value as HTML.
 *
 * Four passes repair legacy double encoding while placing a hard upper bound on
 * hostile or accidentally deeply nested input. Strict mode deliberately keeps
 * incomplete references such as "Rock &amp" unchanged.
 */
export const MAX_ENTITY_DECODE_PASSES = 4

export function decodeTextEntities(value: string | null | undefined): string {
  let result = value ?? ''
  for (let pass = 0; pass < MAX_ENTITY_DECODE_PASSES; pass += 1) {
    if (!result.includes('&')) break
    const decoded = decode(result, { level: 'html5', scope: 'strict' })
    if (decoded === result) break
    result = decoded
  }
  return result
}

export function normalizeTrackText<T extends Track>(track: T): T {
  return { ...track, artist: decodeTextEntities(track.artist), title: decodeTextEntities(track.title) }
}

export function normalizeLiveSetTrackText<T extends LiveSetTrack>(track: T): T {
  return { ...track, artist: decodeTextEntities(track.artist), title: decodeTextEntities(track.title) }
}

export function normalizeEpisodeText<T extends Episode>(episode: T): T {
  return {
    ...episode,
    title: decodeTextEntities(episode.title),
    tracks: episode.tracks?.map(normalizeTrackText),
  }
}

export function normalizeCatalogSourceText<T extends CatalogSource>(source: T): T {
  return {
    ...source,
    title: decodeTextEntities(source.title),
    episodes: source.episodes.map(normalizeEpisodeText),
  }
}

export function normalizeDetectionJobText<T extends DetectionJob>(job: T): T {
  return { ...job, tracks: job.tracks.map(normalizeTrackText) }
}

export function normalizeOfflineEpisodeText<T extends OfflineEpisode>(episode: T): T {
  return { ...episode, title: decodeTextEntities(episode.title), artist: decodeTextEntities(episode.artist) }
}

export function normalizePodcastSearchResultText<T extends PodcastSearchResult>(item: T): T {
  return { ...item, title: decodeTextEntities(item.title), artist: decodeTextEntities(item.artist) }
}

export function normalizeDjSearchResultText<T extends DjSearchResult>(item: T): T {
  return { ...item, title: decodeTextEntities(item.title), uploader: decodeTextEntities(item.uploader) }
}

export function normalizeLiveSetSearchResultText<T extends LiveSetSearchResult>(item: T): T {
  return { ...item, title: decodeTextEntities(item.title), channel: decodeTextEntities(item.channel) }
}

export function normalizeLiveSetDetailsText<T extends LiveSetDetails>(item: T): T {
  return {
    ...normalizeLiveSetSearchResultText(item),
    tracks: item.tracks?.map(normalizeLiveSetTrackText),
  }
}
