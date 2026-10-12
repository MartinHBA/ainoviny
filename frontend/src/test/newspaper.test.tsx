import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import App from '../App';
import { api, ApiError, type ArticleSummary } from '../services/api';
import { safeMarkdown } from '../pages/Article';

const story: ArticleSummary = { id: 'synthetic', slug: 'synthetic-test', title: '[SYNTHETIC] Skúšobný nadpis', perex: 'Jasne označený syntetický testovací text.', category: 'Slovensko', publishedAt: '2026-10-12T04:00:00Z' };
function app(path = '/') { return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>); }

describe('newspaper', () => {
  it('renders the masthead and honest empty edition', async () => {
    vi.spyOn(api, 'latest').mockResolvedValue({ date: null, featured: null, articles: [] });
    vi.spyOn(api, 'list').mockResolvedValue({ articles: [], nextCursor: null });
    app(); expect(screen.getByRole('link', { name: 'Verejný dohľad — domov' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: /Dobré noviny začínajú/ })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Hlavná navigácia' })).toBeInTheDocument();
  });
  it('loads published headlines dynamically and gives each a shareable URL', async () => {
    vi.spyOn(api, 'latest').mockResolvedValue({ date: '2026-10-12', featured: story, articles: [] });
    vi.spyOn(api, 'list').mockResolvedValue({ articles: [story], nextCursor: null });
    app(); expect(await screen.findByRole('heading', { name: story.title })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: story.title })).toHaveAttribute('href', '/clanok/synthetic-test');
  });
  it('handles API failures without fake news', async () => {
    vi.spyOn(api, 'latest').mockRejectedValue(new ApiError(503));
    vi.spyOn(api, 'list').mockRejectedValue(new ApiError(503));
    app(); expect(await screen.findByRole('alert')).toHaveTextContent('Noviny sa nepodarilo načítať.');
  });
  it('renders Markdown, evidence links and disclosure', async () => {
    vi.spyOn(api, 'article').mockResolvedValue({ id: story.id, slug: story.slug, headline: story.title, perex: story.perex, category: story.category, publishedAt: story.publishedAt,
      markdown: '## Syntetický podklad\n\nTestovací **článok**.', originalSources: ['https://www.vlada.gov.sk/rss/'], aiDisclosure: 'Syntetický text pripravený pomocou AI a schválený redaktorom.' });
    app('/clanok/synthetic-test'); expect(await screen.findByRole('heading', { name: 'Ako sme na to prišli' })).toBeInTheDocument();
    expect(screen.getByText('článok')).toHaveProperty('tagName', 'STRONG');
    expect(screen.getByRole('link', { name: /pôvodný dokument/ })).toHaveAttribute('href', 'https://www.vlada.gov.sk/rss/');
  });
  it('shows 404 instead of an unpublished article', async () => {
    vi.spyOn(api, 'article').mockRejectedValue(new ApiError(404));
    app('/clanok/unpublished'); expect(await screen.findByRole('heading', { name: 'Tento článok tu nie je.' })).toBeInTheDocument();
  });
  it('filters archive requests by category', async () => {
    const list = vi.spyOn(api, 'list').mockResolvedValue({ articles: [], nextCursor: null });
    app('/rubrika/verejne-peniaze'); await waitFor(() => expect(list).toHaveBeenCalledWith({ category: 'Verejné peniaze' }, expect.any(AbortSignal)));
  });
  it('sanitizes scripts, event handlers, images and javascript links', () => {
    const result = safeMarkdown('<script>alert(1)</script><img src=x onerror=alert(1)>\n\n[bad](javascript:alert(1))\n\n**safe**');
    expect(result).not.toMatch(/<script|onerror|<img|href="javascript:/i); expect(result).toContain('<strong>safe</strong>');
  });
});
