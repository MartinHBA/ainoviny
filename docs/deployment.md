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
6. After the job succeeds, open `https://proud-field-0b4892d03.2.azurestaticapps.net` for this instance, or the `staticWebAppUrl` Bicep output. Empty editions are expected until an approved article is published; enable the automatic editor below for unattended publication.

To upload the local source using your normal Git login, run from the repository root:

```powershell
git add .
git commit -m "Add newspaper MVP and GitHub deployment workflows"
git push origin main
```

This frontend workflow creates no Azure resources and does not call the AI model. Full backend/frontend releases can use `deploy.yml` after configuring Azure OIDC as described in the CI/CD section below.

#### Function App Deployment Center

The backend is already deployed if the earlier ZIP upload succeeded and `/api/articles` responds. Deployment Center sets up future backend releases through GitHub; npm is not needed for this Java build.

1. Open Function App **`verejnydohlad-wny6o5yct2uf2` → Deployment → Deployment Center**.
2. Choose **Continuous Deployment (CI/CD)**, source **GitHub**, and build provider **GitHub Actions**.
3. Select owner **MartinHBA**, repository **ainoviny**, and branch **main**.
4. Choose **Add a workflow**. For authentication choose **User-assigned identity** and the suggested new identity. The portal creates a deployment identity, grants Website Contributor on this Function App, configures GitHub OIDC and adds the workflow's authentication values to GitHub. This identity is separate from the Function App's runtime managed identity.
5. Select **Preview file**. This repository has its Maven project in `backend`, not at the repository root. For the Java template set:

```yaml
env:
  AZURE_FUNCTIONAPP_NAME: 'verejnydohlad-wny6o5yct2uf2'
  POM_XML_DIRECTORY: 'backend'
  JAVA_VERSION: '21'
```

The Maven step must run in `backend` (through `working-directory: ${{ env.POM_XML_DIRECTORY }}`) and build with `mvn -B clean verify -DfunctionAppName=${{ env.AZURE_FUNCTIONAPP_NAME }}`. With this override, the artifact folder is `backend/target/azure-functions/verejnydohlad-wny6o5yct2uf2`. If the generated template does **not** override `functionAppName`, the folder instead remains `backend/target/azure-functions/verejny-dohlad-local`; the actual deployment target is still `verejnydohlad-wny6o5yct2uf2`. Ensure artifact upload and deployment use the folder actually built, with `host.json` at the package root, and exclude `local.settings.json`. Preserve the portal-generated login values and OIDC permissions.

6. Select **Save**. If the preview cannot be edited, edit the new workflow under GitHub **Code → .github/workflows** after saving and commit the corrections. The initial run may fail until the Maven path is corrected. Check the generated workflow under **Actions** or the portal's **Logs** tab; subsequent pushes to `main` trigger it automatically.
7. After a successful deployment, verify `Invoke-RestMethod 'https://verejnydohlad-wny6o5yct2uf2.azurewebsites.net/api/articles'`. An empty articles array is a valid response before human publication.

See [Microsoft's Deployment Center instructions and Java workflow template](https://learn.microsoft.com/azure/azure-functions/functions-how-to-github-actions). The existing full-release `deploy.yml` expects separately configured environment variables and an OIDC credential scoped to `azure-production`; portal-generated authentication does not automatically configure that workflow.

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

For this Windows Consumption Function App keep the site setting `javaVersion: '21'`, matching Maven and GitHub Java 21. Azure CLI 2.91.0 can report an `Invalid version: 21` warning during ZIP deployment because its runtime list uses `21.0`; that warning alone is not a deployment failure. Do not change the Function host to `21.0` to suppress it: a production check on 12 October 2026 showed that this left `JAVA_HOME` unresolved, crashed the Java worker and returned HTTP 502. The verified Function setting is `21`. If an earlier template or command applied `21.0`, restore it through the resource configuration API:

```powershell
$appSubscriptionId = az account show --query id --output tsv
az resource update --ids "/subscriptions/$appSubscriptionId/resourceGroups/$appResourceGroup/providers/Microsoft.Web/sites/$functionAppName/config/web" --api-version 2023-12-01 --set properties.javaVersion=21 --query properties.javaVersion --output tsv
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

Open the returned URL. An empty newspaper edition is expected until the first approved article is published. Frontend release and later article publication are separate: new approved articles appear through the API without another frontend build.

Deployment command references: [Java Function ZIP deployment](https://learn.microsoft.com/azure/azure-functions/deployment-zip-push), [SWA CLI deployment](https://azure.github.io/static-web-apps-cli/docs/cli/swa-deploy/).

#### Activate the automatic AI editor

This replaces routine human editorial work. Code changes alone do not activate it in an already provisioned Function App. First deploy the updated backend (the same ZIP/GitHub route as before) and frontend (GitHub **Deploy newspaper frontend**), so the website accurately discloses AI approval. Keep automatic publication disabled until both releases succeed.

The existing instance has only `gpt-4.1-mini`. The Azure model catalogue for `myAItest` in West Europe lists `gpt-4.1` / `2025-04-14` with GlobalStandard support, checked 12 October 2026. Read-only quota checks showed 1,000 capacity units available for GlobalStandard gpt-4.1 and 4,999 remaining for gpt-4.1-mini. Add the editor deployment in the **same** OpenAI account; no new OpenAI account, Storage Account or Function App is needed. It requires separate approval of its usage costs under the original provisioning requirement. Do not run the create command until that approval is given.

The current writer deployment has capacity 1: just 1 request/minute and 1,000 tokens/minute. Its generation output allowance alone is 2,200 tokens, before source input. The commands below also correct that existing deployment to capacity 300 (300,000 TPM), and give the editor capacity 100 (100,000 TPM), within the observed quota. These are GlobalStandard rate-limit allocations, not reserved provisioned-throughput units; actual input/output token usage is billed. Capacity does not replace the application's 16-call/3-article daily limits. See [Azure quota allocation](https://learn.microsoft.com/azure/ai-foundry/openai/how-to/quota) and [Azure OpenAI pricing](https://azure.microsoft.com/pricing/details/azure-openai/). Review model/version and quota again for another account before applying.

```powershell
# Local compilation and read-only provisioning preview:
az bicep build --file infrastructure/editor-model.bicep --outfile .local/editor-model.json
if ($LASTEXITCODE -ne 0) { throw 'Editor model template compilation failed.' }
az deployment group what-if --resource-group AI --template-file infrastructure/editor-model.bicep --parameters resourceName=myAItest configureWriterCapacity=true
if ($LASTEXITCODE -ne 0) { throw 'Editor model preview failed.' }

# After model cost/provisioning approval:
az deployment group create --name newspaper-editor-model --resource-group AI --template-file infrastructure/editor-model.bicep --parameters resourceName=myAItest configureWriterCapacity=true
if ($LASTEXITCODE -ne 0) { throw 'Editor model deployment failed; keep automatic publication disabled.' }

# Enable only after the updated backend/frontend and editor model are deployed:
az functionapp config appsettings set --resource-group verejny-dohlad --name verejnydohlad-wny6o5yct2uf2 --settings AZURE_OPENAI_EDITOR_DEPLOYMENT=gpt-4.1 AUTO_PUBLISH_ENABLED=true MAX_ARTICLES_PER_DAY=8 MAX_PUBLISHED_ARTICLES_PER_DAY=5 MAX_PRESS_RELEASES_PER_RUN=40 MAX_AI_CALLS_PER_DAY=40 --output none
if ($LASTEXITCODE -ne 0) { throw 'Automatic editor settings update failed.' }
Invoke-RestMethod 'https://verejnydohlad-wny6o5yct2uf2.azurewebsites.net/api/articles'
```

Existing Function managed identity OpenAI User permission applies to both model deployments in that account. Updating settings can restart the host. No paid AI call is made by the final public API check; the next timer run will call the models and publish only accepted articles. Inspect `MorningEdition` logs: run summaries include `collected`, `drafts`, `published`, `rejected` and `errors`. A missing deployment, exhausted budget or failed editor request never triggers an approval fallback.

For subsequent infrastructure deployments pass `openAiEditorDeployment=gpt-4.1 autoPublishEnabled=true` to `main.bicep`, or set these parameters in your parameter file. Their default is disabled, so deploying the defaults later will suspend automatic publication. Token pricing is not hardcoded; set `EDITOR_INPUT_USD_PER_MILLION` and `EDITOR_OUTPUT_USD_PER_MILLION` to the chosen Azure model/SKU rates if estimated cost logging is wanted.

To suspend automatic publication without deleting data:

```powershell
az functionapp config appsettings set --resource-group verejny-dohlad --name verejnydohlad-wny6o5yct2uf2 --settings AUTO_PUBLISH_ENABLED=false --output none
```

#### Initialize and use editorial operations

The timer initializes source registry rows automatically as sources are checked, so unattended operation requires no human CLI initialization. The commands below are optional for manual registry snapshots and recovery. For those commands use the editor RBAC described below: the logged-in human identity needs Blob/Table Data Contributor; ordinary management-plane Contributor access alone does not grant application-data access.

```powershell
Remove-Item Env:APP_STORAGE_CONNECTION_STRING -ErrorAction SilentlyContinue
$env:STORAGE_ACCOUNT_NAME = $storageAccountName
$env:AI_MODE = 'azure'
$env:AZURE_OPENAI_ENDPOINT = $aiEndpoint
$env:AZURE_OPENAI_DEPLOYMENT = $aiDeploymentName
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/editorial/editorial.ps1 init
if ($LASTEXITCODE -ne 0) { throw 'Editorial storage initialization failed.' }
```

With explicit cloud `STORAGE_ACCOUNT_NAME`, the editorial wrapper ignores local emulator settings. The optional `init` stores the full registry snapshot. Use `collect` for real originals and `run` for the paid AI pipeline when manual operations are needed. Follow `editorial-workflow.md` for optional recovery; automatic operation does not require listing, approving or publishing drafts by hand. Only the Function identity's OpenAI permission is assigned by `main.bicep`; a human using the AI CLI needs OpenAI User separately.

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

Containers/tables are defined by Bicep. The backend collector reads its packaged registry and upserts checked source rows automatically. A full registry snapshot (`init`) is optional operational metadata. Do not use local emulator environment settings for production.

## Editor RBAC

Give a dedicated editor identity **Storage Blob Data Contributor** and **Storage Table Data Contributor** on the one account. Give **Cognitive Services OpenAI User** on the chosen AI account if the editor will recheck drafts. Authenticate with `az login`, set `STORAGE_ACCOUNT_NAME` and clear `APP_STORAGE_CONNECTION_STRING`. Never give the browser SAS tokens, function admin keys, RBAC tokens or connection strings.

This MVP trusts authorized developer/editor identities with underlying Storage write access; it is not a per-editor multi-tenant CMS. Named reviewer attribution records the CLI-supplied human name. RBAC provides access control, but immutable identity audit is not yet implemented. Use a tightly restricted editor group and review Azure access logs as needed.

## Launch checks

After an approved deployment:

1. `check-feeds` succeeds; `collect` stores real originals. Confirm AI endpoint/deployment/role on the intended account before explicitly running paid `run`.
2. In automatic mode check the final editor audit and `published`/`rejected` run counters. In manual mode inspect a draft, compare its claims with originals, and approve/publish through CLI. Model-quality evaluation before launch is separate from unattended daily operation.
3. Verify `/api/articles` and a shareable `/clanok/{slug}` show the same content, sources and disclosure; unapproved slugs return 404.
4. Check Function CORS with the exact production browser origin. Ensure private Blob anonymous access fails.
5. Check Application Insights run/exception logs and token usage. Set budget alerts externally in the subscription; the MVP call counter controls AI attempts, not all cloud charges.
6. Review schedule, source failures and the archive periodically. Missing sources must not silently become invented news.

## Schedule and recovery

`DAILY_SCHEDULE=0 0 3 * * *` stays fixed at 03:00 UTC: 04:00 in Slovak winter, 05:00 in summer. No `WEBSITE_TIME_ZONE` dependency. Manual runs and overlapping hosts share the global Blob lease and persisted daily budget. Interrupting a process may leave a lease until its 60-second expiry; retry after that. Re-running resumes a checkpoint or creates no duplicate article. Reject unusable incomplete drafts with CLI rather than deleting underlying entities.

Do not overwrite published Markdown or indexes manually. Corrections to already published articles require a future versioned correction workflow; published/rejected articles are deliberately immutable through this CLI. Keep originals and review evidence private and retained for accountability. Storage lifecycle retention/cleanup can be configured later with an explicit policy; this MVP never silently deletes source records.
