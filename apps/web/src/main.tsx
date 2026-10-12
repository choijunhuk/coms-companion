import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './style.css';

function BrowserLink() {
  const [epoch, setEpoch] = useState(0);
  useEffect(() => {
    const onHashChange = () => setEpoch((current) => current + 1);
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);
  return <App key={epoch} />;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><BrowserLink /></React.StrictMode>);
