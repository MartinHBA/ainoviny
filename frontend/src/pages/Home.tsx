import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type ArticlePage, type Edition } from '../services/api';
import { EditorialNote, Empty, ErrorState, Loading, Story, slovakDate } from '../components/Newspaper';

export function Home() {
  const [data, setData] = useState<{ edition: Edition; latest: ArticlePage }>();
  const [error, setError] = useState(false); const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setError(false); setData(undefined);
    Promise.all([api.latest(controller.signal), api.list({}, controller.signal)]).then(([edition, latest]) => setData({ edition, latest })).catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, [attempt]);
  if (error) return <ErrorState retry={() => setAttempt(n => n + 1)} />;
  if (!data) return <Loading />;
  const featured = data.edition.featured || data.latest.articles[0];
  const inEdition = new Set([featured?.id, ...data.edition.articles.map(a => a.id)]);
  const other = data.latest.articles.filter(a => !inEdition.has(a.id));
  return <>
    <div className="section-line"><span>Ranné vydanie</span><span>{data.edition.date ? slovakDate(`${data.edition.date}T12:00:00Z`) : 'Priestor pre podstatné správy'}</span></div>
    <div className="frontpage-grid">
      <div>{featured ? <><Story article={featured} lead /><div className="secondary-stories">{data.edition.articles.map(a => <Story article={a} key={a.id} />)}</div></> : <Empty />}</div>
      <EditorialNote />
    </div>
    <section className="latest-section" aria-labelledby="latest-title"><div className="section-line"><h2 id="latest-title">Najnovšie články</h2><Link to="/archiv">Celý archív ↗</Link></div>
      {other.length ? <div className="latest-stories">{other.map(a => <Story article={a} key={a.id} />)}</div> : <p className="quiet-note">{featured ? 'Všetky najnovšie články nájdete v rannom vydaní vyššie.' : 'Po prvom schválenom vydaní tu nájdete najnovšie texty.'}</p>}
    </section>
    <div className="principles"><div><span>01</span><h3>Pôvodný zdroj</h3><p>Ku každému článku pripojíme podklady, z ktorých vychádzame.</p></div><div><span>02</span><h3>Jasné súvislosti</h3><p>Oddeľujeme tvrdenia inštitúcií od toho, čo dokumenty dokladajú.</p></div><div><span>03</span><h3>Ľudský úsudok</h3><p>AI pomáha s prípravou. O zverejnení rozhoduje redaktor.</p></div></div>
  </>;
}
