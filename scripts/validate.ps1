$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $workspace
try {
    & mvn -B -f backend/pom.xml verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
    & npm --prefix frontend test
    if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }
    & npm --prefix frontend run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    & npx playwright test
    if ($LASTEXITCODE -ne 0) { throw 'Browser tests failed' }
    & az bicep build --file infrastructure/main.bicep --outfile .local/main.json
    if ($LASTEXITCODE -ne 0) { throw 'Bicep validation failed' }
} finally { Pop-Location }
