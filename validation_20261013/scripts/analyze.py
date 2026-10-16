#!/usr/bin/env python3
"""Recompute acceptance metrics solely from durable per-trial evidence."""
import collections, hashlib, json, math, pathlib, statistics, sys
p=pathlib.Path(sys.argv[1]);rows=[json.loads(x) for x in (p/'records.jsonl').read_text().splitlines()]
env=json.loads((p/'environment.json').read_text());status=json.loads((p/'summary.json').read_text())
extension=p/'revocation-extension'
extension_complete=False
if (extension/'summary.json').exists():
 extension_complete=json.loads((extension/'summary.json').read_text())['complete']
 rows.extend(json.loads(x) for x in (extension/'records.jsonl').read_text().splitlines())
def group(kind):return [r for r in rows if r['kind']==kind]
def metric(rs, predicate=lambda r:r['ok']):
 return {'numerator':sum(bool(predicate(r)) for r in rs),'denominator':len(rs),'percent':100*sum(bool(predicate(r)) for r in rs)/len(rs) if rs else None}
policy=group('policy');bi=group('bilateral');rev=group('revocation_access');tr=group('trace')
m={'registered':len({r['vehicle'] for r in group('registration') if r['ok']}),'registration':metric(group('registration')),
   'pipeline':metric(group('pipeline')),'authorizedRecovery':metric([r for r in policy if r['expected']],lambda r:r['recovered']),
   'unauthorizedRecovery':metric([r for r in policy if not r['expected']],lambda r:r['recovered']),
   'bilateral':{str(c):metric([r for r in bi if r['category']==c]) for c in range(4)},
   'revocationOperations':metric(group('revoke')),'distinctRevoked':len({r['target'] for r in group('revoke')}),
   'revokedDenial':metric([r for r in rev if not r['expected']],lambda r:r['ok'] and r.get('reason')=='REVOKED'),'remainingRecovery':metric([r for r in rev if r['expected']]),
   'trace':metric(tr),'distinctTracedIdentities':len({r['source'] for r in tr}),
   'traceScenarios':dict(collections.Counter(r['scenario'] for r in tr))}
m['revocationRounds']=len({r['round'] for r in group('revoke')})
round_integrity=[]
for round_id in sorted({r['round'] for r in group('revoke')}):
 updates=[r for r in group('revoke') if r['round']==round_id]
 access=[r for r in rev if r['round']==round_id]
 targets={r['target'] for r in updates}
 denied={r['receiver'] for r in access if not r['expected']}
 kept={r['receiver'] for r in access if r['expected']}
 messages={r['message'] for r in updates+access}
 round_integrity.append(len(targets)>=20 and targets==denied and not (targets & kept) and len(kept)>=5 and len(messages)==1)
def all_pass(metric):return metric['denominator']>0 and metric['numerator']==metric['denominator']
new_pipeline=[r for r in group('pipeline') if r.get('segment')=='resumed']
m['uninterruptedPipeline']=metric(new_pipeline) if new_pipeline else m['pipeline']
m['priorPipelineCount']=len(group('pipeline'))-len(new_pipeline) if new_pipeline else 0
new_trial_order=[r['trial'] for r in new_pipeline]
sequence_valid=(not new_pipeline or new_trial_order==list(range(env['priorCompleted']+1,env['priorCompleted']+1+len(new_pipeline))))
new_continuous=[r for r in group('continuous') if r.get('segment')=='resumed']
unique_messages=(not new_pipeline or len({r['message'] for r in new_continuous})==len(new_continuous))
checks={'scale':m['registered']>=100 and all_pass(m['registration']),'policies':len({r['policy'] for r in policy})>=10,
 'pipeline':sequence_valid and unique_messages and m['uninterruptedPipeline']['denominator']>=1000 and m['uninterruptedPipeline']['percent']>=99.9,
 'authorized':all_pass(m['authorizedRecovery']),'unauthorized':m['unauthorizedRecovery']['denominator']>0 and m['unauthorizedRecovery']['numerator']==0 and all(r['ok'] for r in policy if not r['expected']),
 'bilateral':len({(r['sender'],r['receiver']) for r in bi})>=50 and all(all_pass(x) for x in m['bilateral'].values()),
 'revocation':m['revocationRounds']>=100 and all(round_integrity) and len({r['message'] for r in group('revoke')})>=100 and m['revocationOperations']['denominator']>=2000 and all_pass(m['revocationOperations']) and m['distinctRevoked']>=20 and all_pass(m['revokedDenial']) and all_pass(m['remainingRecovery']),
 'trace':len({r['sample'] for r in tr})>=100 and m['trace']['denominator']>=100 and all_pass(m['trace'])}
# Separate authorized timings from policy-rejection fast paths. Never impute absent timings as zero.
timings={}
for kind in ['registration','continuous','pipeline','revoke','trace']:
 for field in ['keygenMs','registrationMs','encryptMs','transformMs','decryptMs','traceMs','wallMs']:
  v=sorted(float(r[field]) for r in group(kind) if r['ok'] and (not new_pipeline or kind=='registration' or r.get('segment')=='resumed') and isinstance(r.get(field),(int,float)))
  if v:timings[kind+'.'+field]={'n':len(v),'mean':statistics.mean(v),'median':statistics.median(v),'p95':v[math.ceil(.95*len(v))-1],'max':max(v)}
per_policy=[]
for pol in json.loads((p/'policies.json').read_text()):
 subset=[r for r in group('continuous') if r['policy']==pol['id'] and (not new_pipeline or r.get('segment')=='resumed')]
 entry=dict(pol,completed=len(subset),correct=sum(r['ok'] for r in subset))
 for field in ['encryptMs','transformMs','decryptMs']:
  v=[r[field] for r in subset if r['ok'] and (not new_pipeline or kind=='registration' or r.get('segment')=='resumed') and isinstance(r.get(field),(int,float))]
  entry[field]=statistics.mean(v) if v else None
 per_policy.append(entry)
complete=status['complete'] and (bool(new_pipeline) or env['profile']!='full' or extension_complete)
result={'acceptancePassed':env['profile']=='full' and complete and all(checks.values()),'complete':complete,
 'timingScope':'registration from original run; pipeline phases from resumed segment only' if new_pipeline else 'original run',
 'profile':env['profile'],'metrics':m,'criteria':checks,'timings':timings,'perPolicy':per_policy,
 'evidenceSha256':hashlib.sha256((p/'records.jsonl').read_bytes()).hexdigest(),
 'extensionEvidenceSha256':hashlib.sha256((extension/'records.jsonl').read_bytes()).hexdigest() if extension_complete else None,
 'notes':['Counts are empirical outcomes, not a protocol security proof.','Revocation covers 100 independent ciphertext rounds with 20 targets per round. Resumed runs execute all rounds in one segment.','Tracing is white-box scalar-copy tracing; categories have identical cryptographic input form.']}
(p/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({'acceptancePassed':result['acceptancePassed'],'metrics':m},ensure_ascii=False))
