#!/usr/bin/env python3
"""Four-JVM acceptance run. No modifications to config/local or runtime/.
Run from project root: python3 validation_20261013/scripts/acceptance.py --run NAME
The full profile is intentionally substantial; smoke is NOT acceptance evidence.
"""
import argparse, datetime, hashlib, json, math, os, pathlib, platform, random
import shutil, statistics, subprocess, sys, time, urllib.request, urllib.error
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = ROOT / 'validation_20261013'
parser = argparse.ArgumentParser()
parser.add_argument('--run', required=True)
parser.add_argument('--profile', choices=['full', 'smoke'], default='full')
parser.add_argument('--port', type=int, default=8980)
parser.add_argument('--setup-cache', type=pathlib.Path, help='Optional PUBLIC curator deployment with capacity=128, universe=50; only CRS files copied')
args = parser.parse_args()
if pathlib.Path(args.run).name != args.run: parser.error('--run must be one directory name')
OUT = BASE / 'results' / args.run
OUT.mkdir(parents=True, exist_ok=False)
FULL = args.profile == 'full'
N, CAP, U = (100, 128, 50) if FULL else (8, 8, 50)
LOOPS, BILATERAL, ROUNDS, REVOKE_N, TRACE_N = (1000, 52, 5, 20, 100) if FULL else (10, 4, 1, 1, 3)
SEED = 20261013
rng = random.Random(SEED)
ports = {role: args.port+i for i, role in enumerate(['cloud', 'owner', 'user', 'curator'])}
procs, logs, csrf = {}, [], {}
records = []
record_file = (OUT / 'records.jsonl').open('w', encoding='utf-8', buffering=1)
start_wall = time.time()

def write_json(path, obj):
    temp = path.with_suffix(path.suffix+'.tmp')
    temp.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')
    temp.replace(path)

def record(kind, ok, **fields):
    row = dict(kind=kind, ok=bool(ok), at=datetime.datetime.now(datetime.timezone.utc).isoformat(), **fields)
    records.append(row);record_file.write(json.dumps(row, ensure_ascii=False)+'\n')
    if not ok: print('FAILED', kind, fields, flush=True)
    write_json(OUT/'progress.json', {'last':row,'records':len(records),'elapsedSeconds':time.time()-start_wall})
    return row

def progress(message): print(datetime.datetime.now().isoformat(timespec='seconds'),message,flush=True)

def request(role, path, body=None, local=True):
    data = None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8')
    headers = {} if data is None else {'Content-Type':'application/json'}
    if data is not None and local: headers['X-Local-Token'] = csrf[role]
    req=urllib.request.Request('http://127.0.0.1:%d%s'%(ports[role],path),data=data,headers=headers)
    with urllib.request.urlopen(req,timeout=600) as response: return json.load(response)

def state(role):return request(role,'/api/state')
def action(role, path, body):return request(role,'/api/'+path,body)

def wait_ready():
    deadline=time.monotonic()+7200
    while time.monotonic()<deadline:
        try:
            ss={r:state(r) for r in ports}
            if all(s.get('localReady') for s in ss.values()):
                csrf.update({r:s['csrf'] for r,s in ss.items()});return
            phase=ss['curator'].get('phase','')
            if ss['curator'].get('error'):raise RuntimeError(ss['curator']['error'])
            progress('Setup: '+phase)
        except (OSError,ValueError):pass
        time.sleep(10)
    raise TimeoutError('Setup did not finish within two hours')

def register(role, attrs, ordinal):
    started=time.perf_counter()
    job=action('curator','register',{'name':'实验车辆-%03d'%ordinal,'role':role,'attributeNames':['属性%02d'%(a+1) for a in attrs]})['job']
    deadline=time.monotonic()+3600
    while time.monotonic()<deadline:
        try:
            s=state('curator');j=next(j for j in s['jobs'] if j['id']==job)
            if j['status']=='done':
                v=next(v for v in s['vehicles'] if v['id']==j['vehicle'])
                record('registration',v['registered'] and v['attributes']==attrs,vehicle=v['id'],role=role,
                       keygenMs=v['keygenMs'],registrationMs=v['registrationMs'],wallMs=(time.perf_counter()-started)*1000)
                return v['id']
        except (OSError,ValueError):pass
        time.sleep(.25)
    raise TimeoutError('Registration timed out for '+job)

POLICIES=[]
for rows in range(5,51,5):
    # Odd-indexed cases are conjunctions; even-indexed are mandatory A1 plus OR pairs.
    if (rows//5)%2:
        expr=' AND '.join('A%d'%i for i in range(1,rows+1));structure='AND'
    else:
        terms=['A1'];i=2
        while i<=rows:
            terms.append('(A%d OR A%d)'%(i,i+1) if i<rows else 'A%d'%i);i+=2
        expr=' AND '.join(terms);structure='AND_OR'
    POLICIES.append({'id':'P%02d'%(rows//5),'rows':rows,'distinctAttributes':rows,'structure':structure,'expression':expr})
write_json(OUT/'policies.json',POLICIES)

def send(sender,policy,index):
    # Fixed-size UTF-8 payload, with unique prefix to catch stale plaintext.
    text=('RABE-%06d-'%index).ljust(1024,'x')
    result=action('owner','send',{'sender':sender,'policy':policy['expression'],'text':text,'title':'acceptance-%06d'%index})
    return result,text

def decrypt(message,receiver,text,reverse,expected=True,**meta):
    started=time.perf_counter()
    try:
        response=action('user','decrypt',{'id':message['id'],'receiver':receiver,'reverse':reverse})
        allowed=response.get('ok') is True
        exact=allowed and response.get('text')==text
        correct=exact if expected else (not allowed and response.get('reason') in ['RECEIVER_POLICY','SENDER_POLICY','REVOKED'])
        record(meta.pop('kind','decrypt'),correct,expected=expected,recovered=exact,receiver=receiver,message=message['id'],
               reason=response.get('reason'),transformMs=response.get('transformMs'),decryptMs=response.get('decryptMs'),
               wallMs=(time.perf_counter()-started)*1000,**meta)
        return correct,response
    except Exception as ex:
        record(meta.pop('kind','decrypt'),False,expected=expected,recovered=False,receiver=receiver,message=message['id'],
               error=type(ex).__name__+': '+str(ex),wallMs=(time.perf_counter()-started)*1000,**meta)
        return False,{}

meta={'profile':args.profile,'seed':SEED,'registeredVehicles':N,'capacity':CAP,'attributes':U,
      'continuousTrials':LOOPS,'bilateralPairs':BILATERAL,'revocationRounds':ROUNDS,'revocationsPerRound':REVOKE_N,
      'traceSamples':TRACE_N,'hostPlatform':platform.platform(),'cpu':platform.processor(),'logicalCpus':os.cpu_count(),
      'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),
      'jarSha256':hashlib.sha256((ROOT/'dist/rabe-city.jar').read_bytes()).hexdigest(),
      'gitCommit':subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True).stdout.strip(),
      'transport':'four independent JVMs, loopback HTTP, one sequential request stream',
      'payloadBytes':1024,'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
write_json(OUT/'environment.json',meta)
fatal=None
try:
    subprocess.run(['java','-jar','dist/rabe-city.jar','prepare',str(OUT/'config'),f'http://127.0.0.1:{ports["cloud"]}',str(CAP),str(U),f'http://127.0.0.1:{ports["curator"]}'],cwd=ROOT,check=True,capture_output=True)
    for role in ports:
        p=OUT/'config'/(role+'.properties')
        lines=p.read_text().splitlines()
        lines=[('port='+str(ports[role])) if x.startswith('port=') else ('bind=127.0.0.1') if x.startswith('bind=') else ('data='+str(OUT/'private-runtime'/role)) if x.startswith('data=') else x for x in lines]
        p.write_text('\n'.join(lines)+'\n');p.chmod(0o600)
    if args.setup_cache:
        source=args.setup_cache.resolve();manifest=json.loads((source/'deployment.json').read_text())
        if manifest['capacity']!=CAP or manifest['universe']!=U:raise ValueError('Cache scale mismatch')
        dest=OUT/'private-runtime/curator';dest.mkdir(parents=True)
        shutil.copy2(source/'deployment.json',dest/'deployment.json')
        for k in range(int(math.log2(CAP))+1):
            (dest/('level-%d'%k)).mkdir()
            for file in ['context.json','cross.bin']:shutil.copy2(source/('level-%d'%k)/file,dest/('level-%d'%k)/file)
        meta['setupCache']='public CRS reused; no registry, samples or vehicle private keys imported'
        write_json(OUT/'environment.json',meta)
    for role in ports:
        f=(OUT/(role+'.log')).open('wb');logs.append(f)
        procs[role]=subprocess.Popen(['java','-Xmx2g','-jar','dist/rabe-city.jar',str(OUT/'config'/(role+'.properties'))],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
    wait_ready();progress('Four nodes ready')
    quarter=N//4;groups={k:[] for k in ['owner_full','owner_missing','user_full','user_missing']}
    for group in groups:
        role=group.split('_')[0];attrs=list(range(50)) if group.endswith('full') else list(range(1,50))
        for _ in range(quarter):
            v=register(role,attrs,sum(map(len,groups.values()))+1);groups[group].append(v)
            progress('Registered vehicle %d/%d'%(v+1,N))
    write_json(OUT/'groups.json',groups)
    full,missing=groups['user_full'],groups['user_missing'];of,om=groups['owner_full'],groups['owner_missing']
    latest={}
    # Exactly 1000 consecutive positive pipelines; no retry or intervening negative requests.
    for i in range(LOOPS):
        policy=POLICIES[i%10];t=time.perf_counter()
        try:
            m,text=send(of[i%len(of)],policy,i);ok,r=decrypt(m,full[i%len(full)],text,policy['expression'],kind='continuous',trial=i+1,policy=policy['id'],encryptMs=m.get('encryptMs'))
            record('pipeline',ok,trial=i+1,policy=policy['id'],wallMs=(time.perf_counter()-t)*1000)
            latest[policy['id']]=(m,text)
        except Exception as ex:record('pipeline',False,trial=i+1,policy=policy['id'],error=str(ex),wallMs=(time.perf_counter()-t)*1000)
        if (i+1)%10==0:progress('Continuous pipelines %d/%d'%(i+1,LOOPS))
    # Every receiving vehicle against every policy; equality check rather than HTTP success.
    for policy in POLICIES:
        m,text=latest[policy['id']]
        for receiver in full+missing:
            decrypt(m,receiver,text,policy['expression'],expected=receiver in full,kind='policy',policy=policy['id'])
    for i in range(BILATERAL):
        category=i%4;idx=i//4
        sender=(of if category in [0,1] else om)[idx%quarter]
        receiver=(full if category in [0,2] else missing)[idx%quarter]
        m,text=send(sender,POLICIES[idx%10],10000+i)
        decrypt(m,receiver,text,POLICIES[idx%10]['expression'],expected=category==0,kind='bilateral',category=category,sender=sender,pair=i+1)
    # Five independent ciphertexts, 20 random distinct removals each: 100 updates.
    unique=set()
    for round_i in range(ROUNDS):
        targets=rng.sample(full,REVOKE_N);unique.update(targets)
        m,text=send(of[0],POLICIES[0],20000+round_i)
        for target in targets:
            t=time.perf_counter();r=action('owner','revoke',{'id':m['id'],'target':target})
            record('revoke',r.get('version',0)>0,round=round_i+1,target=target,message=m['id'],wallMs=(time.perf_counter()-t)*1000)
        for receiver in full:
            decrypt(m,receiver,text,POLICIES[0]['expression'],expected=receiver not in targets,kind='revocation_access',round=round_i+1)
    write_json(OUT/'revocation_targets.json',{'distinctTargets':sorted(unique),'rounds':ROUNDS})
    # All are valid white-box copies of r, not arbitrary black-box pirate decoders.
    for i in range(TRACE_N):
        source=(full+missing)[i%len(full+missing)]
        sample=action('user','leak',{'source':source});t=time.perf_counter()
        tracer=['cloud','curator'][i%2];r=action(tracer,'trace',{'sample':sample['id']})
        record('trace',r.get('slot')==source,source=source,identified=r.get('slot'),sample=sample['id'],tracer=tracer,
               scenario=['key_copy','exposure','unauthorized_sharing'][i%3],traceMs=r.get('traceMs'),wallMs=(time.perf_counter()-t)*1000)
        if (i+1)%10==0:progress('Trace samples %d/%d'%(i+1,TRACE_N))
except BaseException as ex:
    fatal=type(ex).__name__+': '+str(ex);progress('RUN INCOMPLETE: '+fatal)
finally:
    for p in procs.values():p.terminate()
    for p in procs.values():
        try:p.wait(timeout=10)
        except subprocess.TimeoutExpired:p.kill();p.wait()
    for f in logs:f.close()
    record_file.close()
    summary={'profile':args.profile,'complete':fatal is None,'fatal':fatal,'elapsedSeconds':time.time()-start_wall,'counts':{},'timings':{}}
    for kind in sorted({x['kind'] for x in records}):
        rows=[r for r in records if r['kind']==kind];summary['counts'][kind]={'total':len(rows),'correct':sum(r['ok'] for r in rows)}
        for field in ['keygenMs','registrationMs','encryptMs','transformMs','decryptMs','traceMs','wallMs']:
            values=sorted(float(r[field]) for r in rows if isinstance(r.get(field),(int,float)))
            if values:summary['timings'][kind+'.'+field]={'n':len(values),'mean':statistics.mean(values),'median':statistics.median(values),'p95':values[math.ceil(.95*len(values))-1],'max':max(values)}
    summary['acceptanceEligible']=FULL and fatal is None and summary['counts'].get('registration',{}).get('total',0)>=100 and summary['counts'].get('pipeline',{}).get('total',0)>=1000
    write_json(OUT/'summary.json',summary)
    progress(json.dumps(summary,ensure_ascii=False))
if fatal:sys.exit(1)
if any(not r['ok'] for r in records):sys.exit(2)
