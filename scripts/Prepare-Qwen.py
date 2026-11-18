"""Download immutable original Qwen weights and converter outside the workspace."""
from pathlib import Path
import hashlib
import sys
import tarfile
import urllib.request

RUNTIME = '2d8a32502909ca065c41c4cf5f2fb1c427c047fc'
REVISION = 'da87bfb608c14b7cf20ba1ce41287e8de496c0cd'
root = Path(sys.argv[1]).resolve()
workspace = Path(__file__).resolve().parents[1]
if root == workspace or workspace in root.parents:
    raise SystemExit('Choose an artifact directory outside the repository')
root.mkdir(parents=True, exist_ok=True)

def download(url, path, checksum=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.part')
    with urllib.request.urlopen(url, timeout=120) as source, temporary.open('wb') as output:
        while block := source.read(1024 * 1024):
            output.write(block)
    with temporary.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if checksum and checksum != digest:
        temporary.unlink()
        raise RuntimeError('Checksum mismatch: ' + path.name)
    temporary.replace(path)
    print(path.name, digest, flush=True)

archive = root / 'llama-runtime.tar.gz'
download(f'https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{RUNTIME}', archive,
         '7befec5f2a7fbd7fc69b69e4f80c9824a64cb760cd1b18aa0e1a6b1af53de7d8')
with tarfile.open(archive) as bundle:
    for item in bundle.getmembers():
        if root not in (root / item.name).resolve().parents or item.issym() or item.islnk():
            raise RuntimeError('Unsafe runtime archive')
    bundle.extractall(root, filter='data')
model = root / 'upstream-model'
for name in ['LICENSE', 'config.json', 'generation_config.json', 'merges.txt',
             'model.safetensors', 'tokenizer.json', 'tokenizer_config.json', 'vocab.json']:
    checksum = 'cd2a512003e2f9f3cd3c32a9c3573f820bb28c940f73c57b1ddaa983d9223eba' if name == 'model.safetensors' else None
    download(f'https://huggingface.co/Qwen/Qwen3-0.6B-Base/resolve/{REVISION}/{name}', model / name, checksum)
runtime = root / f'llama.cpp-{RUNTIME}'
f16 = root / 'Qwen3-0.6B-Base-F16.gguf'
output = root / 'Qwen3-0.6B-Base-Q4_K_M.gguf'
print(f'python "{runtime / "convert_hf_to_gguf.py"}" "{model}" --outfile "{f16}" --outtype f16')
print(f'llama-quantize "{f16}" "{output}" Q4_K_M 2')
