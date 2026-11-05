// Field trees of a flow's data, for Studio's Schemas tab: from JSON schemas (gw:CODE references followed), example
// values and the JSON paths of mapping rules. No DOM: studio.js imports it and node --test checks it (src/test/js).

/**
 * @typedef {{ name: string, type: string, required: boolean|null, notes: string, children: Field[] }} Field
 *   type is '' when unknown; array items are a child named '[]'; required is null when nothing says.
 * @typedef {{ label: string|null, fields: Field[], problem?: string }} Option   one alternative body; label null for a plain body
 * @typedef {{ options: Option[], problem?: string, note?: string }} Shape   problem: broken; note: neutral remark
 */

const MAX_DEPTH = 8;
const field = (name, type, required, notes, children) =>
  ({ name, type: type || '', required: required === undefined ? null : required, notes: notes || '', children: children || [] });
/** Where nesting stops: deeper levels, or a reference cycle. */
const more = (why = 'more levels') => field('…', '', null, why);
const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
const isObject = v => v !== null && typeof v === 'object' && !Array.isArray(v);

/** The tokens of a JSON path: $.a.b[*]['x-y'][0] → ['a', 'b', '*', 'x-y', 0]. */
export function toks(p) { const t = []; const s = String(p || '').replace(/^\$/, ''); const re = /\.([A-Za-z0-9_-]+)|\['([^']*)'\]|\[(\*|\d+)\]/g; let m; while ((m = re.exec(s))) t.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : (m[3] === '*' ? '*' : Number(m[3]))); return t; }

const parse = text => { try { return JSON.parse(text); } catch (e) { return undefined; } };
const at = (doc, pointer) => String(pointer || '').split('/').filter(Boolean)
  .map(k => k.replace(/~1/g, '/').replace(/~0/g, '~')).reduce((n, k) => (n == null ? undefined : n[k]), doc);
const notesOf = s => [
  s.description,
  Array.isArray(s.enum) && 'one of ' + s.enum.map(v => (typeof v === 'string' ? v : JSON.stringify(v))).join(', '),
  s.format && 'format ' + s.format,
  s.const !== undefined && 'always ' + (typeof s.const === 'string' ? s.const : JSON.stringify(s.const)),
].filter(Boolean).join('; ');

/**
 * The fields of a gw_json_schema (texts: schema text by code). A oneOf / anyOf at its root gives one option per
 * alternative (labelled with its gw: code, else its title, else "Option n"), each with the root's own properties too;
 * never merged. allOf and $ref siblings are merged at every level; a reference cycle stops with a "…" row where it
 * repeats; missing references are shown, not dropped.
 * @returns {Shape}
 */
export function schemaShape(code, texts) {
  if (!has(texts, code)) return { options: [], problem: 'Schema ' + code + ' does not exist' };
  // parsed once per code, so the same schema is the same object (cycles are found by identity)
  const docs = new Map();
  const docOf = c => {
    if (!docs.has(c)) docs.set(c, has(texts, c) ? (parse(texts[c]) === undefined ? null : parse(texts[c])) : undefined);
    return docs.get(c);
  };
  const doc = docOf(code);
  if (doc === null) return { options: [], problem: 'Schema ' + code + ' is not valid JSON' };

  /** Follows a $ref chain to a schema object: { s, d } or { problem }. */
  const deref = (node, d) => {
    const seen = new Set();
    while (isObject(node) && typeof node.$ref === 'string') {
      if (seen.has(node)) return { problem: 'reference cycle' };
      seen.add(node);
      const ref = node.$ref;
      if (ref.startsWith('gw:')) {
        const hash = ref.indexOf('#');
        const c = ref.slice(3, hash < 0 ? undefined : hash);
        const target = docOf(c);
        if (target === undefined) return { problem: 'gw:' + c + ' not found' };
        if (target === null) return { problem: 'gw:' + c + ' is not valid JSON' };
        d = target;
        node = at(target, hash < 0 ? '' : ref.slice(hash + 1));
      } else if (ref.startsWith('#')) {
        node = at(d, ref.slice(1));
      } else {
        return { problem: ref + ' is not followed' };
      }
      if (node === undefined) return { problem: ref + ' points to nothing' };
    }
    return isObject(node) ? { s: node, d } : { problem: null }; // a boolean schema: nothing to show, nothing wrong
  };
  /** The schema objects a node stands for: its $ref target's, its allOf parts', then itself (when it says more than $ref). */
  const partsOf = (node, d, visiting) => {
    if (!isObject(node) || visiting.has(node)) return [];
    visiting.add(node);
    const out = [];
    if (typeof node.$ref === 'string') {
      const r = deref(node, d);
      if (r.s) out.push(...partsOf(r.s, r.d, visiting));
      else if (r.problem) out.push({ problem: r.problem });
    }
    (Array.isArray(node.allOf) ? node.allOf : []).forEach(p => out.push(...partsOf(p, d, visiting)));
    if (typeof node.$ref !== 'string' || Object.keys(node).some(k => k !== '$ref')) out.push({ s: node, d });
    return out;
  };
  /** What a node says, merged over its parts. */
  const info = (node, d) => {
    const m = { type: '', props: [], required: new Set(), items: null, choices: null, notes: isObject(node) ? notesOf(node) : '', problem: '', schemas: [] };
    partsOf(node, d, new Set()).forEach(p => {
      if (!p.s) { m.problem = m.problem || p.problem; return; }
      const s = p.s;
      m.schemas.push(s);
      const t = Array.isArray(s.type) ? s.type.find(x => x !== 'null') : s.type;
      if (t && !m.type) m.type = t;
      Object.entries(isObject(s.properties) ? s.properties : {}).forEach(([n, sub]) => { if (!m.props.some(x => x[0] === n)) m.props.push([n, sub, p.d]); });
      (Array.isArray(s.required) ? s.required : []).forEach(r => m.required.add(r));
      if (isObject(s.items) && !m.items) m.items = { node: s.items, d: p.d };
      const ch = Array.isArray(s.oneOf) ? s.oneOf : Array.isArray(s.anyOf) ? s.anyOf : null;
      if (ch && !m.choices) m.choices = ch.map(c => ({ node: c, d: p.d }));
      if (!m.notes) m.notes = notesOf(s);
    });
    if (!m.type && m.props.length) m.type = 'object';
    if (!m.type && m.items) m.type = 'array';
    return m;
  };
  const typeName = m => {
    if (m.choices) return m.props.length ? 'object, one of' : 'one of';
    if (m.type !== 'array') return m.type;
    const it = m.items ? info(m.items.node, m.items.d).type : '';
    return it ? 'array of ' + it : 'array';
  };
  const cyclic = (m, stack) => m.schemas.some(s => stack.has(s));
  const inside = (m, stack) => new Set([...stack, ...m.schemas]);

  /** The fields under a node: its properties, an array's item row, and the union of its oneOf / anyOf options. */
  const childrenOf = (m, depth, stack) => {
    if (depth > MAX_DEPTH) return [more()];
    if (m.type === 'array' && m.items && !m.props.length) return itemRow(m.items, depth, stack);
    const own = m.props.map(([name, sub, d]) => fieldOf(name, sub, d, m.required.has(name), depth, stack));
    if (!m.choices) return own;
    const lists = m.choices.map(c => { const cm = info(c.node, c.d); return cyclic(cm, stack) ? [more('reference cycle')] : childrenOf(cm, depth, inside(cm, stack)); });
    const union = [];
    lists.flat().forEach(f => { if (!own.some(o => o.name === f.name) && !union.some(u => u.name === f.name)) union.push({ ...f }); });
    // M only when every option requires it
    union.forEach(f => { if (f.required !== null) f.required = lists.every(l => l.some(x => x.name === f.name && x.required === true)); });
    return [...own, ...union];
  };
  const fieldOf = (name, node, d, required, depth, stack) => {
    const m = info(node, d);
    if (!m.schemas.length && m.problem) return field(name, '⚠ ' + m.problem, required);
    const children = cyclic(m, stack) ? (m.props.length || m.items || m.choices ? [more('reference cycle')] : [])
      : childrenOf(m, depth + 1, inside(m, stack));
    return field(name, typeName(m), required, m.notes, children);
  };
  /** An array's item fields under a '[]' row (none when the items have no fields). */
  const itemRow = (items, depth, stack) => {
    const m = info(items.node, items.d);
    const fields = cyclic(m, stack) ? [more('reference cycle')] : childrenOf(m, depth + 1, inside(m, stack));
    return fields.length ? [field('[]', typeName(m), null, m.notes, fields)] : [];
  };

  const root = info(doc, doc);
  const stack = new Set(root.schemas);
  if (!root.schemas.length) return { options: [], problem: root.problem ? 'Schema ' + code + ': ' + root.problem : undefined };
  if (!root.choices) return { options: [{ label: null, fields: childrenOf(root, 0, stack) }] };
  const shared = childrenOf({ ...root, choices: null }, 0, stack);
  return {
    options: root.choices.map((c, i) => {
      const m = info(c.node, c.d);
      const ref = isObject(c.node) && typeof c.node.$ref === 'string' && c.node.$ref.startsWith('gw:') ? c.node.$ref.slice(3) : '';
      const title = (isObject(c.node) && c.node.title) || (m.schemas.find(s => s.title) || {}).title;
      const own = cyclic(m, stack) ? [more('reference cycle')] : childrenOf(m, 0, inside(m, stack));
      const option = { label: ref || title || 'Option ' + (i + 1), fields: [...shared, ...own.filter(f => !shared.some(x => x.name === f.name))] };
      if (!m.schemas.length && m.problem) option.problem = m.problem;
      return option;
    }),
  };
}

const valueType = v => (v === null || v === undefined ? '' : Array.isArray(v)
  ? (v.length && valueType(v[0]) ? 'array of ' + valueType(v[0]) : 'array') : typeof v === 'object' ? 'object' : typeof v);
const valueFields = (v, depth) => {
  if (depth > MAX_DEPTH) return [more()];
  if (Array.isArray(v)) {
    // the fields of every object item, not just the first
    const item = v.some(isObject) ? Object.assign({}, ...v.filter(isObject)) : v[0];
    const items = v.length ? valueFields(item, depth + 1) : [];
    return items.length ? [field('[]', valueType(item), null, '', items)] : [];
  }
  if (!isObject(v)) return [];
  return Object.entries(v).map(([k, x]) => field(k, valueType(x), null, '', valueFields(x, depth + 1)));
};

/** The fields of an example value (e.g. a step's response example), typed from the values. @returns {Shape} */
export function exampleShape(value) {
  return { options: [{ label: null, fields: valueFields(value, 0) }] };
}

/**
 * A tree of JSON paths relative to a body ($.customer.name, $.items[*].sku, $['x-y']); `$` alone adds nothing. Paths in
 * `required` are marked required. @returns {Shape}
 */
export function pathsShape(paths, required = new Set()) {
  const root = [];
  paths.forEach(p => {
    const t = toks(p);
    let list = root;
    t.forEach((k, i) => {
      const name = k === '*' || typeof k === 'number' ? '[]' : k;
      let f = list.find(x => x.name === name);
      if (!f) { f = field(name, '', null); list.push(f); }
      if (i === t.length - 1 && required.has(p)) f.required = true;
      list = f.children;
    });
  });
  return { options: [{ label: null, fields: root }] };
}

/**
 * @typedef {{ heading: string, source: { label: string, schema?: string, scope?: string },
 *   params: { type: 'PATH'|'QUERY'|'HEADER', names: string[] }[], shape: Shape }} Column
 *   source.schema: the schema to open; source.scope: the Mapping tab scope to open ('resp', 'resp:<id>' or a step id)
 * @typedef {{ key: string, title: string, detail: string, columns: Column[] }} Block
 */

const PARAMS = ['PATH', 'QUERY', 'HEADER'];
/** A JSON path from tokens: ['a', '*', 'x-y'] → $.a[*].x-y (names other than letters, digits, _ and - in ['...']). */
const pathOf = t => '$' + t.map(k => (typeof k === 'number' ? '[' + k + ']' : k === '*' ? '[*]'
  : /^[A-Za-z0-9_-]+$/.test(k) ? '.' + k : "['" + k + "']")).join('');
const unique = list => [...new Set(list)];
const params = byType => PARAMS.filter(t => byType[t] && byType[t].length).map(type => ({ type, names: unique(byType[type]) }));
/** The reserved named response that is never sent to the client (it feeds additionalInfo of SNAP responses). */
const INFO_RESPONSE = 'additionalInfo';

/** Every path the flow reads: rule sources, body template placeholders, ${...} in conditions, cases and success checks. */
function reads(f) {
  const out = [];
  [...f.steps.flatMap(s => s.rules || []), ...(f.response || []), ...(f.responses || []).flatMap(r => r.rules || [])]
    .forEach(r => { if (r.source) out.push(r.source); });
  f.steps.forEach(s => (String(s.bodyTemplate || '').match(/\$\{[^}]*\}/g) || []).forEach(x => {
    const p = x.slice(2, -1).split(':')[0].trim();
    if (/^(request|steps)\./.test(p)) out.push('$.' + p);
  }));
  f.steps.forEach(s => [s.condition, s.success, ...(s.cases || []).map(c => c.when)].forEach(e => (String(e || '').match(/\$\{([^}]+)\}/g) || [])
    .forEach(x => out.push('$.' + x.slice(2, -1).trim()))));
  return out;
}

/** The read paths under a prefix (['request', 'body']), relative to it. */
const under = (paths, prefix) => paths.map(toks)
  .filter(t => t.length >= prefix.length && prefix.every((k, i) => t[i] === k)).map(t => t.slice(prefix.length));

const schemaColumn = (heading, code, texts) => ({ heading, source: { label: code, schema: code }, params: [], shape: schemaShape(code, texts) });

/** A body written by rules: BODY targets as a tree (required from the rule), other types as parameter lines. */
function rulesColumn(heading, rules, scope) {
  const body = rules.filter(r => r.type === 'BODY' && r.target);
  const byType = {};
  rules.filter(r => PARAMS.includes(r.type) && r.target).forEach(r => (byType[r.type] = byType[r.type] || []).push(r.target));
  return { heading, source: { label: 'mapping rules', scope }, params: params(byType),
    shape: pathsShape(body.map(r => r.target), new Set(body.filter(r => r.required).map(r => r.target))) };
}

/** What a body template sends: a JSON template's keys; XML / SOAP and text templates get a note (fields not shown). */
function templateShape(template) {
  const t = String(template).trim();
  if (!/^[[{]/.test(t)) return { options: [], note: 'XML or text template: its fields are not shown here' };
  const value = parse(t.replace(/\$\{[^}]*\}/g, 'null'));
  return value === undefined ? { options: [], problem: 'Body template is not valid JSON' } : exampleShape(value);
}

/**
 * The blocks of a flow's Schemas tab, in pipeline order: the client request, one per external call (isExternal(step))
 * with what it sends and receives, the client response and each named response.
 * @param f a Studio draft flow; texts: schema text by code
 * @returns {Block[]}
 */
export function flowOverview(f, { texts, isExternal }) {
  const read = reads(f);
  const blocks = [];

  const pathVars = (String(f.path || '').match(/\{([^}]+)\}/g) || []).map(v => v.slice(1, -1).split(':')[0].replace(/^\*/, ''));
  const requestParams = params({
    PATH: [...pathVars, ...under(read, ['request', 'path']).filter(t => t.length).map(t => String(t[0]))],
    QUERY: under(read, ['request', 'query']).filter(t => t.length).map(t => String(t[0])),
    HEADER: under(read, ['request', 'headers']).filter(t => t.length).map(t => String(t[0])),
  });
  const request = f.reqSchema ? schemaColumn('Body', f.reqSchema, texts)
    : { heading: 'Body', source: { label: 'fields the flow reads' }, params: [], shape: pathsShape(under(read, ['request', 'body']).map(pathOf)) };
  blocks.push({ key: 'request', title: 'Client request', detail: '', columns: [{ ...request, params: requestParams }] });

  [...f.steps].filter(isExternal).sort((a, b) => Number(a.order || 0) - Number(b.order || 0)).forEach(s => {
    const sends = String(s.bodyTemplate || '').trim()
      ? { ...rulesColumn('Sends', (s.rules || []).filter(r => r.type !== 'BODY'), s.id), source: { label: 'body template', scope: s.id }, shape: templateShape(s.bodyTemplate) }
      : rulesColumn('Sends', s.rules || [], s.id);
    let receives;
    const example = s.example ? parse(s.example) : undefined;
    if (s.respSchema) receives = schemaColumn('Receives', s.respSchema, texts);
    else if (example !== undefined) receives = { heading: 'Receives', source: { label: 'response example' }, params: [], shape: exampleShape(example) };
    else receives = { heading: 'Receives', source: { label: 'fields the flow reads' }, params: [], shape: pathsShape(under(read, ['steps', s.name, 'body']).map(pathOf)) };
    blocks.push({ key: s.id, title: s.order + ' · ' + s.name + ' → ' + s.target,
      detail: s.method + ' ' + s.path + (s.enabled === false ? ' · disabled' : ''), columns: [sends, receives] });
  });

  const response = f.respSchema ? schemaColumn('Body', f.respSchema, texts) : rulesColumn('Body', (f.response || []).filter(r => r.type !== 'HEADER'), 'resp');
  response.params = rulesColumn('Body', (f.response || []).filter(r => r.type === 'HEADER'), 'resp').params;
  blocks.push({ key: 'response', title: 'Client response', detail: '', columns: [response] });
  (f.responses || []).filter(r => r.name !== INFO_RESPONSE).forEach(r => blocks.push({ key: 'resp:' + r.id,
    title: "Response '" + r.name + "' · " + r.status, detail: r.description || '', columns: [rulesColumn('Body', r.rules || [], 'resp:' + r.id)] }));
  return blocks;
}
