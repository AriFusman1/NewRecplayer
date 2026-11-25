import { Fragment, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import { FILE_STATUSES, type FileResult, type FileStatus, type TagJob } from '../../api/types';
import { MessageCounts } from '../../components/autotag/MessageCounts';
import { Icon } from '../../components/common/Icon';
import { useI18n } from '../../i18n';
import { useAction } from '../../state/feedback';
import { formatBytes, formatMs, formatNumber, formatUs } from '../../util/time';
import { JobStatusBadge } from './JobStatusBadge';

const POLL_MS = 2000;

const STATUS_LOOK: Record<FileStatus, string> = {
  MATCHED_SAVED: 'ok', MATCHED_EXISTING_UPDATED: 'ok', MATCHED: 'ok', NOT_MATCHED: 'neutral', NOT_A_PCAP: 'neutral', FAILED: 'err',
};

/** One tagging job: progress while it runs (polled), then per-file results. */
export function TagJobPage() {
  const { t } = useI18n();
  const { jobId = '' } = useParams();
  const { run, busy } = useAction();
  const [job, setJob] = useState<TagJob | null>(null);
  const [filter, setFilter] = useState<FileStatus | ''>('');
  const [open, setOpen] = useState<Set<number>>(new Set());
  const runRef = useRef(run);
  runRef.current = run;

  // poll until the job is finished
  useEffect(() => {
    let timer = 0;
    let stopped = false;
    const load = async () => {
      let finished = false;
      await runRef.current(async () => {
        const loaded = await api.tagJob(jobId);
        setJob(loaded);
        finished = loaded.status === 'DONE' || loaded.status === 'CANCELLED' || loaded.status === 'FAILED';
      });
      if (!finished && !stopped) timer = window.setTimeout(() => void load(), POLL_MS);
    };
    void load();
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [jobId]);

  if (!job) return <div className="muted">{t('common.loading')}</div>;

  const active = job.status === 'PENDING' || job.status === 'UPLOADING' || job.status === 'RUNNING';
  const counts = new Map<FileStatus, number>();
  for (const result of job.results) counts.set(result.status, (counts.get(result.status) ?? 0) + 1);
  const results = [...job.results].sort((a, b) => a.index - b.index).filter((result) => !filter || result.status === filter);
  const toggle = (index: number) => setOpen((current) => {
    const next = new Set(current);
    if (next.has(index)) next.delete(index);
    else next.add(index);
    return next;
  });
  const source = job.source;

  return (
    <div>
      <div className="page-head">
        <div className="col" style={{ gap: 4 }}>
          <Link to="/autotag" className="small muted">{t('job.back')}</Link>
          <h1>{t('job.title')} · <bdi>{job.categoryName}</bdi></h1>
          <span className="small muted">
            {t(`at.src.${source.kind}`)}{source.folder ? <> · <bdi className="mono">{source.folder}</bdi></> : null}
            {source.fromUs !== null || source.toUs !== null
              ? <> · <span className="mono ltr">{source.fromUs !== null ? formatUs(source.fromUs) : '…'} – {source.toUs !== null ? formatUs(source.toUs) : '…'}</span></>
              : null}
            {' · '}{t('job.by', { name: job.createdBy || '—', time: formatMs(job.createdAtMs) })}
          </span>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <JobStatusBadge status={job.status} />
          {job.evaluateOnly && <span className="badge neutral">{t('at.evalBadge')}</span>}
          {active && (
            <button className="btn" disabled={busy} onClick={() => void run(async () => setJob(await api.cancelTagJob(job.id)))}>
              <Icon name="stop" size={12} />{t('job.cancel')}</button>
          )}
        </div>
      </div>

      <div className="stack-lg">
        <section className="card">
          <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
            <span>{t('job.progress', { done: job.filesDone, total: job.inputs.length })}</span>
            <span className="small muted">{formatBytes(job.bytesDone)} / {formatBytes(job.bytesTotal)}</span>
            {job.failureReason && <span className="badge err">{job.failureReason}</span>}
          </div>
          <div className="progress" style={{ marginTop: 8 }}>
            <div style={{ width: `${(100 * job.filesDone) / Math.max(1, job.inputs.length)}%` }} />
          </div>
          <div className="row" style={{ gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
            <span className="small muted">{t('job.rules')}:</span>
            {job.rules.map((rule) => <span key={rule.id} className="chip tag"><bdi>{rule.name}</bdi></span>)}
          </div>
        </section>

        <section className="card">
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <button className={`chip selectable${filter === '' ? ' on' : ''}`} onClick={() => setFilter('')}>
              {t('job.all')} · {job.results.length}</button>
            {FILE_STATUSES.filter((status) => counts.has(status) || status !== 'MATCHED').map((status) => (
              <button key={status} className={`chip selectable${filter === status ? ' on' : ''}`} onClick={() => setFilter(status)}>
                {t(`fs.${status}`)} · {counts.get(status) ?? 0}</button>
            ))}
          </div>
          <div className="table-wrap" style={{ marginTop: 10 }}>
            <table className="table">
              <thead>
                <tr><th /><th>{t('job.file')}</th><th>{t('at.status')}</th><th>{t('job.matchedRules')}</th><th>{t('job.messages')}</th><th>{t('job.record')}</th></tr>
              </thead>
              <tbody>
                {results.map((result) => (
                  <Fragment key={result.index}>
                    <ResultRow result={result} open={open.has(result.index)} onToggle={() => toggle(result.index)} />
                    {open.has(result.index) && <ResultDetails result={result} />}
                  </Fragment>
                ))}
                {results.length === 0 && <tr><td colSpan={6} className="muted">{active ? t('job.waiting') : t('job.noResults')}</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  );
}

function ResultRow({ result, open, onToggle }: { result: FileResult; open: boolean; onToggle: () => void }) {
  const { t, dir } = useI18n();
  return (
    <tr>
      <td>
        {result.counts.length > 0 || result.error
          ? <button className="btn ghost icon sm" onClick={onToggle} aria-expanded={open}>{open ? '▾' : dir === 'rtl' ? '◂' : '▸'}</button>
          : null}
      </td>
      <td>
        <div title={result.path}><bdi>{result.name}</bdi>{result.gzipped && <span className="badge neutral" style={{ marginInlineStart: 6 }}>gz</span>}</div>
        {result.path !== result.name && <div className="small muted mono ltr ellipsis">{result.path}</div>}
      </td>
      <td><span className={`badge ${STATUS_LOOK[result.status]}`}>{t(`fs.${result.status}`)}</span></td>
      <td>{result.matchedRules.map((rule) => <span key={rule} className="chip tag"><bdi>{rule}</bdi></span>)}</td>
      <td>{formatNumber(result.totalMessages)}</td>
      <td>
        {result.recordIds.map((id) => <Link key={id} className="btn ghost sm" to={`/?record=${id}`}>{t('job.openRecord')}</Link>)}
      </td>
    </tr>
  );
}

function ResultDetails({ result }: { result: FileResult }) {
  const { t } = useI18n();
  return (
    <tr className="result-details">
      <td />
      <td colSpan={5}>
        {result.error && <div className="alert error small">{result.error}</div>}
        {result.counts.length > 0 && <MessageCounts counts={result.counts} />}
        <div className="small muted" style={{ marginTop: 6 }}>
          {t('stats.problems', { parse: result.parseErrors, unknown: result.unknownOpcodes })} · {formatBytes(result.size)}
        </div>
      </td>
    </tr>
  );
}
