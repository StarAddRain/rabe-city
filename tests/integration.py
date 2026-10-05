#!/usr/bin/env python3
"""Four real JVM processes; isolated keys/configs, no external Python packages.
Run: python3 tests/integration.py --output /absolute/test-output --base-port 8280
"""
import argparse,json,os,pathlib,subprocess,time,urllib.request,urllib.error,concurrent.futures
P=argparse.ArgumentParser();P.add_argument('--output',required=True);P.add_argument('--base-port',type=int,default=8280);args=P.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[1];OUT=pathlib.Path(args.output).resolve();OUT.mkdir(parents=True,exist_ok=True)
if (OUT/'config').exists():raise SystemExit('Use a NEW output directory: existing keys/configs are never overwritten')
BASE=args.base_port;PROCS={};LOGS=[];CHECKS=[];TIMINGS=[]
def check(ok,label):
 if not ok:raise AssertionError(label)
 CHECKS.append(label)
def request(port,path,body=None,token=None,expect=200):
 headers={};data=None
 if body is not None:headers['Content-Type']='application/json';data=json.dumps(body).encode()
 if token:headers['Authorization']='Bearer '+token
 r=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=data,headers=headers)
 try:
  with urllib.request.urlopen(r,timeout=180) as f:code=f.status;out=json.load(f)
 except urllib.error.HTTPError as e:code=e.code;out=json.load(e)
 if code!=expect:raise AssertionError(f'{path} HTTP {code} != {expect}: {out}')
 return out
ports={'cloud':BASE,'owner':BASE+1,'user':BASE+2,'curator':BASE+3}
def state(role):return request(ports[role],'/api/state')
def local(role,path,body,expect=200):
 s=state(role);r=urllib.request.Request(f'http://127.0.0.1:{ports[role]}/api/'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Local-Token':s['csrf']})
 try:
  with urllib.request.urlopen(r,timeout=180) as f:code=f.status;out=json.load(f)
 except urllib.error.HTTPError as e:code=e.code;out=json.load(e)
 check(code==expect,f'{role}/{path} HTTP {expect} ({out.get("reason","")})')
 if code!=expect:raise AssertionError(out)
 return out
def wait(test,seconds=180):
 end=time.time()+seconds
 while time.time()<end:
  try:
   if test():return
  except (OSError,AssertionError):pass
  time.sleep(.2)
 raise AssertionError('Wait timeout')
def start(role):
 f=open(OUT/(role+'.log'),'ab');LOGS.append(f);PROCS[role]=subprocess.Popen(['java','-Xmx1g','-jar','dist/rabe-city.jar',str(OUT/'config'/(role+'.properties'))],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
def stop(role):
 p=PROCS.pop(role,None)
 if p:
  p.terminate()
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:p.kill();p.wait()
def register(role,attrs,name='regression'):
 before=state('curator')['ctr'];r=local('curator','register',{'name':name,'role':role,'attributes':attrs});wait(lambda:state('curator')['ctr']==before+1)
 v=state('curator')['vehicles'][before];check(v['registered'] and v['role']==role and v['attributes']==attrs,'registered '+str(before));TIMINGS.append({'vehicle':before,'keygenMs':v['keygenMs'],'registrationMs':v['registrationMs']});return before
counter=0
def send(sender=0,policy='A1 AND A2'):
 global counter
 counter+=1;text=f'车辆专属明文-{counter}';m=local('owner','send',{'sender':sender,'text':text,'policy':policy,'title':f'Test {counter}'});return m,text
def decrypt(m,receiver,ok=True,reverse='A1 AND A2',text=None,reason=None):
 r=local('user','decrypt',{'id':m['id'],'receiver':receiver,'reverse':reverse});check(r['ok']==ok,f'decrypt {receiver} ok={ok}')
 if text:check(r.get('text')==text,'plaintext exact match')
 if reason:check(r.get('reason')==reason,'denial '+reason)
 return r
try:
 subprocess.run(['java','-jar','dist/rabe-city.jar','prepare',str(OUT/'config'),f'http://127.0.0.1:{BASE}','8','5',f'http://127.0.0.1:{BASE+3}'],cwd=ROOT,check=True,capture_output=True)
 tokens={}
 for role in ports:
  p=OUT/'config'/(role+'.properties');lines=p.read_text().splitlines();lines=[('port='+str(ports[role])) if l.startswith('port=') else ('data='+str(OUT/'data'/role).replace('\\','/')) if l.startswith('data=') else l for l in lines];p.write_text('\n'.join(lines)+'\n');tokens[role]=next(l.split('=',1)[1] for l in lines if l.startswith('token='))
 for role in ports:start(role)
 wait(lambda:all(state(r)['localReady'] for r in ports));check(state('curator')['ctr']==0,'initial zero registrations')
 # Node authority is enforced in the backend, including direct HTTP bypasses.
 for role in ['owner','user','cloud']:
  local(role,'register',{'name':'denied','role':'user','attributes':[0]},403);local(role,'deregister',{'vehicle':0},403);local(role,'rejoin',{'vehicle':0},403)
 for path in ['send','decrypt','trace','revoke','leak','motion/set']:local('curator',path,{},403)
 request(BASE+3,'/node/submit',{},'bad-token',403);CHECKS.append('invalid node token denied')
 full=[0,1,2,3,4]
 names=['特斯拉','新能源','授权通行','市政服务','车队成员']
 local('curator','register',{'name':'owner-full','role':'owner','attributeNames':names+['特斯拉']});wait(lambda:state('curator')['ctr']==1)
 check(state('curator')['vehicles'][0]['attributes']==full,'named attributes allocated sequentially and deduplicated')
 expected_names={'A'+str(i+1):name for i,name in enumerate(names)}
 check(state('curator')['attributeNames']==expected_names,'persistent name dictionary exposed')
 local('curator','register',{'name':'overflow-attribute','role':'user','attributeNames':['第六个属性']},409)
 check(state('curator')['attributeNames']==expected_names and state('curator')['ctr']==1,'dictionary overflow rejected atomically')
 m1,t1=send()
 # Stop C briefly so an authorized but pending registration can be attacked deterministically.
 stop('user');job=local('curator','register',{'name':'user-full','role':'user','attributeNames':names})['job']
 owner_keys=json.loads((OUT/'data/owner/private-keys.json').read_text())['keys']['0']['secret'];pub=[{k:v for k,v in x.items() if k not in ['r','q','z']} for x in owner_keys]
 request(BASE+3,'/node/submit',{'job':job,'ctr':0,'public':pub},tokens['user'],409);check(state('curator')['ctr']==1,'stale ctr leaves state unchanged')
 request(BASE+3,'/node/submit',{'job':job,'ctr':1,'public':pub},tokens['owner'],403);CHECKS.append('wrong role cannot submit approved job')
 bad=json.loads(json.dumps(pub));bad[1]['V'][0]=bad[1]['Q']
 request(BASE+3,'/node/submit',{'job':job,'ctr':1,'public':bad},tokens['user'],400);check(state('curator')['ctr']==1,'invalid cross-term atomically rejected')
 start('user');wait(lambda:state('curator')['ctr']==2);decrypt(m1,1,False,reason='NOT_REGISTERED_AT_ENCRYPTION')
 register('user',[0,2],'user-missing');m3,t3=send();decrypt(m3,1,text=t3);decrypt(m3,2,False,reason='RECEIVER_POLICY')
 check('inbox' not in state('user') and t3 not in json.dumps(state('user'),ensure_ascii=False),'global state contains no plaintext')
 check(len(request(BASE+2,'/api/inbox?vehicle=1')['inbox'])==1,'decrypting vehicle sees inbox');check(request(BASE+2,'/api/inbox?vehicle=2')['inbox']==[],'other vehicle has empty inbox')
 request(BASE+2,'/api/inbox?vehicle=0',expect=403);CHECKS.append('foreign role inbox denied')
 register('owner',[0,2],'owner-missing');m4,t4=send();decrypt(m4,1,text=t4)
 badsender,_=send(3);decrypt(badsender,1,False,reason='SENDER_POLICY');decrypt(badsender,2,False,reason='RECEIVER_POLICY');decrypt(badsender,1,True,reverse='A1 AND A3')
 register('user',full,'user-late');decrypt(m3,4,False,reason='NOT_REGISTERED_AT_ENCRYPTION');m5,t5=send();decrypt(m5,4,text=t5);decrypt(m5,1,text=t5);decrypt(m3,1,text=t3)
 db=json.loads((OUT/'data/curator/registry-state.json').read_text());check(db['ctr']==5 and [None if x is None else x.split('-')[1] for x in db['latest']]==['4','2','0',None],'Section6 latest completed blocks at ctr5');check(db['D1'][2]['0']['vehicle']==4 and db['D1'][2]['1']['vehicle']==1,'D1 partial overwrite');check('1:2' in db['D2'] and '4:2' not in db['D2'],'D2 helper issued only for completed block')
 local('owner','revoke',{'id':m5['id'],'target':1});decrypt(m5,1,False,reason='REVOKED');decrypt(m5,4,text=t5)
 sample=local('user','leak',{'source':1});tr=local('cloud','trace',{'sample':sample['id']});check(tr['identity']==2,'trace real copied secret identity')
 local('curator','deregister',{'vehicle':1});check(state('curator')['ctr']==5 and state('curator')['enrolled']==4,'deregister does not recycle counter');local('user','decrypt',{'id':m3['id'],'receiver':1,'reverse':'A1'},403)
 register('user',full,'user-after-deregister');register('owner',full,'owner-late');register('user',full,'user-final');m8,t8=send()
 for receiver in [4,5,7]:decrypt(m8,receiver,text=t8)
 local('user','decrypt',{'id':m8['id'],'receiver':1,'reverse':'A1'},403);decrypt(m5,4,text=t5)
 local('owner','revoke',{'id':m8['id'],'target':5});decrypt(m8,5,False,reason='REVOKED');decrypt(m8,4,text=t8);decrypt(m8,7,text=t8)
 check(local('cloud','trace',{'sample':sample['id']})['identity']==2,'trace after deregistration')
 local('curator','register',{'name':'overflow','role':'user','attributes':full},409)
 # Rejoin at full capacity must restore aggregates without restoring another removed member.
 local('curator','deregister',{'vehicle':2})
 local('curator','rejoin',{'vehicle':1});check(state('curator')['ctr']==8 and state('curator')['vehicles'][1]['registered'],'rejoin reuses identity at full capacity')
 local('curator','rejoin',{'vehicle':1},409)
 mr,tr=send();decrypt(mr,1,text=tr);decrypt(mr,4,text=tr)
 local('user','decrypt',{'id':mr['id'],'receiver':2,'reverse':'A1'},403)
 decrypt(m5,1,False,reason='REVOKED')
 local('curator','deregister',{'vehicle':1});local('curator','rejoin',{'vehicle':1})
 local('curator','rejoin',{'vehicle':2});local('curator','deregister',{'vehicle':1})
 # Repeated exchanges with all 4 bilateral policy cases, plus nontrivial OR expressions.
 for i in range(12):
  m,text=send(0,'A1 AND (A2 OR A3)');decrypt(m,4,text=text);decrypt(m,2,text=text,reverse='A1')
 # Shared motion clock across all four proxies. Frozen coordinates are deterministic in UI.
 local('cloud','motion/set',{'paused':True});clocks=[request(ports[r],'/api/motion') for r in ports];check(len({x['elapsed'] for x in clocks})==1 and all(x['paused'] for x in clocks),'four nodes share identical paused simulation time');frozen=clocks[0]['elapsed']
 # Persistence: restart all JVMs, preserving registration, keys, messages, per-car inbox and motion.
 for role in list(PROCS):stop(role)
 for role in ports:start(role)
 wait(lambda:all(state(r)['localReady'] for r in ports));check(state('curator')['ctr']==8 and state('curator')['enrolled']==7,'persistent ctr and deregistration');check(state('curator')['attributeNames']==expected_names,'attribute dictionary survives restart');check(state('owner')['attributeNames']==expected_names,'dictionary available on A');check(request(BASE,'/api/motion')['elapsed']==frozen,'clock persisted across B restart');decrypt(m8,4,text=t8);check(request(BASE+2,'/api/inbox?vehicle=2')['inbox'][0]['receiver']==2,'vehicle-scoped inbox persists')
 local('cloud','motion/set',{'paused':False});time.sleep(.25);a=request(BASE+1,'/api/motion')['elapsed'];c=request(BASE+2,'/api/motion')['elapsed'];check(abs(a-c)<.3 and a>frozen,'live proxies follow B clock')
 # Public files at D and B must not contain the ordinary private scalar fields.
 d=json.loads((OUT/'data/curator/registry-state.json').read_text());check(all(not any(k in pk for k in ['r','q','z']) for v in d['vehicles'] for pk in v['public']),'curator stores only registration public keys')
 (OUT/'classes').mkdir(exist_ok=True)
 subprocess.run(['javac','-encoding','UTF-8','-cp',str(ROOT/'dist/rabe-city.jar')+os.pathsep+str(ROOT/'lib/*'),'-d',str(OUT/'classes'),str(ROOT/'tests/CryptoChecks.java')],cwd=ROOT,check=True)
 core=subprocess.run(['java','-cp',str(OUT/'classes')+os.pathsep+str(ROOT/'dist/rabe-city.jar')+os.pathsep+str(ROOT/'lib/*'),'city.CryptoChecks'],cwd=ROOT,text=True,capture_output=True,check=True);(OUT/'crypto.log').write_text(core.stdout);check('CRYPTO_CHECKS_PASSED=' in core.stdout,'offline crypto regression (including true Deregister algebra)')
 report={'ok':True,'apiAssertions':len(CHECKS),'checks':CHECKS,'registrationTimings':TIMINGS,'crypto':core.stdout.strip(),'vehiclesEverRegistered':8,'activeVehicles':7,'scope':'four JVM loopback functional regression, not 100-vehicle benchmark'};(OUT/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in report.items() if k not in ['checks','registrationTimings']},ensure_ascii=False),flush=True)
finally:
 for role in list(PROCS):stop(role)
 for f in LOGS:f.close()
