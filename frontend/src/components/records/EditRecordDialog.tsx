import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/client';
import type { CaptureSummary, PcapRecord } from '../../api/types';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction, useFeedback } from '../../state/feedback';
import { Modal } from '../common/Modal';
import { TimeField } from '../common/TimeField';
import { RangeGraph, sumSeries } from '../graphs/RangeGraph';
import { endpointSide } from '../../util/mode';
import { CategorySelect } from './CategorySelect';
import { TagInput } from './TagInput';

/**
 * Name, description, times, category and tags. The times are a cut of the whole recording, chosen
 * on the same packets-over-time graph as when uploading (drag, or the arrows under it) or typed.
 * The category can only change to one with the same connection mode; times are re-validated by the server.
 */
export function EditRecordDialog({ record, onClose }: { record: PcapRecord; onClose: () => void }) {
  const { t } = useI18n();
  const { recordById, fileById, loaded, modeOf } = useCatalog();
  const file = fileById.get(record.fileId);
  const { toast } = useFeedback();
  const { run, busy } = useAction();
  const [mode] = useState(() => modeOf(record.id));
  const [name, setName] = useState(record.name);
  const [description, setDescription] = useState(record.description);
  const [startUs, setStartUs] = useState<number | null>(record.startUs);
  const [endUs, setEndUs] = useState<number | null>(record.endUs);
  /** What the time fields show: the last valid value (an invalid entry leaves it as is). */
  const [fieldStartUs, setFieldStartUs] = useState(record.startUs);
  const [fieldEndUs, setFieldEndUs] = useState(record.endUs);
  /** The whole recording's timeline, for the graph; null until loaded (or if it cannot be). */
  const [summary, setSummary] = useState<CaptureSummary | null>(null);
  const [categoryId, setCategoryId] = useState(record.categoryId);
  const [tagIds, setTagIds] = useState(record.tagIds);
  const valid = name.trim() && categoryId && startUs !== null && endUs !== null;
  // another user deleted the record (or its category, which deletes the record)
  const gone = loaded && !recordById.has(record.id);

  useEffect(() => {
    if (!gone) return;
    toast('warning', t('edit.recordDeleted', { name: record.name }));
    onClose();
  }, [gone]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let cancelled = false;
    api.fileSummary(record.fileId).then((loadedSummary) => { if (!cancelled) setSummary(loadedSummary); }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [record.fileId]);

  // the record's own ports, highlighted on the graph like when it was cut
  const highlight = useMemo(() => {
    if (!summary?.timeline?.length || !mode) return null;
    const side = endpointSide(mode);
    const chosen = summary.endpoints.filter((endpoint) => endpoint.protocol === side.protocol && endpoint.role === side.role
      && record.ports.includes(endpoint.port));
    return chosen.length ? sumSeries(summary.timeline.length, chosen) : null;
  }, [summary, mode, record.ports]);

  if (gone) return null;

  const setRangeFromGraph = (fromUs: number, toUs: number) => {
    setStartUs(fromUs);
    setEndUs(toUs);
    setFieldStartUs(fromUs);
    setFieldEndUs(toUs);
  };
  const setStart = (valueUs: number | null) => {
    setStartUs(valueUs);
    if (valueUs !== null) setFieldStartUs(valueUs);
  };
  const setEnd = (valueUs: number | null) => {
    setEndUs(valueUs);
    if (valueUs !== null) setFieldEndUs(valueUs);
  };

  const save = async () => {
    if (!valid) return;
    const saved = await run(() => api.updateRecord(record.id,
      { name: name.trim(), description, startUs: startUs!, endUs: endUs!, categoryId, tagIds }),
    t('upload.saved', { name: name.trim() }));
    if (saved) onClose();
  };

  return (
    <Modal title={t('edit.title')} onClose={onClose} wide
      footer={<>
        <button className="btn ghost" onClick={onClose}>{t('common.cancel')}</button>
        <button className="btn primary" disabled={!valid || busy} onClick={save}>{busy ? t('common.loading') : t('common.save')}</button>
      </>}>
      <p className="small muted">{t('edit.hint')}</p>
      <div className="grid-2">
        <div className="field">
          <label>{t('upload.name')}</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <CategorySelect value={categoryId} onChange={setCategoryId} lockedMode={mode} />
      </div>
      <div className="field">
        <label>{t('upload.description')}</label>
        <textarea className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {summary?.timeline && summary.timeline.length > 0 && !!summary.binUs && (
        <RangeGraph startUs={summary.startUs} endUs={summary.endUs} binUs={summary.binUs} total={summary.timeline} highlight={highlight}
          selectedStartUs={startUs} selectedEndUs={endUs} onChange={setRangeFromGraph} />
      )}
      <div className="grid-2">
        <TimeField label={t('upload.start')} valueUs={fieldStartUs} onChange={setStart} boundsUs={file} />
        <TimeField label={t('upload.end')} valueUs={fieldEndUs} isEnd onChange={setEnd} boundsUs={file} />
      </div>
      <TagInput selected={tagIds} onChange={setTagIds} />
    </Modal>
  );
}
