import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, api, type CreateBody } from '../api/client';
import type { Category, Endpoint, Preview, UploadResult } from '../api/types';
import { ModeBadge, Warnings } from '../components/Badges';
import { BarGraph } from '../components/BarGraph';
import { Icon } from '../components/Icon';
import { Modal } from '../components/Modal';
import { TimeField } from '../components/TimeField';
import { useI18n } from '../i18n';
import { useCatalog } from '../state/catalog';
import { useFeedback } from '../state/feedback';
import { useNickname } from '../state/nickname';
import { formatBytes, formatDuration, formatNumber, formatUs } from '../util/time';

type Phase = { kind: 'idle' } | { kind: 'uploading'; pct: number; name: string } | { kind: 'scanning'; name: string } | { kind: 'form'; upload: UploadResult };

/** Drop a capture → summary → fill the record → live preview → save (with duplicate handling). */
export function UploadPage() {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { showError } = useFeedback();
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });

  const start = async (file: File) => {
    setPhase({ kind: 'uploading', pct: 0, name: file.name });
    try {
      const res = await api.upload(file, nickname, (f) =>
        setPhase(f >= 1 ? { kind: 'scanning', name: file.name } : { kind: 'uploading', pct: Math.round(f * 100), name: file.name }));
      setPhase({ kind: 'form', upload: res });
    } catch (e) {
      setPhase({ kind: 'idle' });
      showError(e);
    }
  };

  const reset = () => {
    if (phase.kind === 'form') void api.discardUpload(phase.upload.uploadId).catch(() => undefined);
    setPhase({ kind: 'idle' });
  };

  return (
    <div>
      <div className="page-head">
        <div><h1>{t('nav.upload')}</h1></div>
        {phase.kind === 'form' && <button className="btn" onClick={reset}><Icon name="upload" />{t('upload.another')}</button>}
      </div>
      {phase.kind === 'idle' && <DropZone onFile={start} />}
      {(phase.kind === 'uploading' || phase.kind === 'scanning') && (
        <div className="dropzone">
          <div className="big-icon"><span className="spinner" style={{ width: 24, height: 24 }} /></div>
          <strong>{phase.name}</strong>
          <span className="muted">{phase.kind === 'uploading' ? t('upload.uploading', { pct: phase.pct }) : t('upload.scanning')}</span>
          {phase.kind === 'uploading' && <div className="progress" style={{ width: 280 }}><div style={{ width: `${phase.pct}%` }} /></div>}
        </div>
      )}
      {phase.kind === 'form' && <RecordForm key={phase.upload.uploadId} upload={phase.upload} onDone={() => setPhase({ kind: 'idle' })} />}
    </div>
  );
}

function DropZone({ onFile }: { onFile: (f: File) => void }) {
  const { t } = useI18n();
  const [over, setOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    const f = e.dataTransfer.files?.[0];
    if (f) onFile(f);
  };
  return (
    <div className={`dropzone${over ? ' over' : ''}`} onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)} onDrop={onDrop}>
      <div className="big-icon"><Icon name="upload" size={28} /></div>
      <h2>{t('upload.drop')}</h2>
      <span className="muted">{t('upload.or')}</span>
      <button className="btn primary" onClick={() => input.current?.click()}><Icon name="file" />{t('upload.browse')}</button>
      <input ref={input} type="file" hidden accept=".pcap,.pcapng,.cap,.dmp,application/vnd.tcpdump.pcap"
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ''; }} />
    </div>
  );
}

interface Duplicate {
  nameConflict: string | null;
  sameFile: { fileId: string; originalName: string; records: string[] } | null;
  existingFileId: string;
}

function RecordForm({ upload, onDone }: { upload: UploadResult; onDone: () => void }) {
  const { t, tc } = useI18n();
  const { catalog, categoryById } = useCatalog();
  const { nickname } = useNickname();
  const { showError, toast, popup } = useFeedback();
  const navigate = useNavigate();
  const s = upload.summary;

  const [name, setName] = useState(upload.fileName.replace(/\.(pcapng|pcap|cap)$/i, ''));
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [ports, setPorts] = useState<number[]>([]);
  const [ip, setIp] = useState('');
  const [startUs, setStartUs] = useState<number | null>(s.startUs);
  const [endUs, setEndUs] = useState<number | null>(s.endUs);
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [computing, setComputing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dup, setDup] = useState<{ d: Duplicate; step: 'ask' | 'reuse' } | null>(null);

  const category: Category | undefined = categoryById.get(categoryId);
  const mode = category?.mode;
  const protocol = mode === 'UDP_SERVER' ? 'UDP' : 'TCP';
  const role = mode === 'TCP_CLIENT' ? 'src' : 'dst';

  const endpoints = useMemo(() => (mode ? s.endpoints.filter((e) => e.protocol === protocol && e.role === role) : []), [s, mode, protocol, role]);
  const ipSuggestions = useMemo(() => {
    const set = new Set<string>();
    endpoints.filter((e) => ports.includes(e.port)).forEach((e) => e.ips.forEach((x) => set.add(x)));
    return [...set];
  }, [endpoints, ports]);
  const tags = catalog.tags.filter((tg) => !tg.categoryId || tg.categoryId === categoryId);

  // a category change changes which ports apply
  useEffect(() => {
    setPorts([]);
    setTagIds((ids) => ids.filter((id) => catalog.tags.find((x) => x.id === id && (!x.categoryId || x.categoryId === categoryId))));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [categoryId]);

  // live preview (debounced) whenever the draft changes
  useEffect(() => {
    if (!categoryId || ports.length === 0 || startUs === null || endUs === null) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    setComputing(true);
    const h = window.setTimeout(async () => {
      try {
        const p = await api.preview({ uploadId: upload.uploadId, categoryId, ports, ip: ip.trim() || null, startUs, endUs });
        if (!cancelled) setPreview(p);
      } catch (e) {
        if (!cancelled) {
          setPreview(null);
          showError(e);
        }
      } finally {
        if (!cancelled) setComputing(false);
      }
    }, 350);
    return () => { cancelled = true; window.clearTimeout(h); };
  }, [upload.uploadId, categoryId, ports, ip, startUs, endUs, showError]);

  const body = (over: Partial<CreateBody> = {}): CreateBody => ({
    uploadId: upload.uploadId, fileId: null, name: name.trim(), description, categoryId, tagIds, ports,
    ip: ip.trim() || null, startUs: startUs ?? 0, endUs: endUs ?? 0, createdBy: nickname, resolution: null, ...over,
  });

  const save = async (b: CreateBody) => {
    setSaving(true);
    try {
      const r = await api.createRecord(b);
      if (b.uploadId === null) void api.discardUpload(upload.uploadId).catch(() => undefined);
      toast('success', t('upload.saved', { name: r.name }));
      onDone();
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'DUPLICATE') setDup({ d: e.params as unknown as Duplicate, step: 'ask' });
      else showError(e);
    } finally {
      setSaving(false);
    }
  };

  const trySave = () => {
    if (!name.trim()) return popup(t('err.title'), t('err.NAME_REQUIRED'));
    if (startUs === null || endUs === null) return popup(t('err.title'), t('err.INVALID_TIME'));
    if (preview && preview.errors.length) {
      return popup(t('err.title'), preview.errors.map((x) => tc('err', x.code, x.params)).join('\n'));
    }
    void save(body());
  };

  const maxPackets = Math.max(1, ...endpoints.map((e) => e.packets));

  return (
    <div className="upload-layout">
      <div className="stack-lg">
        <section className="card">
          <div className="card-title"><h2>{t('upload.summary')}</h2><span className="badge neutral">{s.format}</span></div>
          <dl className="kv">
            <dt>{t('upload.file')}</dt><dd>{upload.fileName} · {formatBytes(upload.size)}</dd>
            <dt>{t('upload.recording')}</dt><dd><span className="mono ltr">{formatUs(s.startUs)} → {formatUs(s.endUs)}</span> <span className="muted">({formatDuration(s.endUs - s.startUs)})</span></dd>
            <dt>{t('upload.frames')}</dt><dd>{formatNumber(s.frames)}</dd>
            <dt>{t('upload.protocols')}</dt><dd>TCP {formatNumber(s.tcpPackets)} · UDP {formatNumber(s.udpPackets)}</dd>
            <dt>SHA-256</dt><dd className="mono ltr muted" style={{ fontSize: 11.5 }}>{upload.sha256}</dd>
          </dl>
          {upload.sameFile && (
            <div className="alert info" style={{ marginTop: 12 }}><Icon name="info" />
              <span>{t('dup.sameFile', { file: upload.sameFile.originalName, records: upload.sameFile.records.join(', ') || '—' })}</span></div>
          )}
          {s.warnings.length > 0 && <div style={{ marginTop: 12 }}><Warnings items={s.warnings} /></div>}
        </section>

        <section className="card">
          <div className="card-title"><h2>{t('upload.details')}</h2></div>
          <div className="col" style={{ gap: 14 }}>
            <div className="grid-2">
              <div className="field"><label>{t('upload.name')}</label>
                <input className="input" value={name} onChange={(e) => setName(e.target.value)} /></div>
              <div className="field"><label>{t('upload.category')}</label>
                <select className="select" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                  <option value="">{t('upload.selectCategory')}</option>
                  {catalog.categories.map((c) => <option key={c.id} value={c.id}>{c.name} — {t(`mode.${c.mode}`)}</option>)}
                </select>
                {catalog.categories.length === 0 && <span className="hint">{t('upload.noCategories')}</span>}
                {mode && <span className="hint">{t(`modeHint.${mode}`)}</span>}
              </div>
            </div>
            <div className="field"><label>{t('upload.description')} <span className="muted">({t('common.optional')})</span></label>
              <textarea className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} /></div>

            {mode && (
              <div className="field">
                <label>{t('upload.ports')} — {role === 'dst' ? t('upload.portsDst') : t('upload.portsSrc')}</label>
                {endpoints.length === 0 ? <span className="hint">{t('upload.noPorts', { protocol })}</span> : (
                  <div className="port-list">
                    {endpoints.map((e) => <PortRow key={e.port} e={e} max={maxPackets} on={ports.includes(e.port)}
                      toggle={() => setPorts(ports.includes(e.port) ? ports.filter((p) => p !== e.port) : [...ports, e.port])} />)}
                  </div>
                )}
              </div>
            )}

            {mode && (
              <div className="field">
                <label>{t('upload.ipFilter')}</label>
                <input className="input mono" style={{ direction: 'ltr' }} list="ip-suggestions" value={ip} placeholder={t('upload.ipAll')}
                  onChange={(e) => setIp(e.target.value)} />
                <datalist id="ip-suggestions">{ipSuggestions.map((x) => <option key={x} value={x} />)}</datalist>
                <span className="hint">{role === 'dst' ? t('upload.ipHintDst') : t('upload.ipHintSrc')}</span>
              </div>
            )}

            <div className="grid-2">
              <TimeField label={t('upload.start')} valueUs={s.startUs} onChange={setStartUs} />
              <TimeField label={t('upload.end')} valueUs={s.endUs} isEnd onChange={setEndUs} />
            </div>

            <div className="field">
              <label>{t('upload.tags')}</label>
              {tags.length === 0 ? <span className="hint">{t('upload.noTags')}</span> : (
                <div className="row wrap">
                  {tags.map((tg) => {
                    const on = tagIds.includes(tg.id);
                    return <button key={tg.id} className={`chip selectable${on ? ' on' : ''}`}
                      onClick={() => setTagIds(on ? tagIds.filter((x) => x !== tg.id) : [...tagIds, tg.id])}>
                      <bdi>#{tg.name}</bdi>{!tg.categoryId && <span className="muted"> · {t('tags.global')}</span>}</button>;
                  })}
                </div>
              )}
            </div>
          </div>
        </section>
      </div>

      <aside className="sticky-side">
        <section className="card">
          <div className="card-title"><h2>{t('upload.preview')}</h2>{computing && <span className="spinner" />}</div>
          {!preview ? <p className="muted small">{computing ? t('upload.computing') : t('err.PORTS_REQUIRED')}</p> : (
            <div className="col" style={{ gap: 14 }}>
              {preview.stats && (
                <>
                  <div className="stats">
                    <div className="stat"><div className="label">{t('upload.messages')}</div><div className="value">{formatNumber(preview.stats.messageCount)}</div></div>
                    <div className="stat"><div className="label">{t('upload.duration')}</div><div className="value ltr">{formatDuration(preview.stats.durationUs)}</div></div>
                    <div className="stat"><div className="label">{t('upload.protocol')}</div><div className="value">{preview.stats.protocol}</div></div>
                    <div className="stat"><div className="label">{t('live.bytes')}</div><div className="value">{formatBytes(preview.stats.bytes)}</div></div>
                  </div>
                  <div className="row wrap"><span className="chip">{category?.name}</span>{mode && <ModeBadge mode={mode} />}
                    {preview.stats.protocol === 'TCP' && <span className="chip">{t('card.connections', { n: preview.stats.connections })}</span>}</div>
                  {preview.stats.messageCount > 0 && <BarGraph histogram={preview.stats.histogram} binUs={preview.stats.binUs} durationUs={preview.stats.durationUs} height={70} />}
                  <Warnings items={preview.stats.warnings} />
                </>
              )}
              <Warnings items={preview.errors} kind="err" />
            </div>
          )}
          <hr className="divider" />
          <button className="btn primary" style={{ width: '100%', height: 40 }} disabled={saving || !preview || computing} onClick={trySave}>
            {saving ? t('common.loading') : t('upload.save')}</button>
        </section>
      </aside>

      {dup?.step === 'ask' && (
        <Modal title={t('dup.title')} icon="warning" onClose={() => setDup(null)}
          footer={<>
            <button className="btn ghost" onClick={() => setDup(null)}>{t('common.cancel')}</button>
            <button className="btn" onClick={() => { setDup(null); void save(body({ resolution: 'NEW_NAME' })); }}>{t('dup.no')}</button>
            <button className="btn primary" onClick={() => setDup({ ...dup, step: 'reuse' })}>{t('dup.yes')}</button>
          </>}>
          {dup.d.nameConflict && <p>{t('dup.nameExists', { name: dup.d.nameConflict })}</p>}
          {dup.d.sameFile && <p>{t('dup.sameFile', { file: dup.d.sameFile.originalName, records: dup.d.sameFile.records.join(', ') })}</p>}
          <strong>{t('dup.question')}</strong>
        </Modal>
      )}
      {dup?.step === 'reuse' && (
        <Modal title={t('dup.reuseTitle')} icon="info" onClose={() => setDup(null)}
          footer={<>
            <button className="btn ghost" onClick={() => setDup(null)}>{t('common.cancel')}</button>
            <button className="btn primary" onClick={() => { const d = dup.d; setDup(null); void save(body({ uploadId: null, fileId: d.existingFileId, resolution: 'NEW_NAME' })); }}>
              {t('dup.reuse')}</button>
          </>}>
          <p>{t('dup.reuseBody')}</p>
        </Modal>
      )}
    </div>
  );
}

function PortRow({ e, max, on, toggle }: { e: Endpoint; max: number; on: boolean; toggle: () => void }) {
  const { t } = useI18n();
  return (
    <label className={`port-row${on ? ' on' : ''}`}>
      <input type="checkbox" checked={on} onChange={toggle} style={{ accentColor: 'var(--accent)' }} />
      <span className="mono" style={{ fontWeight: 700 }}>{e.port}</span>
      <div className="bar-mini"><div style={{ width: `${(e.packets / max) * 100}%` }} /></div>
      <span className="small muted">{t('upload.packets', { n: formatNumber(e.packets) })} · {formatBytes(e.bytes)}</span>
    </label>
  );
}
