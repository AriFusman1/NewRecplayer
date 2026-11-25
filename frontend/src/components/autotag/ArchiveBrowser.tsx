import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { ArchiveFolder, ArchiveObject } from '../../api/types';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import { formatBytes, formatUs } from '../../util/time';
import { Icon } from '../common/Icon';

interface Props {
  prefix: string;
  onPrefix: (prefix: string) => void;
  /** Given to choose files: the chosen objects by key. */
  selected?: Map<string, ArchiveObject>;
  onToggle?: (object: ArchiveObject) => void;
}

/** Browses the archive one folder at a time; files can be ticked when `onToggle` is given. */
export function ArchiveBrowser({ prefix, onPrefix, selected, onToggle }: Props) {
  const { t } = useI18n();
  const { run, busy } = useAction();
  const [folder, setFolder] = useState<ArchiveFolder | null>(null);

  useEffect(() => {
    void run(async () => setFolder(await api.archiveBrowse(prefix)));
  }, [prefix]);

  const parts = prefix.split('/').filter(Boolean);
  const name = (path: string) => path.replace(/\/$/, '').split('/').pop() ?? path;

  return (
    <div className="archive-browser">
      <div className="row small" style={{ gap: 4, flexWrap: 'wrap' }}>
        <button className="btn ghost sm" onClick={() => onPrefix('')}><Icon name="folder" size={13} />{t('at.archiveRoot')}</button>
        {parts.map((part, i) => (
          <span key={i} className="row" style={{ gap: 4 }}>
            <span className="muted">/</span>
            <button className="btn ghost sm" onClick={() => onPrefix(`${parts.slice(0, i + 1).join('/')}/`)}><bdi>{part}</bdi></button>
          </span>
        ))}
        {busy && <span className="muted">{t('common.loading')}</span>}
      </div>
      <div className="table-wrap" style={{ maxHeight: 320, overflow: 'auto' }}>
        <table className="table">
          <tbody>
            {folder?.folders.map((sub) => (
              <tr key={sub} className="clickable" onClick={() => onPrefix(sub)}>
                {onToggle && <td />}
                <td><Icon name="folder" size={14} /> <bdi>{name(sub)}</bdi></td>
                <td /><td />
              </tr>
            ))}
            {folder?.files.map((object) => (
              <tr key={object.key}>
                {onToggle && (
                  <td style={{ width: 30 }}>
                    <input type="checkbox" checked={selected?.has(object.key) ?? false} aria-label={object.key} onChange={() => onToggle(object)} />
                  </td>
                )}
                <td><Icon name="file" size={14} /> <bdi>{name(object.key)}</bdi></td>
                <td className="small">{formatBytes(object.size)}</td>
                <td className="small mono ltr">{formatUs(object.lastModifiedUs)}</td>
              </tr>
            ))}
            {folder && folder.folders.length === 0 && folder.files.length === 0 && (
              <tr><td className="muted" colSpan={4}>{t('at.archiveEmpty')}</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
