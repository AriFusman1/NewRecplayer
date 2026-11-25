import type { JobStatus } from '../../api/types';
import { useI18n } from '../../i18n';

const LOOK: Record<JobStatus, string> = {
  PENDING: 'neutral', UPLOADING: 'neutral', RUNNING: 'warn', DONE: 'ok', CANCELLED: 'neutral', FAILED: 'err',
};

export function JobStatusBadge({ status }: { status: JobStatus }) {
  const { t } = useI18n();
  return <span className={`badge ${LOOK[status]}`}>{t(`job.${status}`)}</span>;
}
