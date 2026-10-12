# Azure deployment

Nothing in the local setup or CI provisions Azure resources or calls paid AI. **Do not execute the provisioning commands below until the account owner explicitly approves the resources and potential costs.** Provisioning/release GitHub workflows are manual, with false-by-default approval inputs. Configure required reviewers on the `azure-production` environment; the YAML cannot create that repository setting for you.

## Resource boundaries

Main template creates a Windows Java 21 Function App, Y1 Consumption hosting plan, **one** Standard LRS Storage Account, three private containers/tables and SWA Free. Function runtime artifacts, Azure Files content share and application data all use that account. App clients use managed identity with Blob/Table Data Contributor; Consumption runtime/content-share connection settings use ARM-resolved storage keys held in Function settings. Frontend never sees those keys. No second account, database or cache.

Azure OpenAI is **existing by default**: pass its resource name, group, subscription, endpoint and deployment. `openai.bicep` is a separate optional template requiring separate cost approval; it defaults to gpt-4.1-mini / 2025-04-14 / GlobalStandard capacity 1 but accepts model/version/SKU/region overrides. Confirm subscription quota and current model availability before use; West Europe is preferred for app/storage, not assumed for every AI deployment.

The prompt's four-service limit and monitoring requirement are met by using **existing Application Insights** via secure connection-string parameter. If no component exists, the main template can explicitly create an optional Insights component with `createApplicationInsights=true` against an **existing** Log Analytics workspace ID. This is the documented monitoring exception to the four-service limit; it creates neither another Storage Account nor a new Log Analytics workspace. Never enable that option without reviewing monitoring cost/retention. Production should provide monitoring; leaving the connection empty provides host logs only.

SWA Free cannot link a bring-your-own Function App to its built-in `/api` route. Deploy static assets with `VITE_API_BASE_URL=https://FUNCTION_APP.azurewebsites.net/api`. Function CORS contains only the returned SWA origin. Add a custom-domain origin explicitly if needed. Do not enable credentials or wildcard CORS. See [Microsoft's integration restriction](https://learn.microsoft.com/azure/static-web-apps/functions-bring-your-own).

## Deployment order

For a new environment, the order is **Resource Group → `openai.bicep` → `main.bicep` → application deployment**. Both templates deploy into an existing Resource Group; neither creates the group. `main.bicep` references an **existing** OpenAI account and assigns the Function App access to it, so the account and model deployment must be ready first.

If you already have an OpenAI account **and a deployed model**, skip `openai.bicep` and pass their actual values to `main.bicep`. An OpenAI account without a model deployment is not sufficient.

## Preparation and template validation

1. Install Azure CLI/Bicep, authenticate as the account owner and choose the intended subscription.
2. Identify the approved resource group, existing Azure OpenAI resource/deployment and existing monitoring connection string.
3. Review `main.bicep` parameters and the `.bicepparam.example`. Name prefix must contain lowercase letters/digits/hyphens and produce a valid storage name; use the default unless needed.
4. Compile locally; this requires no provisioning:

```powershell
New-Item -ItemType Directory -Force .local | Out-Null
az bicep build --file infrastructure/openai.bicep --outfile .local/openai.json
az bicep build --file infrastructure/main.bicep --outfile .local/main.json
```

`az bicep build` only compiles a template locally. It creates no Azure resources, and the order of these two compilation commands does not determine deployment order.

## Provisioning a new environment

After explicit resource/cost approval, follow the steps below from the repository root. Replace the example names and choose an AI region supporting the selected model/version/SKU and your subscription quota. Use a secure ignored parameter file or CI secrets for the existing Application Insights connection string; do not commit live secrets.

### 1. Create the Resource Group

The simplest setup places the app and OpenAI in the same group. If they belong in separate groups, prepare both groups before their respective deployments.

```powershell
$appResourceGroup = 'verejny-dohlad'
$appLocation = 'westeurope'
$aiResourceGroup = $appResourceGroup
$aiResourceName = 'REPLACE_WITH_UNIQUE_OPENAI_NAME'
$aiLocation = 'REPLACE_WITH_SUPPORTED_AI_REGION'
$aiDeploymentName = 'gpt-4.1-mini'

az group create --name $appResourceGroup --location $appLocation
```

### 2. Create OpenAI and deploy the model

Skip this step if a suitable account and model deployment already exist. `openai.bicep` creates both; it is not called by `main.bicep` or the current GitHub provisioning workflow.

```powershell
az deployment group what-if --resource-group $aiResourceGroup --template-file infrastructure/openai.bicep --parameters resourceName=$aiResourceName location=$aiLocation deploymentName=$aiDeploymentName
if ($LASTEXITCODE -ne 0) { throw 'OpenAI what-if failed. Resolve the error before creating resources.' }
az deployment group create --name openai-bootstrap --resource-group $aiResourceGroup --template-file infrastructure/openai.bicep --parameters resourceName=$aiResourceName location=$aiLocation deploymentName=$aiDeploymentName
if ($LASTEXITCODE -ne 0) { throw 'OpenAI deployment failed. Resolve the error before continuing to main.bicep.' }
$aiEndpoint = az deployment group show --name openai-bootstrap --resource-group $aiResourceGroup --query properties.outputs.endpoint.value --output tsv
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($aiEndpoint)) { throw 'OpenAI endpoint output is unavailable.' }
```

Confirm this deployment succeeds before proceeding. If using an existing account, set `$aiResourceGroup`, `$aiResourceName`, `$aiEndpoint` and `$aiDeploymentName` to its actual values instead. For another subscription, also pass `openAiSubscriptionId` to the main template.

The OpenAI template explicitly sets `networkAcls.defaultAction=Allow` for the public endpoint used by this Consumption app; authentication still requires Entra ID/RBAC because API-key authentication is disabled. If a previous template version failed with `NetworkAcls is required for this resouce`, rerun the same `openai-bootstrap` create command using the corrected template and the same resource parameters. Do not read its endpoint output or deploy `main.bicep` until the retry succeeds. Local Bicep compilation checks template syntax/types; it cannot validate every Azure provisioning requirement.

### 3. Provision the application infrastructure

Fill `infrastructure/parameters/dev.bicepparam.example` with the existing OpenAI values if you intend to use a parameter file. The commands below pass those values directly using the variables from the previous steps.

```powershell
az deployment group what-if --resource-group $appResourceGroup --template-file infrastructure/main.bicep --parameters location=$appLocation openAiResourceName=$aiResourceName openAiResourceGroup=$aiResourceGroup openAiEndpoint=$aiEndpoint openAiDeployment=$aiDeploymentName
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure what-if failed. Resolve the error before creating resources.' }
az deployment group create --name newspaper-infrastructure --resource-group $appResourceGroup --template-file infrastructure/main.bicep --parameters location=$appLocation openAiResourceName=$aiResourceName openAiResourceGroup=$aiResourceGroup openAiEndpoint=$aiEndpoint openAiDeployment=$aiDeploymentName
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure deployment failed. Resolve the error before deploying the application.' }
```

If an older template reports `Unsupported` for the OpenAI role assignment because its resource ID cannot be calculated until deployment, this is a what-if prediction limitation, not a `DeploymentFailed` result. Its name previously depended on the new Function App's managed identity principal ID. The corrected template derives the assignment name from the already known Function App resource ID; the actual principal ID is still used to grant the same OpenAI User role. Rerun what-if with the updated template and review the result before provisioning. A preview does not guarantee successful deployment or sufficient role-assignment permissions.

The deployment principal needs rights to create the app resources and assign the documented roles, including OpenAI User in the existing AI resource's subscription/group. Cross-subscription/group RBAC is done by a scoped module. Allow RBAC propagation before the first scheduled run. The deployment outputs include names and public URLs, no storage keys or AI secrets. No Azure provisioning/what-if was performed during implementation.

Role-assignment names use the app resource ID and are stable across ordinary redeployments. If the Function App is deleted/recreated or its managed identity is replaced, remove the assignments belonging to the old identity before redeploying; Azure cannot change an existing assignment's principal in place.

### 4. Deploy the backend and frontend

After `newspaper-infrastructure` reports `Succeeded`, deploy the actual application code. **GitHub Actions is the recommended route; Node.js/npm do not need to be installed on your PC.** GitHub installs Node.js on its runner, downloads the React dependencies, runs tests/build and uploads the output. If the Java backend is already deployed, follow the frontend-only GitHub workflow below. The PowerShell commands later in this section are an optional local alternative.

The `provision.yml` workflow is an alternative to step 3 only: the Resource Group and OpenAI/model must already exist when it starts.

#### Deploy the frontend through GitHub without local npm

The Java backend and the Static Web App are separate resources. Once `/api/articles` responds successfully, use `.github/workflows/deploy-frontend.yml` to build and publish the frontend. This workflow needs only the public API URL and the specific Static Web App deployment token; Azure OIDC setup is unnecessary for this frontend-only release.

1. Commit and push this project, including `frontend/package-lock.json` and `.github/workflows`, to `main`. The workflow must exist on GitHub before it appears under Actions. `.gitignore` excludes local settings, environment files, compiled artifacts and local tools.
2. In GitHub repository **Settings → Environments**, create `azure-production`. Add required reviewers if desired; deployments remain manual through the explicit dispatch confirmation input.
3. In that environment, add variable `FUNCTION_API_URL` with value `https://verejnydohlad-wny6o5yct2uf2.azurewebsites.net/api` for this already provisioned instance. For another instance, use the `functionApiUrl` Bicep output.
4. In Azure Portal open Static Web App **`verejnydohlad-wny6o5yct2uf2-web`** (not the Function App), choose **Manage deployment token**, and copy its token into the GitHub environment secret **`AZURE_STATIC_WEB_APPS_API_TOKEN`**. Keep the token in the GitHub secret; do not put it in source files or chat.
5. Open GitHub **Actions → Deploy newspaper frontend → Run workflow**, select `main`, tick `confirm_deployment` and run it. The runner installs Node 22, runs `npm ci`, tests/build, checks the public API and uploads only the frontend.
6. After the job succeeds, open `https://proud-field-0b4892d03.2.azurestaticapps.net` for this instance, or the `staticWebAppUrl` Bicep output. Empty editions are expected before human publication.

To upload the local source using your normal Git login, run from the repository root:

```powershell
git add .
git commit -m "Add newspaper MVP and GitHub deployment workflows"
git push origin main
```

This frontend workflow creates no Azure resources and does not call the AI model. Full backend/frontend releases can use `deploy.yml` after configuring Azure OIDC as described in the CI/CD section below.

#### Function App Deployment Center

The GitHub screen inside **Function App → Deployment Center** configures backend deployment. Select GitHub owner **MartinHBA**, repository **ainoviny**, and branch **main**. In the generated workflow preview ensure the Java build uses `backend/pom.xml`, for example `mvn -B -f backend/pom.xml verify`, and the upload package points to `backend/target/azure-functions/verejny-dohlad-local`. The Maven package folder name differs from the actual Azure Function App name, which remains `verejnydohlad-wny6o5yct2uf2`.

The portal-generated workflow does not deploy this separate React Static Web App. Use `deploy-frontend.yml` for it. Choose one backend deployment workflow for your releases; if the portal generates a continuous backend workflow, the existing full `deploy.yml` can remain unused. Saving Deployment Center configuration creates/updates a workflow in GitHub; if that happens before your first local push, fetch and reconcile the remote changes before pushing. Do not force-push over the portal-generated commit.

#### Read the deployment outputs

```powershell
$infra = az deployment group show --name newspaper-infrastructure --resource-group $appResourceGroup --query properties.outputs --output json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or !$infra.functionAppName.value) { throw 'Infrastructure outputs are unavailable.' }
$functionAppName = $infra.functionAppName.value
$functionApiUrl = $infra.functionApiUrl.value
$staticWebAppName = $infra.staticWebAppName.value
$staticWebAppUrl = $infra.staticWebAppUrl.value
$storageAccountName = $infra.storageAccountName.value
```

#### Build and upload Java Functions

Run from the repository root. Stop any local Function host first on Windows so its JAR can be replaced. The packaging helper runs backend tests and creates `.local/functions.zip` with `host.json` at the archive root. It excludes `local.settings.json`; cloud app settings come from Bicep.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-backend.ps1
if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed.' }
az functionapp deployment source config-zip --resource-group $appResourceGroup --name $functionAppName --src .local/functions.zip
if ($LASTEXITCODE -ne 0) { throw 'Backend upload failed.' }
Invoke-RestMethod "$functionApiUrl/articles"
```

On a new account the public API should return an empty `articles` array and `nextCursor: null`. If the host is still starting or RBAC is propagating, wait briefly and retry the GET. This read does not generate articles or call AI. A deployed backend enables the morning timer; its configured Azure model calls can incur the previously approved usage costs.

The helper runs in a child PowerShell process with execution allowed for that invocation only. It does not change the user's persistent execution policy. You can also invoke `./scripts/package-backend.ps1` directly if your current PowerShell policy already permits project scripts.

For Windows Function hosting the Azure site setting is `javaVersion: '21.0'`; Java/Maven and GitHub setup still use Java 21. An older infrastructure template used `'21'`, which can produce an `Invalid version: 21` CLI warning even while ZIP upload succeeds. Do not interrupt or repeat an upload solely because of that warning: check its final result and the public API first. The corrected Bicep applies `'21.0'` on the next infrastructure deployment. To update an already provisioned app after its upload completes, use:

```powershell
az functionapp config set --resource-group $appResourceGroup --name $functionAppName --java-version 21.0 --query javaVersion --output tsv
```

Updating runtime configuration may restart the Function host. Verify the API again afterward; a successful ZIP upload by itself does not establish that all functions started correctly.

#### Build and upload the React frontend

Use SWA CLI to upload static assets to the existing Free app. The deployment token is captured into a temporary process environment variable; do not print it or commit it. No additional Static Web App is created by these commands.

```powershell
$env:VITE_API_BASE_URL = $functionApiUrl
npm --prefix frontend ci
if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
npm --prefix frontend test
if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
npm --prefix frontend run build
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }

$env:SWA_CLI_DEPLOYMENT_TOKEN = az staticwebapp secrets list --name $staticWebAppName --resource-group $appResourceGroup --query properties.apiKey --output tsv
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($env:SWA_CLI_DEPLOYMENT_TOKEN)) { throw 'Static Web App deployment token is unavailable.' }
try {
    npx --yes --package @azure/static-web-apps-cli swa deploy ./frontend/dist --env production
    if ($LASTEXITCODE -ne 0) { throw 'Frontend deployment failed.' }
} finally { Remove-Item Env:SWA_CLI_DEPLOYMENT_TOKEN -ErrorAction SilentlyContinue }

Write-Output $staticWebAppUrl
```

Open the returned URL. An empty newspaper edition is expected until a human publishes the first approved article. Frontend release and later article publication are separate: new approved articles appear through the API without another frontend build.

Deployment command references: [Java Function ZIP deployment](https://learn.microsoft.com/azure/azure-functions/deployment-zip-push), [SWA CLI deployment](https://azure.github.io/static-web-apps-cli/docs/cli/swa-deploy/).

#### Initialize and use editorial operations

Use the editor RBAC described below before the first collection run. The logged-in human identity needs Blob/Table Data Contributor; ordinary management-plane Contributor access alone does not grant application-data access.

```powershell
Remove-Item Env:APP_STORAGE_CONNECTION_STRING -ErrorAction SilentlyContinue
$env:STORAGE_ACCOUNT_NAME = $storageAccountName
$env:AI_MODE = 'azure'
$env:AZURE_OPENAI_ENDPOINT = $aiEndpoint
$env:AZURE_OPENAI_DEPLOYMENT = $aiDeploymentName
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/editorial/editorial.ps1 init
if ($LASTEXITCODE -ne 0) { throw 'Editorial storage initialization failed.' }
```

With explicit cloud `STORAGE_ACCOUNT_NAME`, the editorial wrapper ignores local emulator settings. Initialize the registry once, then use `collect` for real originals and `run` for the paid AI pipeline. Follow `editorial-workflow.md` to inspect, correct, approve and publish drafts. Only the Function identity's OpenAI permission is assigned by `main.bicep`; the human editor needs OpenAI User separately to generate/recheck drafts.

## CI/CD configuration

c

| Variable | Value |
|---|---|
| AZURE_CLIENT_ID | OIDC deployment app registration |
| AZURE_TENANT_ID | Tenant ID |
| AZURE_SUBSCRIPTION_ID | Approved subscription |
| AZURE_RESOURCE_GROUP | Existing approved group |
| FUNCTION_APP_NAME | `functionAppName` output |
| FUNCTION_API_URL | `functionApiUrl` output, including `/api` |
| AZURE_OPENAI_RESOURCE_NAME | Existing account |
| AZURE_OPENAI_RESOURCE_GROUP | Existing account group |
| AZURE_OPENAI_ENDPOINT | Existing HTTPS endpoint |
| AZURE_OPENAI_DEPLOYMENT | Actual deployed model identifier |

Secrets: `AZURE_STATIC_WEB_APPS_API_TOKEN` for the specific SWA and `APPLICATIONINSIGHTS_CONNECTION_STRING` for existing monitoring. Configure a federated identity subject scoped to this repository's `azure-production` environment. Deployment identity should have only app deployment rights; provisioning role assignment rights should be separated where practical.

`ci.yml` runs Java/React, browser and Azurite integration tests, and Bicep compilation on push/PR. It never logs into Azure. `provision.yml` requires `approve_paid_resources=true` and references existing AI/monitoring. It does not create the optional OpenAI account. `deploy.yml` requires `confirm_deployment=true`, tests/builds, zip-deploys Java Function package and uploads only static frontend assets; it does not provision.

After provisioning, run the secured CLI once with production `STORAGE_ACCOUNT_NAME`, Azure login/RBAC and `AI_MODE=azure` to initialize registry snapshots (`init`). Containers/tables are already defined by Bicep. Do not use local emulator environment settings for production. The backend collector reads its packaged registry; storing a snapshot is operational metadata.

## Editor RBAC

Give a dedicated editor identity **Storage Blob Data Contributor** and **Storage Table Data Contributor** on the one account. Give **Cognitive Services OpenAI User** on the chosen AI account if the editor will recheck drafts. Authenticate with `az login`, set `STORAGE_ACCOUNT_NAME` and clear `APP_STORAGE_CONNECTION_STRING`. Never give the browser SAS tokens, function admin keys, RBAC tokens or connection strings.

This MVP trusts authorized developer/editor identities with underlying Storage write access; it is not a per-editor multi-tenant CMS. Named reviewer attribution records the CLI-supplied human name. RBAC provides access control, but immutable identity audit is not yet implemented. Use a tightly restricted editor group and review Azure access logs as needed.

## Launch checks

After an approved deployment:

1. `check-feeds` succeeds; `collect` stores real originals. Confirm AI endpoint/deployment/role on the intended account before explicitly running paid `run`.
2. Review one grounded draft and compare every claim with original sources. Approve and publish with CLI.
3. Verify `/api/articles` and a shareable `/clanok/{slug}` show the same content, sources and disclosure; unapproved slugs return 404.
4. Check Function CORS with the exact production browser origin. Ensure private Blob anonymous access fails.
5. Check Application Insights run/exception logs and token usage. Set budget alerts externally in the subscription; the MVP call counter controls AI attempts, not all cloud charges.
6. Review schedule, source failures and the archive periodically. Missing sources must not silently become invented news.

## Schedule and recovery

`DAILY_SCHEDULE=0 0 3 * * *` stays fixed at 03:00 UTC: 04:00 in Slovak winter, 05:00 in summer. No `WEBSITE_TIME_ZONE` dependency. Manual runs and overlapping hosts share the global Blob lease and persisted daily budget. Interrupting a process may leave a lease until its 60-second expiry; retry after that. Re-running resumes a checkpoint or creates no duplicate article. Reject unusable incomplete drafts with CLI rather than deleting underlying entities.

Do not overwrite published Markdown or indexes manually. Corrections to already published articles require a future versioned correction workflow; published/rejected articles are deliberately immutable through this CLI. Keep originals and review evidence private and retained for accountability. Storage lifecycle retention/cleanup can be configured later with an explicit policy; this MVP never silently deletes source records.
