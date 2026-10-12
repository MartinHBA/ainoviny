import { Link, NavLink } from 'react-router-dom';
import type { ArticleSummary } from '../services/api';

export function slovakDate(date: string | Date, options: Intl.DateTimeFormatOptions = {}) {
  return new Intl.DateTimeFormat('sk-SK', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Europe/Bratislava', ...options }).format(new Date(date));
}
export function PublishedTime({ value }: { value: string }) {
  return <time dateTime={value}>{slovakDate(value, { month: 'short', hour: '2-digit', minute: '2-digit' })}</time>;
}
export function Header() {
  return <>
    <a className="skip-link" href="#main">Preskočiť na obsah</a>
    <header className="newspaper-header">
      <div className="edition-line"><time dateTime={new Date().toISOString()}>{slovakDate(new Date(), { weekday: 'long' })}</time><span>Verejný záujem. Každé ráno.</span><span className="edition-country">Slovenské digitálne noviny</span></div>
      <Link to="/" className="masthead" aria-label="Verejný dohľad — domov">VEREJNÝ DOHĽAD</Link>
      <div className="masthead-subtitle"><span>Rozhodnutia pod drobnohľadom</span><span className="subtitle-dot">·</span><span>Súvislosti na prvom mieste</span></div>
      <nav aria-label="Hlavná navigácia">
        <NavLink to="/" end>Domov</NavLink><NavLink to="/rubrika/slovensko">Slovensko</NavLink><NavLink to="/rubrika/verejne-peniaze">Verejné peniaze</NavLink><NavLink to="/rubrika/politika">Politika</NavLink><NavLink to="/archiv">Archív</NavLink>
      </nav>
    </header>
  </>;
}
export function Footer() {
  return <footer className="newspaper-footer">
    <div><Link to="/" className="footer-brand">Verejný dohľad</Link><p>O verejných veciach. Zrozumiteľne.</p></div>
    <div className="footer-note"><p>Pracujeme s oficiálnymi zdrojmi. Články pripravuje AI a pred automatickým zverejnením ich kontroluje samostatný AI editor.</p><Link to="/o-novinach">Ako pracujeme ↗</Link></div>
    <div className="footer-bottom"><span>© {new Date().getFullYear()} Verejný dohľad</span><span>Fakty potrebujú kontext.</span></div>
  </footer>;
}
export function Story({ article, lead = false }: { article: ArticleSummary; lead?: boolean }) {
  return <article className={lead ? 'story story-lead' : 'story'}>
    <span className="eyebrow">{article.category}</span>
    {lead ? <h1><Link to={`/clanok/${article.slug}`}>{article.title}</Link></h1> : <h2><Link to={`/clanok/${article.slug}`}>{article.title}</Link></h2>}
    <p className="story-perex">{article.perex}</p>
    <div className="story-meta"><PublishedTime value={article.publishedAt} /><Link to={`/clanok/${article.slug}`} aria-label={`Čítať: ${article.title}`}>Čítať článok <span aria-hidden="true">↗</span></Link></div>
  </article>;
}
export function Empty({ archive = false }: { archive?: boolean }) {
  return <section className="empty-edition" aria-label="Prázdne vydanie">
    <div className="empty-number" aria-hidden="true">V.</div>
    <span className="eyebrow">{archive ? 'Archív novín' : 'Pred prvým vydaním'}</span>
    <h1>{archive ? 'Tu bude história verejných rozhodnutí.' : 'Dobré noviny začínajú\npoctivým čítaním.'}</h1>
    <p>{archive ? 'V tejto rubrike zatiaľ nie sú zverejnené články. Každý schválený text tu nájde svoje miesto.' : 'Naše prvé vydanie sa pripravuje. Sledujeme oficiálne zdroje, hľadáme podstatné súvislosti a každý článok pred zverejnením kontroluje samostatný AI editor.'}</p>
    <span className="empty-footnote">Zverejníme iba to, čo si zaslúži vašu pozornosť.</span>
  </section>;
}
export function Loading() { return <div role="status" className="status-panel"><span className="eyebrow">Z redakcie</span><p>Načítavame vydanie…</p></div>; }
export function ErrorState({ retry }: { retry: () => void }) { return <div role="alert" className="status-panel"><span className="eyebrow">Spojenie s redakciou</span><h1>Noviny sa nepodarilo načítať.</h1><p>Skúste to, prosím, o chvíľu znova.</p><button className="text-button" onClick={retry}>Skúsiť znova ↗</button></div>; }
export function EditorialNote() {
  return <aside className="editorial-note"><span className="eyebrow">Poznámka redakcie</span><h2>Verejné veci <br />patria verejnosti.</h2><p>Rozhodnutia štátu ovplyvňujú naše každodenné životy. Čítame pôvodné dokumenty a vysvetľujeme, čo z nich vyplýva — aj čo ešte nevieme.</p><Link to="/o-novinach">O našich novinách ↗</Link><div className="note-signature">Redakcia Verejného dohľadu</div></aside>;
}
