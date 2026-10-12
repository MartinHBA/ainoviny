import { useEffect } from 'react';
import { Routes, Route, useLocation, Link } from 'react-router-dom';
import { Header, Footer } from './components/Newspaper';
import { Home } from './pages/Home';
import { Archive } from './pages/Archive';
import { Article } from './pages/Article';
import { About } from './pages/About';

export default function App() {
  const location = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  return <div className="newspaper"><Header /><main id="main" tabIndex={-1}><Routes>
    <Route path="/" element={<Home />} /><Route path="/clanok/:slug" element={<Article />} />
    <Route path="/archiv" element={<Archive />} /><Route path="/rubrika/:category" element={<Archive />} /><Route path="/o-novinach" element={<About />} />
    <Route path="*" element={<div className="status-panel"><h1>Strana sa nenašla.</h1><Link to="/">Titulná strana ↗</Link></div>} />
  </Routes></main><Footer /></div>;
}
