import { useRef, useState, type DragEvent } from 'react';
import { Icon } from '../../components/common/Icon';
import { useI18n } from '../../i18n';
import { useUpload } from '../../state/upload';
import { RecordForm } from './RecordForm';

/**
 * Drop a capture → summary → fill the record → live preview → save (with duplicate handling). The
 * upload and the form live in the upload context, so another page can be visited meanwhile.
 */
export function UploadPage() {
  const { t } = useI18n();
  const { phase, draft, start, discard: reset } = useUpload();

  return (
    <div>
      <div className="page-head">
        <div><h1>{t('nav.upload')}</h1></div>
        {phase.kind === 'form' && <button className="btn" onClick={reset}><Icon name="upload" />{t('upload.another')}</button>}
      </div>
      {phase.kind === 'idle' && <DropZone onFile={(file) => void start(file)} />}
      {(phase.kind === 'uploading' || phase.kind === 'scanning') && (
        <div className="dropzone">
          <div className="big-icon"><span className="spinner" style={{ width: 24, height: 24 }} /></div>
          <strong>{phase.name}</strong>
          <span className="muted">{phase.kind === 'uploading' ? t('upload.uploading', { percent: phase.percent }) : t('upload.scanning')}</span>
          {phase.kind === 'uploading' && <div className="progress" style={{ width: 280 }}><div style={{ width: `${phase.percent}%` }} /></div>}
        </div>
      )}
      {phase.kind === 'form' && draft && <RecordForm key={phase.upload.uploadId} upload={phase.upload} draft={draft} />}
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
