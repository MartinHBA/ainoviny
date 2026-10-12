import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { api, ApiError, type ArticleDetail } from '../services/api';
import { ErrorState, Loading, PublishedTime } from '../components/Newspaper';

export function safeMarkdown(markdown: string): string {
  return DOMPurify.sanitize(marked.parse(markdown, { async: false }) as string, {
    ALLOWED_TAGS: ['p', 'h2', 'h3', 'h4', 'strong', 'em', 'blockquote', 'ul', 'ol', 'li', 'a', 'br', 'hr', 'code'],
    ALLOWED_ATTR: ['href', 'title'], ALLOW_DATA_ATTR: false,
  });
}
function sourceUrl(value: string): string | undefined { try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password ? u.href : undefined; } catch { return undefined; } }
export function Article() {
  const { slug = '' } = useParams(); const [article, setArticle] = useState<ArticleDetail>();
  const [error, setError] = useState<number | null>(null); const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setArticle(undefined); setError(null);
    api.article(slug, controller.signal).then(setArticle).catch(e => { if (!controller.signal.aborted) setError(e instanceof ApiError ? e.status : 503); });
    return () => controller.abort();
  }, [slug, attempt]);
  useEffect(() => { if (article) document.title = `${article.headline} — Verejný dohľad`; return () => { document.title = 'VEREJNÝ DOHĽAD — Rozhodnutia pod drobnohľadom'; }; }, [article]);
  const html = useMemo(() => article ? safeMarkdown(article.markdown) : '', [article]);
  if (error === 404) return <div className="status-panel"><span className="eyebrow">Strana sa nenašla</span><h1>Tento článok tu nie je.</h1><p>Možno ešte nebol zverejnený alebo je odkaz neúplný.</p><Link to="/">Späť na titulnú stranu ↗</Link></div>;
  if (error) return <ErrorState retry={() => setAttempt(n => n + 1)} />;
  if (!article) return <Loading />;
  return <article className="reading-page"><Link to="/" className="back-link">← Titulná strana</Link>
    <header className="article-header"><span className="eyebrow">{article.category}</span><h1>{article.headline}</h1><p className="article-perex">{article.perex}</p><div className="article-byline"><span>Redakcia Verejného dohľadu</span><PublishedTime value={article.publishedAt} /></div></header>
    <div className="article-body" dangerouslySetInnerHTML={{ __html: html }} />
    <section className="source-section" aria-labelledby="sources-heading"><span className="eyebrow">Transparentnosť</span><h2 id="sources-heading">Ako sme na to prišli</h2><p>Vychádzame z nižšie uvedených oficiálnych podkladov. Tvrdenia vydávajúcej inštitúcie pripisujeme ich autorovi. Súlad textu s podkladmi kontrolujeme pomocou AI, pravidiel a ľudskej redakčnej kontroly. Samotná tlačová správa ani AI kontrola nie sú nezávislým overením jej tvrdení.</p>
      <h3>Pôvodné zdroje</h3><ol>{article.originalSources.map((url, index) => { const safe = sourceUrl(url); return safe ? <li key={url}><a href={safe} target="_blank" rel="noopener noreferrer">{new URL(safe).hostname} — pôvodný dokument {index + 1} ↗</a></li> : null; })}</ol>
      <p className="ai-disclosure">{article.aiDisclosure}</p>
    </section><Link to="/archiv" className="back-link">Ďalšie články v archíve →</Link>
  </article>;
}
