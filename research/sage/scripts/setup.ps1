$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# Use an existing Python 3.11 installation. No system CUDA toolkit is needed.
py -3.11 -m venv .venv
if ($LASTEXITCODE -ne 0) { throw 'Install Python 3.11 or use the documented uv setup.' }
& .venv/Scripts/python.exe -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Scientific dependency installation failed' }
& .venv/Scripts/python.exe -m pip install torch==2.7.1 --index-url https://download.pytorch.org/whl/cu128
if ($LASTEXITCODE -ne 0) { throw 'CUDA PyTorch installation failed' }
& .venv/Scripts/python.exe -c 'from sage.models import require_cuda; print(require_cuda())'
if ($LASTEXITCODE -ne 0) { throw 'CUDA verification failed; training will not fall back to CPU' }
