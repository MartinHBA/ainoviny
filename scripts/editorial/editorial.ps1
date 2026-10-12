[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [Parameter(ValueFromRemainingArguments=$true)] [string[]]$Command
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$settingsPath = Join-Path $workspace 'backend/local.settings.json'
$usingCloudStorage = ![string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('STORAGE_ACCOUNT_NAME'))
if ($usingCloudStorage -and [Environment]::GetEnvironmentVariable('APP_STORAGE_CONNECTION_STRING') -eq 'UseDevelopmentStorage=true') {
    throw 'Cloud STORAGE_ACCOUNT_NAME cannot be combined with local Azurite. Clear APP_STORAGE_CONNECTION_STRING first.'
}
if (!$usingCloudStorage -and (Test-Path -LiteralPath $settingsPath)) {
    $settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    foreach ($property in $settings.Values.PSObject.Properties) {
        # Explicit shell settings take priority over local.settings.json.
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($property.Name))) {
            [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
        }
    }
}
$localDirectory = Join-Path $workspace '.local'
New-Item -ItemType Directory -Force $localDirectory | Out-Null
$classpathFile = Join-Path $localDirectory 'backend-classpath.txt'
if (!$SkipBuild -or !(Test-Path -LiteralPath $classpathFile)) {
    & mvn -q -f (Join-Path $workspace 'backend/pom.xml') compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
}
$classpath = (Join-Path $workspace 'backend/target/classes') + [IO.Path]::PathSeparator + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
& java '-cp' $classpath 'sk.verejnydohlad.Cli' @Command
if ($LASTEXITCODE -ne 0) { throw 'Editorial command failed' }
