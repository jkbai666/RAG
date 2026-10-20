"""Local-only destructive-to-own-test-data checks; never touches port 3306."""
import json
import shutil
import subprocess
import time
from pathlib import Path
import integration as api

ROOT = Path(__file__).resolve().parent.parent
shell = shutil.which('pwsh') or shutil.which('powershell')
results=[]

def script(name,*args):
    subprocess.run([shell,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts'/name),*args],cwd=ROOT,check=True)

def stop(service): script('stop.ps1','-Service',service)
def start(): script('start.ps1','-SkipBuild')
def passed(name):
    results.append({'case':name,'result':'PASS'})
    print('PASS '+name,flush=True)

def chroma_vectors(doc_id):
    from urllib import request
    base='http://127.0.0.1:8000/api/v2/tenants/default_tenant/databases/default_database/collections'
    with request.urlopen(base+'/rag_mvp') as r: collection=json.load(r)
    data=json.dumps({'where':{'documentId':doc_id},'include':[]}).encode()
    req=request.Request(base+'/'+collection['id']+'/get',data=data,headers={'Content-Type':'application/json'})
    with request.urlopen(req) as r: return json.load(r)['ids']

try:
    baseline=api.call('GET','/knowledge/status')['readyDocuments']
    doc=api.processed(api.upload('resilience-青禾.txt',(ROOT/'tests/fixtures/青禾项目说明.txt').read_bytes()))
    assert chroma_vectors(doc['id'])
    before=api.call('GET',f"/documents/{doc['id']}/chunks")
    stop('chat-model')
    failure=api.ask('青禾项目的负责人是谁？',502)
    assert failure['code']=='UPSTREAM_FAILURE' and '生成模型' in failure['message'],failure
    passed('real chat model outage returns technical failure, not lack of evidence')
    start()
    assert api.ask('青禾项目负责人是谁？')['grounded'] is True
    stop('chroma')
    api.ask('青禾项目负责人是谁？',502)
    api.call('DELETE','/documents/'+doc['id'],expected=502)
    failed=api.call('GET','/documents/'+doc['id'])
    assert failed['status']=='FAILED' and failed['errorMessage']
    assert api.call('GET','/knowledge/status')['readyDocuments']==baseline
    passed('real Chroma outage blocks deletion, retains retriable non-searchable document')
    # New imports must not be READY if vector writes fail.
    bad=api.processed(api.upload('resilience-write-failure.txt','Chroma故障时的导入不应就绪。'.encode()),'FAILED')
    assert bad['chunkCount']==0
    passed('real Chroma write failure leaves import FAILED')
    start()
    api.call('DELETE','/documents/'+doc['id'],expected=204)
    api.created.remove(doc['id'])
    assert chroma_vectors(doc['id'])==[]
    api.call('DELETE','/documents/'+bad['id'],expected=204)
    api.created.remove(bad['id'])
    assert chroma_vectors(bad['id'])==[]
    passed('recovered deletion cleans actual Chroma vectors and MySQL records')
    retained=api.processed(api.upload('resilience-persistent.txt',(ROOT/'tests/fixtures/青禾项目说明.txt').read_bytes()))
    original=api.call('GET',f"/documents/{retained['id']}/chunks")
    stop('backend'); stop('mysql'); stop('chroma')
    start()
    after=api.call('GET',f"/documents/{retained['id']}/chunks")
    assert after==original
    answer=api.ask('青禾项目负责人是谁？')
    assert answer['grounded'] and '林溪' in answer['answer']
    assert any(s['documentId']==retained['id'] for s in answer['sources'])
    passed('actual backend, MySQL and Chroma restart preserves chunks, vectors and answers')
    interrupted=api.upload('resilience-interrupted.txt',('处理中断测试，青禾负责人为林溪。\n'*10000).encode())
    stop('backend')
    start()
    recovered=api.call('GET','/documents/'+interrupted['id'])
    assert recovered['status']=='FAILED' and '重启' in recovered['errorMessage'],recovered
    passed('interrupted PROCESSING import recovers to FAILED on real backend restart')
except Exception as e:
    results.append({'case':'resilience run','result':'FAIL','detail':repr(e)})
    raise
finally:
    start()
    for doc_id in list(api.created):
        try:
            api.call('DELETE','/documents/'+doc_id,expected=204)
            assert chroma_vectors(doc_id)==[]
            api.created.remove(doc_id)
        except Exception as e: print('Test-only cleanup issue',doc_id,str(e),flush=True)
    output=ROOT/'.local/test-results'
    output.mkdir(parents=True,exist_ok=True)
    (output/'resilience.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),'utf-8')
