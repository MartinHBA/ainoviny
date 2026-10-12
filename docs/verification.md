# Verification record

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
