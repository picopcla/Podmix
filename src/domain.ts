export type TrackSource = 'detected' | 'manual'

export type Track = {
  id: number
  time: number
  artist: string
  title: string
  confidence: number
  source: TrackSource
  verified?: boolean
  catalogValidated?: boolean
  evidence?: string[]
  mbid?: string
  artworkUrl?: string
  spotifyUrl?: string
  deezerUrl?: string
  acousticValidation?: {
    accepted: boolean
    available: boolean
    provider: string
    catalogScore?: number
    acousticScore?: number
    dtwCost?: number
    confidence?: number
    catalogId?: string
  }
}

export type DetectionJobStatus = 'queued' | 'running' | 'completed' | 'failed' | 'cancelled'

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
  operation?: 'download_analyze' | 'analyze' | 'refine'
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
  musical?: boolean
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
