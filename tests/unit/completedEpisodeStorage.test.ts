// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { COMPLETED_EPISODES_KEY as KEY } from '../../src/history'
import { createCompletedEpisodesRepository, type CompletedEpisodesStore } from '../../src/completedEpisodeStorage'
import { getStorageFailures, reportStorageSuccess } from '../../src/safeStorage'
class Store implements CompletedEpisodesStore {
 value: unknown = undefined
 failRead = false
 failWrite = false
 corrupt = false
 writes: string[][] = []
 async read() { if (this.failRead) throw Error('read failed'); return structuredClone(this.value) }
 async write(ids: string[]) { if(this.failWrite)throw new DOMException('quota','QuotaExceededError');this.writes.push([...ids]); this.value=this.corrupt ? ['corrupt'] : [...ids] }
}
beforeEach(()=>{localStorage.clear();reportStorageSuccess(KEY);vi.restoreAllMocks()})
describe('completed episode persistence',()=>{
 it('migrates normalized legacy while preserving original bytes',async()=>{
  const old='["OLD","OLD",4,""]';localStorage.setItem(KEY,old);const s=new Store();
  expect(await createCompletedEpisodesRepository(s).load()).toEqual({ids:['OLD'],ready:true});expect(s.value).toEqual(['OLD']);expect(localStorage.getItem(KEY)).toBe(old)
 })
 it('merges legacy additions after rollback with old IndexedDB data',async()=>{
  localStorage.setItem(KEY,'["FROM-OLDER-APK"]');const s=new Store();s.value=['IDB-ONLY'];
  expect((await createCompletedEpisodesRepository(s).load()).ids).toEqual(['IDB-ONLY','FROM-OLDER-APK'])
 })
 it('persists when localStorage quota refuses compatibility copy',async()=>{
  localStorage.setItem(KEY,'["OLD"]');const s=new Store();const r=createCompletedEpisodesRepository(s);await r.load();
  vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new DOMException('quota','QuotaExceededError')});
  expect(await r.save(['NEW','OLD'])).toBe(true);expect(s.value).toEqual(['NEW','OLD']);expect(localStorage.getItem(KEY)).toBe('["OLD"]');expect(getStorageFailures()).toEqual([])
 })
 it('serializes racing saves and retains IDs absent from a stale caller',async()=>{
  const s=new Store();const r=createCompletedEpisodesRepository(s);
  const a=r.save(['FIRST']);const b=r.save(['SECOND']);expect(await a).toBe(true);expect(await b).toBe(true);expect(s.value).toEqual(['SECOND','FIRST'])
 })
 it('does not overwrite unreadable IndexedDB with empty legacy',async()=>{
  const s=new Store();s.value=['KEEP'];s.failRead=true;const r=createCompletedEpisodesRepository(s);
  expect(await r.load()).toEqual({ids:[],ready:false});expect(await r.save(['NEW'])).toBe(false);expect(s.writes).toEqual([]);expect(s.value).toEqual(['KEEP']);expect(getStorageFailures()).toEqual(['épisodes terminés']);
  s.failRead=false;expect(await r.save(['NEW'])).toBe(true);expect(s.value).toEqual(['NEW','KEEP']);expect(getStorageFailures()).toEqual([])
 })
 it('does not overwrite malformed IndexedDB data',async()=>{
  const s=new Store();s.value={ids:['KEEP']};const r=createCompletedEpisodesRepository(s);expect((await r.load()).ready).toBe(false);expect(await r.save(['NEW'])).toBe(false);expect(s.writes).toEqual([])
 })
 it('signals failed transaction without touching legacy or favorites',async()=>{
  localStorage.setItem(KEY,'["OLD"]');localStorage.setItem('podmix-track-favorites-v1','["FAV"]');const s=new Store();s.failWrite=true;const r=createCompletedEpisodesRepository(s);
  expect((await r.load()).ready).toBe(false);expect(await r.save(['NEW'])).toBe(false);expect(localStorage.getItem(KEY)).toBe('["OLD"]');expect(localStorage.getItem('podmix-track-favorites-v1')).toBe('["FAV"]');expect(getStorageFailures()).toEqual(['épisodes terminés'])
 })
 it('does not claim success or change legacy when readback is wrong',async()=>{
  localStorage.setItem(KEY,'["OLD"]');const s=new Store();s.corrupt=true;const r=createCompletedEpisodesRepository(s);expect(await r.save(['NEW'])).toBe(false);expect(localStorage.getItem(KEY)).toBe('["OLD"]');expect(getStorageFailures()).toEqual(['épisodes terminés'])
 })
 it('resumes saved state in a newly created repository',async()=>{
  const s=new Store();const r=createCompletedEpisodesRepository(s);await r.save(['ONE','TWO']);localStorage.setItem(KEY,'["OLD"]');expect((await createCompletedEpisodesRepository(s).load()).ids).toEqual(['ONE','TWO','OLD'])
 })
})
