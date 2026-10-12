# VEREJNÝ DOHĽAD

Slovak digital newspaper with a Java 21 Azure Functions editorial pipeline, React/TypeScript newspaper frontend, Blob/Table persistence, a separate AI chief editor for automatic publication, optional manual recovery CLI, Bicep and GitHub Actions. No cloud resources are provisioned by installing or running the local project.

## Local setup

Prerequisites: Java 21, Maven 3.9+, Node.js 22 LTS, Azure Functions Core Tools **v4** (`func` on PATH). Azure CLI is needed only for production RBAC or Bicep validation. Install Core Tools using [Microsoft's official instructions](https://learn.microsoft.com/azure/azure-functions/functions-run-local). The repository uses portable local tooling for development where available; `.tools/` is ignored.

```powershell
npm ci
npm --prefix frontend ci
Copy-Item backend/local.settings.json.example backend/local.settings.json
Copy-Item frontend/.env.example frontend/.env
npx playwright install chromium
```

Open three terminals from the repository root:

```powershell
# Terminal 1: keep the single local Storage emulator running
npm run storage

# Terminal 2: provision local containers/tables, then run Java Functions
./scripts/setup-storage/setup-storage.ps1
./scripts/start-backend.ps1

# Terminal 3: Vite proxies /api to localhost:7071
npm run frontend
```

Visit **http://localhost:5173**. An empty edition is intentional until an approved article is published. APIs: `http://localhost:7071/api/articles`, `/api/articles/{slug}`, `/api/editions/latest`. Drafts and storage blobs are private. For unattended production, activate [the automatic AI editor](docs/deployment.md#activate-the-automatic-ai-editor): `gpt-4.1-mini` writes, a separate configurable `gpt-4.1` deployment decides publication. No routine human review is needed; failed or sensitive articles stay unpublished. Automatic publication is disabled until explicitly configured.

The default `AI_MODE=mock` makes **zero paid AI calls**. It assigns real material a score of zero; only explicitly marked synthetic fixtures generate offline drafts. No fake news is seeded on startup. Create an optional local fixture to exercise review/publishing:

```powershell
./scripts/editorial/editorial.ps1 fixture
./scripts/editorial/editorial.ps1 -SkipBuild list
./scripts/editorial/editorial.ps1 -SkipBuild inspect ARTICLE_ID
./scripts/editorial/editorial.ps1 -SkipBuild approve ARTICLE_ID 'Local test editor'
./scripts/editorial/editorial.ps1 -SkipBuild publish ARTICLE_ID
```

The fixture is prominently marked `[SYNTHETIC]`, and CLI fixture creation/publication is restricted to `UseDevelopmentStorage=true`. Its `example.invalid` source is explicitly synthetic and deliberately not a real document; actual articles receive the original government URLs.

## Collecting real sources and using Azure AI

```powershell
# Public source connectivity/body checks only; no Storage or AI needed
./scripts/editorial/editorial.ps1 check-feeds
# Download real originals into Azurite, without AI
./scripts/editorial/editorial.ps1 -SkipBuild collect
```

Verified on 12 October 2026: Úrad vlády SR RSS, MF SR RSS and MH SR Atom. MV SR remains disabled without an established suitable endpoint. NKÚ has a real RSS feed but is disabled because tested item links redirected to a listing and several entries lacked canonical URLs. See [source verification](docs/sources.md) and the configurable [registry](backend/src/main/resources/sources.json).

To use the configured Azure deployment, set `AI_MODE=azure`, `AZURE_OPENAI_ENDPOINT` and `AZURE_OPENAI_DEPLOYMENT` in your ignored settings or environment. Prefer `az login` and **Cognitive Services OpenAI User** RBAC; an optional `AZURE_OPENAI_API_KEY` is supported for local development. Then `editorial.ps1 run` performs the morning pipeline. This step can incur model charges and is never run by offline tests. No paid calls were required to develop this repository.

Timer: `0 0 3 * * *` means **03:00 UTC**, **04:00 CET / 05:00 CEST**. Collection has a two-minute budget; pipeline has an eight-minute budget within the Consumption host's ten-minute timeout. This leaves margin before 07:00 Bratislava. Outages may delay or prevent an edition; there is no guaranteed completion SLA. Manual execution is a local RBAC CLI command, not a public administrative endpoint.

## Tests and builds

```powershell
mvn -B -f backend/pom.xml verify
npm --prefix frontend test
npm --prefix frontend run build
npx playwright test
# Start Azurite first; explicitly opt in to local Storage integration
mvn -f backend/pom.xml -Dtest=AzureStoreIT -Dstorage.integration=true test
# Compile templates only; does not deploy
az bicep build --file infrastructure/main.bicep --outfile .local/main.json
az bicep build --file infrastructure/openai.bicep --outfile .local/openai.json
```

Tests use clearly marked synthetic releases and mock AI. Browser tests check both desktop and mobile, navigation, article URLs, loading/error states and Markdown sanitization. Storage integration tests exercise real Azure SDK operations against Azurite, including duplicate transactions, ETags, leases and publication indexes. See [verification notes](docs/verification.md).

## Deployment and editorial operations

For GitHub frontend deployment, local npm is unnecessary: the GitHub runner installs Node.js, tests/builds React and uploads to the existing Static Web App. Use the manual **Deploy newspaper frontend** workflow with `FUNCTION_API_URL` and the app's deployment token in the `azure-production` GitHub environment. See [the GitHub deployment steps](docs/deployment.md#deploy-the-frontend-through-github-without-local-npm). The full backend/frontend release workflow additionally uses Azure OIDC.

Follow [deployment](docs/deployment.md), [architecture](docs/architecture.md), and [editorial workflow](docs/editorial-workflow.md). Provisioning and release workflows are **manual** and require an explicit affirmative dispatch input. Configure the `azure-production` GitHub environment with required reviewers before enabling deployment. No push or pull request provisions paid resources.

Static Web Apps **Free** calls the Function App URL directly with restricted CORS. Its built-in bring-your-own Function proxy requires Standard and is deliberately unused. The Function runtime and application data share exactly **one Standard LRS Storage Account**. Optional Application Insights references existing monitoring infrastructure; no second Storage Account or database is added.

## Boundaries

AI grounding checks compare text with retrieved material; they cannot prove the issuing institution's assertions. The low-cost writing model performs separate analysis, generation and verification calls. In automatic mode a stronger AI chief editor approves publication, with private audits and shared daily budgets; sensitive or rejected stories remain unpublished. Optional human CLI recovery is available. No autonomous tool loops, vector search, copied editorial images or CMS.

Client rendering supports shareable URLs but provides limited crawler/social preview metadata. A later build can prerender approved articles from the public API or add server rendering; publication must still avoid requiring a manual frontend rebuild. The current MVP intentionally keeps that complexity out.
