import { expect, test } from '@playwright/test';

const article = { id: 'synthetic-browser-fixture', slug: 'synthetic-browser-fixture', title: '[SYNTHETIC] Rozhodnutia, ktoré si zaslúžia verejnú pozornosť', perex: 'Toto je jasne označený syntetický článok určený výlučne na testovanie rozloženia novín a čitateľnosti.', category: 'Verejné peniaze', publishedAt: '2026-10-12T04:15:00Z' };
test('empty edition, navigation and viewport have no horizontal overflow', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ json: route.request().url().includes('/editions/') ? { date: null, featured: null, articles: [] } : { articles: [], nextCursor: null } }));
  await page.goto('/'); await expect(page.getByRole('heading', { name: /Dobré noviny začínajú/ })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.getByRole('link', { name: 'Archív', exact: true }).click(); await expect(page.getByRole('heading', { name: /história verejných/ })).toBeVisible();
});
test('published story opens a safe, readable, directly addressable article', async ({ page }) => {
  await page.route('**/api/**', route => {
    const url = route.request().url();
    const json = url.includes('/articles/synthetic-browser-fixture') ? { ...article, headline: article.title, markdown: '## Syntetický test\n\nTestovací odsek pre čítanie na rôznych obrazovkách.\n\n<script>window.injected=true</script>', originalSources: ['https://www.vlada.gov.sk/rss/'], aiDisclosure: 'Syntetický test. AI príprava, ľudská kontrola.' } : url.includes('/editions/') ? { date: '2026-10-12', featured: article, articles: [] } : { articles: [article], nextCursor: null };
    return route.fulfill({ json });
  });
  await page.goto('/'); await page.getByRole('link', { name: article.title, exact: true }).click();
  await expect(page).toHaveURL(/\/clanok\/synthetic-browser-fixture/); await expect(page.getByRole('heading', { name: 'Ako sme na to prišli' })).toBeVisible();
  await page.reload(); await expect(page.getByRole('heading', { name: article.title })).toBeVisible();
  expect(await page.evaluate(() => 'injected' in window)).toBeFalsy();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
});
test('service outage is clearly distinguished from an empty edition', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ status: 503, json: { error: 'Synthetic failure' } }));
  await page.goto('/'); await expect(page.getByRole('alert')).toContainText('Noviny sa nepodarilo načítať.');
});
