import { useRef, useState } from 'react';
import { api, ApiError } from '../../api/client';
import type { IcdSummary } from '../../api/types';
import { useI18n } from '../../i18n';
import { useFeedback } from '../../state/feedback';
import { Icon } from '../common/Icon';
import { Confirm } from '../common/Modal';

interface IcdProblem {
  path: string;
  message: string;
}

/** The ICDs: upload a JSON ICD (same id replaces it), download or delete uploaded ones. Java ICDs are read-only. */
export function IcdsCard({ icds, reload }: { icds: IcdSummary[]; reload: () => Promise<void> }) {
  const { t } = useI18n();
  const { toast, popup, showError } = useFeedback();
  const fileInput = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState<IcdSummary | null>(null);

  const upload = async (file: File) => {
    setBusy(true);
    try {
      const uploaded = await api.uploadIcd(await file.text());
      toast('success', t('icd.uploaded', { name: uploaded.name }));
      await reload();
    } catch (e) {
      // an invalid ICD lists every problem with its JSON path; show them all
      const problems = e instanceof ApiError && e.code === 'ICD_INVALID' ? (e.params.problems as IcdProblem[]) : null;
      if (problems) popup(t('icd.invalidTitle', { file: file.name }), problems.map((problem) => `${problem.path}: ${problem.message}`).join('\n'));
      else showError(e);
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const doDelete = async () => {
    if (!deleting) return;
    try {
      await api.deleteIcd(deleting.id);
      await reload();
    } catch (e) {
      showError(e);
    }
    setDeleting(null);
  };

  return (
    <section className="card">
      <div className="card-title"><h2>{t('icd.title')}</h2><span className="badge neutral">{icds.length}</span></div>
      <div className="col form-panel">
        <span className="hint small muted">{t('icd.help')}</span>
        <div className="row wrap">
          <input ref={fileInput} type="file" accept=".json,application/json" hidden
            onChange={(e) => { const file = e.target.files?.[0]; if (file) void upload(file); }} />
          <button className="btn primary" disabled={busy} onClick={() => fileInput.current?.click()}>
            <Icon name="upload" />{t('icd.upload')}
          </button>
        </div>
      </div>
      <div className="list" style={{ marginTop: 8 }}>
        {icds.length === 0 && <p className="muted" style={{ padding: 12 }}>{t('icd.empty')}</p>}
        {icds.map((icd) => (
          <div key={icd.id} className="list-row">
            <div className="grow col" style={{ gap: 2, minWidth: 0 }}>
              <span className="ellipsis strong"><bdi>{icd.name}</bdi></span>
              <span className="small muted"><span className="mono ltr">{icd.id}</span> · {t('icd.messages', { n: icd.messageCount })} · {t('icd.usedBy', { n: icd.usedBy.length })}</span>
            </div>
            <span className={`badge ${icd.source === 'JAVA' ? 'neutral' : 'ok'}`}>{t(`icd.source.${icd.source}`)}</span>
            {icd.source === 'JSON' && (
              <>
                <a className="btn ghost icon sm" title={t('common.download')} href={api.icdJsonUrl(icd.id)} download><Icon name="download" /></a>
                <button className="btn ghost icon sm" title={t('common.delete')} disabled={icd.usedBy.length > 0}
                  onClick={() => setDeleting(icd)}><Icon name="trash" /></button>
              </>
            )}
          </div>
        ))}
      </div>
      {deleting && (
        <Confirm title={t('icd.deleteTitle')} body={t('icd.deleteBody', { name: deleting.name })} danger
          confirmLabel={t('common.delete')} cancelLabel={t('common.cancel')} onConfirm={() => void doDelete()} onCancel={() => setDeleting(null)} />
      )}
    </section>
  );
}
