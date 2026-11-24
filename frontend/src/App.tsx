import { useCallback, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { BottomNav } from './components/layout/BottomNav';
import { FeedbackView } from './components/layout/FeedbackView';
import { Header } from './components/layout/Header';
import { NicknameDialog } from './components/layout/NicknameDialog';
import { NowPlayingStrip, PLAYING_PATH } from './components/playback/NowPlaying';
import { I18nProvider } from './i18n';
import { CatalogPage } from './pages/catalog/CatalogPage';
import { HistoryPage } from './pages/history/HistoryPage';
import { PlayerPage } from './pages/player/PlayerPage';
import { PlayingPage } from './pages/playing/PlayingPage';
import { UploadPage } from './pages/upload/UploadPage';
import { CatalogProvider } from './state/catalog';
import { FeedbackProvider } from './state/feedback';
import { LiveProvider } from './state/live';
import { NicknameProvider, useNickname } from './state/nickname';
import { PlaylistProvider } from './state/playlist';

export function App() {
  // the live socket tells the catalog when to refetch
  const [catalogSignal, setCatalogSignal] = useState(0);
  const bump = useCallback(() => setCatalogSignal((signal) => signal + 1), []);
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
          <Route path={PLAYING_PATH} element={<PlayingPage />} />
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <NowPlayingStrip />
      <BottomNav />
      <FeedbackView />
      {!nickname && <NicknameDialog />}
    </>
  );
}
