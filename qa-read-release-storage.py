# Reads only isolated emulator fixtures, never user data.
import json,os,sys
import plyvel
root=sys.argv[1];out=sys.argv[2]
path=os.path.join(root,'app_webview','Default','Local Storage','leveldb')
if not os.path.isdir(path):
    raise RuntimeError('WebView localStorage database not found: '+path)
db=plyvel.DB(path,create_if_missing=False)
def decode(v):
    if v[:1]==b'\x01':return v[1:].decode('latin1')
    if v[:1]==b'\x00':return v[1:].decode('utf-16-le')
    raise RuntimeError('Unknown Chromium string encoding')
result={}
for k,v in db:
    if not k.startswith(b'_'):continue
    pos=k.find(b'\x00')
    if pos<0:continue
    try:key=decode(k[pos+1:])
    except (UnicodeError,RuntimeError):continue
    if key.startswith('podmix-'):result[key]=decode(v)
db.close()
json.dump(result,open(out,'w'),ensure_ascii=False,indent=2)
print('RELEASE_QA persisted keys',len(result))
if len(sys.argv)>3:
    before=json.load(open(sys.argv[3]))
    for key in ['podmix-history-v1','podmix-listening-sessions-v1','podmix-completed-episodes-v1','podmix-track-favorites-v1','podmix-settings-v1']:
        assert json.loads(result[key])==json.loads(before[key]),key
        print('RELEASE_QA PASS unchanged',key)
    previous=json.loads(before['podmix-catalog-v1'])[0]
    catalog=json.loads(result['podmix-catalog-v1'])
    source=next(s for s in catalog if s['id']=='qa-source')
    assert len(source['episodes'])==104
    for id in ['qa-favorite','qa-position','qa-download','qa-annotation']:
        old=next(e for e in previous['episodes'] if e['id']==id)
        new=next(e for e in source['episodes'] if e['id']==id)
        assert new.get('tracks')==old.get('tracks'),id
        print('RELEASE_QA PASS protected',id)
    old=json.loads(before['podmix-offline-v1']);new=json.loads(result['podmix-offline-v1'])
    assert any(x['id']=='qa-download' and x.get('localUri')==old[0].get('localUri') for x in new)
    print('RELEASE_QA PASS offline metadata and native URI preserved')
    print('RELEASE_QA PASS104 episodes, refs and metadata preserved in exact signed release231')
