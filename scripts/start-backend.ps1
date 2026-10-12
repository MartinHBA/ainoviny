$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$settings = Join-Path $workspace 'backend/local.settings.json'
if (!(Test-Path -LiteralPath $settings)) { throw 'Copy backend/local.settings.json.example to backend/local.settings.json first.' }
& mvn -q -f (Join-Path $workspace 'backend/pom.xml') package
if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }
$staging = Join-Path $workspace 'backend/target/azure-functions/verejny-dohlad-local'
Copy-Item -LiteralPath $settings -Destination (Join-Path $staging 'local.settings.json')
& func start --script-root $staging
