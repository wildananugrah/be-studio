// Tests of studio/flow-fields.js (the Schemas tab's data). Run: node --test src/test/js/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { schemaShape, exampleShape, pathsShape } from '../../main/resources/studio/flow-fields.js';

const texts = {
  TRANSFER_REQUEST: '{"type":"object","required":["fromAccount","amount"],"properties":{"fromAccount":{"type":"string","description":"Debit account"},"amount":{"type":"number"}}}',
  TOPUP_REQUEST: '{"type":"object","required":["phoneNo"],"properties":{"phoneNo":{"type":"string"},"amount":{"type":"number"}}}',
  PAYMENT_REQUEST: '{"oneOf":[{"$ref":"gw:TRANSFER_REQUEST"},{"$ref":"gw:TOPUP_REQUEST"}]}',
  NODE: '{"type":"object","properties":{"name":{"type":"string"},"child":{"$ref":"gw:NODE"}}}',
  LIST: '{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string","enum":["A","B"]}}}}}}',
  BROKEN: '{not json',
};
const names = fields => fields.map(f => f.name);

test('plain schema gives one unlabelled option', () => {
  const s = schemaShape('TRANSFER_REQUEST', texts);
  assert.equal(s.options.length, 1);
  assert.equal(s.options[0].label, null);
  assert.deepEqual(s.options[0].fields.map(f => [f.name, f.type, f.required]), [['fromAccount', 'string', true], ['amount', 'number', true]]);
  assert.match(s.options[0].fields[0].notes, /Debit account/);
});

test('router oneOf gives one option per schema', () => {
  const s = schemaShape('PAYMENT_REQUEST', texts);
  assert.deepEqual(s.options.map(o => o.label), ['TRANSFER_REQUEST', 'TOPUP_REQUEST']);
  assert.deepEqual(names(s.options[1].fields), ['phoneNo', 'amount']);
  assert.equal(s.options[1].fields[1].required, false);
});

test('arrays nest their item fields under []', () => {
  const items = schemaShape('LIST', texts).options[0].fields[0];
  assert.equal(items.type, 'array of object');
  assert.equal(items.children[0].name, '[]');
  assert.equal(items.children[0].children[0].name, 'sku');
  assert.match(items.children[0].children[0].notes, /one of A, B/);
});

test('cycles end in an ellipsis row', () => {
  let f = schemaShape('NODE', texts).options[0].fields.find(x => x.name === 'child');
  let depth = 0;
  while (f.children.some(c => c.name === 'child')) { f = f.children.find(c => c.name === 'child'); depth++; }
  assert.ok(depth < 10);
  assert.ok(JSON.stringify(schemaShape('NODE', texts)).includes('"name":"…"'));
});

test('missing or invalid schema gives a problem', () => {
  assert.match(schemaShape('NOPE', texts).problem, /NOPE/);
  assert.match(schemaShape('BROKEN', texts).problem, /BROKEN/);
  assert.deepEqual(schemaShape('BROKEN', texts).options, []);
});

test('allOf merges its parts and its own properties; local refs are followed', () => {
  const t = { M: '{"$defs":{"money":{"type":"number"}},"allOf":[{"properties":{"a":{"type":"string"}},"required":["a"]}],"properties":{"b":{"$ref":"#/$defs/money"}}}' };
  assert.deepEqual(schemaShape('M', t).options[0].fields.map(f => [f.name, f.type, f.required]), [['a', 'string', true], ['b', 'number', false]]);
});

test('example values give types', () => {
  const s = exampleShape({ responseCode: '00', balance: 10.5, ok: true, tags: ['a'], owner: { name: 'x' } });
  assert.deepEqual(s.options[0].fields.map(f => [f.name, f.type]),
    [['responseCode', 'string'], ['balance', 'number'], ['ok', 'boolean'], ['tags', 'array of string'], ['owner', 'object']]);
  assert.equal(s.options[0].fields[4].children[0].name, 'name');
});

test('paths build a tree', () => {
  const s = pathsShape(['$.customer.name', '$.items[*].sku', "$['x-y']", '$', '$.customer.id'], new Set(['$.customer.id']));
  const [customer, items] = s.options[0].fields;
  assert.deepEqual(names(s.options[0].fields), ['customer', 'items', 'x-y']);
  assert.deepEqual(names(customer.children), ['name', 'id']);
  assert.equal(customer.children[1].required, true);
  assert.equal(items.children[0].name, '[]');
  assert.equal(items.children[0].children[0].name, 'sku');
});
