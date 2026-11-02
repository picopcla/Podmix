import { describe, expect, it } from 'vitest'
import { resolveTrackIndexForPlaybackState } from '../../src/playbackIdentity'

describe('resolveTrackIndexForPlaybackState', () => {
  const queue = {
    episodeId: 'find-your-harmony-507',
    mediaIds: [
      'find-your-harmony-507::track::1::0',
      'find-your-harmony-507::track::2::1',
      'find-your-harmony-507::track::3::2',
    ],
    tracks: [
      { time: 0 },
      { time: 1438 },
      { time: 1798 },
    ],
  }

  it('retrouve le titre en cours après la reprise Android d’un épisode continu', () => {
    expect(resolveTrackIndexForPlaybackState(queue, {
      mediaId: 'episode::find-your-harmony-507',
      queueIndex: 0,
      positionSeconds: 1802,
    })).toBe(2)
  })

  it('conserve la sélection directe d’un titre', () => {
    expect(resolveTrackIndexForPlaybackState(queue, {
      mediaId: 'find-your-harmony-507::track::2::1',
      queueIndex: 1,
      positionSeconds: 12,
    })).toBe(1)
  })
})
