import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { AutoTagCapabilities, TagJobSummary, TagRulesView } from '../../api/types';
import { RulesPicker } from '../../components/autotag/RulesPicker';
import { SourcePicker, type Selection } from '../../components/autotag/SourcePicker';
import { Icon } from '../../components/common/Icon';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';
import { useNickname } from '../../state/nickname';
import { formatBytes, formatMs, formatNumber } from '../../util/time';
import { JobStatusBadge } from './JobStatusBadge';

/** Local files are uploaded this many at a time. */
const PARALLEL_UPLOADS = 2;

/**
 * Auto tagging: choose a category (its ICD), the files, and the rules; the run decodes every file,
 * counts every message type and saves the files that match at least one rule as records.
 */
export function AutoTagPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { catalog } = useCatalog();
  const { nickname } = useNickname();
  const { run, busy } = useAction();
  const [capabilities, setCapabilities] = useState<AutoTagCapabilities | null>(null);
  const [categoryId, setCategoryId] = useState('');
  const [rules, setRules] = useState<TagRulesView | null>(null);
  const [ruleIds, setRuleIds] = useState<string[]>([]);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [evaluateOnly, setEvaluateOnly] = useState(false);
  const [upload, setUpload] = useState<{ done: number; total: number } | null>(null);
  const [jobs, setJobs] = useState<TagJobSummary[]>([]);
  const categories = catalog.categories.filter((category) => category.icdId);

  useEffect(() => {
    void run(async () => {
      setCapabilities(await api.autoTagCapabilities());
      setJobs(await api.tagJobs());
    });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const loadRules = useCallback(async (id: string) => {
    if (!id) {
      setRules(null);
      return;
    }
    await run(async () => setRules(await api.tagRules(id)));
  }, [run]);

  useEffect(() => {
    setRuleIds([]);
    void loadRules(categoryId);
  }, [categoryId]); // eslint-disable-line react-hooks/exhaustive-deps

  const max = capabilities?.maxFilesPerRun ?? 0;
  const overLimit = selection?.count !== null && selection !== null && max > 0 && selection.count! > max;
  const canStart = !busy && !upload && categoryId && ruleIds.length > 0 && selection !== null && selection.count !== null
    && selection.count > 0 && !overLimit;

  const start = () => run(async () => {
    if (!selection) return;
    const local = selection.kind === 'LOCAL_FILES' || selection.kind === 'LOCAL_FOLDER';
    const job = await api.createTagJob({
      categoryId, ruleIds, evaluateOnly, kind: selection.kind, createdBy: nickname,
      files: local ? selection.local.map(({ file, path }) => ({ name: file.name, path, size: file.size })) : undefined,
      keys: selection.kind === 'ARCHIVE_FILES' ? selection.keys : undefined,
      folder: selection.folder, recursive: selection.recursive, fromUs: selection.fromUs, toUs: selection.toUs,
    });
    if (local) {
      // one or two at a time; a file that fails to upload is reported by the job as FAILED
      setUpload({ done: 0, total: selection.local.length });
      let next = 0;
      const worker = async () => {
        while (next < selection.local.length) {
          const index = next++;
          await api.uploadTagJobFile(job.id, index, selection.local[index].file).catch(() => undefined);
          setUpload((current) => current && { ...current, done: current.done + 1 });
        }
      };
      try {
        await Promise.all(Array.from({ length: PARALLEL_UPLOADS }, worker));
      } finally {
        setUpload(null);
      }
      await api.startTagJob(job.id);
    }
    navigate(`/autotag/jobs/${job.id}`);
  });

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>{t('at.title')}</h1>
          <p className="muted">{t('at.subtitle')}</p>
        </div>
      </div>

      <div className="stack-lg">
        <section className="card">
          <span className="section-title">1 · {t('at.category')}</span>
          <div className="row" style={{ gap: 10, marginTop: 8, flexWrap: 'wrap' }}>
            <select className="select" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <option value="">{t('at.chooseCategory')}</option>
              {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
            </select>
            {rules?.icdId && <span className="small muted">{t('tf.icd')}: <span className="mono ltr">{rules.tree?.name ?? rules.icdId} ({rules.icdId})</span></span>}
          </div>
          {categories.length === 0 && <p className="small muted">{t('at.noIcdCategories')}</p>}
        </section>

        <section className="card">
          <span className="section-title">2 · {t('at.source')}</span>
          <div style={{ marginTop: 8 }}>
            <SourcePicker archiveConfigured={capabilities?.archiveConfigured ?? false} onChange={setSelection} />
          </div>
          <div className="row" style={{ gap: 12, marginTop: 10, flexWrap: 'wrap' }}>
            {selection && selection.count !== null && (
              <span className={`badge ${overLimit ? 'err' : 'neutral'}`}>
                {t('at.selected', { n: selection.count, size: formatBytes(selection.totalBytes ?? 0) })}</span>
            )}
            {selection && selection.count === null && <span className="small muted">{t('at.counting')}</span>}
            {max > 0 && <span className="small muted">{t('at.limit', { max: formatNumber(max) })}</span>}
          </div>
          {overLimit && <p className="filter-error">{t('at.overLimit', { max: formatNumber(max) })}</p>}
        </section>

        <section className="card">
          <span className="section-title">3 · {t('at.rules')}</span>
          <p className="small muted" style={{ margin: '4px 0 8px' }}>{t('at.rulesHelp')}</p>
          {rules ? <RulesPicker view={rules} selectedIds={ruleIds} onSelected={setRuleIds} onChanged={() => void loadRules(categoryId)} />
            : <p className="small muted">{t('at.chooseCategoryFirst')}</p>}
        </section>

        <section className="card">
          <span className="section-title">4 · {t('at.run')}</span>
          <label className="row" style={{ gap: 8, marginTop: 8 }}>
            <input type="checkbox" checked={evaluateOnly} onChange={(e) => setEvaluateOnly(e.target.checked)} />
            <span>{t('at.evaluateOnly')}</span>
          </label>
          <p className="small muted" style={{ margin: '4px 0 10px' }}>{t('at.evaluateOnlyHelp')}</p>
          {upload && (
            <div className="stack" style={{ marginBottom: 10 }}>
              <span className="small">{t('at.uploading', { done: upload.done, total: upload.total })}</span>
              <div className="progress"><div style={{ width: `${(100 * upload.done) / Math.max(1, upload.total)}%` }} /></div>
            </div>
          )}
          <button className="btn primary" disabled={!canStart} onClick={() => void start()}><Icon name="play" size={12} />{t('at.start')}</button>
        </section>

        <section className="card">
          <span className="section-title">{t('at.pastJobs')}</span>
          {jobs.length === 0 ? <p className="small muted">{t('at.noJobs')}</p> : (
            <div className="table-wrap" style={{ marginTop: 8 }}>
              <table className="table">
                <thead>
                  <tr><th>{t('at.started')}</th><th>{t('at.category')}</th><th>{t('at.rules')}</th><th>{t('at.status')}</th><th>{t('at.files')}</th><th>{t('at.matched')}</th><th /></tr>
                </thead>
                <tbody>
                  {jobs.map((job) => (
                    <tr key={job.id}>
                      <td className="small mono ltr">{formatMs(job.createdAtMs)}</td>
                      <td><bdi>{job.categoryName}</bdi></td>
                      <td className="small"><bdi>{job.ruleNames.join(', ')}</bdi></td>
                      <td><JobStatusBadge status={job.status} />{job.evaluateOnly && <span className="badge neutral" style={{ marginInlineStart: 6 }}>{t('at.evalBadge')}</span>}</td>
                      <td className="small">{job.filesDone} / {job.filesTotal}</td>
                      <td className="small">{(job.statusCounts.MATCHED_SAVED ?? 0) + (job.statusCounts.MATCHED_EXISTING_UPDATED ?? 0) + (job.statusCounts.MATCHED ?? 0)}</td>
                      <td><Link className="btn ghost sm" to={`/autotag/jobs/${job.id}`}>{t('at.open')}</Link></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
