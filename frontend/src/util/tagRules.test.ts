// Run with `npm test`.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { IcdTree, IcdTreeNode, TagRule } from '../api/types';
import { conditionText, draftOf, newCondition, operatorsFor, ruleOf, valueTypeOf } from './tagRules.js';

function node(path: string, kind: IcdTreeNode['kind'], storageType: string | null, extra: Partial<IcdTreeNode> = {}): IcdTreeNode {
  return { name: path, path, kind, type: '', storageType, bitWidth: 0, size: 1, array: null, numeric: false, inArray: false,
    children: [], enumValues: [], ...extra };
}

const KIND = node('targets[*].kind', 'ENUM', 'INT8', { enumValues: [{ value: 1, name: 'AIR' }, { value: 2, name: 'SURFACE' }] });
const COUNT = node('targetCount', 'PRIMITIVE', 'UINT8');
const TREE: IcdTree = {
  id: 'sample-v1', name: 'Sample', byteOrder: 'BIG_ENDIAN',
  messages: [{ opcode: 2, name: 'Track', size: null, fields: [COUNT, node('targets', 'STRUCT', null, { children: [KIND] })] }],
};
const WORDS = { where: 'where', and: 'AND', contains: 'contains', startsWith: 'starts with' };

test('value types and their operators', () => {
  assert.equal(valueTypeOf(KIND), 'ENUM');
  assert.equal(valueTypeOf(COUNT), 'INTEGER');
  assert.equal(valueTypeOf(node('x', 'PRIMITIVE', 'FLOAT32')), 'FLOAT');
  assert.equal(valueTypeOf(node('x', 'PRIMITIVE', 'BOOL')), 'BOOL');
  assert.equal(valueTypeOf(node('x', 'BIT', 'UINT8')), 'INTEGER');
  assert.equal(valueTypeOf(node('x', 'STRUCT', null)), null);
  assert.deepEqual(operatorsFor('ENUM'), ['EQ', 'NE', 'IN', 'NOT_IN']);
  assert.deepEqual(operatorsFor('BOOL'), ['EQ']);
  assert.ok(operatorsFor('STRING').includes('CONTAINS'));
  assert.ok(!operatorsFor('INTEGER').includes('CONTAINS'));
});

test('a draft becomes the rule the API expects', () => {
  const condition = newCondition('FIELDS', 2);
  condition.minCount = '10';
  condition.predicates = [
    { path: 'targets[*].kind', op: 'IN', text: '', text2: '', names: ['AIR', 'SURFACE'], bool: true },
    { path: 'targetCount', op: 'BETWEEN', text: ' 2 ', text2: '18446744073709551615', names: [], bool: true },
  ];
  const rule = ruleOf({ id: null, name: '  Busy  ', conditions: [condition, newCondition('COUNT', 2)] }, TREE);

  assert.equal(rule.name, 'Busy');
  assert.equal(rule.conditions[0].minCount, 10);
  assert.deepEqual(rule.conditions[0].predicates[0].values, [{ name: 'AIR' }, { name: 'SURFACE' }]);
  assert.deepEqual(rule.conditions[0].predicates[1].values, [{ number: '2' }, { number: '18446744073709551615' }], '64-bit stays exact');
  assert.deepEqual(rule.conditions[1].predicates, []);
});

test('a saved rule round-trips through the editor', () => {
  const saved: TagRule = { id: 'r1', name: 'Air', conditions: [{ kind: 'FIELDS', opcode: 2, minCount: 3, predicates: [
    { path: 'targetCount', op: 'IN', values: [{ number: 1 }, { number: 4 }] },
    { path: 'targets[*].kind', op: 'EQ', values: [{ name: 'AIR', number: 1 }] },
  ] }] };

  const again = ruleOf(draftOf(saved), TREE);

  assert.equal(again.id, 'r1');
  assert.deepEqual(again.conditions[0].predicates[0].values, [{ number: '1' }, { number: '4' }]);
  assert.deepEqual(again.conditions[0].predicates[1].values, [{ name: 'AIR' }]);
});

test('readable summary', () => {
  const rule: TagRule = { id: 'r', name: 'x', conditions: [
    { kind: 'FIELDS', opcode: 2, minCount: 10, predicates: [
      { path: 'targets[*].kind', op: 'EQ', values: [{ name: 'AIR' }] },
      { path: 'targetCount', op: 'GT', values: [{ number: 5 }] },
    ] },
    { kind: 'COUNT', opcode: 9, minCount: 1, predicates: [] },
  ] };

  assert.equal(conditionText(rule.conditions[0], TREE, WORDS), '≥10 × Track where targets[*].kind = AIR AND targetCount > 5');
  assert.equal(conditionText(rule.conditions[1], TREE, WORDS), '≥1 × 9 (0x9)', 'an opcode not in the ICD shows its number');
});
