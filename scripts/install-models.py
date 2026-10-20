"""Download pinned local demo models with checksum verification; no API keys needed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--proxy', default=os.environ.get('RAG_DOWNLOAD_PROXY', ''))
    args = parser.parse_args()
    for item in json.loads((ROOT / 'scripts/model-manifest.json').read_text('utf-8')):
        target = (LOCAL / item['path']).resolve()
        if not target.is_relative_to(LOCAL.resolve()):
            raise ValueError('Download path escaped .local')
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and digest(target) == item['sha256']:
            print(f"Verified existing {item['name']}", flush=True)
            continue
        # GitHub works directly; a proxy is optional for model-host downloads.
        proxy = args.proxy if 'huggingface.co/' in item['url'] else ''
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({'http': proxy, 'https': proxy} if proxy else {}))
        part = target.with_name(target.name + '.part')
        print(f"Downloading {item['name']} ...", flush=True)
        request = urllib.request.Request(item['url'], headers={'User-Agent': 'rag-mvp-setup/1'})
        with opener.open(request, timeout=120) as response, part.open('wb') as output:
            last_mb = -100
            total = 0
            while True:
                chunk = response.read(4 * 1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                total += len(chunk)
                mb = total // 1048576
                if mb - last_mb >= 100:
                    print(f"  {mb} MiB", flush=True)
                    last_mb = mb
        if digest(part) != item['sha256']:
            raise ValueError(f"Checksum mismatch: {item['name']}. Incomplete file retained as .part.")
        part.replace(target)
    archive = next(x for x in json.loads((ROOT / 'scripts/model-manifest.json').read_text('utf-8')) if x['name'] == 'llama.cpp')
    destination = LOCAL / 'tools/llama'
    if not (destination / 'llama-server.exe').exists():
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(LOCAL / archive['path']) as z:
            for entry in z.infolist():
                if not (destination / entry.filename).resolve().is_relative_to(destination.resolve()):
                    raise ValueError('Unsafe zip member')
            z.extractall(destination)
    print('Local model files are ready.', flush=True)

if __name__ == '__main__':
    main()
