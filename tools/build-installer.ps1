# Builds dist\Orbit-Setup-<version>.msi (WiX Toolset v3) from a fresh self-contained publish.
#   powershell -ExecutionPolicy Bypass -File tools\build-installer.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$wix = Get-ChildItem "${env:ProgramFiles(x86)}\WiX Toolset v3*\bin" -Directory -ErrorAction SilentlyContinue | Select-Object -Last 1
if (-not $wix) { throw 'WiX Toolset v3 not found (https://wixtoolset.org/docs/wix3/).' }
$dotnet = (Get-Command dotnet -ErrorAction SilentlyContinue).Source
if (-not $dotnet) { $dotnet = "$env:ProgramFiles\dotnet\dotnet.exe" }

[xml]$csproj = Get-Content "$root\windows\Orbit.Windows\Orbit.Windows.csproj"
$version = ($csproj.Project.PropertyGroup | Where-Object Version | Select-Object -First 1).Version
$publish = "$root\dist\windows"
$assets = "$root\windows\Orbit.Windows\Assets"
$obj = "$root\windows\Orbit.Installer\obj"

Write-Host "Publishing Orbit $version..."
if (Test-Path $publish) { Remove-Item $publish -Recurse -Force }
& $dotnet publish "$root\windows\Orbit.Windows" -c Release -r win-x64 --self-contained `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=none `
    -o $publish -v q
if ($LASTEXITCODE) { throw 'publish failed' }

# Branded wizard bitmaps (493x58 banner, 493x312 welcome/finish), generated once.
Add-Type -AssemblyName System.Drawing
function New-Bitmap($w, $h, $path, [scriptblock]$draw) {
    if (Test-Path $path) { return }
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
    & $draw $g $w $h
    $g.Dispose(); $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Bmp); $bmp.Dispose()
}
$logo = [System.Drawing.Image]::FromFile("$assets\orbit-256.png")
New-Bitmap 493 58 "$assets\installer-banner.bmp" {
    param($g, $w, $h)
    $g.Clear([System.Drawing.Color]::White)
    $g.DrawImage($logo, $w - 52, 9, 40, 40)
}
New-Bitmap 493 312 "$assets\installer-dialog.bmp" {
    param($g, $w, $h)
    $g.Clear([System.Drawing.Color]::White)
    # WiX draws its text on the right; the brand panel occupies the left 164 px.
    $rect = New-Object System.Drawing.Rectangle 0, 0, 164, $h
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(0x5B, 0x5B, 0xF6)), ([System.Drawing.Color]::FromArgb(0x22, 0xC3, 0xEE)), 60.0
    $g.FillRectangle($brush, $rect)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(70, 255, 255, 255)), 2
    foreach ($r in 70, 52, 34) { $g.DrawEllipse($pen, 82 - $r, 120 - $r, 2 * $r, 2 * $r) }
    $g.DrawImage($logo, 50, 88, 64, 64)
    $font = New-Object System.Drawing.Font 'Segoe UI Semibold', 16
    $fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = 'Center'
    $g.DrawString('Orbit', $font, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, 210, 164, 40), $fmt)
}
$logo.Dispose()

Write-Host 'Building MSI...'
New-Item -ItemType Directory -Force $obj | Out-Null
$msi = "$root\dist\Orbit-Setup-$version.msi"
& "$($wix.FullName)\candle.exe" -nologo -arch x64 -ext WixUIExtension -ext WixUtilExtension -ext WixFirewallExtension `
    "-dVersion=$version" "-dPublishDir=$publish" "-dAssetsDir=$assets" -out "$obj\" "$root\windows\Orbit.Installer\Orbit.wxs"
if ($LASTEXITCODE) { throw 'candle failed' }
& "$($wix.FullName)\light.exe" -nologo -ext WixUIExtension -ext WixUtilExtension -ext WixFirewallExtension `
    -cultures:en-us -spdb -sice:ICE38 -sice:ICE43 -sice:ICE57 -sice:ICE69 -out $msi "$obj\Orbit.wixobj"
# ICE38/43/57/69 are false positives here: per-machine install (ALLUSERS=1) puts shortcuts in the all-users folders.
if ($LASTEXITCODE) { throw 'light failed' }
Write-Host "Done: $msi ($([math]::Round((Get-Item $msi).Length / 1MB, 1)) MB)"
