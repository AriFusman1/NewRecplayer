import { useState } from 'react';
import { useI18n } from '../../i18n';
import { useLive } from '../../state/live';
import { useNickname } from '../../state/nickname';
import { NicknameDialog } from './NicknameDialog';

export function Header() {
  const { t, lang, setLang } = useI18n();
  const { nickname } = useNickname();
  const { connected } = useLive();
  const [editing, setEditing] = useState(false);
  return (
    <header className="app-header">
      <div className="brand">
        <div className="brand-mark">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="white"><path d="M7 4v16l13-8z" /></svg>
        </div>
        <span>{t('app.name')}</span>
      </div>
      <div className="header-spacer" />
      <span className={`conn-dot${connected ? '' : ' off'}`} title={connected ? 'live' : t('header.offline')} />
      {!connected && <span className="muted small">{t('header.offline')}</span>}
      <div className="lang-toggle" role="group" aria-label="language">
        <button className={lang === 'en' ? 'active' : ''} onClick={() => setLang('en')}>EN</button>
        <button className={lang === 'he' ? 'active' : ''} onClick={() => setLang('he')}>עב</button>
      </div>
      <button className="nick-pill" onClick={() => setEditing(true)} title={t('header.changeNickname')}>
        <span className="avatar">{(nickname || '?').slice(0, 1).toUpperCase()}</span>
        <span className="ellipsis" style={{ maxWidth: 140 }}>{nickname}</span>
      </button>
      {editing && <NicknameDialog onDone={() => setEditing(false)} />}
    </header>
  );
}
