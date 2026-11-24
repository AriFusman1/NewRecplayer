import { Link } from 'react-router-dom';
import { Icon } from '../../components/common/Icon';
import { NowPlayingList } from '../../components/playback/NowPlaying';
import { useI18n } from '../../i18n';
import { useLive } from '../../state/live';

/**
 * The "Now playing" tab: every running replay from everyone, in full. Playback runs on the server,
 * so leaving this tab (or closing the browser) does not affect it.
 */
export function PlayingPage() {
  const { t } = useI18n();
  const { sessions, usage } = useLive();
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{t('now.title')}</h1>
          <p className="muted">{t('now.subtitle')}</p>
        </div>
        {usage.max > 0 && (
          <span className={`badge ${usage.active >= usage.max ? 'warn' : usage.active > 0 ? 'ok' : 'neutral'}`} title={t('usage.hint')}>
            {t('usage.inUse', { active: usage.active, max: usage.max })}
          </span>
        )}
      </div>
      {sessions.length === 0 ? (
        <div className="empty">
          <Icon name="layers" size={28} />
          <div>{t('now.nothing')}</div>
          <Link className="btn" to="/"><Icon name="player" />{t('nav.player')}</Link>
        </div>
      ) : (
        <NowPlayingList />
      )}
    </div>
  );
}
