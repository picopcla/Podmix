import type { Episode } from './domain'
import type { CatalogSource } from './domain'

export type RssImportResult = {
  title: string
  description: string
  artworkUrl: string
  feedUrl: string
  episodes: Episode[]
}

function parseRssDate(dateStr: string): string {
  const d = new Date(dateStr)
  return Number.isNaN(d.getTime()) ? '' : d.toISOString()
}

function getTextContent(parent: Element, selector: string): string {
  return parent.querySelector(selector)?.textContent?.trim() ?? ''
}

function namespacedElement(parent: Element, tagName: string): Element | undefined {
  return parent.getElementsByTagName(tagName)[0] ?? undefined
}

function imageUrl(parent: Element): string {
  const image = namespacedElement(parent, 'itunes:image')
  // iTunes puts the URL in the href attribute; textContent is normally empty.
  return image?.getAttribute('href')?.trim() || image?.textContent?.trim() || ''
}

function durationInSeconds(value: string): string {
  const raw = value.trim()
  if (!raw) return ''
  if (/^\d+$/.test(raw)) return raw
  const parts = raw.match(/^\s*(\d+):(\d{1,2})(?::(\d{1,2}))?\s*$/)
  if (!parts) return ''
  const first = Number(parts[1])
  const second = Number(parts[2])
  const third = Number(parts[3] ?? 0)
  if (second >= 60 || third >= 60) return ''
  // HH:MM:SS when three fields are present, otherwise MM:SS.
  return String(parts[3] === undefined ? first * 60 + second : first * 3600 + second * 60 + third)
}

function getDuration(item: Element, desc: string): string {
  const explicit = durationInSeconds(namespacedElement(item, 'itunes:duration')?.textContent ?? '')
    || durationInSeconds(namespacedElement(item, 'duration')?.textContent ?? '')
  if (explicit) return explicit
  const match = desc.match(/(?:durée|duration|length)[:\s]*(\d+(?::\d{1,2}){1,2})/i)
  return match ? durationInSeconds(match[1]) : ''
}

function stableId(value: string): string {
  // Two inexpensive independent hashes keep IDs compact while retaining the
  // complete RSS identity. Truncating a URL made consecutive episodes share
  // their playback state when their common prefix exceeded 40 characters.
  let forward = 0x811c9dc5
  let backward = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    forward = Math.imul(forward ^ value.charCodeAt(index), 0x01000193)
    backward = Math.imul(backward ^ value.charCodeAt(value.length - 1 - index), 0x01000193)
  }
  return `${(forward >>> 0).toString(36)}-${(backward >>> 0).toString(36)}`
}

function buildEpisodeId(feedUrl: string, guid: string, audioUrl: string, title: string, publishedAt: string): string {
  try {
    const url = new URL(feedUrl)
    return `${url.hostname}/${stableId(`${guid}|${audioUrl}|${title}|${publishedAt}`)}`
  } catch {
    return stableId(`${guid}|${audioUrl}|${title}|${publishedAt}`)
  }
}

export async function importRssLocally(feedUrl: string, limit = 100): Promise<CatalogSource> {
  const response = await fetch(feedUrl)
  if (!response.ok) throw new Error(`Impossible de charger le flux RSS (${response.status})`)

  const xml = await response.text()
  const parser = new DOMParser()
  const doc = parser.parseFromString(xml, 'text/xml')

  const channel = doc.querySelector('channel')
  if (!channel) throw new Error('Format RSS invalide')

  const title = getTextContent(channel, 'title') || 'Sans titre'
  const description = getTextContent(channel, 'description') || getTextContent(channel, 'itunes\\:summary') || ''
  const artworkUrl = imageUrl(channel)
    || channel.querySelector('image > url')?.textContent?.trim()
    || ''

  const items = [...channel.querySelectorAll('item')].slice(0, limit)
  const episodes: Episode[] = items.map((item) => {
    const guid = getTextContent(item, 'guid') || getTextContent(item, 'link')
    const epTitle = getTextContent(item, 'title')
    const epDesc = getTextContent(item, 'description') || ''
    const pubDate = parseRssDate(getTextContent(item, 'pubDate'))
    const audioUrl = item.querySelector('enclosure')?.getAttribute('url') || ''
    const duration = getDuration(item, epDesc)
    const epArtwork = imageUrl(item) || artworkUrl

    return {
      id: buildEpisodeId(feedUrl, guid, audioUrl, epTitle, pubDate),
      title: epTitle || 'Sans titre',
      description: epDesc.slice(0, 5000),
      publishedAt: pubDate,
      audioUrl,
      duration,
      artworkUrl: epArtwork,
    }
  })

  return {
    id: `rss:${feedUrl}`,
    kind: 'podcast' as const,
    title,
    description,
    artworkUrl,
    feedUrl,
    episodes,
  }
}
