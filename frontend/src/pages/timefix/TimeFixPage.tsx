import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Problem, TimeFixView } from '../../api/types';
import { Warnings } from '../../components/common/Badges';
import { Icon } from '../../components/common/Icon';
import { Confirm } from '../../components/common/Modal';
import { MessageTimeFixCard } from '../../components/timefix/MessageTimeFixCard';
import { useI18n } from '../../i18n';
import { useCatalog } from '../../state/catalog';
import { useAction } from '../../state/feedback';
import { useIcds } from '../../state/icds';
import { clearMessage, draftOf, messagesOf, sameDraft, setBase, setKind, toggleField, type Draft } from '../../util/timefix';

/**
 * Time fixing for one category: its ICD as a tree, and per message type the base field and the
 * fields to fix, each with its time kind. Edits stay local until saved.
 */
export function TimeFixPage() {
  const { t } = useI18n();
  const { categoryId = '' } = useParams();
  const { categoryById } = useCatalog();
  const { icds } = useIcds();
  const { run, busy } = useAction();
  const [view, setView] = useState<TimeFixView | null>(null);
  const [saved, setSaved] = useState<Draft>(new Map());
  const [draft, setDraft] = useState<Draft>(new Map());
  const [warnings, setWarnings] = useState<Problem[]>([]);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const category = categoryById.get(categoryId);
  const dirty = !sameDraft(draft, saved);

  // `run` changes with the language; loading must not (it would throw away unsaved edits)
  const runRef = useRef(run);
  runRef.current = run;

  useEffect(() => {
    void runRef.current(async () => {
      const loaded = await api.timeFix(categoryId);
      setView(loaded);
      setSaved(draftOf(loaded.profile?.messages));
      setDraft(draftOf(loaded.profile?.messages));
      setWarnings(loaded.warnings);
    });
  }, [categoryId]);

  // the browser asks before leaving the page with unsaved changes
  useEffect(() => {
    if (!dirty) return undefined;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const save = () => run(async () => {
    const result = await api.saveTimeFix(categoryId, messagesOf(draft));
    setSaved(draftOf(result.profile.messages));
    setDraft(draftOf(result.profile.messages));
    setWarnings(result.warnings);
  }, t('tf.saved'));

  const tree = view?.tree ?? null;
  const icdName = icds.find((icd) => icd.id === view?.icdId)?.name ?? view?.icdId;
  const fixedCount = messagesOf(draft).length;

  return (
    <div>
      <div className="page-head">
        <div className="col" style={{ gap: 4 }}>
          <Link to="/catalog" className="small muted">{t('tf.back')}</Link>
          <h1>{t('tf.title')}{category ? <> · <bdi>{category.name}</bdi></> : null}</h1>
          {view?.icdId && <span className="small muted">{t('tf.icd')}: <bdi>{icdName}</bdi> <span className="mono ltr">({view.icdId})</span></span>}
        </div>
        {tree && (
          <div className="row" style={{ gap: 8 }}>
            {dirty && <span className="badge warn">{t('tf.unsaved')}</span>}
            <button className="btn" disabled={!dirty || busy} onClick={() => setConfirmDiscard(true)}>{t('tf.discard')}</button>
            <button className="btn primary" disabled={!dirty || busy} onClick={() => void save()}><Icon name="check" />{t('common.save')}</button>
          </div>
        )}
      </div>

      {view && !tree && <div className="card"><p className="muted">{t('tf.noIcd')}</p></div>}
      {tree && (
        <div className="stack-lg">
          <p className="small muted" style={{ margin: 0 }}>{t('tf.help')}</p>
          <Warnings items={warnings} />
          <span className="section-title">{t('tf.messagesFixed', { n: fixedCount, total: tree.messages.length })}</span>
          {tree.messages.map((message) => (
            <MessageTimeFixCard key={message.opcode} message={message} fix={draft.get(message.opcode)}
              onBase={(ref) => setDraft(setBase(draft, message.opcode, ref))}
              onToggle={(ref, on) => setDraft(toggleField(draft, message.opcode, ref, on))}
              onKind={(path, kind) => setDraft(setKind(draft, message.opcode, path, kind))}
              onClear={() => setDraft(clearMessage(draft, message.opcode))} />
          ))}
        </div>
      )}

      {confirmDiscard && (
        <Confirm title={t('tf.discardTitle')} body={t('tf.discardBody')} danger confirmLabel={t('tf.discard')} cancelLabel={t('common.cancel')}
          onConfirm={() => { setDraft(saved); setConfirmDiscard(false); }} onCancel={() => setConfirmDiscard(false)} />
      )}
    </div>
  );
}
