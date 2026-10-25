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
  ])('%s', (_label, input, expected) => {
    expect(decodeCredit(input)).toBe(expected)
  })
})
