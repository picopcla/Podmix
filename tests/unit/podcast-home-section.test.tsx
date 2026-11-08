// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'

const testCatalog = vi.hoisted(() => ({ value: [] as unknown[] }))

vi.mock('../../src/storage.ts', () => ({
  loadCatalog: async () => testCatalog.value,
  loadOfflineEpisodes: () => [],
  loadSession: async () => undefined,
  saveCatalog: async () => true,
  saveOfflineEpisodes: () => undefined,
  saveSession: async () => undefined,
}))

vi.mock('../../src/safeStorage.ts', () => ({
  getStorageFailures: () => [],
  isQuotaError: () => false,
  reportStorageFailure: () => undefined,
  reportStorageSuccess: () => undefined,
  safeSetItem: () => true,
  useStorageFailures: () => [],
}))

import App from '../../src/App'

globalThis.IS_REACT_ACT_ENVIRONMENT = true

function podcast(id: number) {
  return {
    id: `podcast-${id}`,
    kind: 'podcast' as const,
    title: `Podcast ${id}`,
    description: `Description ${id}`,
    artworkUrl: '',
    episodes: [],
  }
}

describe('accueil Podcasts', () => {
  let root: ReturnType<typeof createRoot> | undefined
  let container: HTMLDivElement | undefined
  const onlineDescriptor = Object.getOwnPropertyDescriptor(navigator, 'onLine')

  afterEach(async () => {
    await act(async () => root?.unmount())
    container?.remove()
    root = undefined
    container = undefined
    testCatalog.value = []
    localStorage.clear()
    if (onlineDescriptor) Object.defineProperty(navigator, 'onLine', onlineDescriptor)
  })

  it('rend toutes les sources podcast, y compris au-delà de quatre', async () => {
    testCatalog.value = [1, 2, 3, 4, 5].map(podcast)
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
    container = document.createElement('div')
    document.body.append(container)
    root = createRoot(container)

    await act(async () => {
      root!.render(<App />)
      await new Promise((resolve) => setTimeout(resolve, 20))
    })

    await vi.waitFor(() => expect(container!.querySelectorAll('.home-podcasts .home-source-card h2')).toHaveLength(5))
    const titles = [...container.querySelectorAll('.home-podcasts .home-source-card h2')]
      .map((element) => element.textContent)
    expect(titles).toEqual(['Podcast 1', 'Podcast 2', 'Podcast 3', 'Podcast 4', 'Podcast 5'])
  })
})
