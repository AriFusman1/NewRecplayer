import { useCallback, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { BottomNav } from './components/BottomNav';
import { FeedbackView } from './components/FeedbackView';
import { Header } from './components/Header';
import { NicknameDialog } from './components/NicknameDialog';
import { NowPlaying } from './components/NowPlaying';
import { I18nProvider } from './i18n';
import { CatalogPage } from './pages/CatalogPage';
import { HistoryPage } from './pages/HistoryPage';
import { PlayerPage } from './pages/PlayerPage';
import { UploadPage } from './pages/UploadPage';
import { CatalogProvider } from './state/catalog';
import { FeedbackProvider } from './state/feedback';
import { LiveProvider } from './state/live';
import { NicknameProvider, useNickname } from './state/nickname';
import { PlaylistProvider } from './state/playlist';

export function App() {
  // the live socket tells the catalog when to refetch
  const [catalogSignal, setCatalogSignal] = useState(0);
  const bump = useCallback(() => setCatalogSignal((n) => n + 1), []);
  return (
    <I18nProvider>
      <NicknameProvider>
        <FeedbackProvider>
          <LiveProvider onCatalogChange={bump}>
            <CatalogProvider changeSignal={catalogSignal}>
              <PlaylistProvider>
                <BrowserRouter>
                  <Shell />
                </BrowserRouter>
              </PlaylistProvider>
            </CatalogProvider>
          </LiveProvider>
        </FeedbackProvider>
      </NicknameProvider>
    </I18nProvider>
  );
}

function Shell() {
  const { nickname } = useNickname();
  return (
    <>
      <Header />
      <main className="app-main">
        <Routes>
          <Route path="/" element={<PlayerPage />} />
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <NowPlaying />
      <BottomNav />
      <FeedbackView />
      {!nickname && <NicknameDialog />}
    </>
  );
}
