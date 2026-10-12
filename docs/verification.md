# Verification record

## Automatic editor update — 12 October 2026

The automated chief-editor implementation and production fixes passed **45 Java unit tests**, **4 actual Azure SDK/Azurite integration tests**, **7 React tests** and **6 desktop/mobile browser tests** (62 total). New checks cover distinct writer/editor API deployment routing, strict editor JSON output, full-original context and oversized-input rejection, refusals/truncation, rejection of unsupported/sensitive stories, truthful public disclosure, daily budgets, article quota, persisted approval audits, recovery after audit/approval/publication failures, changed-content/source invalidation, and isolation of unpublished content. Production regression tests cover assembling article text from evidence-bearing paragraphs without duplicate model text, selecting exact original excerpts by identifier, rejection of unknown evidence/source identifiers, retained numeric guards, malformed paragraph boundaries, and automatic creation of missing source registry rows. The real Storage SDK tests persist and reload the review audit and atomically publish through Azure Tables. All model responses in automated tests are offline mocks; no paid AI call was made by those tests.

The production frontend build and Maven Function packaging passed. `main.bicep`, `openai.bicep` and `editor-model.bicep` compiled locally. The backend ZIP has `host.json` at its root and excludes `local.settings.json`. Read-only Azure checks confirmed the morning timer, actual model/version, model catalogue and quota. After explicit owner approval, `gpt-4.1` / `2025-04-14` / GlobalStandard capacity 100 was deployed in the existing `myAItest` account and the existing writer capacity increased from 1 to 300. Sibling deployment writes are ordered because Cognitive Services rejected concurrent modifications of the same parent account.

The approved backend ZIP and updated frontend were deployed to the existing Function App and SWA. Live frontend HTML and bundled AI disclosure were checked. `AUTO_PUBLISH_ENABLED=true`, `AZURE_OPENAI_EDITOR_DEPLOYMENT=gpt-4.1` and `MAX_AI_CALLS_PER_DAY=16` are configured; the article limit remains 3. Windows Functions `javaVersion=21.0` caused an unresolved `JAVA_HOME` worker crash and HTTP 502; restoring the verified `21` value and restarting recovered HTTP 200. Infrastructure and deployment documentation now retain the working value despite the Azure CLI version-format warning.

An approved real production run collected 10 originals and successfully called Azure AI, validating managed-identity inference access. Its first pass rejected two ungrounded drafts and left one incomplete generation private; zero articles were published. Source registry writes initially required missing CLI-created rows. The published fixes now upsert these rows automatically and eliminate duplicated publication/evidence text in the generation response. Rejected drafts were not manually approved or recreated. Automated tests prove orchestration safeguards, not that AI editorial decisions are invariably correct.

After the first fixes, an approved retry completed in 15 seconds with `collected=0`, `drafts=1`, `published=0`, `rejected=1`, `errors=[]`: it reused existing originals and rejected a miscopied evidence excerpt. The final generator now selects provided excerpt IDs, and code supplies exact original text; it cannot introduce that copying error. Existing rejected articles and daily budget reservations were retained. The 12 October verification consumed all three article reservations, so the final generation contract will process new eligible subjects after the daily budget resets; no reservation was deleted or quota bypassed to force a publication. GPT-4.1 is deployed and configured, but these failed preliminary checks did not reach its final review stage; real final-editor quality remains unverified for this release.

The checks below record the earlier MVP implementation and are historical.

Verified locally on **12 October 2026** in Windows with Java 21, Maven 3.9, Node 22, Azure Functions Core Tools 4.16, Azurite 3.37, Chromium via Playwright and Bicep CLI 0.48. No Azure resources were provisioned, no paid AI requests were made and no changes were pushed to GitHub.

## Completed checks

| Check | Result |
|---|---|
| Java unit tests | 24 passed: RSS/Atom, XXE prevention, normalization, deduplication, pipeline restart, daily limits, grounding, AI JSON contracts, state transitions and private draft filtering |
| Azure SDK / Azurite integration | 2 passed: original blob retrieval, duplicate same-partition transaction, ETag stale-write rejection, budget CAS/idempotent reservation, lease exclusion, approval/publication, public index pagination and failed-publication atomic rollback/retry |
| React unit tests | 7 passed: masthead, honest empty edition, live-data headline URLs, API errors, Markdown/source/disclosure, unpublished 404, category filter and XSS sanitization |
| Browser tests | 6 passed across desktop Chromium and mobile viewport: navigation, empty state, direct article URL/reload, safe Markdown, API outage and no horizontal overflow |
| Frontend production build | Passed, TypeScript and Vite; self-hosted licensed Newsreader fonts |
| Java Function packaging | Passed; four generated bindings: three GET routes and morning timer |
| Local Function host | Started and initialized Java worker; live `/api/articles` and `/api/editions/latest` returned 200 with published synthetic local test content; missing article 404 |
| Live browser integration | React loaded real Java/Azurite API content; article click and direct URL reload passed, without API interception |
| PowerShell editorial scripts | Storage init, synthetic fixture, export/edit/recheck/approve/publish completed locally; invalid page size 400, POST route unavailable, exact local CORS origin verified |
| Official Java feed check | ÚV SR, MF SR and MH SR parsed with usable source text and robots checks |
| Real collection into Azurite | 10 original releases stored, zero collector errors, zero AI calls |
| Bicep | `main.bicep` and optional `openai.bicep` compiled locally without errors; no cloud deployment |
| npm dependency audit | Root development tools and frontend each returned zero reported vulnerabilities after updates |
| Visual inspection | Desktop and mobile empty-edition screenshots rendered and inspected; responsive serif newspaper layout, navigation and readable source/disclosure layouts |

Browser tests intentionally intercept API requests with explicitly marked synthetic fixtures to test layout and error states. Live HTTP smoke tests separately use the actual Java Functions host and actual Azurite SDK persistence. Synthetic publication lives only in the local emulator, not in Azure or on a public website.

An expected XML parser error message during the XXE test means the malicious DOCTYPE was correctly rejected. The Azure SDK may emit an SLF4J no-binding notice; operational JUL/Function context logging remains available. Neither notice is a failed test.

## Remaining production verification

Actual **Azure OpenAI generation/verification**, managed identity/RBAC propagation, deployed Azure Function operation, SWA hosting/CORS and Application Insights ingestion require an approved Azure account/configuration and were not executed. The implementation includes the Azure v1 API adapter, strict schemas, token logging, configurable deployment and secured workflows. Local tests cannot prove deployment availability/quota or model editorial quality.

Before launch, follow `deployment.md` and approve any cloud costs explicitly. Run one real AI draft using the chosen resource, review its source grounding and editorial quality, then approve/publish only after human review. CI workflows are supplied and locally equivalent checks ran; GitHub-hosted workflow runs and Azure what-if/deployments were not triggered.

The MVP is deployable source, not a claim of an already live cloud newspaper. MV SR and NKÚ remain disabled as documented. AI checks are source-comparison checks, not independent investigations.
