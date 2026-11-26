import type { FieldPredicate, IcdTree, IcdTreeMessage, IcdTreeNode, Operator, RuleCondition, RuleValue, TagRule, ValueType } from '../api/types';

// Pure helpers for the rule editor. The server checks every rule again when it is saved.

/** Operators per field type, as the server allows them. */
const OPERATORS: Record<ValueType, Operator[]> = {
  INTEGER: ['EQ', 'NE', 'GT', 'GE', 'LT', 'LE', 'BETWEEN', 'IN', 'NOT_IN'],
  FLOAT: ['EQ', 'NE', 'GT', 'GE', 'LT', 'LE', 'BETWEEN', 'IN', 'NOT_IN'],
  ENUM: ['EQ', 'NE', 'IN', 'NOT_IN'],
  BOOL: ['EQ'],
  STRING: ['EQ', 'NE', 'CONTAINS', 'STARTS_WITH', 'IN', 'NOT_IN'],
};

export const SYMBOLS: Record<Operator, string> = {
  EQ: '=', NE: '≠', GT: '>', GE: '≥', LT: '<', LE: '≤', BETWEEN: '∈', IN: '∈', NOT_IN: '∉', CONTAINS: '∋', STARTS_WITH: '^',
};

/** How a field is compared, or null when it is not a single value (header, struct, bitfield, padding). */
export function valueTypeOf(node: IcdTreeNode): ValueType | null {
  switch (node.kind) {
    case 'BIT': return 'INTEGER';
    case 'ENUM': return 'ENUM';
    case 'STRING': return 'STRING';
    case 'PRIMITIVE':
      if (node.storageType === 'BOOL') return 'BOOL';
      return node.storageType === 'FLOAT32' || node.storageType === 'FLOAT64' ? 'FLOAT' : 'INTEGER';
    default: return null;
  }
}

export function operatorsFor(type: ValueType): Operator[] {
  return OPERATORS[type];
}

export function isList(op: Operator): boolean {
  return op === 'IN' || op === 'NOT_IN';
}

/** Every node of a message by path. */
export function nodesByPath(message: IcdTreeMessage): Map<string, IcdTreeNode> {
  const nodes = new Map<string, IcdTreeNode>();
  const pending = [...message.fields];
  while (pending.length) {
    const node = pending.shift()!;
    nodes.set(node.path, node);
    pending.push(...node.children);
  }
  return nodes;
}

// ------------------------------------------------------------------ editing drafts

/** A predicate as the editor holds it: raw texts, converted to values on save. */
export interface PredicateDraft {
  path: string;
  op: Operator;
  /** The value, the low end of BETWEEN, or a comma-separated list. */
  text: string;
  /** The high end of BETWEEN. */
  text2: string;
  /** Enum names (one for EQ/NE, several for IN/NOT_IN). */
  names: string[];
  bool: boolean;
}

export interface ConditionDraft {
  key: number;
  kind: 'COUNT' | 'FIELDS';
  opcode: number;
  minCount: string;
  predicates: PredicateDraft[];
}

export interface RuleDraft {
  id: string | null;
  name: string;
  conditions: ConditionDraft[];
}

let nextKey = 1;

export function newPredicate(node: IcdTreeNode): PredicateDraft {
  const type = valueTypeOf(node)!;
  return { path: node.path, op: 'EQ', text: '', text2: '', names: type === 'ENUM' && node.enumValues.length ? [node.enumValues[0].name] : [], bool: true };
}

export function newCondition(kind: 'COUNT' | 'FIELDS', opcode: number): ConditionDraft {
  return { key: nextKey++, kind, opcode, minCount: '1', predicates: [] };
}

function textOf(value: RuleValue | undefined): string {
  if (!value) return '';
  if (value.text !== undefined && value.text !== null) return value.text;
  return value.number === undefined || value.number === null ? '' : String(value.number);
}

export function draftOf(rule: TagRule | null): RuleDraft {
  if (!rule) return { id: null, name: '', conditions: [] };
  return {
    id: rule.id,
    name: rule.name,
    conditions: rule.conditions.map((condition) => ({
      key: nextKey++,
      kind: condition.kind,
      opcode: condition.opcode,
      minCount: String(condition.minCount),
      predicates: condition.predicates.map((predicate) => ({
        path: predicate.path,
        op: predicate.op,
        text: isList(predicate.op) ? predicate.values.map(textOf).join(', ') : textOf(predicate.values[0]),
        text2: textOf(predicate.values[1]),
        names: predicate.values.map((value) => value.name ?? '').filter(Boolean),
        bool: predicate.values[0]?.bool ?? true,
      })),
    })),
  };
}

function splitList(text: string): string[] {
  return text.split(',').map((part) => part.trim()).filter((part) => part.length > 0);
}

function valuesOf(predicate: PredicateDraft, type: ValueType): RuleValue[] {
  switch (type) {
    case 'ENUM': return (isList(predicate.op) ? predicate.names : predicate.names.slice(0, 1)).map((name) => ({ name }));
    case 'BOOL': return [{ bool: predicate.bool }];
    case 'STRING': return isList(predicate.op) ? splitList(predicate.text).map((text) => ({ text })) : [{ text: predicate.text }];
    default:
      if (isList(predicate.op)) return splitList(predicate.text).map((number) => ({ number }));
      if (predicate.op === 'BETWEEN') return [{ number: predicate.text.trim() }, { number: predicate.text2.trim() }];
      return [{ number: predicate.text.trim() }];
  }
}

/** The rule as the API wants it. */
export function ruleOf(draft: RuleDraft, tree: IcdTree): TagRule {
  return {
    id: draft.id,
    name: draft.name.trim(),
    conditions: draft.conditions.map((condition): RuleCondition => {
      const message = tree.messages.find((candidate) => candidate.opcode === condition.opcode);
      const nodes = message ? nodesByPath(message) : new Map<string, IcdTreeNode>();
      return {
        kind: condition.kind,
        opcode: condition.opcode,
        minCount: Number(condition.minCount),
        predicates: condition.kind === 'COUNT' ? [] : condition.predicates.map((predicate): FieldPredicate => {
          const node = nodes.get(predicate.path);
          const type = node ? valueTypeOf(node) ?? 'STRING' : 'STRING';
          return { path: predicate.path, op: predicate.op, values: valuesOf(predicate, type) };
        }),
      };
    }),
  };
}

// ------------------------------------------------------------------ readable summary

function shownValue(value: RuleValue): string {
  if (value.name) return value.name;
  if (value.bool !== undefined && value.bool !== null) return String(value.bool);
  if (value.text !== undefined && value.text !== null) return `"${value.text}"`;
  return String(value.number ?? '');
}

/** "targets[*].kind = AIR", "count ∈ [1 … 5]", "unitName ∈ {"A", "B"}". */
export function predicateText(predicate: FieldPredicate, words: { contains: string; startsWith: string }): string {
  const values = predicate.values.map(shownValue);
  switch (predicate.op) {
    case 'BETWEEN': return `${predicate.path} ∈ [${values[0]} … ${values[1]}]`;
    case 'IN': return `${predicate.path} ∈ {${values.join(', ')}}`;
    case 'NOT_IN': return `${predicate.path} ∉ {${values.join(', ')}}`;
    case 'CONTAINS': return `${predicate.path} ${words.contains} ${values[0]}`;
    case 'STARTS_WITH': return `${predicate.path} ${words.startsWith} ${values[0]}`;
    default: return `${predicate.path} ${SYMBOLS[predicate.op]} ${values[0]}`;
  }
}
