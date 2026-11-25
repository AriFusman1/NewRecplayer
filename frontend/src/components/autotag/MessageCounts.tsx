import type { MessageTypeCount } from '../../api/types';
import { useI18n } from '../../i18n';
import { formatNumber } from '../../util/time';

/** Count per message type: in job results and in a record's details. */
export function MessageCounts({ counts }: { counts: MessageTypeCount[] }) {
  const { t } = useI18n();
  return (
    <table className="table compact">
      <thead><tr><th>{t('stats.opcode')}</th><th>{t('stats.message')}</th><th>{t('stats.count')}</th></tr></thead>
      <tbody>
        {counts.map((count) => (
          <tr key={count.opcode}><td className="mono ltr">{count.opcode}</td><td><bdi>{count.name}</bdi></td><td>{formatNumber(count.count)}</td></tr>
        ))}
      </tbody>
    </table>
  );
}
