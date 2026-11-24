import { useRef, useState, type DragEvent } from 'react';
import { api } from '../../api/client';
import type { UploadResult } from '../../api/types';
import { Icon } from '../../components/common/Icon';
import { useI18n } from '../../i18n';
import { useFeedback } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { RecordForm } from './RecordForm';

type Phase = { kind: 'idle' } | { kind: 'uploading'; percent: number; name: string } | { kind: 'scanning'; name: string } | { kind: 'form'; upload: UploadResult };

/** Drop a capture → summary → fill the record → live preview → save (with duplicate handling). */
export function UploadPage() {
  const { t } = useI18n();
  const { nickname } = useNickname();
  const { showError } = useFeedback();
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });

  const start = async (file: File) => {
    setPhase({ kind: 'uploading', percent: 0, name: file.name });
    try {
      const uploaded = await api.upload(file, nickname, (fraction) =>
        setPhase(fraction >= 1 ? { kind: 'scanning', name: file.name } : { kind: 'uploading', percent: Math.round(fraction * 100), name: file.name }));
      setPhase({ kind: 'form', upload: uploaded });
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
          <span className="muted">{phase.kind === 'uploading' ? t('upload.uploading', { percent: phase.percent }) : t('upload.scanning')}</span>
          {phase.kind === 'uploading' && <div className="progress" style={{ width: 280 }}><div style={{ width: `${phase.percent}%` }} /></div>}
        </div>
      )}
      {phase.kind === 'form' && <RecordForm key={phase.upload.uploadId} upload={phase.upload} onDone={() => setPhase({ kind: 'idle' })} />}
    </div>
  );
}

function DropZone({ onFile }: { onFile: (file: File) => void }) {
  const { t } = useI18n();
  const [over, setOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) onFile(file);
  };
  return (
    <div className={`dropzone${over ? ' over' : ''}`} onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)} onDrop={onDrop}>
      <div className="big-icon"><Icon name="upload" size={28} /></div>
      <h2>{t('upload.drop')}</h2>
      <span className="muted">{t('upload.or')}</span>
      <button className="btn primary" onClick={() => input.current?.click()}><Icon name="file" />{t('upload.browse')}</button>
      <input ref={input} type="file" hidden accept=".pcap,.pcapng,.cap,.dmp,application/vnd.tcpdump.pcap"
        onChange={(e) => { const file = e.target.files?.[0]; if (file) onFile(file); e.target.value = ''; }} />
    </div>
  );
}
