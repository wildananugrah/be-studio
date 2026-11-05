// Tests of studio/flow-fields.js (the Schemas tab's data). Run: node --test "src/test/js/*.test.mjs"
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { schemaShape, exampleShape, pathsShape, flowOverview } from '../../main/resources/studio/flow-fields.js';

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

// ---- flowOverview: the blocks of the tab

const rule = (type, target, source = '', extra = {}) => ({ type, target, source, constant: '', required: false, ...extra });
const flow = {
  reqSchema: '', respSchema: '', path: '/accounts/{accountNo}/debit',
  steps: [
    { id: 's1', order: 1, name: 'debit', target: 'CORE', method: 'POST', path: '/debit/{acc}', bodyTemplate: '', respSchema: '', example: '{"responseCode":"00","balance":1}',
      condition: '', success: "${steps.debit.body.responseCode} == '00'",
      rules: [rule('PATH', 'acc', '$.request.path.accountNo'), rule('BODY', '$.amount', '$.request.body.amount', { required: true }), rule('HEADER', 'X-Channel', '$.request.headers.x-channel')] },
    { id: 's2', order: 2, name: 'notify', target: 'NOTIFY', method: 'POST', path: '/n', bodyTemplate: '{"to": ${request.body.phone}, "text": "${steps.debit.body.balance}"}', respSchema: '', example: '', condition: '', success: '', rules: [] },
    { id: 's3', order: 3, name: 'shape', target: 'TRANSFORM', method: '', path: '', bodyTemplate: '', condition: '', success: '', rules: [rule('BODY', '$.x', '$.steps.debit.body.balance')] },
  ],
  response: [rule('STATUS', 'status', '$.steps.debit.status'), rule('BODY', '$.balance', '$.steps.debit.body.balance'), rule('HEADER', 'X-Ref', '$.steps.debit.body.ref')],
  responses: [{ id: 'o1', name: 'refused', status: '422', rules: [rule('BODY', '$.reason', '$.steps.debit.body.message')] }],
};
const external = s => !['TRANSFORM', 'CONDITION'].includes(s.target);
const blocks = () => flowOverview(flow, { texts, isExternal: external });

test('blocks follow the pipeline and skip non-external steps', () => {
  assert.deepEqual(blocks().map(b => b.key), ['request', 's1', 's2', 'response', 'resp:o1']);
  assert.equal(blocks()[1].title, '1 · debit → CORE');
  assert.equal(blocks()[1].detail, 'POST /debit/{acc}');
});

test('steps are ordered by order, parallel steps keep their array order', () => {
  const steps = [{ ...flow.steps[1], id: 'b', order: 2 }, { ...flow.steps[0], id: 'a1', order: 1 }, { ...flow.steps[0], id: 'a2', order: 1 }];
  assert.deepEqual(flowOverview({ ...flow, steps }, { texts, isExternal: external }).map(b => b.key).slice(1, 4), ['a1', 'a2', 'b']);
});

test('client request without schema lists the fields the flow reads', () => {
  const [col] = blocks()[0].columns;
  assert.equal(col.heading, 'Body');
  assert.equal(col.source.label, 'fields the flow reads');
  assert.deepEqual(col.shape.options[0].fields.map(f => f.name), ['amount', 'phone']);
  assert.deepEqual(col.params, [{ type: 'PATH', names: ['accountNo'] }, { type: 'HEADER', names: ['x-channel'] }]);
});

test('client request with a schema uses it', () => {
  const col = flowOverview({ ...flow, reqSchema: 'PAYMENT_REQUEST' }, { texts, isExternal: external })[0].columns[0];
  assert.deepEqual(col.source, { label: 'PAYMENT_REQUEST', schema: 'PAYMENT_REQUEST' });
  assert.equal(col.shape.options.length, 2);
});

test('a step sends its rules and receives its example', () => {
  const [sends, receives] = blocks()[1].columns;
  assert.deepEqual([sends.heading, receives.heading], ['Sends', 'Receives']);
  assert.deepEqual(sends.source, { label: 'mapping rules', scope: 's1' });
  assert.deepEqual(sends.shape.options[0].fields.map(f => [f.name, f.required]), [['amount', true]]);
  assert.deepEqual(sends.params, [{ type: 'PATH', names: ['acc'] }, { type: 'HEADER', names: ['X-Channel'] }]);
  assert.equal(receives.source.label, 'response example');
  assert.deepEqual(receives.shape.options[0].fields.map(f => f.name), ['responseCode', 'balance']);
});

test('a step with a response schema receives it', () => {
  const steps = [{ ...flow.steps[0], respSchema: 'TOPUP_REQUEST' }];
  const receives = flowOverview({ ...flow, steps }, { texts, isExternal: external })[1].columns[1];
  assert.deepEqual(receives.source, { label: 'TOPUP_REQUEST', schema: 'TOPUP_REQUEST' });
});

test('body template keys with unquoted placeholders', () => {
  const [sends, receives] = blocks()[2].columns;
  assert.deepEqual(sends.source, { label: 'body template', scope: 's2' });
  assert.deepEqual(sends.shape.options[0].fields.map(f => f.name), ['to', 'text']);
  assert.equal(receives.source.label, 'fields the flow reads');
  assert.deepEqual(receives.shape.options[0].fields, []);
});

test('an unparsable body template is a problem', () => {
  const steps = [{ ...flow.steps[1], bodyTemplate: '<xml>${request.body.x}</xml>' }];
  assert.equal(flowOverview({ ...flow, steps }, { texts, isExternal: external })[1].columns[0].shape.problem, 'Body template is not valid JSON');
});

test('receives without schema or example lists what later rules read of that step', () => {
  const steps = [{ ...flow.steps[0], example: '' }];
  const receives = flowOverview({ ...flow, steps }, { texts, isExternal: external })[1].columns[1];
  assert.equal(receives.source.label, 'fields the flow reads');
  assert.deepEqual(receives.shape.options[0].fields.map(f => f.name).sort(), ['balance', 'message', 'ref', 'responseCode']);
});

test('client response ignores STATUS rules', () => {
  const [col] = blocks()[3].columns;
  assert.deepEqual(col.source, { label: 'mapping rules', scope: 'resp' });
  assert.deepEqual(col.shape.options[0].fields.map(f => f.name), ['balance']);
  assert.deepEqual(col.params, [{ type: 'HEADER', names: ['X-Ref'] }]);
  assert.equal(blocks()[4].title, "Response 'refused' · 422");
  assert.deepEqual(blocks()[4].columns[0].source, { label: 'mapping rules', scope: 'resp:o1' });
});
