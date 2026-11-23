import type { Category, Preview } from '../../api/types';
import { ModeBadge, Warnings } from '../../components/common/Badges';
import { Stat } from '../../components/common/Stat';
import { BarGraph } from '../../components/graphs/BarGraph';
import { useI18n } from '../../i18n';
import { formatBytes, formatDuration, formatNumber } from '../../util/time';

interface Props {
  preview: Preview | null;
  computing: boolean;
  category: Category | undefined;
  saving: boolean;
  onSave: () => void;
}

/** What the record would contain with the current settings, and the save button. */
export function PreviewCard({ preview, computing, category, saving, onSave }: Props) {
  const { t } = useI18n();
  const stats = preview?.stats;
  return (
    <section className="card">
      <div className="card-title"><h2>{t('upload.preview')}</h2>{computing && <span className="spinner" />}</div>
      {!preview ? <p className="muted small">{computing ? t('upload.computing') : t('err.PORTS_REQUIRED')}</p> : (
        <div className="col" style={{ gap: 14 }}>
          {stats && (
            <>
              <div className="stats">
                <Stat label={t('upload.messages')} value={formatNumber(stats.messageCount)} />
                <Stat label={t('upload.duration')} value={formatDuration(stats.durationUs)} />
                <Stat label={t('upload.protocol')} value={stats.protocol} />
                <Stat label={t('live.bytes')} value={formatBytes(stats.bytes)} />
              </div>
              <div className="row wrap"><span className="chip">{category?.name}</span>{category && <ModeBadge mode={category.mode} />}
                {stats.protocol === 'TCP' && <span className="chip">{t('card.connections', { n: stats.connections })}</span>}</div>
              {stats.messageCount > 0 && <BarGraph histogram={stats.histogram} binUs={stats.binUs} durationUs={stats.durationUs} height={70} />}
              <Warnings items={stats.warnings} />
            </>
          )}
          <Warnings items={preview.errors} kind="err" />
        </div>
      )}
      <hr className="divider" />
      <button className="btn primary" style={{ width: '100%', height: 40 }} disabled={saving || !preview || computing} onClick={onSave}>
        {saving ? t('common.loading') : t('upload.save')}</button>
    </section>
  );
}
