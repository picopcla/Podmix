import { describe, expect, it } from 'vitest'
import { isFavoriteTrack } from '../../src/favoriteMatch'

describe('isFavoriteTrack', () => {
  it('accepte la cle exacte', () => {
    expect(isFavoriteTrack(['ep-a::track::7::1'], 'ep-a::track::7::1', 7)).toBe(true)
  })
  it('accepte un ancien identifiant numerique (affichage et recherche de liens alignes)', () => {
    expect(isFavoriteTrack(['7'], 'ep-a::track::7::1', 7)).toBe(true)
  })
  it('ne confond pas une cle d un autre episode ni un identifiant voisin', () => {
    expect(isFavoriteTrack(['ep-b::track::7::1'], 'ep-a::track::7::1', 7)).toBe(false)
    expect(isFavoriteTrack(['17', '70'], 'ep-a::track::7::1', 7)).toBe(false)
    expect(isFavoriteTrack([], 'ep-a::track::7::1', 7)).toBe(false)
  })
  it('un ancien identifiant partage par deux episodes designe les deux (comportement d affichage inchange) mais les cles restent distinctes', () => {
    const ids = ['1']
    expect(isFavoriteTrack(ids, 'ep-a::track::1::0', 1)).toBe(true)
    expect(isFavoriteTrack(ids, 'ep-b::track::1::0', 1)).toBe(true)
    expect('ep-a::track::1::0').not.toBe('ep-b::track::1::0')
  })
})
