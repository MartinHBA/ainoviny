# Official source verification

Verified with direct HTTP requests and the Java `check-feeds` collector on **12 October 2026**. Availability can change; execute `editorial.ps1 check-feeds` before a production launch. Runtime respects current robots policies and fails a source independently.

| Institution | Discovered endpoint | Enabled | Content behavior |
|---|---|---|---|
| Úrad vlády SR | [Official RSS](https://www.vlada.gov.sk/rss/) | Yes | 10 parsed items; empty descriptions. Full article body extracted from `#page-content p.govuk-body`; tested latest body 2,920 characters. `/robots.txt` returned 404. |
| MF SR | [Official RSS](https://www.mfsr.sk/sk/rss.html) | Yes | 10 parsed items; empty descriptions. Full body from `main article`; tested latest body 2,351 characters. Former finance.gov.sk redirects to mfsr.sk; current registry uses destination host. |
| MH SR | [Official Atom](https://www.mhsr.sk/feed) | Yes | 50 parsed entries with substantive summaries (tested latest 714 characters). Empty content elements fall back to summary. Only the summary is available to the pipeline; articles must not imply that the full linked document was collected. |
| MV SR | [Official homepage](https://www.minv.sk/) | No | No suitable current feed link was discovered. No guessed URL is configured. |
| NKÚ SR | [Official linked RSS](https://www.nku.gov.sk/sk/web/nku/aktuality/-/journal/rss/33855/66340) | No | XML returns 200 but descriptions are empty; some entries omit canonical article URLs and a tested article URL redirects to a listing. Reliable body resolution remains unimplemented. |

MF and MH robots were checked by the Java collector when resolving feeds/body URLs. NKÚ's robots contains restrictions for administrative/API routes; no collector bypasses them. Redirects, authentication challenges and CAPTCHAs are never followed or bypassed. A redirecting source needs an explicitly verified registry update.

To change sources, edit `backend/src/main/resources/sources.json`, ensure the feed URL and allowed HTTPS hosts are real, implement/test a reliable body selector if necessary, run `check-feeds`, then rebuild/redeploy the backend. `Sources` Table stores a registry snapshot and check timestamps; it is not an administrative override endpoint. `init` refreshes snapshots without erasing operational history.

Feeds may have missing/bad dates or URLs. Such entries are skipped. Initial collection considers only the previous seven days and at most the configured release limit. A summary is treated as the actual evidence scope; full documents are never implied when unavailable.
