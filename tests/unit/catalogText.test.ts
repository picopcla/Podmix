// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import type { CatalogSource } from '../../src/domain'
import { loadCatalog, saveCatalog } from '../../src/storage'
import { importRssFeed } from '../../src/api'
import { mergeCatalogSource } from '../../src/catalogMerge'

const fixture = (): CatalogSource => ({
  id: 'src-ent', kind: 'podcast', title: 'Above &amp;amp;amp;amp; Beyond',
  description: 'Keep <b>description</b> &amp;', artworkUrl: 'https://example.test/a?x=1&amp;y=2',
  feedUrl: 'https://example.test/ab.xml?a=1&amp;b=2',
  unseenEpisodeIds: ['ep-ent'],
  episodes: [{id: 'ep-ent', title: 'Find Your Harmony &amp;amp;amp;amp; Friends',
    audioUrl: 'https://example.test/audio?a=1&amp;b=2', description: 'Original &lt;b&gt;text&lt;/b&gt;',
    duration: '0:30', publishedAt: '2026-11-13T12:00:00Z', artworkUrl: '',
    tracks: [{id: 3,time: 19,artist:'A &amp;amp; B',title:'C &#38; D',confidence:100,source:'manual',timestampSource:'manual'}],
    analysis: {jobId:'job-keep',status:'completed',stage:'done',progress:100,updatedAt:'2026-11-13T12:00:00Z'},
  }],
})
function verify(before: CatalogSource, after: CatalogSource) {
  expect(after.title).toBe('Above & Beyond')
  expect(after.episodes[0].title).toBe('Find Your Harmony & Friends')
  expect(after.episodes[0].tracks?.[0].artist).toBe('A & B')
  expect(after.episodes[0].tracks?.[0].title).toBe('C & D')
  const textOnly = structuredClone(before)
  textOnly.title = after.title
  textOnly.episodes[0].title = after.episodes[0].title
  textOnly.episodes[0].tracks![0].artist = 'A & B'
  textOnly.episodes[0].tracks![0].title = 'C & D'
  expect(after).toEqual(textOnly)
}
beforeEach(() => { localStorage.clear(); vi.unstubAllGlobals() })
it('loads and persists repaired legacy catalogue text without losing metadata', async () => {
  const before = fixture(), original = JSON.stringify([before])
  localStorage.setItem('podmix-catalog-v1', original)
  const [after] = loadCatalog()
  verify(before, after)
  expect(localStorage.getItem('podmix-catalog-v1')).toBe(original)
  expect(await saveCatalog([after])).toBe(true)
  verify(before, loadCatalog()[0])
})
it('normalizes RSS API imports before they enter React state', async () => {
  const before = fixture()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(before), {status: 200})))
  verify(before, await importRssFeed(before.feedUrl!))
})
it('normalizes refreshed text while preserving previous manual tracks and analysis', () => {
  const before = fixture(), fresh = structuredClone(before)
  fresh.id = 'fresh-feed-id'
  fresh.episodes[0].tracks = undefined
  fresh.episodes[0].analysis = undefined
  verify(before, mergeCatalogSource(before, fresh))
  expect(before.title).toContain('&amp;')
})
