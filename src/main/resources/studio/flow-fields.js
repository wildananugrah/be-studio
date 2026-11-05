// Field trees of a flow's data, for Studio's Schemas tab: from JSON schemas (gw:CODE references followed), example
// values and the JSON paths of mapping rules. No DOM: studio.js imports it and node --test checks it (src/test/js).

/**
 * @typedef {{ name: string, type: string, required: boolean|null, notes: string, children: Field[] }} Field
 *   type is '' when unknown; array items are a child named '[]'; required is null when nothing says.
 * @typedef {{ label: string|null, fields: Field[] }} Option   one alternative body; label null for a plain body
 * @typedef {{ options: Option[], problem?: string }} Shape
 */

const MAX_DEPTH = 8;
const field = (name, type, required, notes, children) =>
  ({ name, type: type || '', required: required === undefined ? null : required, notes: notes || '', children: children || [] });
/** Where nesting stops: deeper levels, or a reference cycle. */
const more = () => field('…', '', null, 'more levels (or a reference cycle)');

/** The tokens of a JSON path: $.a.b[*]['x-y'][0] → ['a', 'b', '*', 'x-y', 0]. */
export function toks(p) { const t = []; const s = String(p || '').replace(/^\$/, ''); const re = /\.([A-Za-z0-9_-]+)|\['([^']*)'\]|\[(\*|\d+)\]/g; let m; while ((m = re.exec(s))) t.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : (m[3] === '*' ? '*' : Number(m[3]))); return t; }

const parse = text => { try { return JSON.parse(text); } catch (e) { return undefined; } };
const at = (doc, pointer) => String(pointer || '').split('/').filter(Boolean)
  .map(k => k.replace(/~1/g, '/').replace(/~0/g, '~')).reduce((n, k) => (n == null ? n : n[k]), doc);

/**
 * The fields of a gw_json_schema (texts: schema text by code). A oneOf / anyOf at its root gives one option per
 * alternative, labelled with its gw: code, else its title, else "Option n"; never merged.
 * @returns {Shape}
 */
export function schemaShape(code, texts) {
  if (!Object.prototype.hasOwnProperty.call(texts, code)) return { options: [], problem: 'Schema ' + code + ' does not exist' };
  const doc = parse(texts[code]);
  if (doc === undefined) return { options: [], problem: 'Schema ' + code + ' is not valid JSON' };

  /** Follows $ref chains: gw:CODE[#/pointer] into that schema, #/pointer within the current document. */
  const deref = (node, d) => {
    for (let i = 0; i < 10 && node && typeof node === 'object' && typeof node.$ref === 'string'; i++) {
      const ref = node.$ref;
      if (ref.startsWith('gw:')) {
        const hash = ref.indexOf('#');
        d = parse(texts[ref.slice(3, hash < 0 ? undefined : hash)]);
        node = at(d, hash < 0 ? '' : ref.slice(hash + 1));
      } else if (ref.startsWith('#')) {
        node = at(d, ref.slice(1));
      } else {
        return { s: undefined, d };
      }
    }
    return { s: node && typeof node === 'object' && typeof node.$ref !== 'string' ? node : undefined, d };
  };
  const typeName = (s, d) => {
    let t = Array.isArray(s.type) ? s.type.find(x => x !== 'null') : s.type;
    if (!t && s.properties) t = 'object';
    if (!t && s.items) t = 'array';
    if (t !== 'array') return t || '';
    const items = deref(s.items, d).s;
    const it = items ? typeName(items, d) : '';
    return it ? 'array of ' + it : 'array';
  };
  const notes = s => [
    s.description,
    Array.isArray(s.enum) && 'one of ' + s.enum.map(v => (typeof v === 'string' ? v : JSON.stringify(v))).join(', '),
    s.format && 'format ' + s.format,
    s.const !== undefined && 'always ' + (typeof s.const === 'string' ? s.const : JSON.stringify(s.const)),
  ].filter(Boolean).join('; ');
  /** The properties of a schema (its own and its allOf parts'), as fields. */
  const fieldsOf = (node, d, depth) => {
    const { s, d: sd } = deref(node, d);
    if (!s) return [];
    if (depth > MAX_DEPTH) return [more()];
    const parts = [...(Array.isArray(s.allOf) ? s.allOf.map(p => deref(p, sd)).filter(p => p.s) : []), { s, d: sd }];
    const required = new Set(parts.flatMap(p => (Array.isArray(p.s.required) ? p.s.required : [])));
    const out = [];
    parts.forEach(p => Object.entries(p.s.properties || {}).forEach(([name, sub]) => {
      if (!out.some(f => f.name === name)) out.push(fieldOf(name, sub, p.d, required.has(name), depth));
    }));
    return out;
  };
  const fieldOf = (name, node, d, required, depth) => {
    const { s, d: sd } = deref(node, d);
    if (!s) return field(name, '', required);
    const choices = Array.isArray(s.oneOf) ? s.oneOf : Array.isArray(s.anyOf) ? s.anyOf : null;
    let children;
    if (depth + 1 > MAX_DEPTH) children = [more()];
    else if (choices) children = union(choices.map(c => fieldsOf(c, sd, depth + 1)));
    else children = typeName(s, sd).startsWith('array') ? itemRow(s, sd, depth + 1) : fieldsOf(s, sd, depth + 1);
    return field(name, choices ? 'one of' : typeName(s, sd), required, notes(s), children);
  };
  /** An array's item fields under a '[]' row (none when the items have no fields). */
  const itemRow = (s, d, depth) => {
    const { s: items, d: id } = deref(s.items, d);
    const fields = items ? fieldsOf(items, id, depth + 1) : [];
    return fields.length ? [field('[]', typeName(items, id), null, notes(items), fields)] : [];
  };
  const bodyFields = (node, d) => {
    const { s, d: sd } = deref(node, d);
    return s && typeName(s, sd).startsWith('array') ? itemRow(s, sd, 0) : fieldsOf(node, d, 0);
  };

  const { s: root, d: rootDoc } = deref(doc, doc);
  const choices = root && (Array.isArray(root.oneOf) ? root.oneOf : Array.isArray(root.anyOf) ? root.anyOf : null);
  if (!choices) return { options: [{ label: null, fields: bodyFields(doc, doc) }] };
  return {
    options: choices.map((c, i) => {
      const ref = c && typeof c.$ref === 'string' && c.$ref.startsWith('gw:') ? c.$ref.slice(3) : '';
      const target = deref(c, rootDoc).s;
      return { label: ref || (c && c.title) || (target && target.title) || 'Option ' + (i + 1), fields: bodyFields(c, rootDoc) };
    }),
  };
}

/** Fields of several alternatives in one list, each name once (the first wins). */
const union = lists => {
  const out = [];
  lists.flat().forEach(f => { if (!out.some(x => x.name === f.name)) out.push(f); });
  return out;
};

const valueType = v => (v === null || v === undefined ? '' : Array.isArray(v)
  ? (v.length && valueType(v[0]) ? 'array of ' + valueType(v[0]) : 'array') : typeof v === 'object' ? 'object' : typeof v);
const valueFields = (v, depth) => {
  if (depth > MAX_DEPTH) return [more()];
  if (Array.isArray(v)) { const items = v.length ? valueFields(v[0], depth + 1) : []; return items.length ? [field('[]', valueType(v[0]), null, '', items)] : []; }
  if (!v || typeof v !== 'object') return [];
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

/** Every path the flow reads: rule sources, body template placeholders, and ${...} in conditions and success checks. */
function reads(f) {
  const out = [];
  [...f.steps.flatMap(s => s.rules || []), ...(f.response || []), ...(f.responses || []).flatMap(r => r.rules || [])]
    .forEach(r => { if (r.source) out.push(r.source); });
  f.steps.forEach(s => (String(s.bodyTemplate || '').match(/\$\{[^}]*\}/g) || []).forEach(x => {
    const p = x.slice(2, -1).split(':')[0].trim();
    if (/^(request|steps)\./.test(p)) out.push('$.' + p);
  }));
  f.steps.forEach(s => [s.condition, s.success].forEach(e => (String(e || '').match(/\$\{([^}]+)\}/g) || [])
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
    let sends;
    if (String(s.bodyTemplate || '').trim()) {
      let value;
      try { value = JSON.parse(s.bodyTemplate.replace(/\$\{[^}]*\}/g, 'null')); } catch (e) { value = undefined; }
      sends = { ...rulesColumn('Sends', (s.rules || []).filter(r => r.type !== 'BODY'), s.id), source: { label: 'body template', scope: s.id },
        shape: value === undefined ? { options: [], problem: 'Body template is not valid JSON' } : exampleShape(value) };
    } else {
      sends = rulesColumn('Sends', s.rules || [], s.id);
    }
    let receives;
    const example = s.example ? (() => { try { return JSON.parse(s.example); } catch (e) { return undefined; } })() : undefined;
    if (s.respSchema) receives = schemaColumn('Receives', s.respSchema, texts);
    else if (example !== undefined) receives = { heading: 'Receives', source: { label: 'response example' }, params: [], shape: exampleShape(example) };
    else receives = { heading: 'Receives', source: { label: 'fields the flow reads' }, params: [], shape: pathsShape(under(read, ['steps', s.name, 'body']).map(pathOf)) };
    blocks.push({ key: s.id, title: s.order + ' · ' + s.name + ' → ' + s.target, detail: s.method + ' ' + s.path, columns: [sends, receives] });
  });

  const response = f.respSchema ? schemaColumn('Body', f.respSchema, texts) : rulesColumn('Body', (f.response || []).filter(r => r.type !== 'HEADER'), 'resp');
  response.params = rulesColumn('Body', (f.response || []).filter(r => r.type === 'HEADER'), 'resp').params;
  blocks.push({ key: 'response', title: 'Client response', detail: '', columns: [response] });
  (f.responses || []).forEach(r => blocks.push({ key: 'resp:' + r.id, title: "Response '" + r.name + "' · " + r.status, detail: r.description || '',
    columns: [rulesColumn('Body', r.rules || [], 'resp:' + r.id)] }));
  return blocks;
}
