import { useEffect, useMemo, useState } from 'react';
import { useDebounce } from 'use-debounce';
import { api } from '../../api/client';
import type { ArchiveObject, ArchivePreview, SourceKind } from '../../api/types';
import { useI18n, type MessageKey } from '../../i18n';
import { useAction } from '../../state/feedback';
import { chosenFiles, filterFolder, folderName, totalSize, type LocalFile } from '../../util/localFiles';
import { parseBoundUs, readDateTime } from '../../util/time';
import { DateTimeField } from '../common/DateTimeField';
import { Icon } from '../common/Icon';
import { ArchiveBrowser } from './ArchiveBrowser';

/** What the user chose; `count`/`totalBytes` are null while an archive folder is being counted. */
export interface Selection {
  kind: SourceKind;
  /** Local sources: the files to upload. */
  local: LocalFile[];
  /** ARCHIVE_FILES: the chosen keys. */
  keys: string[];
  /** Folder sources: the folder (archive prefix, or the local folder's name). */
  folder: string | null;
  recursive: boolean;
  fromUs: number | null;
  toUs: number | null;
  count: number | null;
  totalBytes: number | null;
}

const KINDS: { kind: SourceKind; label: MessageKey; icon: string }[] = [
  { kind: 'LOCAL_FILES', label: 'at.src.LOCAL_FILES', icon: 'file' },
  { kind: 'LOCAL_FOLDER', label: 'at.src.LOCAL_FOLDER', icon: 'folder' },
  { kind: 'ARCHIVE_FILES', label: 'at.src.ARCHIVE_FILES', icon: 'file' },
  { kind: 'ARCHIVE_FOLDER', label: 'at.src.ARCHIVE_FOLDER', icon: 'layers' },
];

/** The four ways to choose a run's files. Local folders are filtered here, in the browser. */
export function SourcePicker({ archiveConfigured, onChange }: { archiveConfigured: boolean; onChange: (selection: Selection | null) => void }) {
  const { t } = useI18n();
  const { run } = useAction();
  const [kind, setKind] = useState<SourceKind>('LOCAL_FILES');
  const [files, setFiles] = useState<File[]>([]);
  const [folderFiles, setFolderFiles] = useState<File[]>([]);
  const [recursive, setRecursive] = useState(true);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [prefix, setPrefix] = useState('');
  const [chosen, setChosen] = useState<Map<string, ArchiveObject>>(new Map());
  const [preview, setPreview] = useState<ArchivePreview | null>(null);

  // the span is typed in Israel time and becomes UTC epoch µs here; NaN = typed but invalid
  const fromUs = from ? parseBoundUs(from, false) ?? NaN : null;
  const toUs = to ? parseBoundUs(to, true) ?? NaN : null;
  const spanInvalid = Number.isNaN(fromUs) || Number.isNaN(toUs) || (fromUs !== null && toUs !== null && fromUs > toUs);
  const span = spanInvalid ? { fromUs: null, toUs: null } : { fromUs, toUs };
  const usesSpan = kind === 'LOCAL_FOLDER' || kind === 'ARCHIVE_FOLDER';

  const local = useMemo(() => (kind === 'LOCAL_FILES' ? chosenFiles(files)
    : kind === 'LOCAL_FOLDER' ? filterFolder(folderFiles, recursive, span.fromUs, span.toUs) : []),
  [kind, files, folderFiles, recursive, span.fromUs, span.toUs]);

  // an archive folder is counted on the server, a moment after the last change
  const [previewKey] = useDebounce(`${kind}|${prefix}|${recursive}|${span.fromUs}|${span.toUs}`, 400);
  useEffect(() => {
    setPreview(null);
    if (kind !== 'ARCHIVE_FOLDER' || spanInvalid) return;
    void run(async () => setPreview(await api.archivePreview({
      categoryId: '', ruleIds: [], evaluateOnly: true, kind, folder: prefix, recursive, fromUs: span.fromUs, toUs: span.toUs, createdBy: '',
    })));
    // `run` changes with the language; that must not count again
  }, [previewKey]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (usesSpan && spanInvalid) {
      onChange(null);
      return;
    }
    const base = { kind, local: [] as LocalFile[], keys: [] as string[], folder: null as string | null, recursive, ...span };

    switch (kind) {
      case 'LOCAL_FILES':
        onChange({ ...base, local, count: local.length, totalBytes: totalSize(local) });
        break;
      case 'LOCAL_FOLDER':
        onChange({ ...base, local, folder: folderName(folderFiles), count: local.length, totalBytes: totalSize(local) });
        break;
      case 'ARCHIVE_FILES': {
        const objects = [...chosen.values()];
        onChange({ ...base, keys: objects.map((object) => object.key), count: objects.length,
          totalBytes: objects.reduce((sum, object) => sum + object.size, 0) });
        break;
      }
      case 'ARCHIVE_FOLDER':
        onChange({ ...base, folder: prefix, count: preview?.count ?? null, totalBytes: preview?.totalBytes ?? null });
        break;
    }
  }, [kind, local, folderFiles, chosen, prefix, recursive, preview, span.fromUs, span.toUs, spanInvalid, usesSpan]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggleChosen = (object: ArchiveObject) => setChosen((current) => {
    const next = new Map(current);
    if (next.has(object.key)) next.delete(object.key);
    else next.set(object.key, object);
    return next;
  });

  return (
    <div className="stack">
      <div className="seg" role="tablist">
        {KINDS.map((option) => {
          const disabled = option.kind.startsWith('ARCHIVE') && !archiveConfigured;
          return (
            <button key={option.kind} role="tab" aria-selected={kind === option.kind} className={`btn sm${kind === option.kind ? ' active' : ''}`}
              disabled={disabled} title={disabled ? t('at.archiveOff') : undefined} onClick={() => setKind(option.kind)}>
              <Icon name={option.icon} size={14} />{t(option.label)}</button>
          );
        })}
      </div>

      {kind === 'LOCAL_FILES' && (
        <label className="field">
          <span className="label">{t('at.pickFiles')}</span>
          <input type="file" multiple onChange={(e) => setFiles([...(e.target.files ?? [])])} />
          <span className="small muted">{t('at.anyExtension')}</span>
        </label>
      )}

      {kind === 'LOCAL_FOLDER' && (
        <label className="field">
          <span className="label">{t('at.pickFolder')}</span>
          {/* webkitdirectory is not in React's types; every browser in use supports it */}
          <input type="file" multiple {...{ webkitdirectory: '', directory: '' }} onChange={(e) => setFolderFiles([...(e.target.files ?? [])])} />
          {folderFiles.length > 0 && <span className="small muted">{t('at.folderPicked', { name: folderName(folderFiles) ?? '', n: folderFiles.length })}</span>}
        </label>
      )}

      {(kind === 'ARCHIVE_FILES' || kind === 'ARCHIVE_FOLDER') && (
        <ArchiveBrowser prefix={prefix} onPrefix={setPrefix}
          selected={kind === 'ARCHIVE_FILES' ? chosen : undefined} onToggle={kind === 'ARCHIVE_FILES' ? toggleChosen : undefined} />
      )}
      {kind === 'ARCHIVE_FOLDER' && <p className="small muted" style={{ margin: 0 }}>{t('at.archiveFolderHint', { folder: prefix || '/' })}</p>}

      {usesSpan && (
        <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
          <label className="row small" style={{ gap: 6 }}>
            <input type="checkbox" checked={recursive} onChange={(e) => setRecursive(e.target.checked)} />{t('at.subfolders')}
          </label>
          <div className="range-filter" title={t('at.spanHint')}>
            <span className="range-label"><Icon name="calendar" size={15} />{t('at.span')}</span>
            <DateTimeField value={from} invalid={spanInvalid && from !== ''} placeholder={t('player.from')} onChange={setFrom}
              dayTime="start" maxDay={readDateTime(to)} />
            <span className="muted" aria-hidden="true">–</span>
            <DateTimeField value={to} invalid={spanInvalid && to !== ''} placeholder={t('player.to')} onChange={setTo}
              dayTime="end" minDay={readDateTime(from)} />
          </div>
        </div>
      )}
      {usesSpan && spanInvalid && <p className="filter-error">{t('player.rangeReversed')}</p>}
    </div>
  );
}
