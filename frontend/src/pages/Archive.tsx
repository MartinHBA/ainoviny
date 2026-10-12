import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, type ArticleSummary } from '../services/api';
import { Empty, ErrorState, Loading, Story } from '../components/Newspaper';

const categories: Record<string, string> = { slovensko: 'Slovensko', 'verejne-peniaze': 'Verejné peniaze', politika: 'Politika' };
export function Archive() {
  const { category: categorySlug } = useParams(); const category = categorySlug ? categories[categorySlug] : undefined;
  const [articles, setArticles] = useState<ArticleSummary[]>([]); const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true); const [error, setError] = useState(false); const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(false); setArticles([]); setCursor(null);
    api.list({ category }, controller.signal).then(page => { setArticles(page.articles); setCursor(page.nextCursor); }).catch(() => { if (!controller.signal.aborted) setError(true); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [category, attempt]);
  async function more() {
    if (!cursor || loading) return; setLoading(true); setError(false);
    try { const page = await api.list({ category, cursor }); setArticles(old => [...old, ...page.articles]); setCursor(page.nextCursor); } catch { setError(true); } finally { setLoading(false); }
  }
  if (categorySlug && !category) return <div className="status-panel"><h1>Rubrika neexistuje.</h1></div>;
  return <><div className="section-line"><span>{category || 'Archív'}</span><span>Všetky zverejnené články</span></div>
    {articles.length ? <div className="archive-grid">{articles.map(a => <Story key={a.id} article={a} />)}</div> : !loading && !error ? <Empty archive /> : null}
    {loading && <Loading />}{error && <ErrorState retry={() => articles.length ? void more() : setAttempt(n => n + 1)} />}
    {cursor && !loading && !error && <button className="archive-more text-button" onClick={() => void more()}>Staršie články ↓</button>}
  </>;
}
