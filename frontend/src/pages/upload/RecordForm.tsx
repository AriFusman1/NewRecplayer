import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, api, type CreateBody, type DraftBody } from '../../api/client';
import type { Endpoint, Tag, UploadResult } from '../../api/types';
import { sumSeries } from '../../components/graphs/RangeGraph';
import { TimeField } from '../../components/common/TimeField';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useFeedback } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { toggleIn } from '../../util/list';
import { endpointSide } from '../../util/mode';
import { tagAllowedIn } from '../../util/records';
import { formatBytes, formatNumber } from '../../util/time';
import { DuplicateDialog, type Duplicate } from './DuplicateDialog';
import { PreviewCard } from './PreviewCard';
import { SummaryCard } from './SummaryCard';
import { useDraftPreview } from './useDraftPreview';

const CAPTURE_EXTENSION = /\.(pcapng|pcap|cap)$/i;

/** Fill in a record for an uploaded capture → live preview → save (with duplicate handling). */
export function RecordForm({ upload, onDone }: { upload: UploadResult; onDone: () => void }) {
  const { t, tc } = useI18n();
  const { catalog, categoryById } = useCatalog();
  const { nickname } = useNickname();
  const { showError, toast, popup } = useFeedback();
  const navigate = useNavigate();
  const summary = upload.summary;

  const [name, setName] = useState(upload.fileName.replace(CAPTURE_EXTENSION, ''));
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [ports, setPorts] = useState<number[]>([]);
  const [ip, setIp] = useState('');
  const [startUs, setStartUs] = useState<number | null>(summary.startUs);
  const [endUs, setEndUs] = useState<number | null>(summary.endUs);
  /** Bumped when the graph sets the range, so the time fields re-read it. */
  const [rangeRev, setRangeRev] = useState(0);
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [duplicate, setDuplicate] = useState<Duplicate | null>(null);

  const category = categoryById.get(categoryId);
  const mode = category?.mode;
  const side = mode ? endpointSide(mode) : null;
  const endpoints = useMemo(() => (side
    ? summary.endpoints.filter((endpoint) => endpoint.protocol === side.protocol && endpoint.role === side.role)
    : []), [summary, side?.protocol, side?.role]); // eslint-disable-line react-hooks/exhaustive-deps
  const chosen = useMemo(() => endpoints.filter((endpoint) => ports.includes(endpoint.port)), [endpoints, ports]);
  const ipSuggestions = useMemo(() => [...new Set(chosen.flatMap((endpoint) => endpoint.ips))], [chosen]);
  const highlight = useMemo(() => (summary.timeline?.length && chosen.length ? sumSeries(summary.timeline.length, chosen) : null), [summary.timeline, chosen]);
  const tags = catalog.tags.filter((tag) => tagAllowedIn(tag, categoryId));

  // a category change changes which ports and tags apply
  useEffect(() => {
    setPorts([]);
    setTagIds((ids) => ids.filter((id) => catalog.tags.some((tag) => tag.id === id && tagAllowedIn(tag, categoryId))));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [categoryId]);

  const draft: DraftBody | null = categoryId && ports.length > 0 && startUs !== null && endUs !== null
    ? { uploadId: upload.uploadId, categoryId, ports, ip: ip.trim() || null, startUs, endUs }
    : null;
  const { preview, computing } = useDraftPreview(draft);

  const body = (over: Partial<CreateBody> = {}): CreateBody => ({
    uploadId: upload.uploadId, fileId: null, name: name.trim(), description, categoryId, tagIds, ports,
    ip: ip.trim() || null, startUs: startUs ?? 0, endUs: endUs ?? 0, createdBy: nickname, resolution: null, ...over,
  });

  /** A DUPLICATE answer opens the duplicate dialog instead of an error popup. */
  const save = async (body: CreateBody) => {
    setDuplicate(null);
    setSaving(true);
    try {
      const saved = await api.createRecord(body);
      if (body.uploadId === null) void api.discardUpload(upload.uploadId).catch(() => undefined);
      toast('success', t('upload.saved', { name: saved.name }));
      onDone();
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'DUPLICATE') setDuplicate(e.params as unknown as Duplicate);
      else showError(e);
    } finally {
      setSaving(false);
    }
  };

  const trySave = () => {
    if (!name.trim()) return popup(t('err.title'), t('err.NAME_REQUIRED'));
    if (startUs === null || endUs === null) return popup(t('err.title'), t('err.INVALID_TIME'));
    if (preview && preview.errors.length) {
      return popup(t('err.title'), preview.errors.map((problem) => tc('err', problem.code, problem.params)).join('\n'));
    }
    void save(body());
  };

  const setRangeFromGraph = (fromUs: number, toUs: number) => {
    setStartUs(fromUs);
    setEndUs(toUs);
    setRangeRev((rev) => rev + 1);
  };

  return (
    <div className="upload-layout">
      <div className="stack-lg">
        <SummaryCard upload={upload} highlight={highlight} startUs={startUs} endUs={endUs} onRangeChange={setRangeFromGraph} />

        <section className="card">
          <div className="card-title"><h2>{t('upload.details')}</h2></div>
          <div className="col" style={{ gap: 14 }}>
            <div className="grid-2">
              <div className="field"><label>{t('upload.name')}</label>
                <input className="input" value={name} onChange={(e) => setName(e.target.value)} /></div>
              <div className="field"><label>{t('upload.category')}</label>
                <select className="select" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                  <option value="">{t('upload.selectCategory')}</option>
                  {catalog.categories.map((category) => <option key={category.id} value={category.id}>{category.name} — {t(`mode.${category.mode}`)}</option>)}
                </select>
                {catalog.categories.length === 0 && <span className="hint">{t('upload.noCategories')}</span>}
                {mode && <span className="hint">{t(`modeHint.${mode}`)}</span>}
              </div>
            </div>
            <div className="field"><label>{t('upload.description')} <span className="muted">({t('common.optional')})</span></label>
              <textarea className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} /></div>

            {side && (
              <>
                <PortPicker role={side.role} protocol={side.protocol} endpoints={endpoints} ports={ports}
                  onToggle={(port) => setPorts(toggleIn(ports, port))} />
                <div className="field">
                  <label>{t('upload.ipFilter')}</label>
                  <input className="input mono" style={{ direction: 'ltr' }} list="ip-suggestions" value={ip} placeholder={t('upload.ipAll')}
                    onChange={(e) => setIp(e.target.value)} />
                  <datalist id="ip-suggestions">{ipSuggestions.map((suggestion) => <option key={suggestion} value={suggestion} />)}</datalist>
                  <span className="hint">{side.role === 'dst' ? t('upload.ipHintDst') : t('upload.ipHintSrc')}</span>
                </div>
              </>
            )}

            <div className="grid-2">
              <TimeField key={`s-${rangeRev}`} label={t('upload.start')} valueUs={rangeRev === 0 ? summary.startUs : startUs ?? summary.startUs} onChange={setStartUs} />
              <TimeField key={`e-${rangeRev}`} label={t('upload.end')} valueUs={rangeRev === 0 ? summary.endUs : endUs ?? summary.endUs} isEnd onChange={setEndUs} />
            </div>

            <TagPicker tags={tags} selected={tagIds} onToggle={(id) => setTagIds(toggleIn(tagIds, id))} />
          </div>
        </section>
      </div>

      <aside className="sticky-side">
        <PreviewCard preview={preview} computing={computing} category={category} saving={saving} onSave={trySave} />
      </aside>

      {duplicate && (
        <DuplicateDialog duplicate={duplicate} onCancel={() => setDuplicate(null)}
          onSaveAsNew={() => void save(body({ resolution: 'NEW_NAME' }))}
          onReuseFile={(fileId) => void save(body({ uploadId: null, fileId, resolution: 'NEW_NAME' }))} />
      )}
    </div>
  );
}

/** The ports seen in the capture on the side this mode filters on, busiest first. */
function PortPicker({ role, protocol, endpoints, ports, onToggle }: {
  role: 'dst' | 'src'; protocol: string; endpoints: Endpoint[]; ports: number[]; onToggle: (port: number) => void;
}) {
  const { t } = useI18n();
  const busiest = Math.max(1, ...endpoints.map((endpoint) => endpoint.packets));
  return (
    <div className="field">
      <label>{t('upload.ports')} — {role === 'dst' ? t('upload.portsDst') : t('upload.portsSrc')}</label>
      {endpoints.length === 0 ? <span className="hint">{t('upload.noPorts', { protocol })}</span> : (
        <div className="port-list">
          {endpoints.map((endpoint) => (
            <PortRow key={endpoint.port} endpoint={endpoint} busiest={busiest} selected={ports.includes(endpoint.port)}
              onToggle={() => onToggle(endpoint.port)} />
          ))}
        </div>
      )}
    </div>
  );
}

/** @param busiest packet count of the busiest port, which gets a full-width bar */
function PortRow({ endpoint, busiest, selected, onToggle }: {
  endpoint: Endpoint; busiest: number; selected: boolean; onToggle: () => void;
}) {
  const { t } = useI18n();
  return (
    <label className={`port-row${selected ? ' on' : ''}`}>
      <input type="checkbox" checked={selected} onChange={onToggle} style={{ accentColor: 'var(--accent)' }} />
      <span className="mono" style={{ fontWeight: 700 }}>{endpoint.port}</span>
      <div className="bar-mini"><div style={{ width: `${(endpoint.packets / busiest) * 100}%` }} /></div>
      <span className="small muted">{t('upload.packets', { n: formatNumber(endpoint.packets) })} · {formatBytes(endpoint.bytes)}</span>
    </label>
  );
}

function TagPicker({ tags, selected, onToggle }: { tags: Tag[]; selected: string[]; onToggle: (id: string) => void }) {
  const { t } = useI18n();
  return (
    <div className="field">
      <label>{t('upload.tags')}</label>
      {tags.length === 0 ? <span className="hint">{t('upload.noTags')}</span> : (
        <div className="row wrap">
          {tags.map((tag) => (
            <button key={tag.id} className={`chip selectable${selected.includes(tag.id) ? ' on' : ''}`} onClick={() => onToggle(tag.id)}>
              <bdi>#{tag.name}</bdi>{!tag.categoryId && <span className="muted"> · {t('tags.global')}</span>}</button>
          ))}
        </div>
      )}
    </div>
  );
}
