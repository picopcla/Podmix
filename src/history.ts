export const PLAYBACK_HISTORY_KEY = 'podmix-history-v1'
export const LISTENING_SESSIONS_KEY = 'podmix-listening-sessions-v1'
export const COMPLETED_EPISODES_KEY = 'podmix-completed-episodes-v1'

export type PlaybackHistoryItem = {
  id: string
  title: string
  artist: string
  url: string
  position: number
  duration?: number
  playedAt: string
}

export type ListeningSession = PlaybackHistoryItem & {
  sessionId: string
  startedAt: string
  updatedAt: string
}

const finiteNumber = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, value) : 0
const validDate = (value: unknown, fallback = new Date(0).toISOString()) =>
  typeof value === 'string' && Number.isFinite(Date.parse(value)) ? value : fallback

export function normalizeHistoryItem(value: unknown): PlaybackHistoryItem | undefined {
  if (!value || typeof value !== 'object') return undefined
  const item = value as Partial<PlaybackHistoryItem>
  if (typeof item.id !== 'string' || !item.id || typeof item.title !== 'string' || typeof item.url !== 'string') return undefined
  const duration = finiteNumber(item.duration)
  return {
    id: item.id,
    title: item.title,
    artist: typeof item.artist === 'string' ? item.artist : '',
    url: item.url,
    position: finiteNumber(item.position),
    ...(duration > 0 ? { duration } : {}),
    playedAt: validDate(item.playedAt),
  }
}

function readArray(key: string): unknown[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function loadPlaybackHistory(): PlaybackHistoryItem[] {
  const seen = new Set<string>()
  return readArray(PLAYBACK_HISTORY_KEY)
    .map(normalizeHistoryItem)
    .filter((item): item is PlaybackHistoryItem => Boolean(item))
    .sort((left, right) => Date.parse(right.playedAt) - Date.parse(left.playedAt))
    .filter((item) => !seen.has(item.id) && Boolean(seen.add(item.id)))
    .slice(0, 1_000)
}

export function savePlaybackHistory(items: PlaybackHistoryItem[]) {
  localStorage.setItem(PLAYBACK_HISTORY_KEY, JSON.stringify(items.slice(0, 1_000)))
}

/** Les éléments terminés ne font pas partie de « Reprendre ». */
export function loadCompletedEpisodeIds(): string[] {
  return [...new Set(readArray(COMPLETED_EPISODES_KEY)
    .filter((value): value is string => typeof value === 'string' && value.length > 0)
  )].slice(0, 2_000)
}

export function saveCompletedEpisodeIds(ids: string[]) {
  localStorage.setItem(COMPLETED_EPISODES_KEY, JSON.stringify([...new Set(ids)].slice(0, 2_000)))
}

export function loadListeningSessions(): ListeningSession[] {
  const stored = readArray(LISTENING_SESSIONS_KEY)
  if (!stored.length) {
    return loadPlaybackHistory().map((item, index) => ({
      ...item,
      sessionId: `legacy-${Date.parse(item.playedAt)}-${index}`,
      startedAt: item.playedAt,
      updatedAt: item.playedAt,
    }))
  }
  return stored.flatMap((value) => {
    const item = normalizeHistoryItem(value)
    if (!item || !value || typeof value !== 'object') return []
    const session = value as Partial<ListeningSession>
    if (typeof session.sessionId !== 'string' || !session.sessionId) return []
    return [{ ...item, sessionId: session.sessionId, startedAt: validDate(session.startedAt, item.playedAt), updatedAt: validDate(session.updatedAt, item.playedAt) }]
  }).sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt)).slice(0, 2_000)
}

export function updateListeningSessions(sessions: ListeningSession[], item: PlaybackHistoryItem, now = new Date()): ListeningSession[] {
  const timestamp = now.toISOString()
  const current = sessions[0]
  const coalesce = current?.id === item.id && now.getTime() - Date.parse(current.updatedAt) < 30 * 60 * 1_000
  const next: ListeningSession = coalesce
    ? { ...current, ...item, updatedAt: timestamp, playedAt: timestamp }
    : { ...item, sessionId: `${now.getTime().toString(36)}-${crypto.randomUUID?.() ?? Math.random().toString(36).slice(2)}`, startedAt: timestamp, updatedAt: timestamp, playedAt: timestamp }
  return [next, ...(coalesce ? sessions.slice(1) : sessions)].slice(0, 2_000)
}

export function saveListeningSessions(sessions: ListeningSession[]) {
  localStorage.setItem(LISTENING_SESSIONS_KEY, JSON.stringify(sessions.slice(0, 2_000)))
}

export function searchListeningSessions(sessions: ListeningSession[], query: string) {
  const normalized = query.trim().toLocaleLowerCase('fr')
  if (!normalized) return sessions
  return sessions.filter((item) => `${item.title} ${item.artist}`.toLocaleLowerCase('fr').includes(normalized))
}
