import assert from 'node:assert/strict'
import {
  MAX_ENTITY_DECODE_PASSES,
  decodeTextEntities,
  normalizeCatalogSourceText,
} from '../../src/text.ts'

assert.equal(decodeTextEntities('Mark Sherry, BiXX &amp; Sue McLaren'), 'Mark Sherry, BiXX & Sue McLaren')
assert.equal(decodeTextEntities('David Forbes &amp;amp; Lostly'), 'David Forbes & Lostly')
assert.equal(decodeTextEntities('Kyros &#38; KRPDS &#x26; Friends'), 'Kyros & KRPDS & Friends')
assert.equal(decodeTextEntities('Caf&eacute; d&apos;Été — déjà & vivant'), "Café d'Été — déjà & vivant")
assert.equal(decodeTextEntities('Rock &amp et &#38 sans point-virgule'), 'Rock &amp et &#38 sans point-virgule')
assert.equal(decodeTextEntities('<b>brut</b> &lt;i&gt;encodé&lt;/i&gt;'), '<b>brut</b> <i>encodé</i>')
assert.equal(
  decodeTextEntities('&amp;amp;amp;amp;amp;'),
  '&amp;',
  `le décodage doit s'arrêter après ${MAX_ENTITY_DECODE_PASSES} passes`,
)

const source = {
  id: 'source&amp;id',
  kind: 'podcast',
  title: 'Above &amp;amp; Beyond',
  description: 'Description &lt;b&gt;conservée&lt;/b&gt;',
  artworkUrl: 'https://example.test/a&amp;b.jpg',
  feedUrl: 'https://example.test/feed?a=1&amp;b=2',
  episodes: [{
    id: 'episode&amp;id',
    title: 'Épisode &#38; final',
    description: 'Texte &lt;em&gt;source&lt;/em&gt;',
    publishedAt: '2026-10-13T12:43:00Z',
    duration: '3600',
    audioUrl: 'https://example.test/audio?a=1&amp;b=2',
    artworkUrl: 'https://example.test/cover&amp;.jpg',
    tracks: [{ id: 1, time: 0, artist: 'BiXX &amp; Sue McLaren', title: 'Believe &#x26; Receive', confidence: 100, source: 'manual' }],
  }],
}
const normalized = normalizeCatalogSourceText(source)
assert.equal(normalized.title, 'Above & Beyond')
assert.equal(normalized.episodes[0].title, 'Épisode & final')
assert.equal(normalized.episodes[0].tracks[0].artist, 'BiXX & Sue McLaren')
assert.equal(normalized.episodes[0].tracks[0].title, 'Believe & Receive')
assert.equal(normalized.id, source.id)
assert.equal(normalized.feedUrl, source.feedUrl)
assert.equal(normalized.episodes[0].audioUrl, source.episodes[0].audioUrl)
assert.equal(normalized.description, source.description)

console.log('PASS: entités nommées/numériques, double encodage, sûreté HTML, données existantes et limite de passes')
