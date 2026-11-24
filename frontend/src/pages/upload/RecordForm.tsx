import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, api, type CreateBody, type DraftBody } from '../../api/client';
import type { CaptureSummary, Endpoint, Mode, UploadResult } from '../../api/types';
import { sumSeries } from '../../components/graphs/RangeGraph';
import { TimeField } from '../../components/common/TimeField';
import { CategorySelect } from '../../components/records/CategorySelect';
import { TagInput } from '../../components/records/TagInput';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useFeedback } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { useUpload, type RecordDraft } from '../../state/upload';
import { toggleIn } from '../../util/list';
import { endpointSide } from '../../util/mode';
import { formatBytes, formatNumber } from '../../util/time';
import { DuplicateDialog, type Duplicate } from './DuplicateDialog';
import { PreviewCard } from './PreviewCard';
import { SummaryCard } from './SummaryCard';
import { useDraftPreview } from './useDraftPreview';

/**
 * Fill in a record for an uploaded capture → live preview → save (with duplicate handling). The
 * fields live in the upload context, so they survive switching pages.
 */
export function RecordForm({ upload, draft }: { upload: UploadResult; draft: RecordDraft }) {
  const { t, tc } = useI18n();
  const { categoryById } = useCatalog();
  const { nickname } = useNickname();
  const { showError, toast, popup } = useFeedback();
  const { patch, setTagIds, finish, closeIfExpired } = useUpload();
  const navigate = useNavigate();
  const summary = upload.summary;
  const { name, description, categoryId, ports, ip, startUs, endUs, tagIds } = draft;
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

  const previewDraft: DraftBody | null = categoryId && ports.length > 0 && startUs !== null && endUs !== null
    ? { uploadId: upload.uploadId, categoryId, ports, ip: ip.trim() || null, startUs, endUs }
    : null;
  const { preview, computing } = useDraftPreview(previewDraft);

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
      finish();
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'DUPLICATE') setDuplicate(e.params as unknown as Duplicate);
      else if (!closeIfExpired(e)) showError(e);
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

  const setRangeFromGraph = (fromUs: number, toUs: number) =>
    patch({ startUs: fromUs, endUs: toUs, fieldStartUs: fromUs, fieldEndUs: toUs });
  // an invalid entry clears the value but keeps what the field shows
  const setStart = (valueUs: number | null) => patch(valueUs === null ? { startUs: null } : { startUs: valueUs, fieldStartUs: valueUs });
  const setEnd = (valueUs: number | null) => patch(valueUs === null ? { endUs: null } : { endUs: valueUs, fieldEndUs: valueUs });

  return (
    <div className="upload-layout">
      <div className="stack-lg">
        <SummaryCard upload={upload} highlight={highlight} startUs={startUs} endUs={endUs} onRangeChange={setRangeFromGraph} />

        <section className="card">
          <div className="card-title"><h2>{t('upload.details')}</h2></div>
          <div className="col" style={{ gap: 14 }}>
            <div className="grid-2">
              <div className="field"><label>{t('upload.name')}</label>
                <input className="input" value={name} onChange={(e) => patch({ name: e.target.value })} /></div>
              {/* a category change changes which ports apply */}
              <CategorySelect value={categoryId} onChange={(id) => patch({ categoryId: id, ports: [] })} suggestedMode={suggestMode(summary)} />
            </div>
            <div className="field"><label>{t('upload.description')} <span className="muted">({t('common.optional')})</span></label>
              <textarea className="textarea" value={description} onChange={(e) => patch({ description: e.target.value })} /></div>

            {side && (
              <>
                <PortPicker role={side.role} protocol={side.protocol} endpoints={endpoints} ports={ports}
                  onToggle={(port) => patch({ ports: toggleIn(ports, port) })} />
                <div className="field">
                  <label>{t('upload.ipFilter')}</label>
                  <input className="input mono" style={{ direction: 'ltr' }} list="ip-suggestions" value={ip} placeholder={t('upload.ipAll')}
                    onChange={(e) => patch({ ip: e.target.value })} />
                  <datalist id="ip-suggestions">{ipSuggestions.map((suggestion) => <option key={suggestion} value={suggestion} />)}</datalist>
                  <span className="hint">{side.role === 'dst' ? t('upload.ipHintDst') : t('upload.ipHintSrc')}</span>
                </div>
              </>
            )}

            <div className="grid-2">
              <TimeField label={t('upload.start')} valueUs={draft.fieldStartUs} onChange={setStart} boundsUs={summary} />
              <TimeField label={t('upload.end')} valueUs={draft.fieldEndUs} isEnd onChange={setEnd} boundsUs={summary} />
            </div>

            <TagInput selected={tagIds} onChange={setTagIds} />
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

/** The mode a new category most likely needs: UDP server when the capture has only UDP, else TCP server. */
function suggestMode(summary: CaptureSummary): Mode {
  return summary.tcpPackets === 0 && summary.udpPackets > 0 ? 'UDP_SERVER' : 'TCP_SERVER';
}
