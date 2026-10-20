"""Real backend/model/MySQL/Chroma smoke regression. Uses only its own uploaded IDs."""
import argparse
import json
import time
import uuid
from pathlib import Path
from urllib import request, error

BASE = 'http://127.0.0.1:8088/api'
ROOT = Path(__file__).resolve().parent
created = []
results = []

def call(method, path, data=None, headers=None, expected=200):
    req = request.Request(BASE + path, data=data, headers=headers or {}, method=method)
    try:
        with request.urlopen(req, timeout=150) as response:
            status, payload = response.status, response.read()
    except error.HTTPError as e:
        status, payload = e.code, e.read()
    assert status == expected, (method, path, status, payload.decode('utf-8', errors='replace')[:600])
    return json.loads(payload) if payload else None

def ask(question, expected=200):
    return call('POST','/chat', json.dumps({'question':question},ensure_ascii=False).encode(), {'Content-Type':'application/json'}, expected)

def upload(name, content, expected=202):
    boundary = 'RagTest' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    doc = call('POST','/documents',body,{'Content-Type':f'multipart/form-data; boundary={boundary}'},expected)
    if expected == 202:
        created.append(doc['id'])
        assert doc['status'] == 'PROCESSING'
    return doc

def processed(doc, state='READY'):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        current = call('GET','/documents/' + doc['id'])
        if current['status'] != 'PROCESSING':
            assert current['status'] == state, current
            return current
        time.sleep(.3)
    raise AssertionError('Import did not finish: ' + doc['id'])

def check(name, callback):
    callback()
    results.append({'case':name,'result':'PASS'})
    print('PASS ' + name, flush=True)

def run():
    assert call('GET','/health')['status'] == 'UP'
    initial = call('GET','/knowledge/status')
    assert initial['modelConfigured'] is True
    check('question validation', lambda: ask('   ',400))
    check('overlong Unicode question validation', lambda: ask('😀'*2001,400))
    if initial['readyDocuments'] == 0:
        check('empty knowledge base', lambda: ask('谁是负责人？',409))
    check('unsupported extension', lambda: upload('blocked.exe',b'bad',422))
    check('empty file', lambda: upload('empty.txt',b'',422))
    check('oversized file', lambda: upload('too-large.txt',b'x'*(20*1024*1024+1),413))
    invalid = processed(upload('broken.pdf', b'not a PDF'), 'FAILED')
    assert invalid['errorMessage'] and invalid['chunkCount'] == 0
    check('failed PDF has readable failure and is not READY', lambda: None)
    scanned = processed(upload('integration-scanned.pdf',(ROOT/'fixtures/扫描型.pdf').read_bytes()),'FAILED')
    assert '正文' in scanned['errorMessage'] or 'PDF' in scanned['errorMessage']
    check('image-only PDF explicitly fails without OCR',lambda: None)
    invalid_text = processed(upload('integration-invalid-encoding.txt',bytes([255,254,255])),'FAILED')
    assert invalid_text['errorMessage']
    check('invalid UTF-8 is not silently decoded',lambda: None)
    txt = processed(upload('integration-' + uuid.uuid4().hex[:6] + '-青禾.txt',(ROOT/'fixtures/青禾项目说明.txt').read_bytes()))
    duplicate = processed(upload(txt['fileName'],(ROOT/'fixtures/青禾项目说明.txt').read_bytes()))
    assert duplicate['id'] != txt['id'] and duplicate['fileName'] == txt['fileName']
    original_ids = {c['id'] for c in call('GET',f"/documents/{txt['id']}/chunks")['items']}
    assert original_ids.isdisjoint({c['id'] for c in call('GET',f"/documents/{duplicate['id']}/chunks")['items']})
    check('same-name uploads retain independent document and chunk IDs',lambda: None)
    md = processed(upload('integration-导入说明.md',(ROOT/'fixtures/导入说明.md').read_bytes()))
    pdf = processed(upload('integration-两页.pdf',(ROOT/'fixtures/两页文本.pdf').read_bytes()))
    chunks = call('GET',f"/documents/{txt['id']}/chunks")
    assert chunks['total'] >= 1 and '林溪' in ''.join(c['text'] for c in chunks['items'])
    assert all(c['index'] == i+1 for i,c in enumerate(chunks['items']))
    assert all(c['pageStart'] is None and c['pageEnd'] is None for c in chunks['items'])
    check('TXT and Markdown import', lambda: None)
    pages = call('GET',f"/documents/{pdf['id']}/chunks")
    assert pages['items'][0]['pageStart'] == 1 and pages['items'][-1]['pageEnd'] == 2, pages
    assert '林溪' in ''.join(c['text'] for c in pages['items']) and '2026' in ''.join(c['text'] for c in pages['items'])
    assert all(c['pageEnd'] >= c['pageStart'] for c in pages['items'])
    check('two-page text PDF with page metadata',lambda: None)
    text = ''.join(f'第{i:04d}项，青禾的资料需要保留全文😀。\n' for i in range(270))
    big = processed(upload('integration-unicode.txt',text.encode()))
    first = call('GET',f"/documents/{big['id']}/chunks?page=1&pageSize=20")
    second = call('GET',f"/documents/{big['id']}/chunks?page=2&pageSize=20")
    assert first['total'] > 20 and second['items'][0]['index'] == 21, (first['total'],second)
    all_chunks = first['items'] + second['items']
    page = 3
    while len(all_chunks) < first['total']:
        all_chunks.extend(call('GET',f"/documents/{big['id']}/chunks?page={page}&pageSize=20")['items'])
        page += 1
    assert all(len(c['text']) <= 300 for c in all_chunks)
    assert all(all_chunks[i]['text'][-50:] == all_chunks[i+1]['text'][:50] for i in range(len(all_chunks)-1))
    reconstructed = all_chunks[0]['text'] + ''.join(c['text'][50:] for c in all_chunks[1:])
    assert reconstructed == text, (len(reconstructed),len(text))
    check('Unicode chunk overlap, full reconstruction and pagination',lambda: None)
    answer = ask('青禾项目的负责人是谁？')
    assert answer['grounded'] is True and '林溪' in answer['answer'] and answer['sources'], answer
    for source in answer['sources']:
        actual = call('GET',f"/documents/{source['documentId']}/chunks?pageSize=100")
        assert any(c['id'] == source['chunkId'] and c['text'] == source['text'] and c['index'] == source['chunkIndex'] for c in actual['items'])
    check('real grounded answer and exact source snapshots',lambda: None)
    unknown = ask('青禾项目的预算金额具体是多少元？')
    assert unknown['grounded'] is False and unknown['sources'] == [], unknown
    check('missing evidence is not fabricated',lambda: None)
    call('DELETE','/documents/'+md['id'],expected=204)
    created.remove(md['id'])
    call('GET','/documents/'+md['id'],expected=404)
    check('document deletion removes API record',lambda: None)
    return answer

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--keep-demo',action='store_true')
    args=parser.parse_args()
    failure=None
    try:
        answer=run()
    except Exception as e:
        failure=repr(e)
        results.append({'case':'integration failure','result':'FAIL','detail':failure})
        raise
    finally:
        for doc_id in list(created):
            try:
                current=call('GET','/documents/'+doc_id)
                if current['status']=='PROCESSING': processed(current,state='FAILED')
                call('DELETE','/documents/'+doc_id,expected=204)
                created.remove(doc_id)
            except Exception as cleanup:
                print('Cleanup issue for test-owned document',doc_id,str(cleanup)[:200],flush=True)
        out=ROOT.parent/'.local/test-results'
        out.mkdir(parents=True,exist_ok=True)
        (out/'integration.json').write_text(json.dumps({'cases':results,'failure':failure},ensure_ascii=False,indent=2),'utf-8')
    if args.keep_demo:
        docs=call('GET','/documents')['items']
        if not any(d['fileName']=='青禾项目说明.txt' and d['status']=='READY' for d in docs):
            processed(upload('青禾项目说明.txt',(ROOT/'fixtures/青禾项目说明.txt').read_bytes()))
        print('Demo document retained for trying the UI.')
    print(f'{len(results)} checks passed; test-created documents cleaned up.')

if __name__=='__main__': main()
