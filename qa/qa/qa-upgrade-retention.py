# Isolated emulator QA, never user data. Run 'seed' on baseline 230, 'verify' on candidate 231.
import base64, hashlib, json, os, subprocess, sys, threading, time, urllib.request
MODE=sys.argv[1]
OUT='/tmp/qa-out'
os.makedirs(OUT,exist_ok=True)
def adb(*args):
    r=subprocess.run(['adb',*args],capture_output=True,text=True,timeout=60)
    if r.returncode: raise RuntimeError(r.stderr)
    return r.stdout.strip()
def log(s): print('UPGRADE_QA '+s,flush=True)
pid=adb('shell','pidof','com.podmix.next.ng')
if not pid: raise RuntimeError('app not running')
adb('forward','tcp:9222','localabstract:webview_devtools_remote_'+pid)
page=None
for _ in range(30):
    try:
        targets=json.load(urllib.request.urlopen('http://127.0.0.1:9222/json/list',timeout=3))
        page=next(t for t in targets if t.get('type')=='page' and 'localhost' in t.get('url',''));break
    except Exception:time.sleep(1)
if not page:raise RuntimeError('no WebView')
import websocket
ws=websocket.create_connection(page['webSocketDebuggerUrl'],timeout=30)
ws.settimeout(60); seq=0; pending={};lock=threading.Lock()
def reader():
    while True:
        try:m=json.loads(ws.recv())
        except Exception:return
        if 'id' in m:
            with lock:pending[m['id']]=m
threading.Thread(target=reader,daemon=True).start()
def send(method,params=None):
    global seq
    seq+=1;i=seq;ws.send(json.dumps({'id':i,'method':method,'params':params or {}}));end=time.time()+60
    while time.time()<end:
        with lock:
            if i in pending:return pending.pop(i)
        time.sleep(.05)
    raise RuntimeError('CDP timeout '+method)
def ev(script):
    r=send('Runtime.evaluate',{'expression':script,'awaitPromise':True,'returnByValue':True})
    if 'exceptionDetails' in r.get('result',{}):raise RuntimeError(str(r))
    return r.get('result',{}).get('result',{}).get('value')
def native(method,args=None):
    return ev('Capacitor.nativePromise("PodmixPlayer",'+json.dumps(method)+','+json.dumps(args or {})+')')
def check(ok,name):
    log(('PASS ' if ok else 'FAIL ')+name)
    if not ok:raise AssertionError(name)
def shot(name):
    data=send('Page.captureScreenshot',{'format':'jpeg','quality':55}).get('result',{}).get('data')
    check(bool(data),'capture '+name)
    open(OUT+'/'+name+'.jpg','wb').write(base64.b64decode(data))
    for i in range(0,len(data),1900):print('B64IMG '+name+' '+data[i:i+1900],flush=True)
send('Runtime.enable');send('Page.enable')
for _ in range(30):
    if ev('!!document.querySelector("button[aria-label]")'):break
    time.sleep(1)
info=native('getAppInfo');log('APPINFO '+json.dumps(info))
check(info.get('versionCode')==(10230 if MODE=='seed' else 10231),'native version '+MODE)
if MODE=='seed':
    # A real generated WAV download, not fabricated metadata or a placeholder file.
    native('download',{'id':'qa-download','url':'http://localhost:8099/qa.wav','title':'QA DOWNLOAD'})
    state=None
    for _ in range(60):
        state=native('getDownload',{'id':'qa-download'})
        if state.get('status')=='completed':break
        if state.get('status')=='failed':raise RuntimeError(str(state))
        time.sleep(1)
    check(state.get('status')=='completed','baseline real download completed')
    def episode(id,date='2026-11-12'):
        return dict(id=id,title=id,description='QA synthetic only',publishedAt=date,duration='45:00',audioUrl='http://localhost:8099/'+id+'.wav',artworkUrl='')
    old=[episode(id,'2020-01-01') for id in ['qa-favorite','qa-position','qa-download','qa-annotation']]
    track=dict(id=1,time=12,artist='QA ARTIST',title='QA FAVORITE TRACK',confidence=1,source='manual',evidence=['QA annotation retained'])
    old[0]['tracks']=[track];old[3]['tracks']=[dict(track,id=2,title='QA MANUAL NOTE')]
    fresh=[episode('qa-recent-'+str(i)) for i in range(100)]
    source=dict(id='qa-source',kind='podcast',title='QA PRESERVATION',description='Synthetic test only',artworkUrl='',feedUrl='https://qa.invalid/feed',episodes=old+fresh)
    history=[dict(id='qa-position',title='qa-position',artist=source['title'],url=old[1]['audioUrl'],position=1234,duration=2700,playedAt='2026-11-13T07:00:00Z')]
    offline=[dict(id='qa-download',title='qa-download',artist=source['title'],remoteUrl=old[2]['audioUrl'],localUri=state.get('localUri'),status='completed',bytesDownloaded=state.get('bytesDownloaded'),totalBytes=state.get('totalBytes'),addedAt='2026-11-12T12:00:00Z')]
    session=[dict(history[0],sessionId='qa-session',startedAt=history[0]['playedAt'],updatedAt=history[0]['playedAt'])]
    settings=json.loads(ev('localStorage.getItem("podmix-settings-v1")') or '{}')
    settings.update(automaticAnalysis=False,continuousPlayback=True,musicBrainzValidation=False,mobileDataSaver=False,maxPodcastEpisodes=100,maxShowEpisodes=100,maxDjEpisodes=100)
    seeds={'podmix-api-url':'http://localhost:8099','podmix-catalog-v1':json.dumps([source]),'podmix-history-v1':json.dumps(history),'podmix-offline-v1':json.dumps(offline),'podmix-track-favorites-v1':json.dumps(['qa-favorite::track::1::0']),'podmix-listening-sessions-v1':json.dumps(session),'podmix-completed-episodes-v1':json.dumps(['qa-position']),'podmix-settings-v1':json.dumps(settings),'podmix-last-feed-refresh':'0'}
    # Force-stop JS before it can apply old 230's unsafe pruning to the oversized seed.
    # The write is one synchronous turn; React state is not changed or reloaded on baseline.
    native('syncFavorites',{'ids':['qa-favorite::track::1::0']})
    ev('(()=>{const seeds='+json.dumps(seeds)+';for(const [k,v] of Object.entries(seeds))localStorage.setItem(k,v);return "seeded"})()')
    actual=ev('Object.fromEntries(Object.keys(localStorage).map(k=>[k,localStorage.getItem(k)]))')
    open(OUT+'/baseline-local.json','w').write(json.dumps(actual))
    open(OUT+'/baseline-download.json','w').write(json.dumps(state))
    open(OUT+'/fresh-feed.json','w').write(json.dumps(dict(source,episodes=fresh)))
    # No reload or React state update on old version after oversized seed.
    adb('shell','am','force-stop','com.podmix.next.ng')
    log('BASELINE_READY data persisted, app stopped, no uninstall/clear')
else:
    before=json.load(open(OUT+'/baseline-local.json'))
    for _ in range(30):
        after=ev('Object.fromEntries(Object.keys(localStorage).map(k=>[k,localStorage.getItem(k)]))')
        catalog=json.loads(after.get('podmix-catalog-v1','[]'))
        if catalog and len(catalog[0]['episodes'])>=104:break
        time.sleep(1)
    time.sleep(5)
    after=ev('Object.fromEntries(Object.keys(localStorage).map(k=>[k,localStorage.getItem(k)]))')
    catalog=json.loads(after['podmix-catalog-v1']);episodes=catalog[0]['episodes']
    check(len(episodes)==104,'over-limit catalogue retains 4 protected plus100 recent')
    oldids=['qa-favorite','qa-position','qa-download','qa-annotation']
    for id in oldids:check(any(e['id']==id for e in episodes),'referenced episode '+id)
    for key in ['podmix-history-v1','podmix-listening-sessions-v1','podmix-completed-episodes-v1','podmix-track-favorites-v1','podmix-settings-v1']:
        check(json.loads(after[key])==json.loads(before[key]),'unchanged '+key)
    original=json.loads(before['podmix-catalog-v1'])[0]['episodes']
    for id in ['qa-favorite','qa-annotation']:
        check(next(e for e in episodes if e['id']==id)['tracks']==next(e for e in original if e['id']==id)['tracks'],'manual tracks/evidence '+id)
    state=native('getDownload',{'id':'qa-download'});baseline=json.load(open(OUT+'/baseline-download.json'))
    check(state.get('status')=='completed','native download still completed')
    check(state.get('localUri')==baseline.get('localUri'),'native download URI preserved')
    offline=json.loads(after['podmix-offline-v1']);check(any(x['id']=='qa-download' and x.get('localUri')==baseline.get('localUri') for x in offline),'offline metadata preserved')
    # Trigger real app refresh through UI, then assert retained references after fresh feed excludes them.
    refresh=ev('(()=>{const b=[...document.querySelectorAll("button")].find(b=>(b.textContent||"").includes("QA PRESERVATION"));if(b)b.click();return !!b})()')
    if not refresh:
        ev('document.querySelector(".home-source-card")?.click()')
    time.sleep(1)
    refreshed=ev('(()=>{const b=[...document.querySelectorAll("button")].find(b=>(b.textContent||"").includes("Actualiser"));if(b)b.click();return !!b})()')
    check(refreshed,'refresh button clicked')
    time.sleep(5)
    after=ev('Object.fromEntries(Object.keys(localStorage).map(k=>[k,localStorage.getItem(k)]))')
    episodes=json.loads(after['podmix-catalog-v1'])[0]['episodes']
    for id in oldids:check(any(e['id']==id for e in episodes),'after refresh absent protected '+id)
    ev('(()=>{const b=[...document.querySelectorAll("button")].find(b=>b.getAttribute("aria-label")==="Voir les favoris");b?.click()})()')
    time.sleep(1);check('QA FAVORITE TRACK' in ev('document.body.innerText'),'favorite visible after upgrade+refresh')
    shot('upgrade231-favorites')
    ev('(()=>{const b=[...document.querySelectorAll("button")].find(b=>b.getAttribute("aria-label")==="Réglages");b?.click()})()')
    time.sleep(1)
    ev('(()=>{const p=[...document.querySelectorAll("p.settings-copy")].find(p=>p.textContent.includes("Version installée"));p?.scrollIntoView({block:"center"});})()')
    shot('upgrade231-version')
    open(OUT+'/candidate-local.json','w').write(json.dumps(after))
    log('UPGRADE_SUCCESS 230 to231 synthetic data, referenced episodes beyond cap and absent feed retained')
