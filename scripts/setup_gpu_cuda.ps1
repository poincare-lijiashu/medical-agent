#requires -Version 5.1
# One-shot GPU (CUDA torch) enabler for the edu_agent env, with verify + rollback pointer.
# Run when a good connection is available (this box's international bandwidth is poor;
# the CPU fallback is fully functional). embedder/reranker auto-select GPU once this succeeds.

$ErrorActionPreference = "Stop"
$py = "python"
$proj = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

Write-Host "[1/4] snapshot current versions (rollback baseline)"
& $py -m pip freeze | Out-File -Encoding utf8 (Join-Path $proj "data\env_before_gpu.txt")
$cur = (& $py -c "import torch;print(torch.__version__)")
Write-Host "      current torch = $cur"

Write-Host "[2/4] install CUDA torch (pick a reachable index; try aliyun then pytorch-official)"
$ok = $false
foreach ($idx in @("https://mirrors.aliyun.com/pytorch-wheels/cu124/", "https://download.pytorch.org/whl/cu124")) {
    Write-Host "      trying: $idx"
    try {
        if ($idx -like "*aliyun*") {
            & $py -m pip install --no-deps --force-reinstall "torch==2.5.1+cu124" -f $idx
        } else {
            & $py -m pip install --no-deps --force-reinstall --index-url $idx "torch==2.5.1"
        }
        $ok = $true; break
    } catch { Write-Host "      failed: $($_.Exception.Message)" }
}
if (-not $ok) { Write-Host "GPU install failed; env remains on previous torch."; exit 1 }

Write-Host "[3/4] verify CUDA visible"
& $py -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available(), 'dev', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'none')"

Write-Host "[4/4] app regression on GPU"
Push-Location $proj
& $py -c "import os,sys; sys.path.insert(0,'.'); from backend.core.embedder import embed_texts, device; import time; t=time.time(); v=embed_texts(['metformin first line']*64); print('device',device(),'n',len(v),'dim',len(v[0]),'sec',round(time.time()-t,2))"
Pop-Location

Write-Host "Done. Rollback if needed: & $py -m pip install --force-reinstall torch==2.5.1 --index-url https://pypi.tuna.tsinghua.edu.cn/simple  (CPU build)"
Write-Host "After a successful GPU switch, restart the backend so it re-selects the device."
