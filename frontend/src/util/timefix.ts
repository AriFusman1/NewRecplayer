import type { IcdTreeMessage, IcdTreeNode, MessageTimeFix, TimeFieldRef, TimeKind } from '../api/types';

// Pure helpers for editing a time-fix profile on the client. The server checks everything again on save.

/** The profile being edited: one entry per message opcode that has a base or fields. */
export type Draft = Map<number, MessageTimeFix>;

export function draftOf(messages: MessageTimeFix[] | undefined): Draft {
  return new Map((messages ?? []).map((message) => [message.opcode, { ...message, fields: [...message.fields] }]));
}

/** The draft as the API wants it, in opcode order, without empty entries. */
export function messagesOf(draft: Draft): MessageTimeFix[] {
  return [...draft.values()]
    .filter((message) => message.base || message.fields.length > 0)
    .sort((a, b) => a.opcode - b.opcode);
}

/** True if both drafts would save the same profile. */
export function sameDraft(a: Draft, b: Draft): boolean {
  return JSON.stringify(messagesOf(a)) === JSON.stringify(messagesOf(b));
}

function entry(draft: Draft, opcode: number): MessageTimeFix {
  return draft.get(opcode) ?? { opcode, base: null, fields: [] };
}

function withEntry(draft: Draft, message: MessageTimeFix): Draft {
  const next = new Map(draft);
  next.set(message.opcode, message);
  return next;
}

/** Makes {@code ref} the message's base field (a field cannot be base and fixed at once). */
export function setBase(draft: Draft, opcode: number, ref: TimeFieldRef): Draft {
  const message = entry(draft, opcode);
  return withEntry(draft, { ...message, base: ref, fields: message.fields.filter((field) => field.path !== ref.path) });
}

/** Ticks or unticks a field to fix. */
export function toggleField(draft: Draft, opcode: number, ref: TimeFieldRef, on: boolean): Draft {
  const message = entry(draft, opcode);
  const fields = message.fields.filter((field) => field.path !== ref.path);
  return withEntry(draft, { ...message, fields: on ? [...fields, ref] : fields });
}

/** Changes the time kind of the base or a fixed field. */
export function setKind(draft: Draft, opcode: number, path: string, kind: TimeKind): Draft {
  const message = entry(draft, opcode);
  return withEntry(draft, {
    ...message,
    base: message.base?.path === path ? { path, kind } : message.base,
    fields: message.fields.map((field) => (field.path === path ? { path, kind } : field)),
  });
}

export function clearMessage(draft: Draft, opcode: number): Draft {
  const next = new Map(draft);
  next.delete(opcode);
  return next;
}

/** What a field is in the message's draft: the base, a field to fix, or neither. */
export function roleOf(message: MessageTimeFix | undefined, path: string): { role: 'base' | 'fix' | null; kind: TimeKind | null } {
  if (message?.base?.path === path) return { role: 'base', kind: message.base.kind };
  const field = message?.fields.find((candidate) => candidate.path === path);
  return field ? { role: 'fix', kind: field.kind } : { role: null, kind: null };
}

/**
 * A first guess for a newly selected field, from its type (the user can change it): 64-bit integers
 * usually hold epoch milliseconds, floats and 32-bit or smaller integers a time of day in seconds.
 */
export function defaultKind(node: IcdTreeNode): TimeKind {
  if (node.storageType === 'INT64' || node.storageType === 'UINT64') return 'REGULAR_MILLIS';
  return 'LTIME';
}

/** Opcode as shown in the UI: decimal and hex. */
export function opcodeLabel(opcode: number): string {
  return `${opcode} (0x${opcode.toString(16).toUpperCase()})`;
}

/** Every node of a message, depth first, with its depth. */
export function flatten(message: IcdTreeMessage): { node: IcdTreeNode; depth: number }[] {
  const rows: { node: IcdTreeNode; depth: number }[] = [];
  const visit = (node: IcdTreeNode, depth: number) => {
    rows.push({ node, depth });
    node.children.forEach((child) => visit(child, depth + 1));
  };
  message.fields.forEach((node) => visit(node, 0));
  return rows;
}

/** Client-side check shown next to a message (the server does the full check on save). */
export function missingBase(message: MessageTimeFix | undefined): boolean {
  return !!message && !message.base && message.fields.length > 0;
}
