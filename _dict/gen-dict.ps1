# 从 rime-ice 词库生成 App 用的精简拼音词典
# 输出格式（TSV，每行一条）：
#   全拼key \t 简拼abbr \t 词 \t 权重
# 其中 key 为无声调拼音（音节直接相连），abbr 为各音节首字母（如 ni hao -> nh）
param(
    [string]$CharDict = "D:\code\floatkb\_dict\8105.yaml",
    [string]$WordDict = "D:\code\floatkb\_dict\base.yaml",
    [string]$OutFile  = "D:\code\floatkb\app\src\main\assets\pinyin.txt",
    [int]$MaxWords    = 100000
)

$ErrorActionPreference = 'Stop'

function Is-Cjk([string]$s) {
    foreach ($ch in $s.ToCharArray()) {
        $c = [int][char]$ch
        if (-not (($c -ge 0x4E00 -and $c -le 0x9FFF) -or ($c -ge 0x3400 -and $c -le 0x4DBF))) { return $false }
    }
    return $true
}

# "key|word" -> @(weight, abbr)
$records = [System.Collections.Generic.Dictionary[string, object[]]]::new()

function Add-Entry([string]$word, [string]$pinyin, [int]$weight) {
    $pinyin = $pinyin.Trim()
    if ($pinyin -eq '') { return }
    $syll = $pinyin -split '\s+'
    $key = ($syll -join '').ToLower()
    if ($key -eq '') { return }
    $abbr = (($syll | ForEach-Object { $_.Substring(0, 1) }) -join '').ToLower()
    $k = "$key|$word"
    $existing = $null
    if ($records.TryGetValue($k, [ref]$existing)) {
        if ($weight -gt $existing[0]) { $records[$k] = @($weight, $abbr) }
    } else {
        $records[$k] = @($weight, $abbr)
    }
}

# ---- 1) 单字表（全部收录）----
$charCount = 0
foreach ($line in [System.IO.File]::ReadLines($CharDict)) {
    if ($line.StartsWith('#') -or $line.Trim() -eq '' -or $line.StartsWith('---')) { continue }
    $p = $line.Split("`t")
    if ($p.Count -lt 2) { continue }
    $w = $p[0].Trim()
    if ($w.Length -ne 1 -or -not (Is-Cjk $w)) { continue }
    $weight = 1
    if ($p.Count -ge 3) { [void][int]::TryParse($p[2].Trim(), [ref]$weight) }
    Add-Entry $w $p[1] $weight
    $charCount++
}
"单字：$charCount"

# ---- 2) 词库（按权重取前 N 个 2~4 字词）----
$words = [System.Collections.Generic.List[object]]::new()
foreach ($line in [System.IO.File]::ReadLines($WordDict)) {
    if ($line.StartsWith('#') -or $line.Trim() -eq '' -or $line.StartsWith('---')) { continue }
    $p = $line.Split("`t")
    if ($p.Count -lt 3) { continue }
    $w = $p[0].Trim()
    if ($w.Length -lt 2 -or $w.Length -gt 4) { continue }
    if (-not (Is-Cjk $w)) { continue }
    $weight = 0
    if (-not [int]::TryParse($p[2].Trim(), [ref]$weight)) { continue }
    if ($weight -lt 100) { continue }
    $words.Add([pscustomobject]@{ W = $w; P = $p[1]; G = $weight })
}
"词库候选：$($words.Count)"

$kept = $words | Sort-Object -Property G -Descending | Select-Object -First $MaxWords
foreach ($x in $kept) { Add-Entry $x.W $x.P $x.G }
"实际收录词：$($kept.Count)"

# ---- 3) 输出：按 key 排序，同 key 内按权重降序 ----
New-Item -ItemType Directory -Force -Path (Split-Path $OutFile) | Out-Null

$rows = $records.GetEnumerator() | ForEach-Object {
    $parts = $_.Key.Split('|')
    [pscustomobject]@{ Key = $parts[0]; Abbr = $_.Value[1]; Word = $parts[1]; W = $_.Value[0] }
}
$sorted = $rows | Sort-Object -Property Key, @{Expression = 'W'; Descending = $true }

$sw = [System.IO.StreamWriter]::new($OutFile, $false, [System.Text.UTF8Encoding]::new($false))
$total = 0
foreach ($r in $sorted) {
    $sw.Write($r.Key); $sw.Write("`t")
    $sw.Write($r.Abbr); $sw.Write("`t")
    $sw.Write($r.Word); $sw.Write("`t")
    $sw.WriteLine($r.W)
    $total++
}
$sw.Dispose()

"输出行数：$total"
"文件大小：{0:N0} KB" -f ((Get-Item $OutFile).Length/1KB)
