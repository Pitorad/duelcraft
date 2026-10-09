# Builds a DuelCraft release into dist\ (packaging sheet):
#   duelcraft-<version>.jar             the mod (Melty puts it in the instance's mods folder)
#   DuelCraft-Minecraft-<version>.zip   portable Prism Launcher + the DuelCraft instance (Minecraft 26.3, Fabric 0.19.5)
#                                       with Fabric API and e4mc, credits, licence and source pointer (Melty unpacks it to {managed})
# Nothing from Minecraft or Yu-Gi-Oh! Master Duel is in either file.
#
#   powershell -ExecutionPolicy Bypass -File tools\package.ps1 [-NoBuild] [-SourceUrl https://github.com/...]
param([switch]$NoBuild, [string]$SourceUrl = "https://github.com/Pitorad/duelcraft")
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $PSScriptRoot
$version = (Select-String -Path "$root\fabric\gradle.properties" -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()

# Pinned downloads (same as SkyCraft, which plays on Melty), checked against these hashes.
$prismVersion = "11.1.1"
$prismZip = "PrismLauncher-Windows-MSVC-Portable-$prismVersion.zip"
$prismUrl = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prismVersion/$prismZip"
$prismSha256 = "ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7"
$prismLicenseUrl = "https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/$prismVersion/LICENSE"
$fabricApiJar = "fabric-api-0.161.0+26.3.jar"
$fabricApiUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/bNnaTiuM/fabric-api-0.161.0%2B26.3.jar"
$fabricApiSha512 = "ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d"
$e4mcJar = "e4mc-fabric-6.2.2-modern.jar"
$e4mcUrl = "https://cdn.modrinth.com/data/qANg5Jrr/versions/AouleFRY/e4mc-fabric-6.2.2-modern.jar"
$e4mcSha512 = "01ef0a8c5b76e2cb0effd337bad3350d8807d100d0ec661e01b2ffb20af7b652f756c5eaa11bee233c37905bfd7b573f7a85f3d15bfd2833962c76f02cd59a86"

function Get-Pinned([string]$url, [string]$path, [string]$algorithm, [string]$hash) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory (Split-Path $path) -Force | Out-Null
        Invoke-WebRequest -Uri $url -OutFile $path -UseBasicParsing
    }
    if ($hash -and (Get-FileHash $path -Algorithm $algorithm).Hash -ne $hash.ToUpper()) {
        Remove-Item $path
        throw "$path doesn't match its pinned $algorithm hash"
    }
}

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
function New-ZipFromFolder([string]$path, [string]$folder) {
    $zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $base = (Resolve-Path $folder).Path.TrimEnd('\') + '\'
        Get-ChildItem $folder -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
            $name = $_.FullName.Substring($base.Length).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $zip.Dispose() }
}

if (-not $NoBuild) {
    & cmd /c "$root\tools\build_native.bat"
    if ($LASTEXITCODE) { throw "the native library didn't build" }
    Push-Location $root
    try {
        python -I tools\preflight.py --quiet
        if ($LASTEXITCODE) { throw "preflight has blocking findings" }
        python -I tools\gen_code.py
        python -I tools\pack_scripts.py
    } finally { Pop-Location }
    Push-Location "$root\fabric"
    try {
        .\gradlew.bat build --no-configuration-cache
        if ($LASTEXITCODE) { throw "the mod didn't build" }
    } finally { Pop-Location }
}
$jar = "$root\fabric\build\libs\duelcraft-$version.jar"
if (-not (Test-Path $jar)) { throw "missing $jar" }

$cache = "$root\.tools\prism"
Get-Pinned $prismUrl "$cache\$prismZip" SHA256 $prismSha256
Get-Pinned $fabricApiUrl "$cache\$fabricApiJar" SHA512 $fabricApiSha512
Get-Pinned $e4mcUrl "$cache\$e4mcJar" SHA512 $e4mcSha512
Get-Pinned $prismLicenseUrl "$cache\PrismLauncher-$prismVersion-LICENSE.txt" "" ""

$dist = "$root\dist"
New-Item -ItemType Directory $dist -Force | Out-Null
Get-ChildItem $dist | Remove-Item -Recurse -Force

$bundle = "$dist\bundle"
Copy-Item -Recurse "$root\tools\minecraft-bundle" $bundle
Expand-Archive "$cache\$prismZip" "$bundle\Prism" -Force
Copy-Item "$cache\PrismLauncher-$prismVersion-LICENSE.txt" "$bundle\Prism\LICENSE-PrismLauncher.txt"
$ocgRev = (git -C "$root\third_party\ocgcore" rev-parse --short HEAD)
$scriptsRev = (git -C "$root\third_party\CardScripts" rev-parse --short HEAD)
(Get-Content "$bundle\THIRD-PARTY.txt" -Raw).Replace("{PRISM_VERSION}", $prismVersion).Replace("{VERSION}", $version).Replace("{OCG_REV}", $ocgRev).Replace("{SCRIPTS_REV}", $scriptsRev) |
    Set-Content "$bundle\THIRD-PARTY.txt" -NoNewline -Encoding UTF8
Copy-Item "$root\LICENSE" "$bundle\LICENSE.txt"
Set-Content "$bundle\SOURCE.txt" "DuelCraft $version source code (AGPL-3.0-or-later): $SourceUrl`r`nBuilt from: ocgcore $ocgRev, CardScripts $scriptsRev." -Encoding UTF8
$mods = "$bundle\Prism\instances\DuelCraft\.minecraft\mods"
New-Item -ItemType Directory $mods -Force | Out-Null
Copy-Item "$cache\$fabricApiJar" $mods
Copy-Item "$cache\$e4mcJar" $mods
Set-Content "$bundle\bundle-version.txt" "DuelCraft $version, Prism Launcher $prismVersion, $fabricApiJar, $e4mcJar" -NoNewline
New-ZipFromFolder "$dist\DuelCraft-Minecraft-$version.zip" $bundle
Remove-Item -Recurse -Force $bundle
Copy-Item $jar "$dist\duelcraft-$version.jar"

Get-ChildItem $dist | ForEach-Object { "{0,-40} {1,12:N0} bytes  {2}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash }
