"""Isolated actual Podmix HTTP API and relay, two real distinct decoded MP3s.
Only test boundary: permit the local fixture origin instead of public-only SSRF
validation. No session/relay logic or client production functions replaced.
"""
import sys,tempfile,pathlib,threading,urllib.request,json,hashlib,subprocess,struct,statistics
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'server'))
import dev_server as prod
with tempfile.TemporaryDirectory(prefix='relay-identity-') as td:
 root=pathlib.Path(td);audio={}
 for name,hz in [('A',440),('B',880)]:
  p=root/(name+'.mp3');subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i',f'sine=frequency={hz}:duration=1','-ar','8000','-ac','1','-codec:a','libmp3lame','-b:a','32k',str(p)],check=True);audio[name]=p.read_bytes()
 reads=[]
 class Origin(BaseHTTPRequestHandler):
  def log_message(self,*args):pass
  def do_GET(self):
   name=self.path.removesuffix('.mp3').lstrip('/');data=audio[name];reads.append(name);self.send_response(200);self.send_header('Content-Type','audio/mpeg');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 origin=ThreadingHTTPServer(('127.0.0.1',0),Origin);threading.Thread(target=origin.serve_forever,daemon=True).start();base=f'http://127.0.0.1:{origin.server_port}'
 validate=prod.validate_cast_audio_url
 def fixture_only(value):
  if value in [base+'/A.mp3',base+'/B.mp3']:return value
  return validate(value)
 prod.validate_cast_audio_url=fixture_only
 prod.DATA_DIR=root;prod.DATABASE=root/'db.sqlite3';prod.initialize()
 api=ThreadingHTTPServer(('127.0.0.1',0),prod.Handler);threading.Thread(target=api.serve_forever,daemon=True).start();apiurl=f'http://127.0.0.1:{api.server_port}';prod.CAST_PUBLIC_BASE=apiurl+'/v1/cast';prod.CAST_LAN_BASE=apiurl+'/v1/cast'
 tokens={};records=[]
 def decoded_hz(data):
  pcm=subprocess.run(['ffmpeg','-v','error','-i','pipe:0','-f','s16le','-ac','1','-ar','8000','pipe:1'],input=data,capture_output=True,check=True).stdout
  vals=struct.unpack('<'+'h'*(len(pcm)//2),pcm);vals=vals[len(vals)//4:len(vals)*3//4];cross=sum(a<0<=b for a,b in zip(vals,vals[1:]));return cross/(len(vals)/8000)
 for name in ['A','B','B']:
  body=json.dumps({'url':base+'/'+name+'.mp3','title':'Station '+name}).encode()
  session=json.load(urllib.request.urlopen(urllib.request.Request(apiurl+'/v1/cast/sessions',body,{'Content-Type':'application/json'})))
  uri=session['lanRelayUrl'];data=urllib.request.urlopen(uri).read();h=hashlib.sha256(data).hexdigest();assert data==audio[name];hz=decoded_hz(data);assert abs(hz-({'A':440,'B':880}[name]))<8
  records.append({'source':name,'token':session['id'],'uri':uri,'sha256':h,'decodedHz':hz,'bytes':len(data)})
  tokens.setdefault(name,uri)
 assert len({r['token'] for r in records})==3
 # Negatives: command B echoed as CurrentURI does not prove audio B. Receiver
 # keeps A HTTP connection/content. The byte oracle must reject this mismatch.
 old=urllib.request.urlopen(tokens['A']).read();commanded_uri=tokens['B'];reported_uri=commanded_uri
 old_confirm=True and reported_uri==commanded_uri
 content_confirm=hashlib.sha256(old).digest()==hashlib.sha256(audio['B']).digest()
 assert old_confirm and not content_confirm
 print('ACTUAL_SERVER_SESSIONS_HTTP_RELAY_PASS',json.dumps(records))
 print('DECODED_440_880_DISTINCT_PASS origins_read='+repr(reads))
 print('NEGATIVE_STALE_AUDIO_REJECTED despite echoed_current_uri_playing=true')
 api.shutdown();origin.shutdown()
