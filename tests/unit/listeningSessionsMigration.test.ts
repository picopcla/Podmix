// @vitest-environment jsdom
import { beforeEach, expect, it } from 'vitest'
import { loadListeningSessions, saveListeningSessions } from '../../src/history'
import { createDurableRepository, type DurableStore } from '../../src/durableStorage'
const history = JSON.stringify([{id:'ep-x',title:'Session synthétique',artist:'Podcast synthétique',url:'/x.wav',position:42,duration:600,playedAt:'2026-08-12T10:00:00.000Z'}])
beforeEach(() => localStorage.clear())
it('imports absent legacy sessions once but respects an explicit empty list', () => {
  localStorage.setItem('podmix-history-v1', history)
  expect(loadListeningSessions()).toHaveLength(1)
  saveListeningSessions([])
  expect(loadListeningSessions()).toEqual([])
  expect(localStorage.getItem('podmix-history-v1')).toBe(history)
})
it('distinguishes a new durable seed from an authoritative saved empty list across restart', async () => {
  const values = new Map<string, unknown>()
  const store: DurableStore = {read: async key => values.get(key), write: async (key,value) => {values.set(key,value)}}
  localStorage.setItem('podmix-history-v1', history)
  const first = createDurableRepository(store)
  await first.initialize()
  expect(first.isNewRecord('podmix-listening-sessions-v1')).toBe(true)
  expect(await first.save('podmix-listening-sessions-v1','[]')).toBe(true)
  expect(first.isNewRecord('podmix-listening-sessions-v1')).toBe(false)
  const next = createDurableRepository(store)
  await next.initialize()
  expect(next.isNewRecord('podmix-listening-sessions-v1')).toBe(false)
  expect(next.read('podmix-listening-sessions-v1')).toBe('[]')
  expect(localStorage.getItem('podmix-history-v1')).toBe(history)
})
