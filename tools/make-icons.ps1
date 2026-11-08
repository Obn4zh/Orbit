# Generates the Orbit brand icons (PNG + ICO) with System.Drawing. Run from the repo root:
#   powershell -ExecutionPolicy Bypass -File tools\make-icons.ps1
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot

function New-OrbitBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)
    $r = [single]($size * 0.25)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $s = [single]$size
    $path.AddArc(0, 0, $r, $r, 180, 90); $path.AddArc($s - $r, 0, $r, $r, 270, 90)
    $path.AddArc($s - $r, $s - $r, $r, $r, 0, 90); $path.AddArc(0, $s - $r, $r, $r, 90, 90); $path.CloseFigure()
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.PointF]::new(0, 0)), ([System.Drawing.PointF]::new($s, $s)),
        ([System.Drawing.Color]::FromArgb(255, 0x5B, 0x5B, 0xF6)), ([System.Drawing.Color]::FromArgb(255, 0x22, 0xC3, 0xEE))
    $g.FillPath($brush, $path)
    $c = $s / 2
    $w = [single][Math]::Max(1.2, $size * 0.04)
    foreach ($ring in @(@(0.30, 90), @(0.18, 180))) {
        $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb($ring[1], 255, 255, 255)), $w
        $rr = [single]($s * $ring[0]); $g.DrawEllipse($pen, $c - $rr, $c - $rr, 2 * $rr, 2 * $rr)
    }
    $white = [System.Drawing.Brushes]::White
    $d = [single]($s * 0.08); $g.FillEllipse($white, $c - $d, $c - $d, 2 * $d, 2 * $d)
    $sd = [single]($s * 0.055); $sx = [single]($s * 0.735); $sy = [single]($s * 0.31)
    $g.FillEllipse($white, $sx - $sd, $sy - $sd, 2 * $sd, 2 * $sd)
    $g.Dispose()
    return $bmp
}

function Get-PngBytes($bmp) { $ms = New-Object System.IO.MemoryStream; $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); return ,$ms.ToArray() }

# Web (apple-touch-icon) and docs
(New-OrbitBitmap 180).Save("$root\shared\web\icon.png", [System.Drawing.Imaging.ImageFormat]::Png)

# Multi-size ICO with PNG-compressed entries
$sizes = 16, 24, 32, 48, 64, 128, 256
$images = foreach ($sz in $sizes) { ,(Get-PngBytes (New-OrbitBitmap $sz)) }
$ico = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $ico
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz = $sizes[$i]; $len = $images[$i].Length
    $bw.Write([byte]($(if ($sz -ge 256) { 0 } else { $sz }))); $bw.Write([byte]($(if ($sz -ge 256) { 0 } else { $sz })))
    $bw.Write([byte]0); $bw.Write([byte]0); $bw.Write([uint16]1); $bw.Write([uint16]32)
    $bw.Write([uint32]$len); $bw.Write([uint32]$offset); $offset += $len
}
foreach ($img in $images) { $bw.Write($img) }
$bw.Flush()
New-Item -ItemType Directory -Force "$root\windows\Orbit.Windows\Assets" | Out-Null
[System.IO.File]::WriteAllBytes("$root\windows\Orbit.Windows\Assets\orbit.ico", $ico.ToArray())
(New-OrbitBitmap 256).Save("$root\windows\Orbit.Windows\Assets\orbit-256.png", [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output "Icons written."
