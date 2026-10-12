export interface ArticleSummary {
  id: string; slug: string; title: string; perex: string; category: string; publishedAt: string;
}
export interface ArticleDetail {
  id: string; slug: string; headline: string; perex: string; category: string; publishedAt: string;
  markdown: string; originalSources: string[]; aiDisclosure: string;
}
export interface ArticlePage { articles: ArticleSummary[]; nextCursor: string | null }
export interface Edition { date: string | null; featured: ArticleSummary | null; articles: ArticleSummary[] }

export class ApiError extends Error { constructor(public status: number) { super(`API request failed (${status})`); } }

const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '');
async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timeout = window.setTimeout(abort, 20_000);
  try {
    const response = await fetch(base + path, { signal: controller.signal, headers: { Accept: 'application/json' } });
    if (!response.ok) throw new ApiError(response.status);
    return await response.json() as T;
  } finally { window.clearTimeout(timeout); signal?.removeEventListener('abort', abort); }
}
export const api = {
  latest: (signal?: AbortSignal) => get<Edition>('/editions/latest', signal),
  list: (options: { cursor?: string; category?: string } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ pageSize: '12' });
    if (options.cursor) params.set('cursor', options.cursor);
    if (options.category) params.set('category', options.category);
    return get<ArticlePage>(`/articles?${params}`, signal);
  },
  article: (slug: string, signal?: AbortSignal) => get<ArticleDetail>(`/articles/${encodeURIComponent(slug)}`, signal),
};
