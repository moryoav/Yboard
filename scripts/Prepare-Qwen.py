"""Download immutable original Qwen weights and converter outside the workspace."""
from pathlib import Path
import argparse
import hashlib
import tarfile
import urllib.request

RUNTIME = '2d8a32502909ca065c41c4cf5f2fb1c427c047fc'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output_directory', type=Path)
parser.add_argument('--qwen35', action='store_true', help='Prepare Qwen3.5-0.8B-Base')
arguments = parser.parse_args()
model_name, revision, weights, weight_hash = (
    ('Qwen3.5-0.8B-Base', 'dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68',
     'model.safetensors-00001-of-00001.safetensors',
     'c2b1e5a17d9c1e27685d92ed9b382911ebb99955ecd89052d1721241adfbab6c')
    if arguments.qwen35 else
    ('Qwen3-0.6B-Base', 'da87bfb608c14b7cf20ba1ce41287e8de496c0cd',
     'model.safetensors', 'cd2a512003e2f9f3cd3c32a9c3573f820bb28c940f73c57b1ddaa983d9223eba')
)
root = arguments.output_directory.resolve()
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
files = {'LICENSE': None, 'config.json': None, 'merges.txt': None, weights: weight_hash,
         'tokenizer.json': None, 'tokenizer_config.json': None, 'vocab.json': None}
if arguments.qwen35:
    files['model.safetensors.index.json'] = None
    files['tokenizer.json'] = 'fe000e3ed39ed12b8d2481d527d44f93c65d37e87645d2dcc80d1bf9d50d2927'
else:
    files['generation_config.json'] = None
for name, checksum in files.items():
    download(f'https://huggingface.co/Qwen/{model_name}/resolve/{revision}/{name}', model / name, checksum)
runtime = root / f'llama.cpp-{RUNTIME}'
f16 = root / f'{model_name}-F16.gguf'
output = root / f'{model_name}-Q4_K_M.gguf'
print(f'python "{runtime / "convert_hf_to_gguf.py"}" "{model}" --outfile "{f16}" --outtype f16')
print(f'llama-quantize "{f16}" "{output}" Q4_K_M 2')
