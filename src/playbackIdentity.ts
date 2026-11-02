export type PlaybackTrackQueue = {
  episodeId: string
  mediaIds: string[]
  tracks: Array<{ time: number }>
}

export type PlaybackIdentityState = {
  mediaId: string
  queueIndex: number
  positionSeconds: number
}

export function nativeEpisodeId(mediaId: string) {
  return mediaId.startsWith('episode::') ? mediaId.slice('episode::'.length) : undefined
}

export function resolveTrackIndexForPlaybackState(
  queue: PlaybackTrackQueue | undefined,
  state: PlaybackIdentityState,
) {
  if (!queue) return -1
  const mediaIndex = queue.mediaIds.indexOf(state.mediaId)
  if (mediaIndex >= 0) return mediaIndex
  if (nativeEpisodeId(state.mediaId) === queue.episodeId) {
    return queue.tracks.reduce(
      (activeIndex, track, trackIndex) => state.positionSeconds >= track.time ? trackIndex : activeIndex,
      0,
    )
  }
  return state.queueIndex >= 0 && state.queueIndex < queue.mediaIds.length ? state.queueIndex : -1
}
