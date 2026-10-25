Add-Type -AssemblyName System.Drawing

# 生成「浮动键盘」应用图标：
#  - mipmap-*/ic_launcher.png(round)    旧系统(API 24-25)用
#  - mipmap-xxxhdpi/ic_launcher_fg.png  自适应图标前景(API 26+)
#  - 预览图用于查看效果

$res = "D:\code\floatkb\app\src\main\res"

function New-RoundedPath([System.Drawing.RectangleF]$r, [float]$radius) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $p.AddArc($r.X, $r.Y, $d, $d, 180, 90)
    $p.AddArc($r.Right - $d, $r.Y, $d, $d, 270, 90)
    $p.AddArc($r.Right - $d, $r.Bottom - $d, $d, $d, 0, 90)
    $p.AddArc($r.X, $r.Bottom - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function Draw-Glyph([System.Drawing.Graphics]$g, [float]$S, [float]$ox, [float]$oy, [bool]$shadow) {
    $panelW = 0.70 * $S
    $panelH = 0.46 * $S
    $px = $ox + (1 - 0.70) * $S / 2
    $py = $oy + 0.37 * $S

    if ($shadow) {
        for ($i = 8; $i -ge 1; $i--) {
            $a = 14 * (9 - $i)
            $sr = New-Object System.Drawing.RectangleF(($px - $i * 0.005 * $S), ($py + 0.05 * $S + $i * 0.008 * $S), $panelW, $panelH)
            $sp = New-RoundedPath $sr (0.11 * $S)
            $sb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb($a, 0, 0, 0))
            $g.FillPath($sb, $sp); $sb.Dispose(); $sp.Dispose()
        }
    }

    $pr = New-Object System.Drawing.RectangleF($px, $py, $panelW, $panelH)
    $pp = New-RoundedPath $pr (0.11 * $S)
    $pb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0xF2, 0xF5, 0xFB))
    $g.FillPath($pb, $pp); $pb.Dispose()

    $cols = 4
    $padX = 0.055 * $S
    $padY = 0.045 * $S
    $gapX = 0.024 * $S
    $gapY = 0.024 * $S
    $kw = ($panelW - 2 * $padX - ($cols - 1) * $gapX) / $cols
    $kh = ($panelH - 2 * $padY - 2 * $gapY) / 3

    $dark = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0x1B, 0x20, 0x30))
    $accent = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0x4C, 0x8D, 0xF6))

    for ($r = 0; $r -lt 2; $r++) {
        for ($c = 0; $c -lt $cols; $c++) {
            $kx = $px + $padX + $c * ($kw + $gapX)
            $ky = $py + $padY + $r * ($kh + $gapY)
            $kp = New-RoundedPath (New-Object System.Drawing.RectangleF($kx, $ky, $kw, $kh)) (0.026 * $S)
            $g.FillPath($dark, $kp); $kp.Dispose()
        }
    }

    $y3 = $py + $padY + 2 * ($kh + $gapY)
    $p0 = New-RoundedPath (New-Object System.Drawing.RectangleF(($px + $padX), $y3, $kw, $kh)) (0.026 * $S)
    $g.FillPath($dark, $p0); $p0.Dispose()

    $spaceW = 2 * $kw + $gapX
    $p1 = New-RoundedPath (New-Object System.Drawing.RectangleF(($px + $padX + $kw + $gapX), $y3, $spaceW, $kh)) (0.026 * $S)
    $g.FillPath($dark, $p1); $p1.Dispose()

    $p2 = New-RoundedPath (New-Object System.Drawing.RectangleF(($px + $padX + $kw + $gapX + $spaceW + $gapX), $y3, $kw, $kh)) (0.026 * $S)
    $g.FillPath($accent, $p2); $p2.Dispose()

    $dark.Dispose(); $accent.Dispose(); $pp.Dispose()
}

function New-Icon([int]$size, [bool]$withBg, [bool]$shadow) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $S = [float]$size

    if ($withBg) {
        $bgPath = New-RoundedPath (New-Object System.Drawing.RectangleF(0, 0, $S, $S)) (0.235 * $S)
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.PointF(0, 0)),
            (New-Object System.Drawing.PointF($S, $S)),
            [System.Drawing.Color]::FromArgb(255, 0x2A, 0x33, 0x4B),
            [System.Drawing.Color]::FromArgb(255, 0x0C, 0x0E, 0x14))
        $g.FillPath($brush, $bgPath); $brush.Dispose(); $bgPath.Dispose()
    }

    Draw-Glyph $g $S 0 0 $shadow
    $g.Dispose()
    return $bmp
}

# ---- 1) 旧版图标（mdpi..xxxhdpi）----
$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($k in $legacy.Keys) {
    $size = $legacy[$k]
    $bmp = New-Icon $size $true $true
    $dir = Join-Path $res "mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# ---- 2) 自适应图标前景：432x432(108dp@xxxhdpi)，内容缩到安全区 ----
$fgSize = 432
$fg = New-Object System.Drawing.Bitmap($fgSize, $fgSize)
$g = [System.Drawing.Graphics]::FromImage($fg)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
$inner = [float]($fgSize * 0.72)
$off = [float](($fgSize - $inner) / 2)
Draw-Glyph $g $inner $off $off $false
$g.Dispose()
$fg.Save((Join-Path $res "mipmap-xxxhdpi\ic_launcher_fg.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$fg.Dispose()

# ---- 3) 预览 ----
$preview = New-Icon 512 $true $true
$preview.Save("D:\文档\deepseek-harness\default-workspace\icon-preview.png", [System.Drawing.Imaging.ImageFormat]::Png)
$preview.Dispose()

$small = New-Icon 48 $true $true
$smallBig = New-Object System.Drawing.Bitmap(240, 240)
$g2 = [System.Drawing.Graphics]::FromImage($smallBig)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($small, 0, 0, 240, 240)
$g2.Dispose()
$smallBig.Save("D:\文档\deepseek-harness\default-workspace\icon-48px.png", [System.Drawing.Imaging.ImageFormat]::Png)
$smallBig.Dispose(); $small.Dispose()

"icons generated"
Get-ChildItem $res -Recurse -Filter "ic_launcher*.png" | ForEach-Object { "  " + $_.FullName.Replace("$res\", "") }


