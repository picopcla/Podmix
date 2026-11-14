import { expect, test } from '@playwright/test'

async function setup(page: any, scheme = 'http') {
  await page.route('**/podmix/update.json*', (r: any) => r.fulfill({json:{versionCode:1, versionName:'test',notes:[]}}))
  await page.route('**/podmix-api/**', (r: any) => r.fulfill({status:503,json:{message:'isolated'}}))
  const sessions: any[] = []
  await page.route('**/v1/cast/sessions', (r: any) => {
    const body = r.request().postDataJSON()
    sessions.push(body)
    return r.fulfill({status:201,json:{id:'session-'+body.title, relayUrl:'http://192.168.1.24:8099/v1/cast/'+encodeURIComponent(body.title),expiresAt:'2099-01-01T00:00:00Z',startSeconds:0}})
  })
  await page.addInitScript(({scheme}: any) => {
    const state:any={mediaId:'',title:'',artist:'',playing:false,playbackState:3,positionSeconds:0,durationSeconds:0,queueIndex:0,queueSize:1,hasNext:false,hasPrevious:false,stateSequence:1}
    const qa:any={state,commands:[],calls:[],listeners:[],hang:false}
    Object.assign(window,{__boseQA:qa,androidBridge:{}})
    const methods=['appendLibraryChunk','beginLibrarySync','boseDiscover','boseGetState','boseKey','bosePlay','boseSetVolume','cacheArtwork','cast','commitLibrarySync','connectCastDevice','disconnectCast','discoverCastDevices','getApkStatus','getAppInfo','getCastState','getCastVolume','getCompletedEpisodeIds','getFavorites','getPendingPlaybackTarget','getState','getStorage','getVolume','listDownloads','load','next','pause','play','previous','setQueue','setRepeatMode','setVolume','startWebTimestampWorker','syncFavorites','syncResume','syncSubscriptions','removeListener'].map(name=>({name,rtype:'promise'}))
    methods.push({name:'addListener',rtype:'callback'})
    Object.assign(window,{Capacitor:{PluginHeaders:[{name:'PodmixPlayer',methods},{name:'App',methods:[{name:'addListener',rtype:'callback'},{name:'removeListener',rtype:'promise'}]}],
      nativeCallback:(plugin:string,method:string,opts:any,cb:any)=>{if(plugin==='PodmixPlayer'&&method==='addListener')qa.listeners.push(cb);return 'qa-listener'},
      nativePromise:(_plugin:string,method:string,opts:any)=>{
        qa.calls.push({method,opts})
        if(method==='getState'&&qa.hang)return new Promise(()=>{})
        if(method==='setQueue'){Object.assign(state,{mediaId:opts.items[0].id,title:opts.items[0].title,artist:opts.items[0].artist,playing:opts.autoplay,stateSequence:state.stateSequence+1});return Promise.resolve({...state})}
        if(['getState','setRepeatMode','pause','play'].includes(method))return Promise.resolve({...state})
        if(method==='getCastState')return Promise.resolve({connected:false})
        if(method==='discoverCastDevices')return Promise.resolve({devices:[]})
        if(method==='boseDiscover')return Promise.resolve({devices:[{id:'192.168.1.50',ip:'192.168.1.50',name:'Bose QA',type:'SoundTouch 30'}]})
        if(method==='boseGetState')return Promise.resolve({playing:true,positionSeconds:3,volume:25,name:'Bose QA',location:qa.commands.at(-1)?.url||'',title:qa.commands.at(-1)?.title||''})
        if(method==='bosePlay'){qa.commands.push(opts);if(qa.failNext){qa.failNext=false;return Promise.reject(new Error('Ancien flux toujours actif'))}return Promise.resolve({ok:true})}
        if(method==='getAppInfo')return Promise.resolve({versionCode:10235,versionName:'1.0.235-ng',lastUpdateTime:0})
        if(method==='getPendingPlaybackTarget')return Promise.resolve({sourceId:'',episodeId:''})
        if(method==='getFavorites')return Promise.resolve({ids:[],initialized:false})
        if(method==='getCompletedEpisodeIds')return Promise.resolve({ids:[]})
        if(method==='listDownloads')return Promise.resolve({downloads:[]})
        if(method==='getStorage')return Promise.resolve({availableBytes:1e10,totalBytes:1e10,downloadedBytes:0})
        if(method==='getVolume')return Promise.resolve({volume:1})
        return Promise.resolve({ok:true,count:0})
      }}})
    localStorage.setItem('podmix-catalog-v1',JSON.stringify(['A','B'].map(id=>({id:'radio-'+id,kind:'radio',title:'Station '+id,description:'',artworkUrl:'',episodes:[],streamUrl:scheme+'://origin.example/'+id+'.mp3'}))))
  },{scheme})
  await page.goto('/')
  await expect(page.locator('.radio-art')).toHaveCount(2)
  return sessions
}

for(const scheme of ['http','https']) test(`radio ${scheme} must use HyperV relay, never source URL`,async({page})=>{
  await setup(page,scheme)
  await page.locator('.media-card').filter({hasText:'Station A'}).locator('.radio-art').click()
  await page.locator('.mini-output').click()
  await page.locator('.output-device').filter({hasText:'Bose QA'}).click()
  await expect.poll(()=>page.evaluate(()=>(window as any).__boseQA.commands.length)).toBeGreaterThan(0)
  const commands=await page.evaluate(()=>(window as any).__boseQA.commands)
  expect(commands).toEqual([expect.objectContaining({title:'Station A',url:expect.stringMatching(/^http:\/\/192\.168\.1\.24:8099\/v1\/cast\//)})])
})

test('Bose picker remains usable after native getState never resolves',async({page})=>{
  await setup(page)
  await page.locator('.media-card').filter({hasText:'Station A'}).locator('.radio-art').click()
  await page.locator('.mini-output').click()
  await expect(page.locator('.output-device').filter({hasText:'Bose QA'})).toBeVisible()
  await page.evaluate(()=>{(window as any).__boseQA.hang=true})
  await page.locator('.output-device').filter({hasText:'Bose QA'}).click()
  await expect(page.locator('.output-device').filter({hasText:'Bose QA'})).toBeEnabled({timeout:18000})
  await expect(page.locator('.output-message')).toContainText('Le lecteur ne répond pas')
  await page.evaluate(()=>{(window as any).__boseQA.hang=false})
  await page.locator('.output-device').filter({hasText:'Bose QA'}).click()
  await expect(page.locator('.output-modal')).not.toBeVisible()
  await page.locator('.mini-player-global .mini-meta').click()
  await page.locator('.full-player-output').click()
  await expect(page.locator('.output-modal')).toBeVisible()
  await expect(page.locator('.output-device').filter({hasText:'Bose QA'})).toBeEnabled()
  await page.screenshot({path:'test-results/bose-retry-picker-fixed.png'})
})

test('late radio-A native event must not pair A title with radio-B URL',async({page})=>{
  const sessions = await setup(page)
  for(const station of ['A','B']){
    await page.locator('.media-card').filter({hasText:'Station '+station}).locator('.radio-art').click()
    await expect(page.locator('.mini-player-global')).toContainText('Station '+station)
  }
  await page.evaluate(()=>{const q=(window as any).__boseQA;const stale={...q.state,mediaId:'radio-A',title:'Station A',stateSequence:100};q.state=stale;for(const cb of q.listeners)cb(stale)})
  await page.locator('.mini-output').click()
  await page.locator('.output-device').filter({hasText:'Bose QA'}).click()
  await expect.poll(()=>page.evaluate(()=>(window as any).__boseQA.commands.length)).toBeGreaterThan(0)
  const commands=await page.evaluate(()=>(window as any).__boseQA.commands)
  expect(sessions.length).toBeGreaterThan(0)
  for(const request of sessions)expect(request.url).toBe('http://origin.example/'+request.title.replace('Station ','')+'.mp3')
  for(const c of commands)expect(c.url).toContain('/v1/cast/'+encodeURIComponent(c.title))
})


test('failed transfer keeps picker usable and next attempt creates a new relay session',async({page})=>{
  const sessions = await setup(page,'https')
  await page.locator('.media-card').filter({hasText:'Station A'}).locator('.radio-art').click()
  await page.locator('.mini-output').click()
  await page.evaluate(()=>{(window as any).__boseQA.failNext=true})
  const bose=page.locator('.output-device').filter({hasText:'Bose QA'})
  await bose.click()
  await expect(page.locator('.output-message')).toContainText('Ancien flux toujours actif')
  await expect(bose).toBeEnabled()
  await bose.click()
  await expect(page.locator('.output-modal')).not.toBeVisible()
  expect(sessions).toHaveLength(2)
  expect(sessions.every(x=>x.url==='https://origin.example/A.mp3')).toBe(true)
})

test('actual live HTTPS plus CGNAT payload is refused before any Bose command',async({page})=>{
  await setup(page,'https')
  await page.route('**/v1/cast/sessions',(r)=>r.fulfill({status:201,json:{id:'07ebd766dc4b4b4ca31b7452b70fb555',relayUrl:'https://podmix.mb4.fr/podmix-cast/v1/cast/07ebd766dc4b4b4ca31b7452b70fb555',lanRelayUrl:'http://100.116.28.23:8099/v1/cast/07ebd766dc4b4b4ca31b7452b70fb555',expiresAt:'2026-11-15T06:56:36Z',startSeconds:0}}))
  await page.locator('.media-card').filter({hasText:'Station A'}).locator('.radio-art').click()
  await page.locator('.mini-output').click()
  const bose=page.locator('.output-device').filter({hasText:'Bose QA'})
  await bose.click()
  await expect(page.locator('.output-message')).toContainText('Aucun relais HTTP compatible SoundTouch')
  await expect(bose).toBeEnabled()
  expect(await page.evaluate(()=>(window as any).__boseQA.commands)).toEqual([])
})
