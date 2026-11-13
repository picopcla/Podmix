// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { decodeCredit } from '../../src/credits'

describe('decodeCredit', () => {
  it.each([
    ['entité simple', 'Kayosa &amp; Tolland', 'Kayosa & Tolland'],
    ['entité imbriquée, une couche seulement', '&amp;lt;', '&lt;'],
    ['valeur déjà correcte', 'Kayosa & Tolland', 'Kayosa & Tolland'],
    ['esperluette littérale seule', '&', '&'],
    ['chaîne vide', '', ''],
    ['référence incomplète conservée', 'Incomplete &amp stays', 'Incomplete &amp stays'],
    ['texte HTML conservé', '<b>Original</b> &amp; &lt;i&gt;literal&lt;/i&gt;', '<b>Original</b> & <i>literal</i>'],
  ])('%s', (_label, input, expected) => {
    expect(decodeCredit(input)).toBe(expected)
  })
})
