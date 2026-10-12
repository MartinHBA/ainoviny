[CmdletBinding()]
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$SkipBuild) {
    & mvn -B -f (Join-Path $workspace 'backend/pom.xml') verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification/package failed.' }
}
$staging = Join-Path $workspace 'backend/target/azure-functions/verejny-dohlad-local'
if (!(Test-Path -LiteralPath (Join-Path $staging 'host.json'))) { throw 'Function package is missing. Run without -SkipBuild.' }
$outputDirectory = Join-Path $workspace '.local'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$zipPath = Join-Path $outputDirectory 'functions.zip'

# Create a ZIP with host.json at its root. Never include local settings/secrets.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fileStream = [IO.File]::Open($zipPath, [IO.FileMode]::Create)
$archive = [IO.Compression.ZipArchive]::new($fileStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File) {
        if ($file.Name -eq 'local.settings.json') { continue }
        $entryName = $file.FullName.Substring($staging.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $fileStream.Dispose() }
Write-Output "Backend package ready: $zipPath"
