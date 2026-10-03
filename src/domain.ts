export type TrackSource = 'detected' | 'manual'
export type TimestampSource = 'rss' | 'external' | 'youtube' | 'audio' | 'manual' | 'provisional'
export type TimestampStatus = 'provided' | 'manual' | 'pending' | 'verified'

export type Track = {
  id: number
  time: number
  artist: string
  title: string
  confidence: number
  timestampSource?: TimestampSource
  timestampScore?: number
  timestampStatus?: TimestampStatus
  source: TrackSource
  verified?: boolean
  catalogValidated?: boolean
  evidence?: string[]
  mbid?: string
  artworkUrl?: string
  spotifyUrl?: string
  deezerUrl?: string
}

export type DetectionJobStatus = 'queued' | 'running' | 'web_pending' | 'completed' | 'failed' | 'cancelled'

export type DetectionJob = {
  id: string
  episodeId: string
  status: DetectionJobStatus
  stage: string
  progress: number
  createdAt: string
  updatedAt: string
  tracks: Track[]
  duration?: number
  error?: string
  operation?: 'research'
}

export type EpisodeAnalysis = {
  jobId: string
  status: DetectionJobStatus
  stage: string
  progress: number
  updatedAt: string
  error?: string
}

export type Episode = {
  id: string
  title: string
  description: string
  publishedAt: string
  duration: string
  audioUrl: string
  sourceUrl?: string
  // URL 1001Tracklists déjà validée pour cet épisode. Elle évite de refaire
  // une recherche fragile lors d'une actualisation ultérieure.
  webTracklistUrl?: string
  // Source réellement retenue pour un DJ set. Une actualisation ne doit pas
  // remplacer silencieusement une tracklist validée par un autre set du DJ.
  liveSetTracklistUrl?: string
  liveSetTracklistOrigin?: string
  artworkUrl: string
  tracks?: Track[]
  analysis?: EpisodeAnalysis
}

export type CatalogSource = {
  id: string
  kind: 'podcast' | 'show' | 'radio' | 'dj'
  title: string
  description: string
  artworkUrl: string
  feedUrl?: string
  streamUrl?: string
  streamContentType?: string
  musical?: boolean
  /** Épisodes arrivés depuis la dernière actualisation consultée. */
  newEpisodeIds?: string[]
  /** Sous-ensemble non encore vu, utilisé pour la pastille sur la carte. */
  unseenEpisodeIds?: string[]
  episodes: Episode[]
}

export type OfflineEpisode = {
  id: string
  title: string
  artist: string
  remoteUrl: string
  localUri?: string
  status: 'queued' | 'downloading' | 'paused' | 'completed' | 'failed'
  bytesDownloaded?: number
  totalBytes?: number
  addedAt: string
}

export type PodcastSearchResult = {
  id: string
  title: string
  artist: string
  feedUrl: string
  artworkUrl: string
  genre: string
  episodeCount: number
}

export type DjSearchResult = {
  id: string
  title: string
  uploader: string
  url: string
  artworkUrl: string
  duration: number
  viewCount: number
}

export type LiveSetSearchResult = {
  id: string
  provider: 'youtube' | 'soundcloud'
  title: string
  channel: string
  url: string
  artworkUrl: string
  duration: number
  viewCount: number
  publishedAt: string
  score: number
}

export type LiveSetTrack = {
  id: number
  time: number | null
  artist: string
  title: string
  timestampSource: string
  timestampStatus: string
}

export type LiveSetDetails = LiveSetSearchResult & {
  audioUrl?: string
  description?: string
  tracks?: LiveSetTrack[]
  tracklistOrigin?: string
  tracklistUrl?: string
}
