param([string]$Source = (Join-Path $PSScriptRoot '../assets/branding/launcher-anime-source.png'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$resources = Join-Path $PSScriptRoot '../app/src/main/res'
$sourceImage = [Drawing.Image]::FromFile((Resolve-Path -LiteralPath $Source).Path)
try {
    if ($sourceImage.Width -ne $sourceImage.Height) { throw 'Launcher artwork must be square.' }
    $sizes = [ordered]@{ 'drawable-nodpi'=432 }
    foreach ($entry in $sizes.GetEnumerator()) {
        $directory = Join-Path $resources $entry.Key
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        $bitmap = [Drawing.Bitmap]::new($entry.Value, $entry.Value)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.DrawImage($sourceImage, 0, 0, $entry.Value, $entry.Value)
            $fileName = 'ic_launcher_anime.png'
            $bitmap.Save((Join-Path $directory $fileName), [Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
} finally { $sourceImage.Dispose() }
