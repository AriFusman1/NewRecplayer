import type { IcdTree, RuleCondition } from '../../api/types';
import { useI18n } from '../../i18n';
import { predicateText } from '../../util/tagRules';
import { opcodeLabel } from '../../util/timefix';

/**
 * One rule condition as a sentence: "at least 5 × TargetReport where flags.alarm = 1 and …".
 * The words follow the page's language; the field expressions stay left-to-right in their own boxes,
 * so Hebrew and code do not mix on one line.
 */
export function ConditionLine({ condition, tree, first }: { condition: RuleCondition; tree: IcdTree | null; first: boolean }) {
  const { t } = useI18n();
  const message = tree?.messages.find((candidate) => candidate.opcode === condition.opcode);
  const words = { contains: t('op.CONTAINS'), startsWith: t('op.STARTS_WITH') };
  const predicates = condition.kind === 'FIELDS' ? condition.predicates : [];
  const tail = t(predicates.length ? 'at.messagesWhere' : 'at.messages'); // empty in Hebrew: "לפחות 5 הודעות X"
  return (
    <div className="condition-line small">
      {!first && <span className="condition-word">{t('rule.AND')}</span>}
      <span>{t('at.atLeast', { n: condition.minCount })}</span>
      <span className="badge neutral"><bdi>{message?.name ?? opcodeLabel(condition.opcode)}</bdi></span>
      {tail && <span>{tail}</span>}
      {predicates.map((predicate, i) => (
        <span key={i} className="condition-part">
          {i > 0 && <span className="condition-word">{t('rule.AND')}</span>}
          {predicate.op === 'CONTAINS' || predicate.op === 'STARTS_WITH' ? (
            // a word between two boxes, so a Hebrew word never sits inside left-to-right code
            <>
              <code className="condition-code" dir="ltr">{predicate.path}</code>
              <span>{predicate.op === 'CONTAINS' ? words.contains : words.startsWith}</span>
              <code className="condition-code" dir="ltr">"{predicate.values[0]?.text ?? ''}"</code>
            </>
          ) : <code className="condition-code" dir="ltr">{predicateText(predicate, words)}</code>}
        </span>
      ))}
    </div>
  );
}
