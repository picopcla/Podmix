// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { protectedEpisodeIds, retainSourceEpisodes, retainReferencedFeedEpisodes } from '../../src/catalogRetention'
import { mergeCatalogSource } from '../../src/catalogMerge'
import type { CatalogSource, Episode } from '../../src/domain'
const episode=(id:string,date='2026-11-12'):Episode=>({id,title:id,description:'QA',publishedAt:date,duration:'45:00',audioUrl:'https://qa.invalid/'+id,artworkUrl:''})
const source=(episodes:Episode[]):CatalogSource=>({id:'s',kind:'podcast',title:'QA',description:'QA',artworkUrl:'',episodes})
const context=()=>({favoriteTrackIds:[] as string[],history:[] as {id:string;url?:string}[],listeningSessions:[] as {id:string}[],offlineEpisodes:[] as {id:string;remoteUrl?:string}[],completedEpisodeIds:[] as string[],nativeReferencesReady:true})
const old=()=>episode('old','2020-01-01')
const crowded=(e=old())=>source([e,...Array.from({length:100},(_,i)=>episode('recent'+i))])
describe('catalogue retention',()=>{
 for(const kind of ['favorite','history','session','download','completed','manual'] as const) {
  it(`keeps referenced episode beyond limit: ${kind}`,()=>{
   const e=old(),ctx=context()
   if(kind==='favorite')ctx.favoriteTrackIds=[e.id+'::track::1::0']
   if(kind==='history')ctx.history=[{id:e.id}]
   if(kind==='session')ctx.listeningSessions=[{id:e.id}]
   if(kind==='download')ctx.offlineEpisodes=[{id:e.id}]
   if(kind==='completed')ctx.completedEpisodeIds=[e.id]
   if(kind==='manual')e.tracks=[{id:1,time:12,artist:'QA',title:'Keep manual annotation',confidence:1,source:'manual',evidence:['Keep evidence']}]
   const s=crowded(e),before=JSON.stringify(s),ids=protectedEpisodeIds([s],ctx)
   const result=retainSourceEpisodes(s,100,ids)
   expect(result.episodes).toContain(e)
   expect(result.episodes).toHaveLength(101)
   expect(JSON.stringify(s)).toBe(before)
  })
 }
 it('retains all old episodes while native references are unknown',()=>{
  const s=crowded(),ctx={...context(),nativeReferencesReady:false}
  expect(retainSourceEpisodes(s,100,protectedEpisodeIds([s],ctx))).toBe(s)
 })
 it('limits plain unreferenced feed entries and preserves source order',()=>{
  const s=crowded(),result=retainSourceEpisodes(s,100,protectedEpisodeIds([s],context()))
  expect(result.episodes).toEqual(s.episodes.slice(1))
 })
 for(const kind of ['favorite','history','download'] as const) {
  it(`retains absent feed episode with ${kind}`,()=>{
   const e=old(),previous=source([e]),fresh=source([episode('new')]),ctx=context()
   if(kind==='favorite')ctx.favoriteTrackIds=[e.id+'::track::1::0']
   if(kind==='history')ctx.history=[{id:e.id}]
   if(kind==='download')ctx.offlineEpisodes=[{id:e.id}]
   const merged=retainReferencedFeedEpisodes(previous,mergeCatalogSource(previous,fresh),protectedEpisodeIds([previous],ctx))
   expect(merged.episodes).toContain(e)
  })
 }
 it('preserves stable ID and manual track order when feed ID changes',()=>{
  const e={...old(),tracks:[{id:1,time:22,artist:'QA',title:'Favorite',confidence:1,source:'manual' as const,evidence:['QA note']}]}
  const previous=source([e]),fresh=source([{...episode('new-id'),audioUrl:e.audioUrl,tracks:[]}])
  const ctx={...context(),favoriteTrackIds:['old::track::1::0']}
  const merged=retainReferencedFeedEpisodes(previous,mergeCatalogSource(previous,fresh),protectedEpisodeIds([previous],ctx))
  expect(merged.episodes).toHaveLength(1)
  expect(merged.episodes[0].id).toBe('old')
  expect(merged.episodes[0].tracks).toBe(e.tracks)
 })
 it('protects URL-based legacy history references',()=>{
  const s=crowded(),ctx={...context(),history:[{id:'legacy',url:s.episodes[0].audioUrl}]}
  expect(retainSourceEpisodes(s,100,protectedEpisodeIds([s],ctx)).episodes).toHaveLength(101)
 })
 it('does not collapse protected ambiguous legacy episodes',()=>{
  const a=old(),b={...old(),title:'Other episode',audioUrl:'https://qa.invalid/other'},previous=source([a,b])
  const fresh=source([episode('new')]),ctx={...context(),history:[{id:'old'}]}
  const merged=retainReferencedFeedEpisodes(previous,mergeCatalogSource(previous,fresh),protectedEpisodeIds([previous],ctx))
  expect(merged.episodes).toContain(a);expect(merged.episodes).toContain(b)
 })
})
