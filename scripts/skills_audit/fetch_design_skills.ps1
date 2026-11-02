# 抓取两份榜单中的 20 条设计类 Agent Skill 原文，并生成"原版 / 去重版"两份文档。
# 原则：raw 文件逐字节下载；合并文档只加元信息头，正文不做任何改写。

$ErrorActionPreference = 'Stop'
$root = '<repo>'
$outDir = Join-Path $root 'docs\skills-audit'
$outRaw = Join-Path $outDir 'raw'
New-Item -ItemType Directory -Force -Path $outRaw | Out-Null

$entries = @(
  @{ i = 1;  listed = 'frontend-design-direction';  slug = '01-frontend-design-direction';  repo = 'affaan-m/ECC';                          path = 'skills/frontend-design-direction/SKILL.md';           note = '' },
  @{ i = 2;  listed = 'frontend-design';            slug = '02-frontend-design';            repo = 'anthropics/skills';                     path = 'skills/frontend-design/SKILL.md';                      note = '' },
  @{ i = 3;  listed = 'UI/UX Pro Max';              slug = '03-ui-ux-pro-max';              repo = 'nextlevelbuilder/ui-ux-pro-max-skill';  path = '.claude/skills/ui-ux-pro-max/SKILL.md';                note = '榜单使用展示名 UI/UX Pro Max' },
  @{ i = 4;  listed = 'design-review';              slug = '04-design-review';              repo = 'aictrl-dev/skills';                     path = 'skills/design-review/SKILL.md';                        note = '榜单仅给名称；按技能描述匹配到该仓库' },
  @{ i = 5;  listed = 'shadcn';                     slug = '05-shadcn';                     repo = 'shadcn-ui/ui';                          path = 'skills/shadcn/SKILL.md';                               note = '' },
  @{ i = 6;  listed = 'frontend-ui-engineering';    slug = '06-frontend-ui-engineering';    repo = 'addyosmani/agent-skills';               path = 'skills/frontend-ui-engineering/SKILL.md';              note = '榜单仅给名称；按官方技能页溯源到 addyosmani/agent-skills' },
  @{ i = 7;  listed = 'reference-design-contract';  slug = '07-reference-design-contract';  repo = 'nexu-io/open-design';                   path = 'skills/reference-design-contract/SKILL.md';            note = '榜单仅给名称；按 OpenDesign 溯源' },
  @{ i = 8;  listed = 'Taste Skill';                slug = '08-taste-skill';                repo = 'Leonxlnx/taste-skill';                  path = 'skills/taste-skill/SKILL.md';                          note = '安装名 design-taste-frontend' },
  @{ i = 9;  listed = 'Impeccable';                 slug = '09-impeccable';                 repo = 'pbakaus/impeccable';                    path = '.agent/skills/impeccable/SKILL.md';                    note = '仓库内多份等价副本，取 .agent 路径' },
  @{ i = 10; listed = 'emil-design-eng';            slug = '10-emil-design-eng';            repo = 'emilkowalski/skills';                   path = 'skills/emil-design-eng/SKILL.md';                      note = '' },
  @{ i = 11; listed = 'frontend-design';            slug = '11-frontend-design-dup';        repo = 'anthropics/skills';                     path = 'skills/frontend-design/SKILL.md';                      note = '与 #2 同一技能（榜单重复条目）' },
  @{ i = 12; listed = 'prototype';                  slug = '12-prototype';                  repo = 'mattpocock/skills';                     path = 'skills/engineering/prototype/SKILL.md';                note = '' },
  @{ i = 13; listed = 'hyperframes-animation';      slug = '13-hyperframes-animation';      repo = 'heygen-com/hyperframes';                path = 'skills/hyperframes/SKILL.md';                          note = '榜单名 hyperframes-animation；官方仓库当前合并为 hyperframes 主技能' },
  @{ i = 14; listed = 'codebase-design';            slug = '14-codebase-design';            repo = 'mattpocock/skills';                     path = 'skills/engineering/codebase-design/SKILL.md';          note = '' },
  @{ i = 15; listed = 'design-mobile-apps';         slug = '15-design-mobile-apps';         repo = 'sleekdotdesign/agent-skills';           path = 'skills/design-mobile-apps/SKILL.md';                   note = '' },
  @{ i = 16; listed = 'hyperframes-creative';       slug = '16-hyperframes-creative';       repo = 'heygen-com/hyperframes';                path = 'skills/hyperframes/SKILL.md';                          note = '榜单名 hyperframes-creative；与 #13 同一文件（官方已合并）' },
  @{ i = 17; listed = 'web-design-guidelines';      slug = '17-web-design-guidelines';      repo = 'vercel-labs/agent-skills';              path = 'skills/web-design-guidelines/SKILL.md';                note = '' },
  @{ i = 18; listed = 'design-taste-frontend';      slug = '18-design-taste-frontend';      repo = 'Leonxlnx/taste-skill';                  path = 'skills/taste-skill/SKILL.md';                          note = '与 #8 同一技能（Taste Skill = design-taste-frontend）' },
  @{ i = 19; listed = 'high-end-visual-design';     slug = '19-high-end-visual-design';     repo = 'Leonxlnx/taste-skill-classics';         path = 'skills/soft-skill/SKILL.md';                           note = '官方已迁移到 taste-skill-classics 的 soft-skill（OpenDesign 溯源记录）' },
  @{ i = 20; listed = 'ui-ux-pro-max';              slug = '20-ui-ux-pro-max-dup';          repo = 'nextlevelbuilder/ui-ux-pro-max-skill';  path = '.claude/skills/ui-ux-pro-max/SKILL.md';                note = '与 #3 同一技能（榜单重复条目）' }
)

$results = @()
foreach ($e in $entries) {
  $url = "https://raw.githubusercontent.com/$($e.repo)/main/$($e.path)"
  $file = Join-Path $outRaw ($e.slug + '.md')
  $row = [ordered]@{
    index = $e.i
    listedName = $e.listed
    slug = $e.slug
    repo = $e.repo
    path = $e.path
    url = $url
    note = $e.note
    ok = $false
    bytes = 0
    sha256 = ''
    savedAs = ('raw/' + $e.slug + '.md')
    error = ''
  }
  try {
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing -TimeoutSec 60
    $len = (Get-Item -LiteralPath $file).Length
    if ($len -lt 200) { throw "file too small ($len bytes)" }
    $row.ok = $true
    $row.bytes = $len
    $row.sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLower()
  } catch {
    $row.error = $_.Exception.Message
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
  }
  $results += [pscustomobject]$row
  $status = if ($row.ok) { 'OK  ' + $row.bytes } else { 'FAIL ' + $row.error }
  Write-Host ("[{0:D2}] {1} -> {2}" -f $e.i, $e.listed, $status)
}

# manifest.json（UTF-8 无 BOM）
$manifestPath = Join-Path $outDir 'manifest.json'
$manifestJson = $results | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($manifestPath, $manifestJson, (New-Object System.Text.UTF8Encoding($false)))

$enc = New-Object System.Text.UTF8Encoding($false)
$okCount = ($results | Where-Object { $_.ok }).Count

# ---------------- 原版合集 ----------------
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('# 设计类 Agent Skills 原版合集（20 条，GitHub 原文）')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('> 生成时间：2026-11-02  ')
[void]$sb.AppendLine('> 抓取方式：逐条从官方仓库 `raw.githubusercontent.com` 下载 `SKILL.md`，**正文未做任何改写、重排、翻译或润色**。  ')
[void]$sb.AppendLine('> 合并方式：本文档只在每篇正文前后添加元信息与边界标记，正文按原字节写入。  ')
[void]$sb.AppendLine('> 可复核：`docs/skills-audit/manifest.json` 记录每条原始地址、字节数与 SHA-256。  ')
[void]$sb.AppendLine("> 抓取结果：$okCount / 20 成功。")
[void]$sb.AppendLine('')
[void]$sb.AppendLine('## 抓取说明（务必先读）')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('1. 榜单来自用户上传的两张截图（网页/UI 设计 TOP10、下载量 TOP10），共 20 条，**存在 4 组重复**（见去重版）。')
[void]$sb.AppendLine('2. 4 条只给了名称、未给仓库（design-review、frontend-ui-engineering、reference-design-contract、design-mobile-apps），按技能官方页面/技能市场溯源到最匹配仓库，均已在条目标注。')
[void]$sb.AppendLine('3. `hyperframes-animation` 与 `hyperframes-creative` 在当前官方仓库已合并为 `skills/hyperframes/SKILL.md`；因此两条指向同一文件。')
[void]$sb.AppendLine('4. `high-end-visual-design` 已迁移到 `Leonxlnx/taste-skill-classics` 的 `soft-skill`，按 OpenDesign 的溯源记录抓取。')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('## 索引')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('| # | 榜单名称 | 仓库 | 路径 | 字节 | SHA-256（前 12 位） |')
[void]$sb.AppendLine('|---|---|---|---|---|---|')
foreach ($r in $results) {
  $short = if ($r.sha256) { $r.sha256.Substring(0, 12) } else { '—' }
  [void]$sb.AppendLine("| $($r.index) | $($r.listedName) | $($r.repo) | ``$($r.path)`` | $($r.bytes) | ``$short`` |")
}
[void]$sb.AppendLine('')
[void]$sb.AppendLine('---')
[void]$sb.AppendLine('')

foreach ($r in $results) {
  [void]$sb.AppendLine("## $($r.index). $($r.listedName)")
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine("- 榜单名称：$($r.listedName)")
  [void]$sb.AppendLine("- 仓库：``$($r.repo)``")
  [void]$sb.AppendLine("- 路径：``$($r.path)``")
  [void]$sb.AppendLine("- 原始地址：$($r.url)")
  [void]$sb.AppendLine("- 字节数：$($r.bytes)")
  [void]$sb.AppendLine("- SHA-256：``$($r.sha256)``")
  if ($r.note) { [void]$sb.AppendLine("- 说明：$($r.note)") }
  [void]$sb.AppendLine('')
  if ($r.ok) {
    $file = Join-Path $outRaw ($r.slug + '.md')
    $raw = [IO.File]::ReadAllText($file)
    [void]$sb.Append("<!-- ==================== BEGIN VERBATIM ==================== -->`r`n")
    [void]$sb.Append($raw)
    # 原文若本身没有结尾换行，结束标记会紧贴正文最后一行，以保证正文逐字节一致
    [void]$sb.Append("<!-- ==================== END VERBATIM ==================== -->`r`n")
  } else {
    [void]$sb.AppendLine("> 抓取失败：$($r.error)")
  }
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('---')
  [void]$sb.AppendLine('')
}
$originalPath = Join-Path $outDir 'ORIGINAL-20.md'
[IO.File]::WriteAllText($originalPath, $sb.ToString(), $enc)

# ---------------- 去重版 ----------------
$okRows = @($results | Where-Object { $_.ok })
$groups = @()
foreach ($r in $okRows) {
  $hit = $groups | Where-Object { $_.sha -eq $r.sha256 } | Select-Object -First 1
  if ($hit) {
    $hit.covers += $r.index
    $hit.names += $r.listedName
  } else {
    $groups += [pscustomobject]@{
      sha = $r.sha256
      canonical = $r
      covers = @($r.index)
      names = @($r.listedName)
    }
  }
}
$uniqueCount = $groups.Count

$db = New-Object System.Text.StringBuilder
[void]$db.AppendLine('# 设计类 Agent Skills 去重版（按内容哈希合并）')
[void]$db.AppendLine('')
[void]$db.AppendLine('> 生成时间：2026-11-02  ')
[void]$db.AppendLine("> 输入：``ORIGINAL-20.md`` 中 $okCount 条原文；去重规则：对正文计算 SHA-256，**哈希相同即视为同一内容**，只保留一份。  ")
[void]$db.AppendLine("> 结果：**$okCount 条 → $uniqueCount 份唯一内容**。  ")
[void]$db.AppendLine('> 正文仍为 GitHub 原文，未做改写；仅删除重复副本并在头部记录"覆盖了哪些榜单条目"。')
[void]$db.AppendLine('')
[void]$db.AppendLine('## 去重结果')
[void]$db.AppendLine('')
[void]$db.AppendLine('| 唯一内容 | 技能 | 覆盖榜单条目 | 合并说明 |')
[void]$db.AppendLine('|---|---|---|---|')
$n = 0
foreach ($g in $groups) {
  $n++
  $coverText = ($g.covers | ForEach-Object { "#$_" }) -join '、'
  $nameText = ($g.names | Select-Object -Unique) -join ' / '
  $mergeNote = if ($g.covers.Count -gt 1) { '同一内容在榜单出现多次，已合并' } else { '—' }
  [void]$db.AppendLine("| $n | $nameText | $coverText | $mergeNote |")
}
[void]$db.AppendLine('')
[void]$db.AppendLine('## 合并明细')
[void]$db.AppendLine('')
foreach ($g in $groups) {
  if ($g.covers.Count -gt 1) {
    $coverText = ($g.covers | ForEach-Object { "#$_" }) -join '、'
    [void]$db.AppendLine("- $($g.canonical.listedName)：$coverText → 保留 1 份（SHA-256 ``$($g.sha.Substring(0,12))…``）")
  }
}
[void]$db.AppendLine('')
[void]$db.AppendLine('> 注：`hyperframes-animation` 与 `hyperframes-creative` 在当前官方仓库已合并为同一文件，因此哈希相同；这不是抓取错误，而是上游结构变化。')
[void]$db.AppendLine('')
[void]$db.AppendLine('---')
[void]$db.AppendLine('')

$n = 0
foreach ($g in $groups) {
  $n++
  $r = $g.canonical
  [void]$db.AppendLine("## $n. $($r.listedName)")
  [void]$db.AppendLine('')
  [void]$db.AppendLine("- 覆盖榜单条目：$(($g.covers | ForEach-Object { "#$_" }) -join '、')")
  [void]$db.AppendLine("- 仓库：``$($r.repo)``")
  [void]$db.AppendLine("- 路径：``$($r.path)``")
  [void]$db.AppendLine("- 原始地址：$($r.url)")
  [void]$db.AppendLine("- 字节数：$($r.bytes)")
  [void]$db.AppendLine("- SHA-256：``$($r.sha256)``")
  if ($r.note) { [void]$db.AppendLine("- 说明：$($r.note)") }
  [void]$db.AppendLine('')
  $file = Join-Path $outRaw ($r.slug + '.md')
  $raw = [IO.File]::ReadAllText($file)
  [void]$db.Append("<!-- ==================== BEGIN VERBATIM ==================== -->`r`n")
  [void]$db.Append($raw)
  [void]$db.Append("<!-- ==================== END VERBATIM ==================== -->`r`n")
  [void]$db.AppendLine('')
  [void]$db.AppendLine('---')
  [void]$db.AppendLine('')
}
$dedupPath = Join-Path $outDir 'DEDUPED.md'
[IO.File]::WriteAllText($dedupPath, $db.ToString(), $enc)

# ---------------- 自检：从合并文档中反抽正文，逐条比对 SHA-256 ----------------
function Verify-Doc([string]$docPath, $rows) {
  $text = [IO.File]::ReadAllText($docPath)
  $begin = '<!-- ==================== BEGIN VERBATIM ==================== -->'
  $end = '<!-- ==================== END VERBATIM ==================== -->'
  $pos = 0
  $pass = 0
  $fail = 0
  foreach ($r in $rows) {
    if (-not $r.ok) { continue }
    $bi = $text.IndexOf($begin, $pos)
    if ($bi -lt 0) { $fail++; continue }
    $start = $bi + $begin.Length
    if ($text.Substring($start, 2) -eq "`r`n") { $start += 2 }
    elseif ($text[$start] -eq "`n") { $start += 1 }
    $ei = $text.IndexOf($end, $start)
    if ($ei -lt 0) { $fail++; continue }
    $segment = $text.Substring($start, $ei - $start)
    $sha = [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash([Text.Encoding]::UTF8.GetBytes($segment))).Replace('-', '').ToLower()
    if ($sha -eq $r.sha256) { $pass++ } else { $fail++; Write-Host ("  verify FAIL: {0}" -f $r.listedName) }
    $pos = $ei + $end.Length
  }
  $name = Split-Path $docPath -Leaf
  Write-Host ("VERIFY {0}: pass={1} fail={2}" -f $name, $pass, $fail)
}
Verify-Doc $originalPath $results
$canonicalRows = @($groups | ForEach-Object { $_.canonical })
Verify-Doc $dedupPath $canonicalRows

Write-Host ''
Write-Host ("DONE ok={0}/20 unique={1}" -f $okCount, $uniqueCount)
Write-Host ("ORIGINAL: {0} ({1} bytes)" -f $originalPath, (Get-Item -LiteralPath $originalPath).Length)
Write-Host ("DEDUPED : {0} ({1} bytes)" -f $dedupPath, (Get-Item -LiteralPath $dedupPath).Length)
