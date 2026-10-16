#!/usr/bin/env python3
"""Extend a completed isolated run to 100 independent 20-target revocation rounds.

Reuses its 100 real registered identities. Original records remain immutable.
"""
import argparse, datetime, hashlib, json, pathlib, random, subprocess, time, urllib.request

P=argparse.ArgumentParser();P.add_argument('--run',required=True);P.add_argument('--rounds',type=int,default=100);P.add_argument('--wait',action='store_true');a=P.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[2];OUT=ROOT/'validation_20261013/results'/a.run
while a.wait and not (OUT/'summary.json').exists():time.sleep(5)
summary=json.loads((OUT/'summary.json').read_text());assert summary['complete'], 'Primary run must complete first'
env=json.loads((OUT/'environment.json').read_text());assert env['profile']=='full'
assert hashlib.sha256((ROOT/'dist/rabe-city.jar').read_bytes()).hexdigest()==env['jarSha256']
original=[json.loads(line) for line in (OUT/'records.jsonl').read_text().splitlines()]
done=sorted({r['round'] for r in original if r['kind']=='revoke'})
assert done==list(range(1,len(done)+1))
groups=json.loads((OUT/'groups.json').read_text());policies=json.loads((OUT/'policies.json').read_text())
full=groups['user_full'];assert len(full)>=25
DEST=OUT/'revocation-extension';DEST.mkdir(exist_ok=False)
records=[];procs=[];logs=[];csrf={};ports={};fatal=None;start=time.time();rng=random.Random(20261014)
f=(DEST/'records.jsonl').open('w',encoding='utf-8',buffering=1)
def save(name,obj):(DEST/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
def record(kind,ok,**fields):
 row=dict(kind=kind,ok=bool(ok),at=datetime.datetime.now(datetime.timezone.utc).isoformat(),**fields)
 records.append(row);f.write(json.dumps(row,ensure_ascii=False)+'\n');save('progress.json',{'records':len(records),'last':row})
 if not ok:print('FAILED',row,flush=True)
def request(role,path,body=None):
 data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
 headers={} if body is None else {'Content-Type':'application/json','X-Local-Token':csrf[role]}
 req=urllib.request.Request('http://127.0.0.1:%d/api/%s'%(ports[role],path),data=data,headers=headers)
 with urllib.request.urlopen(req,timeout=600) as response:return json.load(response)
save('environment.json',{'seed':20261014,'originalRounds':len(done),'targetRounds':a.rounds,'jarSha256':env['jarSha256'],'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'originalRecordsSha256':hashlib.sha256((OUT/'records.jsonl').read_bytes()).hexdigest()})
try:
 for role in ['cloud','owner','user','curator']:
  config=OUT/'config'/(role+'.properties')
  props=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line and not line.startswith('#'))
  ports[role]=int(props['port']);log=(DEST/(role+'.log')).open('wb');logs.append(log)
  procs.append(subprocess.Popen(['java','-Xmx2g','-jar','dist/rabe-city.jar',str(config)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT))
 deadline=time.monotonic()+600
 while True:
  try:
   states={r:request(r,'state') for r in ports}
   if all(s.get('localReady') for s in states.values()):
    assert len([v for v in states['curator']['vehicles'] if v['registered']])>=100
    csrf.update({r:s['csrf'] for r,s in states.items()});break
  except (OSError,ValueError):pass
  if time.monotonic()>deadline:raise TimeoutError('Restart did not become ready')
  time.sleep(2)
 for round_i in range(len(done)+1,a.rounds+1):
  pol=policies[(round_i-1)%10];targets=rng.sample(full,20)
  text=('REVOCATION-%03d-'%round_i).ljust(1024,'x')
  m=request('owner','send',{'sender':groups['owner_full'][round_i%25],'policy':pol['expression'],'text':text,'title':'revocation-round-%03d'%round_i})
  version=0
  for target in targets:
   t=time.perf_counter();r=request('owner','revoke',{'id':m['id'],'target':target})
   record('revoke',r.get('version',0)>version,round=round_i,target=target,message=m['id'],policy=pol['id'],wallMs=(time.perf_counter()-t)*1000)
   version=r.get('version',version)
  for receiver in full:
   expected=receiver not in targets;t=time.perf_counter()
   try:
    r=request('user','decrypt',{'id':m['id'],'receiver':receiver,'reverse':pol['expression']})
    recovered=r.get('ok') is True and r.get('text')==text
    correct=recovered if expected else r.get('ok') is False and r.get('reason')=='REVOKED'
    record('revocation_access',correct,round=round_i,receiver=receiver,message=m['id'],policy=pol['id'],expected=expected,recovered=recovered,reason=r.get('reason'),transformMs=r.get('transformMs'),decryptMs=r.get('decryptMs'),wallMs=(time.perf_counter()-t)*1000)
   except Exception as e:
    record('revocation_access',False,round=round_i,receiver=receiver,message=m['id'],policy=pol['id'],expected=expected,recovered=False,error=type(e).__name__+': '+str(e))
  print(datetime.datetime.now().isoformat(timespec='seconds'),'Revocation rounds %d/%d'%(round_i,a.rounds),flush=True)
except BaseException as e:
 fatal=type(e).__name__+': '+str(e);print('INCOMPLETE',fatal,flush=True)
finally:
 for p in procs:p.terminate()
 for p in procs:
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:p.kill();p.wait()
 for log in logs:log.close()
 f.close();save('summary.json',{'complete':fatal is None,'fatal':fatal,'elapsedSeconds':time.time()-start,'rounds':len({r['round'] for r in records if r['kind']=='revoke'}),'records':len(records),'correct':sum(r['ok'] for r in records)})
if fatal:raise SystemExit(1)
if any(not r['ok'] for r in records):raise SystemExit(2)
