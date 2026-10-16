#!/usr/bin/env python3
"""Project runtime only. Never impute unobserved success/failure outcomes."""
import collections, datetime, json, pathlib, statistics, sys
p=pathlib.Path(sys.argv[1]);rows=[json.loads(x) for x in (p/'records.jsonl').read_text().splitlines()]
groups=collections.defaultdict(list)
for r in rows:
 if r['kind']=='pipeline':groups[r['policy']].append(r)
assert len(groups)==10 and all(all(r['ok'] for r in rs) for rs in groups.values())
per=[]
for name,rs in sorted(groups.items()):
 mean=statistics.mean(r['wallMs'] for r in rs)
 per.append({'policy':name,'observed':len(rs),'observedMeanMs':mean,'targetTrials':100,'estimatedTotalMs':100*mean,'remainingTrials':max(0,100-len(rs)),'estimatedRemainingMs':max(0,100-len(rs))*mean})
result={'label':'ESTIMATE_NOT_MEASURED','method':'Stratified linear projection: 100 trials per policy × that policy observed mean end-to-end latency.','assumptions':['Same hardware, parameters, payload and concurrency=1.','Per-policy latency remains stationary.','No additional failures, queueing, network degradation or persistent-state growth effects.'],'perPolicy':per,'estimated1000PipelineSeconds':sum(r['estimatedTotalMs'] for r in per)/1000,'estimatedRemainingPipelineSeconds':sum(r['estimatedRemainingMs'] for r in per)/1000,'unmeasuredFunctionalRates':None,'warning':'Runtime projection does not predict success, denial, bilateral authentication or tracing rates.'}
(p/'estimates.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in result.items() if k not in ['perPolicy','assumptions']},ensure_ascii=False))
