#!/usr/bin/env python3
"""Exercise a real local NeoForge server. Requires the app to be running.
Creates its own instance and preserves its world/logs for inspection.
EULA auto-acceptance is the owner's requested local development setting.
"""
import json, os, time, urllib.request, urllib.error

BASE=os.environ.get('SERVER_MANAGER_URL','http://127.0.0.1:8787')+'/api'
def call(path, method='GET', body=None):
    data=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(BASE+path,data=data,method=method,headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=60) as res:
            payload=res.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'{method} {path}: {e.code} {e.read().decode()}') from e

def wait_state(id, desired, seconds=600):
    end=time.time()+seconds
    last=None
    while time.time()<end:
        row=next(i for i in call('/instances') if i['config']['id']==id)
        state=row['state']
        if state!=last:
            print('state:',state,flush=True);last=state
        if state==desired:return row
        if state=='FAILED':raise RuntimeError(json.dumps(call(f'/instances/{id}/events')[-20:],ensure_ascii=False))
        time.sleep(2)
    raise TimeoutError(desired)

def all_events(prefix):
    events=[]
    after=0
    while True:
        batch=call(prefix+'/events?after='+str(after))
        if not batch:return events
        events.extend(batch)
        after=batch[-1]['seq']

health=call('/health')
memory=call('/memory')
print('Memory capacity:',memory,flush=True)
assert memory['maxHeapMb']==memory['totalMb']-memory['reservedMb']
versions=call('/versions')
ports={i['config']['port'] for i in call('/instances')}
port=25575
while port in ports:port+=1
existing=next((i for i in call('/instances') if i['config']['name']=='로컬 검증 서버' and i['state']=='STOPPED'),None)
row=existing or call('/instances','POST',dict(name='로컬 검증 서버',loaderVersion=versions[0],port=port,memoryMb=2048,javaPath=health['javaPath']))
id=row['config']['id']; prefix='/instances/'+id
print('INSTANCE_ID='+id,flush=True)
call(prefix+'/folders','POST',{'path':'config'})
call(prefix+'/text','PUT',{'path':'config/local-check.txt','content':'first'})
doc=call(prefix+'/text?path=config/local-check.txt')
call(prefix+'/text','PUT',{'path':'config/local-check.txt','content':'verified ✓','revision':doc['revision']})
assert call(prefix+'/text?path=config/local-check.txt')['content']=='verified ✓'
call(prefix+'/move','POST',{'from':'config/local-check.txt','to':'config/renamed.txt'})
call(prefix+'/files?path=config/renamed.txt','DELETE')
print('PASS file create/read/edit/move/delete',flush=True)
if not existing:
    call(prefix+'/install','POST')
    wait_state(id,'STOPPED')
started=False
try:
    call(prefix+'/start','POST');started=True
    wait_state(id,'RUNNING',300)
    try:
        call(prefix+'/text','PUT',{'path':'config/blocked.txt','content':'blocked'})
        raise AssertionError('running write was allowed')
    except RuntimeError as e:
        assert '409' in str(e)
    call(prefix+'/command','POST',{'command':'say LOCAL_CONSOLE_VERIFIED'})
    call(prefix+'/command','POST',{'command':'list'})
    deadline=time.time()+20
    while time.time()<deadline:
        events=all_events(prefix)
        if any(e['kind']=='output' and 'LOCAL_CONSOLE_VERIFIED' in e['text'] for e in events):break
        time.sleep(1)
    else:raise AssertionError('console output missing')
    assert any(e['kind']=='input' and e['text']=='list' for e in events)
    print('PASS real server startup, command input/output, running-file lock',flush=True)
    old_pid=next(i['pid'] for i in call('/instances') if i['config']['id']==id)
    call(prefix+'/restart','POST')
    time.sleep(3)
    row=wait_state(id,'RUNNING',300)
    assert row['pid']!=old_pid
    print('PASS graceful restart',flush=True)
finally:
    if started:
        state=next(i['state'] for i in call('/instances') if i['config']['id']==id)
        if state in ('RUNNING','STARTING'):call(prefix+'/stop','POST')
        wait_state(id,'STOPPED',120)
print('PASS graceful stop; all local smoke checks passed',flush=True)
