import { NavLink } from 'react-router-dom';
import { useI18n, type MessageKey } from '../../i18n';
import { Icon } from '../common/Icon';
import { PLAYING_PATH } from '../playback/NowPlaying';

const ITEMS: { to: string; icon: string; label: MessageKey }[] = [
  { to: '/', icon: 'player', label: 'nav.player' },
  { to: PLAYING_PATH, icon: 'layers', label: 'nav.playing' },
  { to: '/upload', icon: 'upload', label: 'nav.upload' },
  { to: '/history', icon: 'history', label: 'nav.history' },
  { to: '/catalog', icon: 'tag', label: 'nav.catalog' },
];

export function BottomNav() {
  const { t } = useI18n();
  return (
    <nav className="bottom-nav">
      {ITEMS.map((i) => (
        <NavLink key={i.to} to={i.to} end={i.to === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
          <Icon name={i.icon} size={19} />
          <span>{t(i.label)}</span>
        </NavLink>
      ))}
    </nav>
  );
}
