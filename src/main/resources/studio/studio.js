// Gateway Studio: edits gw_flow, gw_flow_step, gw_mapping_rule, gw_target_system(_header) and gw_lookup_entry
// through /studio/api (StudioController). Everything is edited in memory; "Save & reload" writes it in one go.
import { html, render, Component } from './vendor/htm-preact.js';

const C = { in: 'oklch(0.56 0.13 255)', call: 'oklch(0.62 0.17 42)', step: 'oklch(0.53 0.15 300)', out: 'oklch(0.55 0.12 160)', err: 'oklch(0.57 0.19 25)', flow: 'oklch(0.45 0.02 260)', java: 'oklch(0.7 0.13 85)' };
const MC = { GET: 'oklch(0.52 0.11 160)', POST: 'oklch(0.6 0.17 42)', PUT: 'oklch(0.52 0.13 255)', PATCH: 'oklch(0.5 0.15 300)', DELETE: 'oklch(0.55 0.19 25)' };
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const MONO = "'Geist Mono',ui-monospace,Menlo,monospace";
const TOKEN_KEY = 'gw.studio.token';

let seq = 0;
const uid = p => p + (++seq);
const clone = v => JSON.parse(JSON.stringify(v));
const str = v => (v === null || v === undefined ? '' : String(v));
const int = v => (str(v).trim() === '' ? null : parseInt(v, 10));
const nn = v => (v === '' ? null : v);
const storage = {
  get(k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch (e) { /* private mode */ } }
};

// ---------- API document <-> editor model ----------
const ruleIn = r => ({ id: uid('r'), type: r.type || 'BODY', target: str(r.target), source: str(r.source), constant: str(r.constant), def: str(r.defaultValue), lookup: str(r.lookup), conv: str(r.converter), fh: str(r.fieldHandler), required: !!r.required });
const ruleOut = r => ({ type: r.type, target: r.target, source: nn(r.source), constant: r.source ? null : nn(r.constant), defaultValue: nn(r.def), lookup: nn(r.lookup), converter: nn(r.conv), fieldHandler: nn(r.fh), required: r.required });

function fromApi(c) {
  const lookups = {};
  c.lookups.forEach(l => { lookups[l.code] = l.entries.map(e => ({ src: str(e.source), tgt: str(e.target) })); });
  return {
    flows: c.flows.map(f => ({
      code: str(f.code), name: str(f.name), method: f.method || 'GET', path: str(f.path), reqSchema: str(f.requestSchema), respSchema: str(f.responseSchema),
      reqHandler: str(f.requestHandler), respHandler: str(f.responseHandler), errorHandler: str(f.errorHandler), successStatus: str(f.successStatus || 200),
      timeout: str(f.timeoutMs), audit: f.auditMode || 'INHERIT', enabled: f.enabled,
      steps: f.steps.map(s => ({ id: uid('s'), name: str(s.name), order: s.order, target: str(s.targetSystem), method: s.method || 'GET', path: str(s.path), onFailure: s.onFailure || 'STOP',
        timeout: str(s.timeoutMs), condition: str(s.condition), success: str(s.success), reqHandler: str(s.requestHandler), respHandler: str(s.responseHandler),
        respSchema: str(s.responseSchema), bodyCodec: str(s.bodyCodec), enabled: s.enabled, rules: s.rules.map(ruleIn) })),
      response: f.response.map(ruleIn)
    })),
    targets: c.targets.map(t => ({ code: str(t.code), base: str(t.baseUrl), connect: str(t.connectTimeoutMs), read: str(t.readTimeoutMs), bodyCodec: str(t.bodyCodec), enabled: t.enabled, headers: t.headers.map(h => ({ n: str(h.name), v: str(h.value) })),
      tls: { mode: (t.tls && t.tls.mode) || 'VERIFY', trust: str(t.tls && t.tls.trustStore), trustPw: str(t.tls && t.tls.trustStorePassword), key: str(t.tls && t.tls.keyStore), keyPw: str(t.tls && t.tls.keyStorePassword) } })),
    lookups,
    schemas: (c.schemas || []).map(x => ({ code: str(x.code), desc: str(x.description), text: str(x.text) }))
  };
}

function toApi(cfg) {
  return {
    flows: cfg.flows.map(f => ({
      code: f.code, name: nn(f.name), method: f.method, path: f.path, requestSchema: nn(f.reqSchema), responseSchema: nn(f.respSchema), requestHandler: nn(f.reqHandler),
      responseHandler: nn(f.respHandler), errorHandler: nn(f.errorHandler), successStatus: int(f.successStatus) || 200, timeoutMs: int(f.timeout), auditMode: f.audit, enabled: f.enabled,
      steps: [...f.steps].sort((a, b) => a.order - b.order).map(s => ({ name: s.name, order: s.order, targetSystem: s.target, method: s.method, path: s.path, condition: nn(s.condition), success: nn(s.success),
        onFailure: s.onFailure, timeoutMs: int(s.timeout), responseSchema: nn(s.respSchema), requestHandler: nn(s.reqHandler), responseHandler: nn(s.respHandler), bodyCodec: nn(s.bodyCodec),
        enabled: s.enabled, rules: s.rules.map(ruleOut) })),
      response: f.response.map(ruleOut)
    })),
    targets: cfg.targets.map(t => ({ code: t.code, baseUrl: t.base, connectTimeoutMs: int(t.connect), readTimeoutMs: int(t.read), bodyCodec: nn(t.bodyCodec), enabled: t.enabled !== false, headers: t.headers.map(h => ({ name: h.n, value: h.v })),
      tls: tlsOut(t.tls) })),
    lookups: Object.keys(cfg.lookups).map(code => ({ code, entries: cfg.lookups[code].map(r => ({ source: r.src, target: r.tgt })) })),
    schemas: cfg.schemas.map(x => ({ code: x.code, description: nn(x.desc), text: x.text }))
  };
}

const TLS0 = { mode: 'VERIFY', trust: '', trustPw: '', key: '', keyPw: '' };
/** VERIFY sends nothing; INSECURE no stores; CUSTOM its stores. */
function tlsOut(t) {
  const x = t || TLS0;
  if (x.mode === 'CUSTOM') return { mode: 'CUSTOM', trustStore: nn(x.trust), trustStorePassword: nn(x.trustPw), keyStore: nn(x.key), keyStorePassword: nn(x.keyPw) };
  return { mode: x.mode === 'INSECURE' ? 'INSECURE' : null, trustStore: null, trustStorePassword: null, keyStore: null, keyStorePassword: null };
}

// ---------- JSON paths and sample data ----------
function toks(p) { const t = []; const s = String(p || '').replace(/^\$/, ''); const re = /\.([A-Za-z0-9_-]+)|\['([^']*)'\]|\[(\*|\d+)\]/g; let m; while ((m = re.exec(s))) t.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : (m[3] === '*' ? '*' : Number(m[3]))); return t; }
function place(root, path, value) {
  const t = toks(path); if (!t.length || !['request', 'steps'].includes(t[0])) return;
  let o = root;
  for (let i = 0; i < t.length; i++) {
    const k = t[i] === '*' ? 0 : t[i]; const last = i === t.length - 1;
    if (last) { if (o[k] === undefined) o[k] = value; return; }
    const nextArr = t[i + 1] === '*' || typeof t[i + 1] === 'number';
    if (o[k] == null || typeof o[k] !== 'object') o[k] = nextArr ? [] : {};
    o = o[k];
  }
}
function datefmt(pattern) {
  const d = new Date(); const p = n => String(n).padStart(2, '0');
  const v = { yyyy: d.getFullYear(), MM: p(d.getMonth() + 1), dd: p(d.getDate()), HH: '09', mm: '30', ss: '00' };
  return pattern.replace(/yyyy|MM|dd|HH|mm|ss|'([^']*)'/g, (tk, q) => (q !== undefined ? q : v[tk]));
}
function guess(key, rule, lookups) {
  const conv = rule && rule.conv || '';
  if (/^DATE_FORMAT:/.test(conv)) return datefmt(conv.split(/(?<!\\):/)[1] || 'yyyyMMdd');
  if (/^(TO_NUMBER|DECIMAL_SCALE)/.test(conv)) return '1500.00';
  if (/^TO_BOOLEAN/.test(conv)) return 'Y';
  if (rule && rule.lookup && lookups[rule.lookup]) { const e = lookups[rule.lookup].find(r => r.src !== '*'); if (e) return e.src; }
  if (/amount|amt|bal/i.test(key)) return '1500.00';
  if (/date|dt$/i.test(key)) return datefmt('yyyyMMdd');
  if (/name/i.test(key)) return 'BUDI SANTOSO';
  if (/^ref|ref(no)?$/i.test(key)) return 'REF' + datefmt('yyyyMMdd') + '0001';
  if (/(responsecode|rc)$/i.test(key)) return '00';
  if (/acc(t|ount)?(no|number)?$/i.test(key)) return '1001';
  return 'sample-' + key;
}
/** A context with a value at every path the flow's rules and expressions read. */
function synthSample(f, lookups) {
  const ctx = { request: { headers: {}, path: {}, query: {}, body: {} }, steps: {} };
  (f.path.match(/\{([^}]+)\}/g) || []).forEach(v => { const k = v.slice(1, -1); ctx.request.path[k] = guess(k, null, lookups); });
  f.steps.forEach(s => { ctx.steps[s.name] = { outcome: 'SUCCESS', status: 200, headers: {}, body: {} }; });
  const all = [...f.steps.flatMap(s => s.rules), ...f.response];
  all.forEach(r => { if (r.source) { const t = toks(r.source).filter(x => typeof x === 'string' && x !== '*'); place(ctx, r.source, guess(t[t.length - 1] || 'value', r, lookups)); } });
  f.steps.forEach(s => [s.condition, s.success].forEach(e => (String(e || '').match(/\$\{([^}]+)\}/g) || []).forEach(x => { const p = '$.' + x.slice(2, -1); const t = toks(p); place(ctx, p, guess(String(t[t.length - 1]), null, lookups)); })));
  return ctx;
}
const resolveEnv = b => String(b).replace(/\$\{([^}:]+)(?::([^}]*))?\}/g, (m, k, d) => (d !== undefined ? d : '<' + k + '>'));
const pretty = v => JSON.stringify(v, null, 2);

// ---------- JSON schemas ----------
const SCHEMA_TEMPLATE = pretty({ $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'object', required: [], properties: {} });
/** A draft 2020-12 schema that accepts {@code v}: every non-null property required, types from the values. */
function inferSchema(v) {
  if (v === null || v === undefined) return {};
  if (Array.isArray(v)) return { type: 'array', items: v.length ? inferSchema(v[0]) : {} };
  if (typeof v === 'object') {
    const properties = {}; Object.keys(v).forEach(k => { properties[k] = inferSchema(v[k]); });
    const required = Object.keys(v).filter(k => v[k] !== null);
    return required.length ? { type: 'object', required, properties } : { type: 'object', properties };
  }
  if (typeof v === 'number') return { type: Number.isInteger(v) ? 'integer' : 'number' };
  if (typeof v === 'boolean') return { type: 'boolean' };
  return { type: 'string' };
}
const schemaDoc = v => pretty({ $schema: 'https://json-schema.org/draft/2020-12/schema', ...inferSchema(v) });
const jsonError = t => { try { JSON.parse(t); return ''; } catch (e) { return e.message; } };
/** Where a schema code is referenced: ['TRANSFER request', 'TRANSFER step debit', ...]. */
const schemaUses = (cfg, code) => cfg.flows.flatMap(f => [
  ...(f.reqSchema === code ? [f.code + ' request'] : []), ...(f.respSchema === code ? [f.code + ' response'] : []),
  ...f.steps.filter(st => st.respSchema === code).map(st => f.code + ' step ' + st.name)]);

// ---------- generated SQL (Rows tab) ----------
function flowSql(f, lookupsUsed) {
  const q = v => "'" + String(v).replace(/'/g, "''") + "'"; const out = [];
  const opt = (c, v, num, cols, vals) => { if (v !== '' && v != null) { cols.push(c); vals.push(num ? v : q(v)); } };
  const fc = ['code', 'name', 'http_method', 'path_pattern'], fv = [q(f.code), q(f.name || f.code), q(f.method), q(f.path)];
  opt('request_schema_code', f.reqSchema, 0, fc, fv); opt('response_schema_code', f.respSchema, 0, fc, fv); opt('request_handler', f.reqHandler, 0, fc, fv); opt('response_handler', f.respHandler, 0, fc, fv); opt('error_handler', f.errorHandler, 0, fc, fv);
  if (String(f.successStatus) !== '200') opt('success_status', f.successStatus, 1, fc, fv); opt('timeout_ms', f.timeout, 1, fc, fv); if (f.audit !== 'INHERIT') opt('audit_mode', f.audit, 0, fc, fv); if (!f.enabled) opt('enabled', 'false', 1, fc, fv);
  out.push('-- ' + (f.name || f.code) + '\nINSERT INTO gw_flow (' + fc.join(', ') + ')\nVALUES (' + fv.join(', ') + ');');
  const steps = [...f.steps].sort((a, b) => a.order - b.order);
  steps.forEach(st => {
    const c = ['flow_id', 'name', 'step_order', 'target_system', 'http_method', 'path_template'], v = ['id', q(st.name), st.order, q(st.target), q(st.method), q(st.path)];
    opt('condition_expr', st.condition, 0, c, v); opt('success_expr', st.success, 0, c, v); if (st.onFailure !== 'STOP') opt('on_failure', st.onFailure, 0, c, v); opt('timeout_ms', st.timeout, 1, c, v);
    opt('response_schema_code', st.respSchema, 0, c, v); opt('request_handler', st.reqHandler, 0, c, v); opt('response_handler', st.respHandler, 0, c, v); opt('body_codec', st.bodyCodec, 0, c, v); if (!st.enabled) opt('enabled', 'false', 1, c, v);
    out.push('INSERT INTO gw_flow_step (' + c.join(', ') + ')\nSELECT ' + v.join(', ') + '\nFROM gw_flow WHERE code = ' + q(f.code) + ';');
  });
  const ruleSql = (r, i, st) => {
    const c = st ? ['flow_id', 'step_id', 'phase', 'seq', 'target_type', 'target_path'] : ['flow_id', 'phase', 'seq', 'target_type', 'target_path'];
    const v = st ? ['f.id', 's.id', "'STEP_REQUEST'", i + 1, q(r.type), q(r.target)] : ['id', "'FLOW_RESPONSE'", i + 1, q(r.type), q(r.target)];
    opt('source_path', r.source, 0, c, v); if (!r.source) opt('constant_value', r.constant, 0, c, v); opt('default_value', r.def, 0, c, v); opt('lookup_code', r.lookup, 0, c, v); opt('converter', r.conv, 0, c, v); opt('field_handler', r.fh, 0, c, v); if (r.required) opt('required', 'true', 1, c, v);
    return 'INSERT INTO gw_mapping_rule (' + c.join(', ') + ')\nSELECT ' + v.join(', ') + (st ? '\nFROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id\nWHERE f.code = ' + q(f.code) + ' AND s.name = ' + q(st.name) + ';' : '\nFROM gw_flow WHERE code = ' + q(f.code) + ';');
  };
  steps.forEach(st => { if (st.rules.length) out.push('-- ' + st.name + ': what the call sends\n' + st.rules.map((r, i) => ruleSql(r, i, st)).join('\n')); });
  if (f.response.length) out.push('-- what the client gets\n' + f.response.map((r, i) => ruleSql(r, i, null)).join('\n'));
  if (lookupsUsed.length) out.push('-- lookups the rules use (skip the ones the target database already has)\n' + lookupsUsed.map(([code, rows]) => rows.map(r => 'INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES (' + q(code) + ', ' + q(r.src) + ', ' + q(r.tgt) + ');').join('\n')).join('\n'));
  return out.join('\n\n');
}

// ---------- small view helpers ----------
const chipView = ch => html`
  <div style="display:flex;align-items:center;gap:6px;padding:4px 4px 4px 8px;border-radius:5px;background:#F4F3EF;font:11px ${MONO}">
    <span style=${`width:6px;height:6px;border-radius:50%;background:${ch.color};flex:none`}></span>
    <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title=${ch.label}>${ch.label}</span>
    <button class="x" onClick=${ch.onRemove} style="border:0;background:none;color:#9A9CA2;cursor:pointer;padding:0 3px;font-size:13px;line-height:1">×</button>
  </div>`;
const zoneOverlay = (z, color, label) => html`
  ${z.active && html`<div style=${`position:absolute;inset:-5px;border:1.5px dashed ${color};border-radius:12px;background:color-mix(in oklch, ${color} 5%, transparent);pointer-events:none`}></div>`}
  ${z.over && html`<div style=${`position:absolute;inset:-5px;border-radius:12px;background:color-mix(in oklch, ${color} 12%, transparent);pointer-events:none;display:grid;place-items:end center;padding-bottom:6px;font:600 11px ${MONO};color:${color}`}>${label || ''}</div>`}`;
const zoneProps = z => ({ onDragOver: z.onDragOver, onDragLeave: z.onDragLeave, onDrop: z.onDrop });
const connector = html`<div style="width:26px;height:1px;background:#ADA99E;margin-top:38px;flex:none"></div>`;
const toggle = (on, onClick, w = 38, h = 22) => html`
  <button onClick=${onClick} title=${on ? 'enabled' : 'disabled'} style=${`width:${w}px;height:${h}px;flex:none;border:0;border-radius:${h / 2}px;background:${on ? 'oklch(0.62 0.13 155)' : '#D3D0C7'};position:relative;cursor:pointer;padding:0`}>
    <span style=${`position:absolute;top:3px;left:${on ? w - h + 3 : 3}px;width:${h - 6}px;height:${h - 6}px;border-radius:50%;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.2)`}></span>
  </button>`;
const errList = errs => errs.length > 0 && html`<div style="display:flex;flex-direction:column;gap:3px">${errs.map(e => html`<div style=${`font:11px/1.45 ${MONO};color:oklch(0.5 0.18 25)`}>${e}</div>`)}</div>`;
const pageHead = (title, desc, btnLabel, onBtn) => html`
  <div style="display:flex;align-items:flex-end;gap:16px">
    <div style="flex:1">
      <div style="font-size:22px;font-weight:600;letter-spacing:-0.02em">${title}</div>
      <div style="margin-top:4px;font-size:12.5px;color:#6A6D75;line-height:1.45">${desc}</div>
    </div>
    ${btnLabel && html`<button class="btn-dark" onClick=${onBtn} style="border:0;background:#17181C;color:#fff;border-radius:7px;padding:9px 14px;cursor:pointer;font-weight:500">${btnLabel}</button>`}
  </div>`;
const inputStyle = (h = 32, size = 12) => `height:${h}px;border:1px solid #E4E1D8;border-radius:6px;padding:0 9px;font:${size}px ${MONO};background:#FAF9F6;width:100%;min-width:0`;
const label10 = t => html`<span style=${`font:10px ${MONO};letter-spacing:.06em;color:#9A9CA2`}>${t}</span>`;
const mono = t => html`<span style=${`font-family:${MONO}`}>${t}</span>`;

// ---------- assistant: safe Markdown ----------
const esc = t => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
function inlineMd(t) {
  // t is already escaped; code spans are cut out first so nothing inside them is formatted
  const codes = [];
  t = t.replace(/`([^`]+)`/g, (m, c) => { codes.push(c); return '\u0000' + (codes.length - 1) + '\u0000'; });
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, label, url) => {
    if (/^studio:/.test(url)) return '<a href="#" data-nav="' + url + '">' + label + '</a>';
    if (/^https?:\/\//.test(url)) return '<a href="' + url + '" target="_blank" rel="noopener noreferrer">' + label + '</a>';
    return label;
  });
  t = t.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/(^|[\s(])\*([^*\s][^*]*)\*(?=$|[\s).,:;!?])/g, '$1<i>$2</i>')
    .replace(/(^|[\s(])_([^_\s][^_]*)_(?=$|[\s).,:;!?])/g, '$1<i>$2</i>');
  return t.replace(/\u0000(\d+)\u0000/g, (m, i) => '<code>' + codes[+i] + '</code>');
}
function md(src) {
  const lines = esc(src).split('\n'); const out = []; let i = 0;
  const isTable = l => /^\s*\|.*\|\s*$/.test(l);
  while (i < lines.length) {
    const l = lines[i];
    const fence = /^\s*(```|~~~)\s*([\w+-]*)\s*$/.exec(l);
    if (fence) {
      const body = []; i++;
      while (i < lines.length && !new RegExp('^\\s*' + fence[1] + '\\s*$').test(lines[i])) body.push(lines[i++]);
      i++;
      out.push('<div class="md-code"><button class="md-copy" data-copy="1">Copy</button>' + (fence[2] ? '<span class="md-lang">' + fence[2] + '</span>' : '') + '<pre><code>' + body.join('\n') + '</code></pre></div>');
      continue;
    }
    const h = /^(#{1,6})\s+(.*)$/.exec(l);
    if (h) { out.push('<h4>' + inlineMd(h[2]) + '</h4>'); i++; continue; }
    if (isTable(l) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const row = r => r.trim().replace(/^\||\|$/g, '').split('|').map(c => inlineMd(c.trim()));
      let html = '<table><thead><tr>' + row(l).map(c => '<th>' + c + '</th>').join('') + '</tr></thead><tbody>'; i += 2;
      while (i < lines.length && isTable(lines[i])) { html += '<tr>' + row(lines[i]).map(c => '<td>' + c + '</td>').join('') + '</tr>'; i++; }
      out.push(html + '</tbody></table>'); continue;
    }
    if (/^\s*([-*]|\d+\.)\s+/.test(l)) {
      const ordered = /^\s*\d+\./.test(l); const items = [];
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
        let item = lines[i].replace(/^\s*([-*]|\d+\.)\s+/, ''); i++;
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i])) item += ' ' + lines[i++].trim();
        items.push('<li>' + inlineMd(item) + '</li>');
      }
      out.push((ordered ? '<ol>' : '<ul>') + items.join('') + (ordered ? '</ol>' : '</ul>')); continue;
    }
    if (!l.trim()) { i++; continue; }
    const para = [];
    while (i < lines.length && lines[i].trim() && !/^\s*(```|~~~|#{1,6}\s|([-*]|\d+\.)\s+)/.test(lines[i]) && !isTable(lines[i])) para.push(lines[i++]);
    if (!para.length) para.push(lines[i++]);
    out.push('<p>' + inlineMd(para.join('<br>')) + '</p>');
  }
  return out.join('');
}

class App extends Component {
  constructor() {
    super();
    this.state = { token: storage.get(TOKEN_KEY) || '', phase: storage.get(TOKEN_KEY) ? 'loading' : 'login', loginError: '', tokenInput: '',
      screen: 'flows', tab: 'pipeline', cur: 0, sel: { kind: 'flow' }, scope: 'resp', drag: null, over: null, toast: null, copied: false,
      cfg: null, baseJson: '', version: null, catalog: null, problems: [], samples: {}, preview: null, saving: false, discardArm: false };
    this.timers = {};
    this.reqSeq = { validate: 0, preview: 0 };
  }

  componentDidMount() {
    if (this.state.token) this.load();
    window.addEventListener('beforeunload', e => { if (this.dirty()) { e.preventDefault(); e.returnValue = ''; } });
  }

  // ---------- server ----------
  async api(method, path, body) {
    const r = await fetch('api/' + path, { method, headers: { 'Content-Type': 'application/json', 'X-Admin-Token': this.state.token }, body: body === undefined ? undefined : JSON.stringify(body) });
    let json = {};
    try { json = await r.json(); } catch (e) { /* empty body */ }
    if (r.status === 401) { storage.set(TOKEN_KEY, null); this.setState({ phase: 'login', loginError: 'The admin token was rejected.' }); throw new Error('unauthorized'); }
    return { status: r.status, json };
  }

  async load() {
    this.setState({ phase: 'loading' });
    try {
      const [c, cat] = await Promise.all([this.api('GET', 'config'), this.api('GET', 'catalog')]);
      if (c.status !== 200) throw new Error((c.json.errors || ['HTTP ' + c.status]).join('; '));
      const cfg = fromApi(c.json);
      this.setState({ phase: 'ready', cfg, baseJson: JSON.stringify(toApi(cfg)), version: c.json.version, catalog: cat.json, problems: [], preview: null,
        cur: Math.min(this.state.cur, Math.max(cfg.flows.length - 1, 0)) }, () => this.changed());
    } catch (e) {
      if (e.message !== 'unauthorized') this.setState({ phase: 'error', loginError: String(e.message || e) });
    }
  }

  dirty() { return this.state.cfg && JSON.stringify(toApi(this.state.cfg)) !== this.state.baseJson; }

  changed() {
    clearTimeout(this.timers.v); this.timers.v = setTimeout(() => this.validate(), 350);
    clearTimeout(this.timers.p); this.timers.p = setTimeout(() => this.runPreview(), 300);
  }

  async validate() {
    const n = ++this.reqSeq.validate;
    try {
      const r = await this.api('POST', 'validate', toApi(this.state.cfg));
      if (n === this.reqSeq.validate && r.status === 200) this.setState({ problems: r.json.errors || [] });
    } catch (e) { /* shown by api() */ }
  }

  async runPreview() {
    const s = this.state; const f = s.cfg && s.cfg.flows[s.cur];
    if (!f || s.screen !== 'flow' || s.tab !== 'mapping') return;
    const st = s.scope !== 'resp' ? f.steps.find(x => x.id === s.scope) : null;
    let sample;
    try { sample = this.sampleText(f) ? JSON.parse(this.sampleText(f)) : synthSample(f, s.cfg.lookups); } catch (e) { this.setState({ preview: { errors: ['sample context is not valid JSON: ' + e.message], rules: [], output: null } }); return; }
    const n = ++this.reqSeq.preview;
    try {
      const r = await this.api('POST', 'preview', { config: toApi(s.cfg), flow: f.code, step: st ? st.name : null, sample });
      if (n === this.reqSeq.preview) this.setState({ preview: r.status === 200 ? { ...r.json, key: f.code + '/' + (st ? st.name : '') } : { errors: r.json.errors || ['HTTP ' + r.status], rules: [], output: null } });
    } catch (e) { /* shown by api() */ }
  }

  sampleText(f) { return this.state.samples[f.code]; }

  async save() {
    const s = this.state;
    this.setState({ saving: true });
    try {
      const r = await this.api('PUT', 'config', { baseVersion: s.version, config: toApi(s.cfg) });
      const ok = r.status === 200;
      const notes = { 200: 'Saved and swapped in atomically. In-flight requests finish on the previous snapshot.', 409: 'Nothing was saved. Someone changed the configuration after you loaded it: copy what you need, then Discard to load the latest.', 422: r.json.saved ? 'Saved, but the reload was rejected. The previous configuration keeps serving traffic.' : 'Rejected, nothing was saved. The current configuration keeps serving traffic.', 503: 'Nothing was saved.' };
      this.setState({ saving: false, toast: { status: String(r.status), color: ok ? 'oklch(0.78 0.13 155)' : 'oklch(0.72 0.15 25)', body: pretty(r.json), note: notes[r.status] || '' } });
      if (ok) this.setState(st => ({ baseJson: JSON.stringify(toApi(st.cfg)), version: r.json.version }));
      if (r.status === 422) this.setState({ problems: r.json.errors || [] });
    } catch (e) { this.setState({ saving: false }); }
  }

  discard() {
    if (!this.state.discardArm) { this.setState({ discardArm: true }); clearTimeout(this.timers.d); this.timers.d = setTimeout(() => this.setState({ discardArm: false }), 3000); return; }
    this.setState({ discardArm: false, samples: {} }); this.load();
  }

  // ---------- state ops ----------
  mut(fn) { this.setState(s => { const cfg = clone(s.cfg); const extra = fn(cfg.flows[s.cur], s, cfg) || {}; return { cfg, ...extra }; }, () => this.changed()); }
  mutCfg(fn) { this.setState(s => { const cfg = clone(s.cfg); fn(cfg, s); return { cfg }; }, () => this.changed()); }
  norm(f) { const os = [...new Set(f.steps.map(x => x.order))].sort((a, b) => a - b); f.steps.forEach(x => { x.order = os.indexOf(x.order) + 1; }); }
  ds(payload) { return e => { e.dataTransfer.effectAllowed = 'copyMove'; try { e.dataTransfer.setData('text/plain', payload.kind); } catch (_) { /* ignore */ } setTimeout(() => this.setState({ drag: payload, over: null }), 0); }; }
  dragEnd = () => this.setState({ drag: null, over: null });
  zone(id, accepts, onDrop) {
    const d = this.state.drag; const active = !!d && accepts(d); const over = active && this.state.over === id;
    return {
      active, over, bg: over ? 'oklch(0.62 0.17 42 / 0.14)' : 'oklch(0.62 0.17 42 / 0.05)',
      onDragOver: e => { const dd = this.state.drag; if (dd && accepts(dd)) { e.preventDefault(); e.stopPropagation(); if (this.state.over !== id) this.setState({ over: id }); } },
      onDragLeave: e => { if (this.state.over === id && !e.currentTarget.contains(e.relatedTarget)) this.setState({ over: null }); },
      onDrop: e => { const dd = this.state.drag; if (!dd || !accepts(dd)) return; e.preventDefault(); e.stopPropagation(); this.setState({ drag: null, over: null }); onDrop(dd); }
    };
  }
  allTargets() {
    const s = this.state; const db = s.cfg.targets.map(t => ({ code: t.code, base: t.base, headers: t.headers, source: 'db' }));
    const yml = (s.catalog.configTargets || []).filter(t => !db.some(d => d.code === t.code)).map(t => ({ code: t.code, base: t.baseUrl || '', headers: (t.headers || []).map(n => ({ n, v: '<application.yml>' })), source: 'yml' }));
    return [...db, ...yml];
  }
  newStep(f, target, order) {
    const base = String(target).toLowerCase().split('_')[0] || 'call';
    let name = base, i = 2; while (f.steps.some(x => x.name === name)) name = base + (i++);
    return { id: uid('s'), name, order, target, method: 'GET', path: '/', onFailure: 'STOP', timeout: '', condition: '', success: '', reqHandler: '', respHandler: '', respSchema: '', bodyCodec: '', enabled: true, rules: [] };
  }
  place(d, order, insert) {
    this.mut(f => {
      if (insert) f.steps.forEach(x => { if (x.order >= order) x.order++; });
      let id;
      if (d.kind === 'call') { const st = this.newStep(f, d.target, order); f.steps.push(st); id = st.id; } else { const st = f.steps.find(x => x.id === d.id); if (st) st.order = order; id = d.id; }
      this.norm(f);
      return { sel: { kind: 'step', id } };
    });
  }

  palette() {
    const cat = this.state.catalog;
    const prevName = (st, f) => { const p = f.steps.filter(x => x.order < st.order); return p.length ? p[p.length - 1].name : null; };
    return [
      { cat: 'Inbound', color: C.in, items: [
        ...this.schemaCodes().map(sc => ({ label: 'Validate · ' + sc, sub: 'request_schema_code', zone: 'in', apply: f => { f.reqSchema = sc; } })),
        ...cat.messageHandlers.map(h => ({ label: h, sub: 'request_handler', java: true, zone: 'in', apply: f => { f.reqHandler = h; } }))] },
      { cat: 'Downstream call', color: C.call, items: this.allTargets().map(t => ({ label: 'Call ' + t.code, sub: resolveEnv(t.base).replace(/^https?:\/\//, ''), kind: 'call', target: t.code })) },
      { cat: 'Step policy', color: C.step, items: [
        { label: 'Run only if…', sub: 'condition_expr', zone: 'step', apply: (st, f) => { const p = prevName(st, f); st.condition = p ? '${steps.' + p + ".status} == 200" : '${request.body.amount} > 0'; } },
        { label: 'Success check', sub: 'success_expr', zone: 'step', apply: st => { st.success = '${steps.' + st.name + ".body.responseCode} == '00'"; } },
        { label: 'Continue on failure', sub: 'on_failure = CONTINUE', zone: 'step', apply: st => { st.onFailure = 'CONTINUE'; } },
        { label: 'Step timeout 3s', sub: 'timeout_ms = 3000', zone: 'step', apply: st => { st.timeout = '3000'; } },
        ...this.schemaCodes().map(sc => ({ label: 'Validate · ' + sc, sub: 'response_schema_code', zone: 'step', apply: st => { st.respSchema = sc; } })),
        ...cat.messageHandlers.map(h => ({ label: h, sub: 'request_handler', java: true, zone: 'step', apply: st => { st.reqHandler = h; } })),
        ...cat.bodyCodecs.map(h => ({ label: h, sub: 'body_codec', java: true, zone: 'step', apply: st => { st.bodyCodec = h; } }))] },
      { cat: 'Client response', color: C.out, items: [
        ...this.schemaCodes().map(sc => ({ label: 'Validate · ' + sc, sub: 'response_schema_code', zone: 'out', apply: f => { f.respSchema = sc; } })),
        { label: 'Respond 201 Created', sub: 'success_status = 201', zone: 'out', apply: f => { f.successStatus = '201'; } },
        ...cat.messageHandlers.map(h => ({ label: h, sub: 'response_handler', java: true, zone: 'out', apply: f => { f.respHandler = h; } }))] },
      { cat: 'Errors', color: C.err, items: cat.errorHandlers.map(h => ({ label: h.lookupCode ? 'Map codes via ' + h.lookupCode : h.name, sub: h.name, java: true, zone: 'err', apply: f => { f.errorHandler = h.name === 'defaultErrorHandler' ? '' : h.name; } })) },
      { cat: 'Flow', color: C.flow, items: [
        { label: 'Audit on', sub: 'audit_mode = ON', zone: 'flow', apply: f => { f.audit = 'ON'; } },
        { label: 'Audit off', sub: 'audit_mode = OFF', zone: 'flow', apply: f => { f.audit = 'OFF'; } },
        { label: 'Flow timeout 8s', sub: 'timeout_ms = 8000', zone: 'flow', apply: f => { f.timeout = '8000'; } }] }
    ].filter(c => c.items.length);
  }

  // ---------- schemas ----------
  schemaCodes() { return this.state.cfg.schemas.map(x => x.code).filter(Boolean); }
  sampleOf(f) { try { const t = this.sampleText(f); return t ? JSON.parse(t) : synthSample(f, this.state.cfg.lookups); } catch (e) { return synthSample(f, this.state.cfg.lookups); } }
  /** The last previewed client response body of {@code f}, if the Mapping tab has run one. */
  responseExample(f) { const pv = this.state.preview; return pv && pv.key === f.code + '/' && pv.output ? pv.output.body : undefined; }
  openSchema(code) {
    this.setState({ screen: 'schemas', focusSchema: code });
    setTimeout(() => { const el = document.getElementById('schema-' + code); if (el) el.scrollIntoView({ block: 'start', behavior: 'smooth' }); }, 50);
  }
  /** Buttons under a schema select: edit the chosen schema, or create one from the sample data and select it. */
  schemaActions(current, example, suggested, assign) {
    const out = [];
    if (current) out.push({ label: 'Edit ' + current + ' →', go: () => this.openSchema(current) });
    out.push({ label: '+ New schema', title: 'Generated from the sample context of the Mapping tab when there is one; check the types', go: () => {
      const codes = this.schemaCodes(); let code = suggested, k = 2; while (codes.includes(code)) code = suggested + '_' + (k++);
      const ex = example(); const fromSample = ex && typeof ex === 'object';
      const i = this.state.cfg.schemas.length;
      this.mut((fl, st, cfg) => { cfg.schemas.push({ code, desc: '', text: fromSample ? schemaDoc(ex) : SCHEMA_TEMPLATE }); assign(fl, code); return { example: fromSample ? { i, text: pretty(ex) } : null }; });
      this.openSchema(code);
    } });
    return out;
  }

  // ---------- problems ----------
  flowProblems(f) { return this.state.problems.filter(m => m.includes(`flow '${f.code}'`)); }
  problemTarget(msg, f) {
    const m = /step '([^']*)'/.exec(msg); const st = m && f.steps.find(x => x.name === m[1]);
    if (st) return { sel: { kind: 'step', id: st.id } };
    if (/mapping rule \d+ of flow '[^']*'(?! step)/.test(msg) || /response_/.test(msg)) return { sel: { kind: 'out' } };
    if (/error_handler/.test(msg)) return { sel: { kind: 'err' } };
    if (/request_/.test(msg)) return { sel: { kind: 'in' } };
    return { sel: { kind: 'flow' } };
  }

  // ---------- render ----------
  render(_, s) {
    if (s.phase === 'login' || s.phase === 'error') return this.renderLogin();
    if (s.phase === 'loading' || !s.cfg) return html`<div style="height:100vh;display:grid;place-items:center;color:#6A6D75">Loading configuration…</div>`;
    const f = s.screen === 'flow' ? s.cfg.flows[s.cur] : null;
    return html`
      <div style="height:100vh;display:flex;flex-direction:column;background:#F4F3EF;overflow:hidden">
        ${this.renderHeader()}
        ${f && this.renderFlow(f)}
        ${s.screen === 'flows' && this.renderFlows()}
        ${s.screen === 'targets' && this.renderTargets()}
        ${s.screen === 'lookups' && this.renderLookups()}
        ${s.screen === 'schemas' && this.renderSchemas()}
        ${s.toast && this.renderToast()}
        ${s.catalog.assistantEnabled && s.assistant && s.assistant.open && this.renderAssistant()}
      </div>`;
  }

  renderLogin() {
    const s = this.state;
    const go = e => { e.preventDefault(); const t = s.tokenInput.trim(); if (!t) return; storage.set(TOKEN_KEY, t); this.setState({ token: t, loginError: '' }, () => this.load()); };
    return html`
      <div style="height:100vh;display:grid;place-items:center;padding:16px">
        <form onSubmit=${go} style="width:100%;max-width:380px;background:#fff;border:1px solid #E4E1D8;border-radius:12px;padding:24px;display:flex;flex-direction:column;gap:14px">
          <div style="display:flex;align-items:center;gap:10px">
            <div style=${`width:24px;height:24px;border-radius:6px;background:${C.call};display:grid;place-items:center;font:600 11px ${MONO};color:#17181C`}>{}</div>
            <span style="font-weight:600;font-size:15px">Gateway Studio</span>
          </div>
          <div style="font-size:12.5px;color:#6A6D75;line-height:1.45">Enter the admin token (${mono('gateway.admin.token')}). It stays in this browser tab only.</div>
          <input class="inp" type="password" autofocus value=${s.tokenInput} onInput=${e => this.setState({ tokenInput: e.currentTarget.value })} placeholder="X-Admin-Token" style=${inputStyle(36)}/>
          ${s.loginError && html`<div style="font-size:12px;color:oklch(0.5 0.18 25)">${s.loginError}</div>`}
          <button class="btn-dark" type="submit" style="border:0;background:#17181C;color:#fff;border-radius:7px;padding:10px 14px;cursor:pointer;font-weight:500">Open studio</button>
        </form>
      </div>`;
  }

  renderHeader() {
    const s = this.state; const dirty = this.dirty();
    const nav = [['Flows', 'flows'], ['Target systems', 'targets'], ['Lookups', 'lookups'], ['Schemas', 'schemas']].map(([label, k]) => {
      const on = s.screen === k || (k === 'flows' && s.screen === 'flow');
      return html`<button onClick=${() => this.setState({ screen: k })} style=${`border:0;background:${on ? '#2B2D33' : 'transparent'};color:${on ? '#FFFFFF' : '#A4A6AC'};padding:6px 11px;border-radius:6px;cursor:pointer;font-weight:500`}>${label}</button>`;
    });
    const n = s.problems.length;
    return html`
      <header style="height:52px;flex:none;display:flex;align-items:center;gap:22px;padding:0 16px;background:#17181C;color:#F4F3EF">
        <div style="display:flex;align-items:center;gap:10px">
          <div style=${`width:24px;height:24px;border-radius:6px;background:${C.call};display:grid;place-items:center;font:600 11px ${MONO};color:#17181C`}>{}</div>
          <span style="font-weight:600;letter-spacing:-0.01em;font-size:14px">Gateway Studio</span>
        </div>
        <nav style="display:flex;gap:2px">${nav}</nav>
        <div style="flex:1"></div>
        ${dirty && html`<span style=${`font:12px ${MONO};color:oklch(0.8 0.12 75)`}>unsaved changes</span>`}
        ${dirty && html`<button onClick=${() => this.discard()} style="border:1px solid #3A3C43;background:none;color:#C9CBD1;padding:6px 11px;border-radius:7px;cursor:pointer">${s.discardArm ? 'Click again to discard' : 'Discard'}</button>`}
        <div style=${`font:12px ${MONO};color:#8E9097`}>${location.host}</div>
        ${s.catalog.assistantEnabled && html`<button onClick=${() => this.toggleAssistant()} title="Ask the project assistant" style=${`display:flex;align-items:center;gap:7px;border:1px solid #3A3C43;background:${s.assistant && s.assistant.open ? '#2B2D33' : 'none'};color:#E9E7E1;padding:6px 11px;border-radius:7px;cursor:pointer;font-weight:500`}><span style=${`font:600 11px ${MONO};color:${C.call}`}>?</span>Ask</button>`}
        <button class="btn-acc" disabled=${s.saving} onClick=${() => this.save()} title=${n ? n + ' problem(s): the save would be rejected' : 'Write to the database and reload'} style=${`display:flex;align-items:center;gap:8px;border:0;background:${C.call};color:#17181C;padding:7px 12px;border-radius:7px;cursor:pointer;font-weight:600`}>
          ${s.saving ? 'Saving…' : 'Save & reload'}
          ${n > 0 && html`<span style=${`font:600 10px ${MONO};background:#17181C;color:#fff;border-radius:9px;padding:2px 6px`}>${n}</span>`}
        </button>
      </header>`;
  }

  renderToast() {
    const t = this.state.toast;
    return html`
      <div style="position:fixed;right:20px;bottom:20px;width:min(440px,calc(100vw - 32px));background:#17181C;color:#E9E7E1;border-radius:10px;box-shadow:0 12px 40px rgba(0,0,0,.25);overflow:hidden;z-index:10">
        <div style="display:flex;align-items:center;gap:10px;padding:10px 14px;border-bottom:1px solid #2B2D33">
          <span style=${`font:600 11px ${MONO};color:#17181C;background:${t.color};padding:2px 7px;border-radius:4px`}>${t.status}</span>
          <span style=${`font:12px ${MONO};color:#8E9097`}>PUT /studio/api/config</span>
          <span style="flex:1"></span>
          <button onClick=${() => this.setState({ toast: null })} style="border:0;background:none;color:#8E9097;cursor:pointer;font-size:15px">×</button>
        </div>
        <pre style=${`margin:0;padding:12px 14px;max-height:240px;overflow:auto;font:11.5px/1.55 ${MONO};white-space:pre-wrap;word-break:break-word`}>${t.body}</pre>
        <div style="padding:0 14px 12px;font-size:11.5px;color:#8E9097">${t.note}</div>
      </div>`;
  }

  // ---------- flows list ----------
  renderFlows() {
    const s = this.state; const base = s.catalog.apiBasePath;
    const open = i => () => this.setState({ screen: 'flow', tab: 'pipeline', cur: i, sel: { kind: 'flow' }, scope: 'resp', preview: null });
    const newFlow = () => this.mutCfg((cfg, st) => {
      let k = 1; while (cfg.flows.some(x => x.code === 'NEW_FLOW_' + k)) k++;
      cfg.flows.push({ code: 'NEW_FLOW_' + k, name: 'New flow', method: 'GET', path: '/v1/new-' + k, reqSchema: '', respSchema: '', reqHandler: '', respHandler: '', errorHandler: '', successStatus: '200', timeout: '', audit: 'INHERIT', enabled: true, steps: [], response: [] });
      setTimeout(() => this.setState({ cur: cfg.flows.length - 1, screen: 'flow', tab: 'pipeline', sel: { kind: 'flow' }, scope: 'resp' }), 0);
    });
    const grid = 'display:grid;grid-template-columns:minmax(0,2.4fr) minmax(0,1.4fr) 70px minmax(0,1.5fr) 80px 90px;gap:12px';
    const other = s.problems.filter(m => !s.cfg.flows.some(f => m.includes(`flow '${f.code}'`)) && !/^(target system|lookup|json schema) '/.test(m));
    return html`
      <div style="flex:1;min-height:0;overflow:auto;padding:28px 32px 60px">
        <div style="max-width:1080px;margin:0 auto">
          ${pageHead('Flows', html`One flow per inbound endpoint under ${mono(base)}. Rows in ${mono('gw_flow')}.`, '+ New flow', newFlow)}
          <div style="margin-top:20px;background:#fff;border:1px solid #E4E1D8;border-radius:10px;overflow:auto">
            <div style=${`${grid};min-width:760px;padding:10px 16px;font:600 10px ${MONO};letter-spacing:.06em;color:#9A9CA2;border-bottom:1px solid #EFEDE6`}>
              <span>ENDPOINT</span><span>CODE</span><span>STEPS</span><span>ERROR HANDLER</span><span>AUDIT</span><span>ENABLED</span>
            </div>
            ${s.cfg.flows.length === 0 && html`<div style="padding:22px 16px;color:#6A6D75">No flows yet.</div>`}
            ${s.cfg.flows.map((x, i) => {
              const np = this.flowProblems(x).length;
              return html`
              <div class="row" onClick=${open(i)} style=${`${grid};min-width:760px;align-items:center;padding:12px 16px;border-bottom:1px solid #F3F1EC;cursor:pointer`}>
                <div style="display:flex;align-items:center;gap:9px;min-width:0">
                  <span style=${`width:48px;text-align:center;flex:none;font:600 10px ${MONO};color:#fff;background:${MC[x.method] || '#555'};padding:3px 0;border-radius:4px`}>${x.method}</span>
                  <span style=${`font:12.5px ${MONO};overflow:hidden;text-overflow:ellipsis;white-space:nowrap`}>${base}${x.path}</span>
                  ${np > 0 && html`<span title=${np + ' problem(s)'} style=${`flex:none;min-width:16px;height:16px;border-radius:8px;padding:0 4px;background:${C.err};color:#fff;display:grid;place-items:center;font:700 10px ${MONO}`}>${np}</span>`}
                </div>
                <div style="min-width:0"><div style=${`font:500 12px ${MONO};overflow:hidden;text-overflow:ellipsis`}>${x.code}</div><div style="font-size:11.5px;color:#9A9CA2">${x.name}</div></div>
                <span style=${`font:12px ${MONO}`}>${x.steps.length}</span>
                <span style=${`font:11.5px ${MONO};color:#6A6D75;overflow:hidden;text-overflow:ellipsis`}>${x.errorHandler || 'defaultErrorHandler'}</span>
                <span style=${`font:11.5px ${MONO};color:#6A6D75`}>${x.audit}</span>
                ${toggle(x.enabled, e => { e.stopPropagation(); this.mutCfg(cfg => { cfg.flows[i].enabled = !cfg.flows[i].enabled; }); })}
              </div>`;
            })}
          </div>
          ${other.length > 0 && html`
            <div style="margin-top:16px;background:#fff;border:1px solid oklch(0.85 0.06 25);border-radius:9px;overflow:hidden">
              <div style="padding:8px 12px;background:oklch(0.96 0.025 25);font-weight:600;color:oklch(0.45 0.17 25);font-size:12px">Other problems · a save would be rejected</div>
              ${other.map(m => html`<div style=${`padding:7px 12px;border-top:1px solid #F1EFE9;font:11px/1.45 ${MONO}`}>${m}</div>`)}
            </div>`}
        </div>
      </div>`;
  }

  // ---------- target systems ----------
  renderTargets() {
    const s = this.state; const cat = s.catalog;
    const used = code => s.cfg.flows.reduce((a, x) => a + x.steps.filter(y => y.target === code).length, 0);
    const T = (i, fn) => this.mutCfg(cfg => fn(cfg.targets[i], cfg));
    const val = e => e.currentTarget.value;
    const newTarget = () => this.mutCfg(cfg => { let k = 1; while (cfg.targets.some(x => x.code === 'NEW_SYSTEM_' + k)) k++; cfg.targets.push({ code: 'NEW_SYSTEM_' + k, base: '${NEW_SYSTEM_' + k + '_URL:http://localhost:9000}', connect: '3000', read: '10000', bodyCodec: '', enabled: true, headers: [], tls: { ...TLS0 } }); });
    const yml = (cat.configTargets || []);
    return html`
      <div style="flex:1;min-height:0;overflow:auto;padding:28px 32px 60px">
        <div style="max-width:1080px;margin:0 auto">
          ${pageHead('Target systems', html`Downstream base URLs, timeouts and fixed headers. A change takes effect on save. Use ${mono('${ENV:default}')} to keep secrets and per-environment addresses out of the database; placeholders resolve on the server. ${mono('gw_target_system')}`, '+ New target system', newTarget)}
          <div style="margin-top:20px;display:grid;grid-template-columns:repeat(auto-fill,minmax(min(360px,100%),1fr));gap:14px">
            ${s.cfg.targets.map((t, i) => {
              const errs = s.problems.filter(m => m.includes(`target system '${t.code}'`) && !m.includes('(application config)'));
              const resolved = resolveEnv(t.base);
              return html`
              <div style=${`background:#fff;border:1px solid ${errs.length ? 'oklch(0.85 0.06 25)' : '#E4E1D8'};border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:12px`}>
                <div style="display:flex;align-items:center;gap:10px">
                  <input class="inp inp-ghost" value=${t.code} onInput=${e => { const v = val(e).toUpperCase().replace(/[^A-Z0-9_]/g, '_'); T(i, (x, cfg) => { cfg.flows.forEach(f2 => f2.steps.forEach(st => { if (st.target === x.code) st.target = v; })); x.code = v; }); }} style=${`flex:1;min-width:0;height:32px;border:1px solid transparent;border-radius:6px;padding:0 8px;margin-left:-8px;font:600 13px ${MONO};background:transparent`}/>
                  <span style=${`font:11px ${MONO};color:#9A9CA2;white-space:nowrap`}>${used(t.code)} steps</span>
                  ${toggle(t.enabled !== false, () => T(i, x => { x.enabled = x.enabled === false; }), 34, 20)}
                </div>
                <label style="display:flex;flex-direction:column;gap:5px">
                  ${label10('BASE_URL')}
                  <input class="inp" value=${t.base} onInput=${e => { const v = val(e); T(i, x => { x.base = v; }); }} placeholder="http://10.20.30.40:9080" style=${inputStyle()}/>
                  <span style=${`font:11px ${MONO};color:${errs.some(m => /base_url/.test(m)) ? 'oklch(0.5 0.18 25)' : 'oklch(0.5 0.12 160)'};word-break:break-all`}>→ ${resolved || '(empty)'}</span>
                </label>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
                  <label style="display:flex;flex-direction:column;gap:5px">${label10('CONNECT_TIMEOUT_MS')}<input class="inp" value=${t.connect} placeholder="3000" onInput=${e => { const v = val(e).replace(/\D/g, ''); T(i, x => { x.connect = v; }); }} style=${inputStyle(30)}/></label>
                  <label style="display:flex;flex-direction:column;gap:5px">${label10('READ_TIMEOUT_MS')}<input class="inp" value=${t.read} placeholder=${String(cat.defaultStepTimeoutMs)} onInput=${e => { const v = val(e).replace(/\D/g, ''); T(i, x => { x.read = v; }); }} style=${inputStyle(30)}/></label>
                </div>
                <label style="display:flex;flex-direction:column;gap:5px">${label10('BODY_CODEC')}
                  <select class="inp" value=${t.bodyCodec} onChange=${e => { const v = val(e); T(i, x => { x.bodyCodec = v; }); }} style=${inputStyle(30)}>
                    <option value="">— JSON (default)</option>
                    ${cat.bodyCodecs.map(c => html`<option value=${c}>${c}</option>`)}
                  </select>
                </label>
                ${this.renderTls(t, i, resolved)}
                <div style="padding-top:10px;border-top:1px solid #EFEDE6;display:flex;flex-direction:column;gap:6px">
                  ${label10('FIXED HEADERS · gw_target_system_header')}
                  ${t.headers.map((h, j) => html`
                    <div style="display:flex;gap:6px;align-items:center">
                      <input class="inp" value=${h.n} onInput=${e => { const v = val(e); T(i, x => { x.headers[j].n = v; }); }} placeholder="X-Api-Key" style=${inputStyle(28, 11.5) + ';width:40%'}/>
                      <input class="inp" value=${h.v} onInput=${e => { const v = val(e); T(i, x => { x.headers[j].v = v; }); }} placeholder="\${API_KEY}" style=${inputStyle(28, 11.5) + ';flex:1'}/>
                      <button class="del" onClick=${() => T(i, x => { x.headers.splice(j, 1); })} style="border:0;background:none;color:#ADA99E;cursor:pointer;font-size:14px;padding:0 4px">×</button>
                    </div>`)}
                  <button onClick=${() => T(i, x => { x.headers.push({ n: '', v: '' }); })} style="align-self:flex-start;border:1px dashed #C9C6BC;background:none;border-radius:5px;padding:4px 9px;cursor:pointer;font-size:11.5px;color:#6A6D75">+ Header</button>
                </div>
                ${errList(errs)}
                <div style="display:flex;justify-content:flex-end">
                  <button class="del" onClick=${() => this.mutCfg(cfg => { cfg.targets.splice(i, 1); })} style="border:0;background:none;color:#9A9CA2;cursor:pointer;font-size:12px;padding:2px 0">Delete target</button>
                </div>
              </div>`;
            })}
          </div>
          ${yml.length > 0 && html`
            <div style="margin-top:28px;font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75">FROM APPLICATION CONFIG · gateway.target-systems (read-only here; a database row with the same code wins)</div>
            <div style="margin-top:10px;display:grid;grid-template-columns:repeat(auto-fill,minmax(min(360px,100%),1fr));gap:14px">
              ${yml.map(t => {
                const overridden = s.cfg.targets.some(d => d.code === t.code && d.enabled !== false);
                return html`
                <div style=${`background:#FAF9F6;border:1px dashed #D9D6CC;border-radius:10px;padding:14px 16px;display:flex;flex-direction:column;gap:6px;opacity:${overridden ? 0.6 : 1}`}>
                  <div style="display:flex;gap:10px;align-items:center"><span style=${`font:600 13px ${MONO};flex:1`}>${t.code}</span><span style=${`font:11px ${MONO};color:#9A9CA2`}>${overridden ? 'overridden by database' : used(t.code) + ' steps'}</span></div>
                  <div style=${`font:11.5px ${MONO};color:#3E4047;word-break:break-all`}>${t.baseUrl}</div>
                  <div style=${`font:11px ${MONO};color:#9A9CA2`}>connect ${t.connectTimeoutMs}ms · read ${t.readTimeoutMs == null ? 'default' : t.readTimeoutMs + 'ms'}${t.bodyCodec ? ' · ' + t.bodyCodec : ''}${t.headers.length ? ' · headers ' + t.headers.join(', ') : ''}</div>
                </div>`;
              })}
            </div>`}
        </div>
      </div>`;
  }

  /** TLS of one target: HTTP/HTTPS from the resolved URL, the mode, and for CUSTOM the trust / key stores. */
  renderTls(t, i, resolved) {
    const tls = t.tls || TLS0; const https = /^https:/i.test(resolved);
    const T = fn => this.mutCfg(cfg => { const x = cfg.targets[i]; x.tls = { ...TLS0, ...(x.tls || {}) }; fn(x.tls); });
    const val = e => e.currentTarget.value;
    const badge = https ? html`<span style=${`font:600 10px ${MONO};color:oklch(0.4 0.12 155);background:oklch(0.94 0.05 155);padding:2px 6px;border-radius:4px`}>HTTPS</span>`
      : html`<span style=${`font:600 10px ${MONO};color:#6A6D75;background:#EFEDE6;padding:2px 6px;border-radius:4px`}>HTTP</span>`;
    const store = (label, key, pwKey, ph, hint) => html`
      <label style="display:flex;flex-direction:column;gap:5px">${label10(label)}
        <textarea class="inp" spellcheck="false" rows=${tls[key].includes('-----BEGIN') ? 5 : 1} value=${tls[key]} placeholder=${ph} onInput=${e => { const v = val(e); T(x => { x[key] = v; }); }} style=${`border:1px solid #E4E1D8;border-radius:6px;padding:6px 9px;font:11.5px/1.45 ${MONO};background:#FAF9F6;resize:vertical;width:100%`}></textarea>
        <input class="inp" type="password" autocomplete="new-password" value=${tls[pwKey]} placeholder="password (PKCS12/JKS or encrypted key), e.g. \${TLS_PASSWORD}" onInput=${e => { const v = val(e); T(x => { x[pwKey] = v; }); }} style=${inputStyle(28, 11.5)}/>
        <span style="font-size:11px;color:#9A9CA2;line-height:1.4">${hint}</span>
      </label>`;
    return html`
      <div style="padding-top:10px;border-top:1px solid #EFEDE6;display:flex;flex-direction:column;gap:8px">
        <div style="display:flex;align-items:center;gap:8px">${label10('TLS · gw_target_system.tls_*')}<span style="flex:1"></span>${badge}</div>
        <select class="inp" value=${tls.mode} onChange=${e => { const v = val(e); T(x => { x.mode = v; }); }} style=${inputStyle(30)}>
          <option value="VERIFY">VERIFY · trusted CAs + host name check (default)</option>
          <option value="INSECURE">INSECURE · skip certificate checks (dev/test only)</option>
          <option value="CUSTOM">CUSTOM · own trust store and/or client key (mTLS)</option>
        </select>
        ${!https && tls.mode !== 'VERIFY' && html`<span style="font-size:11.5px;color:#7A5B12">The base URL is http://, so TLS settings are ignored until it is https://.</span>`}
        ${tls.mode === 'INSECURE' && html`<span style="font-size:11.5px;line-height:1.4;color:oklch(0.48 0.17 25)">Certificates and host names are not verified: anyone in the network path can read and change the traffic. Use only against dev/test systems.</span>`}
        ${tls.mode === 'CUSTOM' && html`
          ${store('TRUST STORE (server CA / self-signed cert)', 'trust', 'trustPw', '/etc/gateway/tls/core-ca.pem  or  \${CORE_CA_PEM}', 'Replaces the JVM CAs for this target. PEM text, or a path to .pem/.crt, .p12/.pfx or .jks. Empty = JVM CAs.')}
          ${store('KEY STORE (client certificate, mutual TLS)', 'key', 'keyPw', '/etc/gateway/tls/gateway-client.p12  or  \${CORE_CLIENT_PEM}', 'Presented to the server. PEM with certificate chain + private key, or .p12/.pfx/.jks. Empty = no client certificate.')}
          <span style="font-size:11px;color:#9A9CA2;line-height:1.4">Keep keys and passwords out of the database: use a file path on the gateway server or a \${ENV_VAR} placeholder. Files are re-read on every Save & reload.</span>`}
      </div>`;
  }

  // ---------- lookups ----------
  renderLookups() {
    const s = this.state; const L = s.cfg.lookups;
    const val = e => e.currentTarget.value;
    const usedBy = code => s.cfg.flows.reduce((a, x) => a + [...x.response, ...x.steps.flatMap(y => y.rules)].filter(r => r.lookup === code).length, 0);
    const handlers = code => s.catalog.errorHandlers.filter(h => h.lookupCode === code).map(h => h.name);
    const upd = (code, fn) => this.mutCfg(cfg => fn(cfg.lookups[code], cfg));
    const newLookup = () => this.mutCfg(cfg => { let k = 1; while (cfg.lookups['NEW_LOOKUP_' + k]) k++; cfg.lookups['NEW_LOOKUP_' + k] = [{ src: '', tgt: '' }]; });
    const rename = (code, v) => this.mutCfg(cfg => {
      if (!v || (v !== code && cfg.lookups[v])) return;
      const nl = {}; Object.keys(cfg.lookups).forEach(k => { nl[k === code ? v : k] = cfg.lookups[k]; }); cfg.lookups = nl;
      cfg.flows.forEach(f2 => [...f2.response, ...f2.steps.flatMap(st => st.rules)].forEach(r => { if (r.lookup === code) r.lookup = v; }));
    });
    const cols = 'display:grid;grid-template-columns:96px 14px 1fr 22px;gap:6px';
    return html`
      <div style="flex:1;min-height:0;overflow:auto;padding:28px 32px 60px">
        <div style="max-width:1080px;margin:0 auto">
          ${pageHead('Lookups', html`Code translation tables used by mapping rules and error handlers. ${mono('*')} is the fallback. Targets are parsed as JSON when valid, otherwise used as text. ${mono('gw_lookup_entry')}`, '+ New lookup', newLookup)}
          <div style="margin-top:20px;display:grid;grid-template-columns:repeat(auto-fill,minmax(min(400px,100%),1fr));gap:14px">
            ${Object.keys(L).map(code => {
              const rows = L[code]; const errs = s.problems.filter(m => m.includes(`lookup '${code}'`));
              const dupe = v => rows.filter(r => r.src === v).length > 1; const hs = handlers(code);
              return html`
              <div key=${code} style=${`background:#fff;border:1px solid ${errs.length ? 'oklch(0.85 0.06 25)' : '#E4E1D8'};border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:10px`}>
                <div style="display:flex;align-items:center;gap:10px">
                  <input class="inp inp-ghost" value=${code} onChange=${e => rename(code, val(e).toUpperCase().replace(/[^A-Z0-9_]/g, '_'))} title="Rename (applies when the field loses focus)" style=${`flex:1;min-width:0;height:32px;border:1px solid transparent;border-radius:6px;padding:0 8px;margin-left:-8px;font:600 13px ${MONO};background:transparent`}/>
                  <span style=${`font:11px ${MONO};color:#9A9CA2;white-space:nowrap`}>${usedBy(code)} rules${hs.length ? ' · ' + hs.join(', ') : ''}</span>
                </div>
                <div style=${`${cols};font:10px ${MONO};letter-spacing:.06em;color:#9A9CA2`}><span>SOURCE_VALUE</span><span></span><span>TARGET_VALUE</span><span></span></div>
                <div style="display:flex;flex-direction:column;gap:5px">
                  ${rows.map((r, j) => html`
                    <div style=${`${cols};align-items:center`}>
                      <input class="inp" value=${r.src} onInput=${e => { const v = val(e); upd(code, x => { x[j].src = v; }); }} style=${inputStyle(28, 11.5) + `;border-color:${!r.src || dupe(r.src) ? 'oklch(0.75 0.12 25)' : '#E4E1D8'}`}/>
                      <span style="color:#ADA99E;text-align:center">→</span>
                      <input class="inp" value=${r.tgt} onInput=${e => { const v = val(e); upd(code, x => { x[j].tgt = v; }); }} style=${inputStyle(28, 11.5)}/>
                      <button class="del" onClick=${() => upd(code, x => { x.splice(j, 1); })} style="border:0;background:none;color:#ADA99E;cursor:pointer;font-size:14px;padding:0">×</button>
                    </div>`)}
                </div>
                <div style="display:flex;gap:6px">
                  <button onClick=${() => upd(code, x => { const star = x.findIndex(r => r.src === '*'); const row = { src: '', tgt: '' }; if (star >= 0) x.splice(star, 0, row); else x.push(row); })} style="border:1px dashed #C9C6BC;background:none;border-radius:5px;padding:4px 9px;cursor:pointer;font-size:11.5px;color:#6A6D75">+ Row</button>
                  ${!rows.some(r => r.src === '*') && html`<button onClick=${() => upd(code, x => { x.push({ src: '*', tgt: 'UNKNOWN' }); })} style="border:1px dashed #C9C6BC;background:none;border-radius:5px;padding:4px 9px;cursor:pointer;font-size:11.5px;color:#6A6D75">+ Fallback *</button>`}
                  <span style="flex:1"></span>
                  <button class="del" onClick=${() => this.mutCfg(cfg => { delete cfg.lookups[code]; })} style="border:0;background:none;color:#9A9CA2;cursor:pointer;font-size:12px">Delete lookup</button>
                </div>
                ${errList(errs)}
              </div>`;
            })}
          </div>
        </div>
      </div>`;
  }

  // ---------- JSON schemas ----------
  renderSchemas() {
    const s = this.state;
    const val = e => e.currentTarget.value;
    const upd = (i, fn) => this.mutCfg(cfg => fn(cfg.schemas[i], cfg));
    const newSchema = () => { let k = 1; while (this.schemaCodes().includes('NEW_SCHEMA_' + k)) k++; const code = 'NEW_SCHEMA_' + k; this.mutCfg(cfg => { cfg.schemas.push({ code, desc: '', text: SCHEMA_TEMPLATE }); }); this.openSchema(code); };
    const rename = (i, old, v) => this.mutCfg(cfg => {
      if (!v || (v !== old && cfg.schemas.some(x => x.code === v))) return;
      cfg.schemas[i].code = v;
      cfg.flows.forEach(f => { if (f.reqSchema === old) f.reqSchema = v; if (f.respSchema === old) f.respSchema = v; f.steps.forEach(st => { if (st.respSchema === old) st.respSchema = v; }); });
      setTimeout(() => this.setState({ focusSchema: v }), 0);
    });
    const ex = s.example || {};
    return html`
      <div style="flex:1;min-height:0;overflow:auto;padding:28px 32px 60px">
        <div style="max-width:1080px;margin:0 auto">
          ${pageHead('JSON schemas', html`Validators for request bodies, client responses and downstream answers (JSON Schema draft 2020-12). An invalid inbound body is answered with 400, an invalid client response with 500. Pick them on a flow's Inbound, Client response or step. ${mono('gw_json_schema')}`, '+ New schema', newSchema)}
          ${s.cfg.schemas.length === 0 && html`<div style="margin-top:20px;padding:22px;border:1.5px dashed #C9C6BC;border-radius:10px;color:#6A6D75;text-align:center">No schemas yet. Create one here, or with <b>+ New schema</b> under a schema field in a flow's inspector (it starts from that flow's sample data).</div>`}
          <div style="margin-top:20px;display:flex;flex-direction:column;gap:14px">
            ${s.cfg.schemas.map((x, i) => {
              const errs = s.problems.filter(m => m.includes(`json schema '${x.code}'`) || m.includes(`Schema '${x.code}'`));
              const parseErr = jsonError(x.text); const uses = schemaUses(s.cfg, x.code);
              const focused = s.focusSchema === x.code; const exOpen = ex.i === i;
              return html`
              <div id=${'schema-' + x.code} key=${i} style=${`background:#fff;border:1px solid ${errs.length || parseErr ? 'oklch(0.85 0.06 25)' : focused ? C.call : '#E4E1D8'};box-shadow:${focused ? '0 0 0 3px oklch(0.62 0.17 42 / 0.15)' : 'none'};border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:10px;scroll-margin-top:16px`}>
                <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
                  <input class="inp inp-ghost" value=${x.code} onChange=${e => rename(i, x.code, val(e).toUpperCase().replace(/[^A-Z0-9_]/g, '_'))} title="Rename (applies when the field loses focus; references follow)" style=${`flex:1;min-width:200px;height:32px;border:1px solid transparent;border-radius:6px;padding:0 8px;margin-left:-8px;font:600 13px ${MONO};background:transparent`}/>
                  <span style=${`font:11px ${MONO};color:#9A9CA2`}>${uses.length ? 'used by ' + uses.join(', ') : 'not used'}</span>
                </div>
                <input class="inp" value=${x.desc} onInput=${e => { const v = val(e); upd(i, y => { y.desc = v; }); }} placeholder="description (optional)" style=${inputStyle(30, 12)}/>
                <textarea class="inp" value=${x.text} onInput=${e => { const v = val(e); upd(i, y => { y.text = v; }); }} spellcheck="false" rows=${Math.min(24, Math.max(8, x.text.split('\n').length + 1))} style=${`border:1px solid #E4E1D8;border-radius:6px;padding:9px 11px;font:12px/1.5 ${MONO};background:#FAF9F6;resize:vertical;width:100%;tab-size:2`}></textarea>
                ${parseErr ? html`<div style=${`font:11px/1.45 ${MONO};color:oklch(0.5 0.18 25)`}>Not valid JSON: ${parseErr}</div>` : errList(errs)}
                ${exOpen && html`
                  <div style="padding:12px;border:1px dashed #C9C6BC;border-radius:8px;display:flex;flex-direction:column;gap:8px;background:#FAF9F6">
                    <div style="font-size:12px;color:#6A6D75">Paste an example JSON document. The schema is replaced by one that accepts it: every non-null field required, types taken from the values. Tighten it afterwards (formats, lengths, enums).</div>
                    <textarea class="inp" value=${ex.text || ''} onInput=${e => this.setState({ example: { i, text: e.currentTarget.value } })} spellcheck="false" rows="8" placeholder='{"fromAccount": "1001", "amount": 150000}' style=${`border:1px solid #E4E1D8;border-radius:6px;padding:9px 11px;font:12px/1.5 ${MONO};background:#fff;resize:vertical;width:100%`}></textarea>
                    ${ex.text && jsonError(ex.text) && html`<div style=${`font:11px ${MONO};color:oklch(0.5 0.18 25)`}>Not valid JSON: ${jsonError(ex.text)}</div>`}
                    <div style="display:flex;gap:6px">
                      <button class="btn-dark" disabled=${!ex.text || !!jsonError(ex.text)} onClick=${() => { const t = schemaDoc(JSON.parse(ex.text)); upd(i, y => { y.text = t; }); this.setState({ example: null }); }} style="border:0;background:#17181C;color:#fff;border-radius:6px;padding:6px 11px;cursor:pointer;font-size:12px;font-weight:500">Replace with generated schema</button>
                      <button onClick=${() => this.setState({ example: null })} style="border:1px solid #E4E1D8;background:#fff;border-radius:6px;padding:6px 11px;cursor:pointer;font-size:12px">Cancel</button>
                    </div>
                  </div>`}
                <div style="display:flex;gap:6px;flex-wrap:wrap">
                  <button disabled=${!!parseErr} onClick=${() => upd(i, y => { y.text = pretty(JSON.parse(y.text)); })} style="border:1px solid #E4E1D8;background:#fff;border-radius:5px;padding:4px 9px;cursor:pointer;font-size:11.5px;color:#3E4047">Format</button>
                  ${!exOpen && html`<button onClick=${() => this.setState({ example: { i, text: '' } })} style="border:1px dashed #C9C6BC;background:none;border-radius:5px;padding:4px 9px;cursor:pointer;font-size:11.5px;color:#6A6D75">Generate from example JSON…</button>`}
                  <span style="flex:1"></span>
                  <button class="del" onClick=${() => this.mutCfg(cfg => { cfg.schemas.splice(i, 1); })} title=${uses.length ? 'Still used: the save will be rejected until the references are removed' : ''} style="border:0;background:none;color:#9A9CA2;cursor:pointer;font-size:12px">Delete schema</button>
                </div>
              </div>`;
            })}
          </div>
        </div>
      </div>`;
  }

  // ---------- one flow ----------
  renderFlow(f) {
    const s = this.state;
    const tabs = [['Pipeline', 'pipeline'], ['Mapping', 'mapping'], ['Tests', 'tests'], ['Rows (SQL)', 'rows']];
    return html`
      <div style="flex:1;min-height:0;display:flex;flex-direction:column">
        <div style="height:48px;flex:none;display:flex;align-items:center;gap:12px;padding:0 16px;background:#FFFFFF;border-bottom:1px solid #E4E1D8;overflow:hidden">
          <button class="x" onClick=${() => this.setState({ screen: 'flows' })} style="border:0;background:none;color:#6A6D75;cursor:pointer;padding:4px 0">Flows</button>
          <span style="color:#C9C6BC">/</span>
          <span style=${`font:600 10.5px ${MONO};color:#fff;background:${MC[f.method] || '#555'};padding:3px 6px;border-radius:4px`}>${f.method}</span>
          <span style=${`font:500 13px ${MONO};white-space:nowrap;overflow:hidden;text-overflow:ellipsis`}>${s.catalog.apiBasePath}${f.path}</span>
          <span style=${`font:11.5px ${MONO};color:#9A9CA2`}>${f.code}</span>
          <div style="flex:1"></div>
          <div style="display:flex;gap:2px;background:#F4F3EF;padding:3px;border-radius:8px;flex:none">
            ${tabs.map(([label, k]) => html`<button onClick=${() => this.setState({ tab: k }, () => this.changed())} style=${`border:0;background:${s.tab === k ? '#FFFFFF' : 'transparent'};box-shadow:${s.tab === k ? '0 1px 2px rgba(23,24,28,.12)' : 'none'};color:#17181C;padding:5px 12px;border-radius:6px;cursor:pointer;font-weight:500`}>${label}</button>`)}
          </div>
        </div>
        ${s.tab === 'pipeline' && this.renderPipeline(f)}
        ${s.tab === 'mapping' && this.renderMapping(f)}
        ${s.tab === 'rows' && this.renderRows(f)}
        ${s.tab === 'tests' && this.renderTests(f)}
      </div>`;
  }

  renderPipeline(f) {
    const s = this.state; const cat = s.catalog;
    const sel = s.sel || {};
    const selBorder = on => (on ? C.call : '#E2DFD6'); const selShadow = on => (on ? '0 0 0 3px oklch(0.62 0.17 42 / 0.15)' : '0 1px 2px rgba(23,24,28,.04)');
    const select = k => e => { if (e) e.stopPropagation(); this.setState({ sel: k }); };
    const isPal = z => d => d.kind === 'pal' && d.item.zone === z;
    const applyFlow = z => d => { this.mut(fl => { d.item.apply(fl); return { sel: { kind: z } }; }); };
    const chip = (label, color, clear) => ({ label, color, onRemove: e => { e.stopPropagation(); this.mut(clear); } });
    const P = this.flowProblems(f);
    const card = (on, extra = '') => `position:relative;flex:none;background:#fff;border:1px solid ${selBorder(on)};box-shadow:${selShadow(on)};border-radius:9px;padding:12px;cursor:pointer;${extra}`;

    const flowZone = this.zone('flow', isPal('flow'), applyFlow('flow'));
    const inZone = this.zone('in', isPal('in'), applyFlow('in'));
    const inChips = [];
    if (f.reqSchema) inChips.push(chip('schema ' + f.reqSchema, C.in, fl => { fl.reqSchema = ''; }));
    if (f.reqHandler) inChips.push(chip(f.reqHandler, C.java, fl => { fl.reqHandler = ''; }));

    const orders = [...new Set(f.steps.map(x => x.order))].sort((a, b) => a - b);
    const canPlace = d => d.kind === 'call' || d.kind === 'move';
    const groups = orders.map(o => {
      const steps = f.steps.filter(x => x.order === o).map(st => {
        const S2 = fl => fl.steps.find(x => x.id === st.id);
        const ch = [];
        if (st.condition) ch.push(chip('run if ' + st.condition.replace(/\$\{|\}/g, ''), C.step, fl => { S2(fl).condition = ''; }));
        if (st.success) ch.push(chip('ok if ' + st.success.replace(/\$\{|\}/g, ''), C.step, fl => { S2(fl).success = ''; }));
        if (st.timeout) ch.push(chip('timeout ' + st.timeout + 'ms', C.step, fl => { S2(fl).timeout = ''; }));
        if (st.respSchema) ch.push(chip('schema ' + st.respSchema, C.step, fl => { S2(fl).respSchema = ''; }));
        if (st.reqHandler) ch.push(chip(st.reqHandler, C.java, fl => { S2(fl).reqHandler = ''; }));
        if (st.respHandler) ch.push(chip(st.respHandler, C.java, fl => { S2(fl).respHandler = ''; }));
        if (st.bodyCodec) ch.push(chip(st.bodyCodec, C.java, fl => { S2(fl).bodyCodec = ''; }));
        return { st, chips: ch, problem: P.some(m => m.includes(`step '${st.name}'`)), on: sel.kind === 'step' && sel.id === st.id,
          zone: this.zone('step-' + st.id, isPal('step'), d => { this.mut(fl => { d.item.apply(S2(fl), fl); return { sel: { kind: 'step', id: st.id } }; }); }) };
      });
      return { order: o, steps, zone: this.zone('grp-' + o, canPlace, d => this.place(d, o, false)), gap: this.zone('gap-' + o, canPlace, d => this.place(d, o, true)) };
    });
    const nextO = (orders.length ? orders[orders.length - 1] : 0) + 1;
    const endGap = this.zone('end', canPlace, d => this.place(d, nextO, false));

    const outZone = this.zone('out', isPal('out'), applyFlow('out'));
    const outChips = [];
    if (f.respSchema) outChips.push(chip('schema ' + f.respSchema, C.out, fl => { fl.respSchema = ''; }));
    if (f.respHandler) outChips.push(chip(f.respHandler, C.java, fl => { fl.respHandler = ''; }));
    if (String(f.successStatus) !== '200') outChips.push(chip('success_status ' + f.successStatus, C.out, fl => { fl.successStatus = '200'; }));
    const toMap = scope => e => { if (e) e.stopPropagation(); this.setState({ tab: 'mapping', scope, preview: null }, () => this.changed()); };

    const errZone = this.zone('err', isPal('err'), applyFlow('err'));
    const ehName = f.errorHandler || 'defaultErrorHandler';
    const eh = cat.errorHandlers.find(h => h.name === ehName);
    const ehDesc = !eh ? 'Not a known ErrorHandler bean.' : eh.lookupCode ? `LookupErrorHandler · reads ${eh.codePath} of the failed call and translates it through ${eh.lookupCode}.` : ehName === 'defaultErrorHandler' ? 'Standard body: errorCode, errorMessage, correlationId, step, details.' : 'Custom ErrorHandler bean.';
    const lrows = eh && eh.lookupCode ? (s.cfg.lookups[eh.lookupCode] || []).map(r => { let t = {}; try { t = JSON.parse(r.tgt); } catch (e) { /* text target */ } return { src: r.src, status: t.status || '', code: t.errorCode || r.tgt }; }) : [];

    return html`
      <div style="flex:1;min-height:0;display:flex">
        <aside style="width:248px;flex:none;background:#FFFFFF;border-right:1px solid #E4E1D8;overflow:auto;padding:16px 12px 32px">
          <div style="font-size:12px;color:#6A6D75;margin:0 4px 16px;line-height:1.45">Drag a policy onto the pipeline. Each drop sets a column or adds a row.</div>
          ${this.palette().map(c => html`
            <div style="margin-bottom:18px">
              <div style=${`display:flex;align-items:center;gap:7px;margin:0 4px 7px;font:600 10px ${MONO};letter-spacing:.08em;text-transform:uppercase;color:#6A6D75`}><span style=${`width:7px;height:7px;border-radius:2px;background:${c.color}`}></span>${c.cat}</div>
              <div style="display:flex;flex-direction:column;gap:4px">
                ${c.items.map(it => html`
                  <div class="pal" draggable="true" onDragStart=${this.ds(it.kind === 'call' ? { kind: 'call', target: it.target } : { kind: 'pal', item: it })} onDragEnd=${this.dragEnd} style="display:flex;align-items:center;gap:9px;padding:7px 8px 7px 9px;border:1px solid #E4E1D8;border-radius:7px;background:#FAF9F6;cursor:grab">
                    <span style=${`width:8px;height:8px;border-radius:50%;flex:none;border:2px solid ${c.color}`}></span>
                    <div style="flex:1;min-width:0">
                      <div style="font-weight:500">${it.label}</div>
                      <div style=${`font:10.5px ${MONO};color:#9A9CA2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis`} title=${it.sub}>${it.sub}</div>
                    </div>
                    ${it.java && html`<span style=${`font:600 9px ${MONO};color:#7A5B12;background:#F4EAD0;padding:2px 4px;border-radius:3px`}>JAVA</span>`}
                  </div>`)}
              </div>
            </div>`)}
        </aside>

        <div style="flex:1;min-width:0;position:relative;display:flex">
          <main style="flex:1;min-width:0;overflow:auto;background-color:#F4F3EF;background-image:radial-gradient(#D9D6CC 1px,transparent 1px);background-size:18px 18px">
            <div style="padding:28px 32px 160px;display:flex;flex-direction:column;gap:22px;width:max-content">

              <div onClick=${select({ kind: 'flow' })} ...${zoneProps(flowZone)} style=${`position:relative;align-self:flex-start;display:flex;align-items:center;gap:14px;padding:9px 14px;background:#fff;border:1px solid ${selBorder(sel.kind === 'flow')};border-radius:9px;cursor:pointer`}>
                <span style=${`font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75`}>FLOW</span>
                <span style="font-weight:600">${f.name || f.code}</span>
                <span style=${`font:11.5px ${MONO};color:#6A6D75`}>timeout ${f.timeout ? f.timeout + 'ms' : 'default'}</span>
                <span style=${`font:11.5px ${MONO};color:#6A6D75`}>audit ${f.audit}</span>
                <span style=${`font:11.5px ${MONO};color:${f.enabled ? '#6A6D75' : C.err}`}>${f.enabled ? 'enabled' : 'disabled'}</span>
                ${zoneOverlay(flowZone, C.call)}
              </div>

              <div style="display:flex;align-items:flex-start">
                <div style="width:156px;flex:none;padding:12px 13px;border-radius:9px;background:#17181C;color:#F4F3EF">
                  <div style=${`font:600 10px ${MONO};letter-spacing:.08em;color:#8E9097`}>CLIENT REQUEST</div>
                  <div style=${`margin-top:8px;font:600 12px ${MONO};color:oklch(0.75 0.14 50)`}>${f.method}</div>
                  <div style=${`margin-top:2px;font:11.5px/1.4 ${MONO};word-break:break-all`}>${cat.apiBasePath}${f.path}</div>
                </div>
                ${connector}

                <div onClick=${select({ kind: 'in' })} ...${zoneProps(inZone)} style=${card(sel.kind === 'in', 'width:206px')}>
                  <div style="display:flex;align-items:center;gap:7px"><span style=${`width:7px;height:7px;border-radius:2px;background:${C.in}`}></span><span style="font-weight:600">Inbound</span></div>
                  <div style="margin-top:4px;font-size:11.5px;color:#6A6D75;line-height:1.4">Runs before any downstream call</div>
                  ${inChips.length > 0 ? html`<div style="margin-top:10px;display:flex;flex-direction:column;gap:4px">${inChips.map(chipView)}</div>`
                    : html`<div style="margin-top:10px;padding:10px;border:1px dashed #D9D6CC;border-radius:6px;font-size:11.5px;color:#9A9CA2;text-align:center">Drop schema or handler</div>`}
                  ${zoneOverlay(inZone, C.in)}
                </div>
                ${connector}

                ${groups.map(g => html`
                  <div style="display:flex;align-items:flex-start">
                    ${g.gap.active && html`
                      <div style="display:flex;align-items:flex-start">
                        <div ...${zoneProps(g.gap)} style=${`width:112px;height:120px;margin-top:20px;border:1.5px dashed ${C.call};border-radius:9px;background:${g.gap.bg};display:grid;place-items:center;text-align:center;padding:8px;font-size:11.5px;line-height:1.35;color:oklch(0.45 0.14 42)`}>Insert as step_order ${g.order}<br/>(runs before)</div>
                        ${connector}
                      </div>`}
                    <div ...${zoneProps(g.zone)} style="position:relative;width:240px;flex:none;display:flex;flex-direction:column;gap:8px">
                      <div style=${`display:flex;justify-content:space-between;align-items:center;height:16px;margin-top:-22px;font:600 10px ${MONO};letter-spacing:.06em;text-transform:uppercase;color:#6A6D75;padding:0 2px`}>
                        <span>step_order ${g.order}</span>
                        ${g.steps.length > 1 && html`<span style="color:oklch(0.55 0.16 42)">parallel · ${g.steps.length}</span>`}
                      </div>
                      ${g.steps.map(x => html`
                        <div draggable="true" onDragStart=${this.ds({ kind: 'move', id: x.st.id })} onDragEnd=${this.dragEnd} onClick=${select({ kind: 'step', id: x.st.id })} ...${zoneProps(x.zone)} style=${card(x.on, 'padding:10px 12px;opacity:' + (x.st.enabled ? 1 : 0.55))}>
                          <div style="display:flex;align-items:center;gap:7px">
                            <span style=${`font:600 10px ${MONO};color:#fff;background:${MC[x.st.method] || '#555'};padding:2px 5px;border-radius:4px`}>${x.st.method}</span>
                            <span style="font-weight:600;font-size:13.5px">${x.st.name}</span>
                            <span style="flex:1"></span>
                            ${!x.st.enabled && html`<span style=${`font:10px ${MONO};color:#9A9CA2`}>disabled</span>`}
                            ${x.problem && html`<span style=${`width:16px;height:16px;border-radius:50%;background:${C.err};color:#fff;display:grid;place-items:center;font:700 10px ${MONO}`}>!</span>`}
                          </div>
                          <div style=${`margin-top:6px;font:11px/1.4 ${MONO};color:#6A6D75;word-break:break-all`}><span style="color:#17181C">${x.st.target}</span> ${x.st.path}</div>
                          ${x.chips.length > 0 && html`<div style="margin-top:8px;display:flex;flex-direction:column;gap:4px">${x.chips.map(chipView)}</div>`}
                          <div style=${`margin-top:9px;padding-top:8px;border-top:1px solid #EFEDE6;display:flex;justify-content:space-between;font:10.5px ${MONO};color:#9A9CA2`}>
                            <span>${x.st.rules.length} request rule${x.st.rules.length === 1 ? '' : 's'}</span><span style=${`color:${x.st.onFailure === 'CONTINUE' ? C.out : '#9A9CA2'}`}>${x.st.onFailure}</span>
                          </div>
                          ${zoneOverlay(x.zone, C.step, 'attach to ' + x.st.name)}
                        </div>`)}
                      ${g.zone.active && html`<div style=${`height:54px;border:1.5px dashed ${C.call};border-radius:9px;background:${g.zone.bg};display:grid;place-items:center;font-size:11.5px;color:oklch(0.45 0.14 42)`}>Run in parallel at step_order ${g.order}</div>`}
                    </div>
                    ${connector}
                  </div>`)}

                ${endGap.active && html`
                  <div style="display:flex;align-items:flex-start">
                    <div ...${zoneProps(endGap)} style=${`width:132px;height:120px;border:1.5px dashed ${C.call};border-radius:9px;background:${endGap.bg};display:grid;place-items:center;text-align:center;padding:8px;font-size:11.5px;line-height:1.35;color:oklch(0.45 0.14 42)`}>New step_order ${nextO}<br/>(runs after)</div>
                    ${connector}
                  </div>`}
                ${f.steps.length === 0 && !endGap.active && html`
                  <div style="display:flex;align-items:flex-start">
                    <div style="width:200px;padding:18px 14px;border:1.5px dashed #C9C6BC;border-radius:9px;text-align:center;font-size:12px;line-height:1.45;color:#6A6D75">Drag a <b>Call</b> from the palette to add the first downstream step</div>
                    ${connector}
                  </div>`}

                <div onClick=${select({ kind: 'out' })} ...${zoneProps(outZone)} style=${card(sel.kind === 'out', 'width:216px')}>
                  <div style="display:flex;align-items:center;gap:7px"><span style=${`width:7px;height:7px;border-radius:2px;background:${C.out}`}></span><span style="font-weight:600">Client response</span></div>
                  <div style=${`margin-top:4px;font:11px ${MONO};color:#6A6D75`}>${f.response.length} FLOW_RESPONSE rules</div>
                  <button class="btn-line" onClick=${toMap('resp')} style="margin-top:9px;width:100%;border:1px solid #E4E1D8;background:#FAF9F6;border-radius:6px;padding:6px 8px;cursor:pointer;font-size:12px;font-weight:500;text-align:left;display:flex;justify-content:space-between">Edit response mapping<span>→</span></button>
                  ${outChips.length > 0 && html`<div style="margin-top:8px;display:flex;flex-direction:column;gap:4px">${outChips.map(chipView)}</div>`}
                  ${zoneOverlay(outZone, C.out)}
                </div>
                ${connector}
                <div style="width:120px;flex:none;padding:12px 13px;border-radius:9px;background:#17181C;color:#F4F3EF">
                  <div style=${`font:600 10px ${MONO};letter-spacing:.08em;color:#8E9097`}>CLIENT</div>
                  <div style=${`margin-top:8px;font:600 20px ${MONO};color:oklch(0.78 0.12 160)`}>${f.successStatus}</div>
                  <div style=${`margin-top:2px;font:11px ${MONO};color:#8E9097`}>+ X-Correlation-Id</div>
                </div>
              </div>

              <div onClick=${select({ kind: 'err' })} ...${zoneProps(errZone)} style=${`position:relative;align-self:flex-start;margin-left:182px;display:flex;gap:22px;align-items:flex-start;background:#fff;border:1px solid ${selBorder(sel.kind === 'err')};box-shadow:${selShadow(sel.kind === 'err')};border-radius:9px;padding:12px 14px;cursor:pointer`}>
                <div style="width:190px">
                  <div style="display:flex;align-items:center;gap:7px"><span style=${`width:7px;height:7px;border-radius:2px;background:${C.err}`}></span><span style="font-weight:600">On failure</span></div>
                  <div style="margin-top:4px;font-size:11.5px;color:#6A6D75;line-height:1.4">A STOP step fails, a schema check fails, or a mapping/handler throws</div>
                </div>
                <div style="width:220px">
                  <div style=${`font:10px ${MONO};letter-spacing:.06em;color:#9A9CA2`}>ERROR_HANDLER</div>
                  <div style=${`margin-top:4px;font:600 13px ${MONO}`}>${ehName}</div>
                  <div style="margin-top:4px;font-size:11.5px;color:#6A6D75;line-height:1.4">${ehDesc}</div>
                </div>
                ${lrows.length > 0 && html`
                  <div style="min-width:260px">
                    <div style=${`font:10px ${MONO};letter-spacing:.06em;color:#9A9CA2`}>gw_lookup_entry · ${eh.lookupCode}</div>
                    <div style="margin-top:6px;display:flex;flex-direction:column;gap:3px">
                      ${lrows.map(r => html`<div style=${`display:grid;grid-template-columns:36px 36px 1fr;gap:8px;font:11px ${MONO}`}><span style="color:#6A6D75">${r.src}</span><span style="color:oklch(0.5 0.17 25)">${r.status}</span><span>${r.code}</span></div>`)}
                    </div>
                  </div>`}
                ${zoneOverlay(errZone, C.err)}
              </div>
            </div>
          </main>

          <div style="position:absolute;left:16px;bottom:16px;max-width:min(560px,calc(100% - 32px))">
            ${P.length > 0 ? html`
              <div style="background:#fff;border:1px solid oklch(0.85 0.06 25);border-radius:9px;box-shadow:0 6px 24px rgba(23,24,28,.08);overflow:hidden">
                <div style="padding:8px 12px;background:oklch(0.96 0.025 25);font-weight:600;color:oklch(0.45 0.17 25);font-size:12px">${P.length} problem${P.length === 1 ? '' : 's'} · a save would be rejected with 422</div>
                <div style="max-height:150px;overflow:auto">
                  ${P.map(m => html`<div class="row" onClick=${() => this.setState(this.problemTarget(m, f))} style=${`padding:7px 12px;border-top:1px solid #F1EFE9;font:11px/1.45 ${MONO};cursor:pointer`}>${m}</div>`)}
                </div>
              </div>` : html`
              <div style="display:inline-flex;align-items:center;gap:7px;padding:6px 11px;background:#fff;border:1px solid #E4E1D8;border-radius:20px;font-size:12px;color:#3D6B4F"><span style="width:7px;height:7px;border-radius:50%;background:oklch(0.62 0.13 155)"></span>${this.dirty() ? 'Flow valid · ready to save' : 'Flow valid · saved'}</div>`}
          </div>
        </div>

        ${this.renderInspector(f, toMap)}
      </div>`;
  }

  renderInspector(f, toMap) {
    const s = this.state; const cat = s.catalog; const sel = s.sel || {};
    const none = (arr, l = '— none') => [{ v: '', l }, ...arr.map(v => ({ v, l: v }))];
    const plain = arr => arr.map(v => ({ v, l: v }));
    const fld = (label, col, value, kind, onSet, extra = {}) => ({ label, col, value: str(value), kind, onSet, ...extra });
    const withCurrent = (opts, v) => (v && !opts.some(o => o.v === v) ? [...opts, { v, l: v + ' (unknown)' }] : opts);
    let insp;
    const st = sel.kind === 'step' ? f.steps.find(x => x.id === sel.id) : null;
    if (st) {
      const S2 = fl => fl.steps.find(x => x.id === st.id);
      const targets = this.allTargets().map(t => t.code);
      insp = { kind: 'Downstream step', color: C.call, title: st.name, table: 'gw_flow_step', fields: [
        fld('Name', 'name', st.name, 'text', (fl, v) => { S2(fl).name = v; }, { hint: 'Later rules read this call as $.steps.' + st.name }),
        fld('Target system', 'target_system', st.target, 'select', (fl, v) => { S2(fl).target = v; }, { options: withCurrent(plain(targets), st.target) }),
        fld('Method', 'http_method', st.method, 'select', (fl, v) => { S2(fl).method = v; }, { options: plain(METHODS) }),
        fld('Path', 'path_template', st.path, 'text', (fl, v) => { S2(fl).path = v; }, { hint: 'Appended to the base URL. Each {var} needs a PATH rule' }),
        fld('Order', 'step_order', st.order, 'text', (fl, v) => { const n = parseInt(v, 10); if (n > 0) S2(fl).order = n; }, { hint: 'Same number = run in parallel' }),
        fld('If it fails', 'on_failure', st.onFailure, 'select', (fl, v) => { S2(fl).onFailure = v; }, { options: [{ v: 'STOP', l: 'STOP · end with error' }, { v: 'CONTINUE', l: 'CONTINUE · record and carry on' }] }),
        fld('Run only if', 'condition_expr', st.condition, 'area', (fl, v) => { S2(fl).condition = v; }, { placeholder: "${steps.x.body.status} == 'ACTIVE'" }),
        fld('Success when', 'success_expr', st.success, 'area', (fl, v) => { S2(fl).success = v; }, { placeholder: '${steps.' + st.name + ".body.responseCode} == '00'" }),
        fld('Timeout (ms)', 'timeout_ms', st.timeout, 'text', (fl, v) => { S2(fl).timeout = v.replace(/\D/g, ''); }, { hint: 'Empty = target read timeout, else ' + cat.defaultStepTimeoutMs }),
        fld('Response schema', 'response_schema_code', st.respSchema, 'select', (fl, v) => { S2(fl).respSchema = v; }, { options: withCurrent(none(this.schemaCodes()), st.respSchema), actions: this.schemaActions(st.respSchema, () => this.sampleOf(f).steps?.[st.name]?.body, f.code + '_' + st.name.toUpperCase().replace(/[^A-Z0-9_]/g, '_') + '_RESPONSE', (fl, c) => { S2(fl).respSchema = c; }) }),
        fld('Request handler', 'request_handler', st.reqHandler, 'select', (fl, v) => { S2(fl).reqHandler = v; }, { options: withCurrent(none(cat.messageHandlers), st.reqHandler) }),
        fld('Response handler', 'response_handler', st.respHandler, 'select', (fl, v) => { S2(fl).respHandler = v; }, { options: withCurrent(none(cat.messageHandlers), st.respHandler) }),
        fld('Wire format', 'body_codec', st.bodyCodec, 'select', (fl, v) => { S2(fl).bodyCodec = v; }, { options: withCurrent(none(cat.bodyCodecs, '— target system default'), st.bodyCodec) }),
        fld('Enabled', 'enabled', String(st.enabled), 'select', (fl, v) => { S2(fl).enabled = v === 'true'; }, { options: [{ v: 'true', l: 'true' }, { v: 'false', l: 'false · skipped with its rules' }] })
      ], mapping: { label: 'Edit request mapping · ' + st.rules.length + ' rules', go: toMap(st.id) },
        del: { label: 'Delete step', go: () => this.mut(fl => { fl.steps = fl.steps.filter(x => x.id !== st.id); this.norm(fl); return { sel: { kind: 'flow' } }; }) } };
    } else if (sel.kind === 'in') {
      insp = { kind: 'Inbound', color: C.in, title: f.method + ' ' + f.path, table: 'gw_flow', fields: [
        fld('Method', 'http_method', f.method, 'select', (fl, v) => { fl.method = v; }, { options: plain(METHODS) }),
        fld('Path', 'path_pattern', f.path, 'text', (fl, v) => { fl.path = v; }, { hint: 'Relative to ' + cat.apiBasePath + '. {name} → $.request.path.name' }),
        fld('Request schema', 'request_schema_code', f.reqSchema, 'select', (fl, v) => { fl.reqSchema = v; }, { options: withCurrent(none(this.schemaCodes()), f.reqSchema), hint: 'Invalid body → 400', actions: this.schemaActions(f.reqSchema, () => this.sampleOf(f).request?.body, f.code + '_REQUEST', (fl, c) => { fl.reqSchema = c; }) }),
        fld('Request handler', 'request_handler', f.reqHandler, 'select', (fl, v) => { fl.reqHandler = v; }, { options: withCurrent(none(cat.messageHandlers), f.reqHandler), hint: 'MessageHandler. May short-circuit with its own response.' })
      ] };
    } else if (sel.kind === 'out') {
      insp = { kind: 'Client response', color: C.out, title: 'HTTP ' + f.successStatus, table: 'gw_flow', fields: [
        fld('Success status', 'success_status', f.successStatus, 'select', (fl, v) => { fl.successStatus = v; }, { options: withCurrent(plain(['200', '201', '202', '204']), f.successStatus) }),
        fld('Response schema', 'response_schema_code', f.respSchema, 'select', (fl, v) => { fl.respSchema = v; }, { options: withCurrent(none(this.schemaCodes()), f.respSchema), hint: 'Invalid → 500', actions: this.schemaActions(f.respSchema, () => this.responseExample(f), f.code + '_RESPONSE', (fl, c) => { fl.respSchema = c; }) }),
        fld('Response handler', 'response_handler', f.respHandler, 'select', (fl, v) => { fl.respHandler = v; }, { options: withCurrent(none(cat.messageHandlers), f.respHandler) })
      ], mapping: { label: 'Edit response mapping · ' + f.response.length + ' rules', go: toMap('resp') } };
    } else if (sel.kind === 'err') {
      insp = { kind: 'On failure', color: C.err, title: f.errorHandler || 'defaultErrorHandler', table: 'gw_flow.error_handler', fields: [
        fld('Error handler', 'error_handler', f.errorHandler, 'select', (fl, v) => { fl.errorHandler = v; }, { options: withCurrent([{ v: '', l: 'defaultErrorHandler' }, ...plain(cat.errorHandlers.map(h => h.name).filter(n => n !== 'defaultErrorHandler'))], f.errorHandler), hint: 'New error codes are new lookup rows plus a save. No code change.' })
      ] };
    } else {
      insp = { kind: 'Flow', color: C.flow, title: f.code, table: 'gw_flow', fields: [
        fld('Code', 'code', f.code, 'text', (fl, v) => { fl.code = v.toUpperCase().replace(/[^A-Z0-9_]/g, '_'); }, { hint: 'Appears in logs and audit' }),
        fld('Name', 'name', f.name, 'text', (fl, v) => { fl.name = v; }),
        fld('Flow timeout (ms)', 'timeout_ms', f.timeout, 'text', (fl, v) => { fl.timeout = v.replace(/\D/g, ''); }, { hint: 'Empty = ' + cat.defaultFlowTimeoutMs + '. Exceeded → 504' }),
        fld('Audit', 'audit_mode', f.audit, 'select', (fl, v) => { fl.audit = v; }, { options: plain(['INHERIT', 'ON', 'OFF']) }),
        fld('Enabled', 'enabled', String(f.enabled), 'select', (fl, v) => { fl.enabled = v === 'true'; }, { options: [{ v: 'true', l: 'true' }, { v: 'false', l: 'false · not routed' }] })
      ], del: { label: 'Delete flow', go: () => this.mutCfg((cfg, st2) => { cfg.flows.splice(st2.cur, 1); setTimeout(() => this.setState({ screen: 'flows', cur: 0, sel: { kind: 'flow' } }), 0); }) } };
    }
    const onSet = fd => e => { const v = e.currentTarget.value; this.mut(fl => fd.onSet(fl, v)); };
    return html`
      <aside style="width:316px;flex:none;background:#fff;border-left:1px solid #E4E1D8;overflow:auto">
        <div style="padding:14px 16px;border-bottom:1px solid #EFEDE6">
          <div style=${`display:flex;align-items:center;gap:7px;font:600 10px ${MONO};letter-spacing:.08em;text-transform:uppercase;color:#6A6D75`}><span style=${`width:7px;height:7px;border-radius:2px;background:${insp.color}`}></span>${insp.kind}</div>
          <div style="margin-top:6px;font-size:17px;font-weight:600;letter-spacing:-0.01em;word-break:break-all">${insp.title}</div>
          <div style=${`margin-top:3px;font:11px ${MONO};color:#9A9CA2`}>${insp.table}</div>
        </div>
        <div style="padding:14px 16px 28px;display:flex;flex-direction:column;gap:13px">
          ${insp.fields.map(fd => html`
            <label style="display:flex;flex-direction:column;gap:5px">
              <span style="display:flex;justify-content:space-between;gap:8px;font-size:12px;font-weight:500">${fd.label}<span style=${`font:10.5px ${MONO};color:#9A9CA2;font-weight:400`}>${fd.col}</span></span>
              ${fd.kind === 'text' && html`<input class="inp" value=${fd.value} onInput=${onSet(fd)} style=${inputStyle()}/>`}
              ${fd.kind === 'select' && html`<select class="inp" value=${fd.value} onChange=${onSet(fd)} style=${inputStyle() + ';padding:0 6px'}>${fd.options.map(o => html`<option value=${o.v}>${o.l}</option>`)}</select>`}
              ${fd.kind === 'area' && html`<textarea class="inp" value=${fd.value} onInput=${onSet(fd)} rows="3" placeholder=${fd.placeholder || ''} style=${`border:1px solid #E4E1D8;border-radius:6px;padding:7px 9px;font:11.5px/1.45 ${MONO};background:#FAF9F6;resize:vertical;width:100%`}></textarea>`}
              ${fd.hint && html`<span style="font-size:11px;color:#9A9CA2;line-height:1.4">${fd.hint}</span>`}
              ${fd.actions && html`<span style="display:flex;gap:6px;flex-wrap:wrap">${fd.actions.map(a => html`<button type="button" class="btn-line" onClick=${e => { e.preventDefault(); a.go(); }} title=${a.title || ''} style="border:1px solid #E4E1D8;background:#FAF9F6;border-radius:5px;padding:3px 8px;cursor:pointer;font-size:11.5px;color:#3E4047">${a.label}</button>`)}</span>`}
            </label>`)}
          ${insp.mapping && html`<button class="btn-dark" onClick=${insp.mapping.go} style="margin-top:4px;border:1px solid #17181C;background:#17181C;color:#fff;border-radius:7px;padding:9px 12px;cursor:pointer;font-weight:500;display:flex;justify-content:space-between">${insp.mapping.label}<span>→</span></button>`}
          ${insp.del && html`<button class="btn-del" onClick=${insp.del.go} style="border:1px solid #E4E1D8;background:#fff;color:oklch(0.5 0.17 25);border-radius:7px;padding:8px 12px;cursor:pointer;font-weight:500">${insp.del.label}</button>`}
        </div>
      </aside>`;
  }

  renderMapping(f) {
    const s = this.state; const cat = s.catalog;
    const scopeStep = s.scope !== 'resp' ? f.steps.find(x => x.id === s.scope) : null;
    const scope = scopeStep ? scopeStep.id : 'resp';
    const rules = scopeStep ? scopeStep.rules : f.response;
    const updRule = (id, fn) => this.mut(fl => { const list = scope === 'resp' ? fl.response : fl.steps.find(x => x.id === scope).rules; const i = list.findIndex(x => x.id === id); fn(list[i], list, i); });
    const listOf = fl => (scope === 'resp' ? fl.response : fl.steps.find(x => x.id === scope).rules);

    // sample context: stored text if the developer edited it, otherwise synthesized from the rules
    const stored = this.sampleText(f);
    let sample = null, sampleErr = '';
    try { sample = stored ? JSON.parse(stored) : synthSample(f, s.cfg.lookups); } catch (e) { sampleErr = e.message; }
    const tree = [];
    const flat = (v, path, depth) => {
      Object.keys(v).forEach(k => {
        const c = v[k]; const p = /^[A-Za-z0-9_-]+$/.test(k) ? path + '.' + k : path + "['" + k + "']";
        const obj = c !== null && typeof c === 'object';
        tree.push({ key: k, path: p, depth, sample: obj ? (Array.isArray(c) ? '[' + c.length + ']' : '') : JSON.stringify(c) });
        if (Array.isArray(c)) { if (c[0] && typeof c[0] === 'object') { tree.push({ key: '[*]', path: p + '[*]', depth: depth + 1, sample: '' }); flat(c[0], p + '[*]', depth + 2); } } else if (obj) flat(c, p, depth + 1);
      });
    };
    if (sample) {
      const visible = { request: sample.request || {}, steps: {}, correlationId: 'preview-correlation-id' };
      Object.keys(sample.steps || {}).forEach(n => { const o = f.steps.find(x => x.name === n); if (!scopeStep || (o && o.order < scopeStep.order)) visible.steps[n] = sample.steps[n]; });
      flat(visible, '$', 0);
    }
    const sorted = [...f.steps].sort((a, b) => a.order - b.order);
    const scopes = [...sorted.map(st => ({ id: st.id, label: st.name, sub: st.order + ' ·' })), { id: 'resp', label: 'Client response', sub: '→' }];
    const ruleAccept = d => ['src', 'conv', 'lookup', 'fh'].includes(d.kind);
    const types = scopeStep ? cat.targetTypes : ['BODY', 'HEADER'];

    const pv = s.preview && s.preview.key === f.code + '/' + (scopeStep ? scopeStep.name : '') ? s.preview : null;
    const resText = (r, i) => {
      const x = pv && pv.rules && pv.rules[i];
      if (!x) return { t: '', c: '#9A9CA2' };
      if (x.error) return { t: 'error · ' + x.error, c: 'oklch(0.55 0.18 25)' };
      if (x.written) return { t: '→ ' + JSON.stringify(x.value), c: 'oklch(0.48 0.11 160)' };
      return r.required ? { t: 'missing (required)', c: 'oklch(0.55 0.18 25)' } : { t: 'skipped · source missing', c: '#9A9CA2' };
    };
    const newZone = this.zone('rule-new', d => d.kind === 'src', d => this.mut(fl => {
      const list = listOf(fl);
      const tk = toks(d.path).filter(x => x !== '*' && typeof x !== 'number'); const key = tk[tk.length - 1] || 'value';
      const st2 = scope === 'resp' ? null : fl.steps.find(x => x.id === scope);
      const pvar = st2 ? (st2.path.match(/\{([^}]+)\}/g) || []).map(v => v.slice(1, -1)).find(v => !st2.rules.some(r => r.type === 'PATH' && r.target === v)) : null;
      const isPath = pvar && /request\.path\./.test(d.path);
      list.push({ id: uid('r'), type: isPath ? 'PATH' : 'BODY', target: isPath ? pvar : '$.' + key, source: d.path, constant: '', def: '', lookup: '', conv: '', fh: '', required: !!isPath });
    }));
    const nzBorder = newZone.active ? C.in : '#C9C6BC'; const nzBg = newZone.over ? 'oklch(0.56 0.13 255 / 0.1)' : newZone.active ? 'oklch(0.56 0.13 255 / 0.04)' : 'transparent';
    const addConst = () => this.mut(fl => { listOf(fl).push({ id: uid('r'), type: 'BODY', target: '$.field', source: '', constant: '', def: '', lookup: '', conv: '', fh: '', required: false }); });
    const dragChip = (label, payload, style) => html`<div class="pal" draggable="true" onDragStart=${this.ds(payload)} onDragEnd=${this.dragEnd} style=${`padding:4px 7px;border-radius:5px;font:11px ${MONO};cursor:grab;${style}`}>${label}</div>`;

    // preview panel
    const out = pv && pv.output;
    let line, headers = [], body = '', note;
    if (scopeStep) {
      const t = this.allTargets().find(x => x.code === scopeStep.target);
      const baseUrl = t ? resolveEnv(t.base) : '<' + scopeStep.target + '?>';
      const path = scopeStep.path.replace(/\{([^}]+)\}/g, (m, k) => (out && out.path[k] !== undefined ? encodeURIComponent(out.path[k]) : m));
      const qs = out ? Object.keys(out.query).map(k => k + '=' + encodeURIComponent(out.query[k])).join('&') : '';
      line = scopeStep.method + ' ' + baseUrl.replace(/\/$/, '') + path + (qs ? '?' + qs : '');
      headers = [...(t ? t.headers.map(h => ({ n: h.n, v: resolveEnv(h.v) })) : []), ...(out ? Object.keys(out.headers).map(k => ({ n: k, v: out.headers[k] })) : []), { n: 'X-Correlation-Id', v: 'preview-correlation-id' }];
      if (scopeStep.reqHandler) headers.push({ n: '…', v: 'plus whatever ' + scopeStep.reqHandler + ' adds' });
      body = scopeStep.method === 'GET' ? '(no body is sent for GET)' : out ? pretty(out.body) : '';
      note = 'Built by the server from STEP_REQUEST rules with the real converters, lookups and field handlers. This step can read ' + (sorted.filter(x => x.order < scopeStep.order).map(x => x.name).join(', ') || 'only the inbound request') + '.';
    } else {
      line = 'HTTP/1.1 ' + f.successStatus;
      headers = [{ n: 'X-Correlation-Id', v: 'preview-correlation-id' }, ...(out ? Object.keys(out.headers).map(k => ({ n: k, v: out.headers[k] })) : [])];
      body = out ? pretty(out.body) : '';
      note = 'Built by the server from FLOW_RESPONSE rules using the sample context. Error responses are built by ' + (f.errorHandler || 'defaultErrorHandler') + ' instead.';
    }
    const pvErrs = sampleErr ? ['sample context is not valid JSON: ' + sampleErr] : pv ? (pv.errors || []) : [];
    const sampleValue = stored !== undefined ? stored : (sample ? pretty(sample) : '');

    return html`
      <div style="flex:1;min-height:0;display:flex">
        <aside style="width:290px;flex:none;background:#fff;border-right:1px solid #E4E1D8;overflow:auto;padding:16px 10px 32px">
          <div style=${`margin:0 6px 4px;font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75`}>CONTEXT</div>
          <div style="margin:0 6px 10px;font-size:11.5px;color:#9A9CA2;line-height:1.4">${scopeStep ? 'Inbound request plus steps with a lower step_order than ' + scopeStep.name + '.' : 'Inbound request plus every step result.'} Drag a field onto a rule.</div>
          <div style="display:flex;flex-direction:column">
            ${tree.map(n => html`
              <div class="ctx" draggable="true" onDragStart=${this.ds({ kind: 'src', path: n.path })} onDragEnd=${this.dragEnd} title=${n.path} style=${`display:flex;align-items:center;gap:8px;padding:4px 6px 4px ${6 + n.depth * 14}px;border-radius:5px;cursor:grab;font:11.5px ${MONO}`}>
                <span style=${`color:${n.depth === 0 ? '#17181C' : n.sample === '' ? '#3E4047' : 'oklch(0.42 0.12 255)'};font-weight:${n.depth === 0 ? 600 : 400}`}>${n.key}</span>
                <span style="flex:1;min-width:0;text-align:right;color:#9A9CA2;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${n.sample}</span>
              </div>`)}
          </div>
          <div style=${`margin:22px 6px 8px;font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75`}>CONVERTERS</div>
          <div style="display:flex;flex-wrap:wrap;gap:5px;padding:0 6px">${cat.converters.map(v => dragChip(v, { kind: 'conv', v }, 'border:1px solid #E4E1D8;background:#FAF9F6'))}</div>
          ${Object.keys(s.cfg.lookups).length > 0 && html`
            <div style=${`margin:20px 6px 8px;font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75`}>LOOKUPS</div>
            <div style="display:flex;flex-wrap:wrap;gap:5px;padding:0 6px">${Object.keys(s.cfg.lookups).map(v => dragChip(v, { kind: 'lookup', v }, 'border:1px solid oklch(0.88 0.05 160);background:oklch(0.97 0.02 160)'))}</div>`}
          ${cat.fieldHandlers.length > 0 && html`
            <div style=${`margin:20px 6px 8px;font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75`}>FIELD HANDLERS</div>
            <div style="display:flex;flex-wrap:wrap;gap:5px;padding:0 6px">${cat.fieldHandlers.map(v => dragChip(v, { kind: 'fh', v }, 'border:1px solid #EBDDB8;background:#FBF5E6'))}</div>`}
        </aside>

        <main style="flex:1;min-width:0;overflow:auto;padding:18px 24px 60px">
          <div style="display:flex;flex-wrap:wrap;gap:6px">
            ${scopes.map(x => html`<button onClick=${() => this.setState({ scope: x.id, preview: null }, () => this.changed())} style=${`display:flex;align-items:center;gap:7px;border:1px solid ${x.id === scope ? '#17181C' : '#E4E1D8'};background:${x.id === scope ? '#17181C' : '#fff'};color:${x.id === scope ? '#fff' : '#17181C'};border-radius:7px;padding:6px 10px;cursor:pointer;font-weight:500`}><span style=${`font:10.5px ${MONO};opacity:.7`}>${x.sub}</span>${x.label}</button>`)}
          </div>
          <div style="margin-top:20px;display:flex;align-items:baseline;gap:10px;flex-wrap:wrap">
            <div style="font-size:18px;font-weight:600;letter-spacing:-0.01em">${scopeStep ? 'What ' + scopeStep.name + ' sends' : 'What the client gets'}</div>
            <div style=${`font:11.5px ${MONO};color:#9A9CA2`}>${scopeStep ? 'STEP_REQUEST · ' + scopeStep.method + ' ' + scopeStep.target + scopeStep.path : 'FLOW_RESPONSE'}</div>
          </div>
          <div style="margin-top:4px;font-size:12px;color:#6A6D75;max-width:640px;line-height:1.45">Rules run top to bottom: value → default → lookup → converter → field handler → required check → write. Drop a field on a rule to replace its source, or a converter / lookup / handler to add it.</div>

          <div style="margin-top:16px;display:flex;flex-direction:column;gap:8px;max-width:880px">
            ${rules.map((r, i) => {
              const z = this.zone('rule-' + r.id, ruleAccept, d => updRule(r.id, x => { if (d.kind === 'src') { x.source = d.path; x.constant = ''; } if (d.kind === 'conv') x.conv = d.v; if (d.kind === 'lookup') x.lookup = d.v; if (d.kind === 'fh') x.fh = d.v; }));
              const res = resText(r, i); const set = k => e => { const v = e.currentTarget.value; updRule(r.id, x => { x[k] = v; }); };
              return html`
              <div ...${zoneProps(z)} style="position:relative;background:#fff;border:1px solid #E4E1D8;border-radius:9px;padding:9px 10px;display:flex;flex-direction:column;gap:7px">
                <div style="display:flex;align-items:center;gap:8px">
                  <span style=${`width:18px;text-align:right;font:11px ${MONO};color:#9A9CA2`}>${i + 1}</span>
                  <select value=${r.type} onChange=${e => { const v = e.currentTarget.value; updRule(r.id, x => { x.type = v; if (v !== 'BODY' && x.target.startsWith('$.')) x.target = x.target.slice(2); if (v === 'BODY' && !x.target.startsWith('$')) x.target = '$.' + x.target; }); }} style=${`height:28px;border:1px solid #E4E1D8;border-radius:5px;background:#FAF9F6;font:600 10.5px ${MONO};padding:0 4px`}>
                    ${withType(types, r.type).map(o => html`<option value=${o}>${o}</option>`)}
                  </select>
                  <input class="inp" value=${r.target} onInput=${set('target')} style=${`flex:1;min-width:120px;height:28px;border:1px solid #E4E1D8;border-radius:5px;padding:0 8px;font:12px ${MONO};background:#fff`}/>
                  <span style="color:#ADA99E">←</span>
                  ${r.source ? html`
                    <div style=${`flex:1.25;min-width:0;height:28px;display:flex;align-items:center;gap:6px;padding:0 4px 0 9px;border-radius:5px;background:oklch(0.96 0.025 255);font:11.5px ${MONO};color:oklch(0.38 0.12 255)`}>
                      <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title=${r.source}>${r.source}</span>
                      <button onClick=${() => updRule(r.id, x => { x.source = ''; })} style="border:0;background:none;color:oklch(0.5 0.08 255);cursor:pointer;font-size:13px;padding:0 3px">×</button>
                    </div>` : html`
                    <input class="inp" value=${r.constant} onInput=${set('constant')} placeholder="constant, or drop a field" style=${`flex:1.25;min-width:0;height:28px;border:1px dashed #C9C6BC;border-radius:5px;padding:0 9px;font:11.5px ${MONO};background:#FAF9F6`}/>`}
                </div>
                <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding-left:26px">
                  ${r.lookup && html`<span style=${`display:flex;align-items:center;gap:4px;padding:2px 3px 2px 7px;border-radius:5px;background:oklch(0.96 0.03 160);font:11px ${MONO};color:oklch(0.38 0.1 160)`}>lookup ${r.lookup}<button onClick=${() => updRule(r.id, x => { x.lookup = ''; })} style="border:0;background:none;color:inherit;cursor:pointer;padding:0 3px">×</button></span>`}
                  ${r.conv && html`<span style=${`display:flex;align-items:center;gap:2px;padding:2px 3px;border-radius:5px;background:oklch(0.95 0.03 300);font:11px ${MONO};color:oklch(0.4 0.13 300)`}><input value=${r.conv} onInput=${set('conv')} style=${`border:0;background:transparent;font:11px ${MONO};color:inherit;width:${Math.max(6, r.conv.length + 1)}ch;padding:0 4px;outline:none`}/><button onClick=${() => updRule(r.id, x => { x.conv = ''; })} style="border:0;background:none;color:inherit;cursor:pointer;padding:0 3px">×</button></span>`}
                  ${r.fh && html`<span style=${`display:flex;align-items:center;gap:4px;padding:2px 3px 2px 7px;border-radius:5px;background:#F6EDD5;font:11px ${MONO};color:#7A5B12`}>${r.fh}<button onClick=${() => updRule(r.id, x => { x.fh = ''; })} style="border:0;background:none;color:inherit;cursor:pointer;padding:0 3px">×</button></span>`}
                  <input class="inp" value=${r.def} onInput=${set('def')} placeholder="default" style=${`width:104px;height:24px;border:1px solid #EFEDE6;border-radius:5px;padding:0 7px;font:11px ${MONO};background:#FAF9F6`}/>
                  <button onClick=${() => updRule(r.id, x => { x.required = !x.required; })} style=${`height:24px;border:1px solid ${r.required ? '#17181C' : '#E4E1D8'};background:${r.required ? '#17181C' : '#fff'};color:${r.required ? '#fff' : '#9A9CA2'};border-radius:5px;padding:0 8px;font:11px ${MONO};cursor:pointer`}>required</button>
                  <span style="flex:1"></span>
                  <span title=${res.t} style=${`font:11px ${MONO};color:${res.c};max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap`}>${res.t}</span>
                  <button class="del" onClick=${() => updRule(r.id, (x, list, idx) => { list.splice(idx, 1); })} style="border:0;background:none;color:#ADA99E;cursor:pointer;font-size:14px;padding:0 4px">×</button>
                </div>
                ${z.active && html`<div style=${`position:absolute;inset:-4px;border:1.5px dashed ${C.in};border-radius:11px;background:oklch(0.56 0.13 255 / 0.04);pointer-events:none`}></div>`}
                ${z.over && html`<div style="position:absolute;inset:-4px;border-radius:11px;background:oklch(0.56 0.13 255 / 0.1);pointer-events:none"></div>`}
              </div>`;
            })}
            <div ...${zoneProps(newZone)} style=${`display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 14px;border:1.5px dashed ${nzBorder};background:${nzBg};border-radius:9px;color:#6A6D75;font-size:12px`}>
              <span>Drop a context field here to add a rule</span>
              <button onClick=${addConst} style="border:1px solid #E4E1D8;background:#fff;border-radius:6px;padding:5px 10px;cursor:pointer;font-size:12px;font-weight:500">+ Constant rule</button>
            </div>
          </div>
        </main>

        <aside style="width:360px;flex:none;background:#17181C;color:#E9E7E1;overflow:auto;padding:16px 18px 32px">
          <div style=${`font:600 10px ${MONO};letter-spacing:.08em;color:#8E9097`}>LIVE PREVIEW · SAMPLE REQUEST</div>
          <div style=${`margin-top:12px;font:600 12px/1.5 ${MONO};color:oklch(0.78 0.13 50);word-break:break-all`}>${line}</div>
          <div style="margin-top:8px;display:flex;flex-direction:column;gap:2px">
            ${headers.map(h => html`<div style=${`font:11.5px/1.5 ${MONO};word-break:break-all`}><span style="color:#8E9097">${h.n}:</span> ${h.v}</div>`)}
          </div>
          <pre style=${`margin:14px 0 0;padding:12px;border-radius:7px;background:#22242A;font:11.5px/1.55 ${MONO};white-space:pre-wrap;word-break:break-all;color:#E9E7E1;min-height:40px`}>${body || (pv ? '' : 'running…')}</pre>
          ${pvErrs.length > 0 && html`<div style="margin-top:12px;display:flex;flex-direction:column;gap:6px">${pvErrs.map(e => html`<div style=${`font:11px/1.45 ${MONO};color:oklch(0.75 0.14 25)`}>${e}</div>`)}</div>`}
          <div style="margin-top:16px;font-size:11.5px;color:#8E9097;line-height:1.5">${note}</div>
          <details style="margin-top:18px" open=${stored !== undefined}>
            <summary style=${`cursor:pointer;font:600 10px ${MONO};letter-spacing:.08em;color:#8E9097`}>SAMPLE CONTEXT ${stored !== undefined ? '· EDITED' : '· GENERATED'}</summary>
            <div style="margin-top:8px;font-size:11.5px;color:#8E9097;line-height:1.5">${'{request: {headers, path, query, body}, steps: {name: {outcome, status, headers, body}}}'}. Kept in this tab only.</div>
            <textarea value=${sampleValue} onInput=${e => { const v = e.currentTarget.value; this.setState(st => ({ samples: { ...st.samples, [f.code]: v } }), () => this.changed()); }} rows="14" spellcheck="false" style=${`margin-top:8px;width:100%;border:1px solid #3A3C43;border-radius:7px;background:#22242A;color:#E9E7E1;padding:10px;font:11px/1.5 ${MONO};resize:vertical`}></textarea>
            ${stored !== undefined && html`<button onClick=${() => this.setState(st => { const n = { ...st.samples }; delete n[f.code]; return { samples: n }; }, () => this.changed())} style="margin-top:6px;border:1px solid #3A3C43;background:none;color:#C9CBD1;border-radius:6px;padding:5px 10px;cursor:pointer;font-size:12px">Regenerate from rules</button>`}
          </details>
        </aside>
      </div>`;
  }

  // ---------- assistant ----------
  async toggleAssistant() {
    const a = this.state.assistant || { open: false, messages: [], input: '', streaming: false, status: null };
    this.setState({ assistant: { ...a, open: !a.open } });
    if (!a.open) { // refresh on every open: the server may have been restarted with new settings
      try { const r = await this.api('GET', 'assistant'); this.setAssistant(x => { x.status = r.status === 200 ? r.json : { configured: false }; }); } catch (e) { /* api() */ }
    }
    setTimeout(() => { const el = document.getElementById('assistant-input'); if (el) el.focus(); }, 50);
  }
  setAssistant(fn) { this.setState(s => { const a = { ...(s.assistant || {}) }; a.messages = (a.messages || []).map(m => ({ ...m })); fn(a); return { assistant: a }; }); }

  assistantContext() {
    const s = this.state; const f = s.screen === 'flow' && s.cfg ? s.cfg.flows[s.cur] : null;
    const problems = f ? this.flowProblems(f) : s.problems;
    return { screen: s.screen, tab: f ? s.tab : null, flowCode: f ? f.code : null,
      flow: f ? toApi({ flows: [f], targets: [], lookups: {}, schemas: [] }).flows[0] : null,
      unsaved: !!this.dirty(), problems: (problems || []).slice(0, 30) };
  }

  async askAssistant(text) {
    const q = (text || '').trim(); const a = this.state.assistant;
    if (!q || a.streaming) return;
    const history = [...a.messages.filter(m => !m.error), { role: 'user', content: q }];
    this.setAssistant(x => { x.messages.push({ role: 'user', content: q }, { role: 'assistant', content: '', pending: true }); x.input = ''; x.streaming = true; });
    const ctrl = new AbortController(); this.assistantAbort = ctrl;
    const finish = fn => this.setAssistant(x => { const last = x.messages[x.messages.length - 1]; fn(last, x); last.pending = false; x.streaming = false; });
    try {
      const r = await fetch('api/assistant/chat', { method: 'POST', signal: ctrl.signal,
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'X-Admin-Token': this.state.token },
        body: JSON.stringify({ messages: history.map(m => ({ role: m.role, content: m.content })), context: this.assistantContext() }) });
      if (r.status === 401) { finish(l => { l.error = true; l.content = 'The admin token was rejected.'; }); return; }
      if (!r.ok || !r.body) { finish(l => { l.error = true; l.content = 'HTTP ' + r.status; }); return; }
      const reader = r.body.getReader(); const dec = new TextDecoder(); let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const chunk = buf.slice(0, idx); buf = buf.slice(idx + 2);
          let ev = 'message', data = '';
          chunk.split('\n').forEach(line => { if (line.startsWith('event:')) ev = line.slice(6).trim(); else if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, ''); });
          let payload = {}; try { payload = JSON.parse(data || '{}'); } catch (e) { /* ignore */ }
          if (ev === 'delta') this.setAssistant(x => { const l = x.messages[x.messages.length - 1]; l.content += payload.text || ''; l.pending = false; });
          else if (ev === 'error') finish(l => { l.error = true; l.content = (l.content ? l.content + '\n\n' : '') + payload.message; });
          else if (ev === 'done') finish(l => { l.usage = payload.usage; });
        }
        const el = document.getElementById('assistant-log'); if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 120) el.scrollTop = el.scrollHeight;
      }
      finish(() => {});
    } catch (e) {
      finish(l => { if (e.name === 'AbortError') { l.content += (l.content ? '\n\n' : '') + '_Stopped._'; } else { l.error = true; l.content = 'Could not reach the gateway: ' + e.message; } });
    }
  }

  onAssistantClick(e) {
    const nav = e.target.closest('[data-nav]');
    if (nav) {
      e.preventDefault();
      const parts = nav.getAttribute('data-nav').replace(/^studio:/, '').split('/');
      if (parts[0] === 'flow') {
        const i = this.state.cfg.flows.findIndex(f => f.code === parts[1]);
        if (i >= 0) this.setState({ screen: 'flow', cur: i, tab: ['pipeline', 'mapping', 'tests', 'rows'].includes(parts[2]) ? parts[2] : 'pipeline', sel: { kind: 'flow' }, scope: 'resp', preview: null }, () => this.changed());
      } else if (['flows', 'targets', 'lookups', 'schemas'].includes(parts[0])) this.setState({ screen: parts[0] });
      return;
    }
    const copy = e.target.closest('[data-copy]');
    if (copy) { const code = copy.parentElement.querySelector('code'); try { navigator.clipboard.writeText(code.textContent); copy.textContent = 'Copied'; setTimeout(() => { copy.textContent = 'Copy'; }, 1200); } catch (err) { /* no clipboard */ } }
  }

  renderAssistant() {
    const s = this.state; const a = s.assistant; const st = a.status;
    const f = s.screen === 'flow' ? s.cfg.flows[s.cur] : null;
    const suggestions = [
      ...(f ? ['Explain the flow ' + f.code + ' step by step'] : []),
      ...(f && this.flowProblems(f).length ? ['How do I fix the problems in ' + f.code + '?'] : []),
      'How do I set up and run this project locally?',
      'Walk me through creating a new flow in Studio',
      'How do I write a custom class (handler) and use it?',
      'How do I generate a unit test document for a flow?',
      'What does each menu in Gateway Studio do?'
    ];
    const send = () => this.askAssistant(a.input);
    return html`
      <aside style="position:fixed;top:52px;right:0;bottom:0;width:min(460px,100vw);background:#fff;border-left:1px solid #E4E1D8;box-shadow:-12px 0 40px rgba(23,24,28,.12);display:flex;flex-direction:column;z-index:20">
        <div style="display:flex;align-items:center;gap:10px;padding:12px 14px;border-bottom:1px solid #EFEDE6">
          <div style=${`width:24px;height:24px;border-radius:6px;background:${C.call};display:grid;place-items:center;font:600 12px ${MONO};color:#17181C`}>?</div>
          <div style="flex:1;min-width:0">
            <div style="font-weight:600">Project assistant</div>
            <div style=${`font:10.5px ${MONO};color:#9A9CA2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis`}>${st ? (st.configured ? st.model + ' · ' + st.baseUrl : 'not configured') : 'connecting…'}</div>
          </div>
          ${a.messages.length > 0 && html`<button class="btn-line" disabled=${a.streaming} onClick=${() => this.setAssistant(x => { x.messages = []; })} style="border:1px solid #E4E1D8;background:#fff;border-radius:6px;padding:4px 9px;cursor:pointer;font-size:12px">New chat</button>`}
          <button class="x" onClick=${() => this.setAssistant(x => { x.open = false; })} style="border:0;background:none;color:#9A9CA2;cursor:pointer;font-size:18px">×</button>
        </div>
        <div id="assistant-log" onClick=${e => this.onAssistantClick(e)} style="flex:1;min-height:0;overflow:auto;padding:14px;display:flex;flex-direction:column;gap:12px">
          ${st && !st.configured && html`<div style="padding:12px;border:1px solid oklch(0.85 0.08 75);background:oklch(0.97 0.03 85);border-radius:8px;font-size:12.5px;line-height:1.5;color:oklch(0.42 0.1 70)">The assistant needs an AI endpoint. Put <code>AI_AUTH_KEY</code> (and <code>AI_BASE_URL</code>, <code>AI_MODEL</code>) in the project's <code>.env</code> file (see <code>.env.example</code>), then restart the gateway.</div>`}
          ${a.messages.length === 0 && html`
            <div style="font-size:12.5px;color:#6A6D75;line-height:1.5">Ask anything about this project: setting it up, building flows in Studio, custom classes, testing, configuration, troubleshooting. Answers come from the project's documentation and this gateway's live configuration${f ? ', including the open flow ' : ''}${f && html`<code>${f.code}</code>`}.</div>
            <div style="display:flex;flex-direction:column;gap:6px">
              ${suggestions.map(q => html`<button class="btn-line" onClick=${() => this.askAssistant(q)} disabled=${st && !st.configured} style="text-align:left;border:1px solid #E4E1D8;background:#FAF9F6;border-radius:8px;padding:8px 10px;cursor:pointer;font-size:12.5px">${q}</button>`)}
            </div>`}
          ${a.messages.map(m => m.role === 'user'
            ? html`<div style="align-self:flex-end;max-width:85%;background:#17181C;color:#fff;border-radius:12px 12px 2px 12px;padding:8px 12px;font-size:13px;line-height:1.45;white-space:pre-wrap">${m.content}</div>`
            : html`<div class="md" style=${`align-self:stretch;font-size:13px;line-height:1.55;color:${m.error ? 'oklch(0.48 0.17 25)' : '#17181C'}`}>
                ${m.pending && !m.content ? html`<span style="color:#9A9CA2">Thinking…</span>` : html`<div dangerouslySetInnerHTML=${{ __html: md(m.content) }}></div>`}
              </div>`)}
        </div>
        <div style="border-top:1px solid #EFEDE6;padding:10px 12px;display:flex;gap:8px;align-items:flex-end">
          <textarea id="assistant-input" class="inp" rows="2" value=${a.input} disabled=${st && !st.configured}
            onInput=${e => { const v = e.currentTarget.value; this.setAssistant(x => { x.input = v; }); }}
            onKeyDown=${e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
            placeholder="Ask about setup, flows, custom classes… (Enter to send)" style="flex:1;resize:none;border:1px solid #E4E1D8;border-radius:8px;padding:8px 10px;font-size:13px;line-height:1.4;background:#FAF9F6;max-height:160px"></textarea>
          ${a.streaming
            ? html`<button class="btn-line" onClick=${() => this.assistantAbort && this.assistantAbort.abort()} style="border:1px solid #E4E1D8;background:#fff;border-radius:8px;padding:9px 12px;cursor:pointer;font-weight:500">Stop</button>`
            : html`<button class="btn-acc" onClick=${send} disabled=${!a.input.trim() || (st && !st.configured)} style=${`border:0;background:${C.call};color:#17181C;border-radius:8px;padding:9px 14px;cursor:pointer;font-weight:600`}>Send</button>`}
        </div>
      </aside>`;
  }

  // ---------- unit tests ----------
  testsOf(f) { return (this.state.tests || {})[f.code] || { cases: [], run: null, opts: { audit: true, logs: true } }; }
  setTests(f, fn) { this.setState(s => { const all = { ...(s.tests || {}) }; const t = clone(all[f.code] || { cases: [], run: null, opts: { audit: true, logs: true } }); fn(t); all[f.code] = t; return { tests: all }; }); }

  async generateTests(f) {
    this.setTests(f, t => { t.loading = true; t.error = ''; });
    try {
      const r = await this.api('GET', 'tests/' + encodeURIComponent(f.code) + '/cases');
      if (r.status !== 200) { this.setTests(f, t => { t.loading = false; t.error = (r.json.errors || ['HTTP ' + r.status]).join('; '); }); return; }
      this.setTests(f, t => {
        t.loading = false; t.run = null;
        t.cases = r.json.cases.map(c => ({ id: uid('t'), enabled: true, open: false, name: c.name, description: str(c.description), method: c.method, path: c.path,
          query: pretty(c.query || {}), headers: pretty(c.headers || {}), body: c.body == null ? '' : pretty(c.body), expected: str(c.expectedStatus) }));
      });
    } catch (e) { /* shown by api() */ }
  }

  async runTests(f) {
    const t = this.testsOf(f); const chosen = t.cases.filter(c => c.enabled);
    let cases;
    try {
      cases = chosen.map(c => ({ name: c.name, description: c.description, method: c.method, path: c.path,
        query: c.query.trim() ? JSON.parse(c.query) : {}, headers: c.headers.trim() ? JSON.parse(c.headers) : {},
        body: c.body.trim() ? JSON.parse(c.body) : null, expectedStatus: int(c.expected) }));
    } catch (e) { this.setTests(f, x => { x.error = 'Fix the JSON of the cases first: ' + e.message; }); return; }
    this.setTests(f, x => { x.running = true; x.error = ''; });
    try {
      const r = await this.api('POST', 'tests/' + encodeURIComponent(f.code) + '/runs', { cases });
      this.setTests(f, x => {
        x.running = false;
        if (r.status !== 200) { x.error = (r.json.errors || ['HTTP ' + r.status]).join('; '); return; }
        x.run = r.json; x.runCaseIds = chosen.map(c => c.id); x.openResult = null;
      });
    } catch (e) { this.setTests(f, x => { x.running = false; }); }
  }

  async downloadReport(f, format) {
    const t = this.testsOf(f);
    const qs = 'format=' + format + '&audit=' + t.opts.audit + '&logs=' + t.opts.logs;
    const r = await fetch('api/tests/runs/' + t.run.runId + '/report?' + qs, { headers: { 'X-Admin-Token': this.state.token } });
    if (!r.ok) { let m = 'HTTP ' + r.status; try { m = (await r.json()).errors.join('; '); } catch (e) { /* not JSON */ } this.setTests(f, x => { x.error = m; }); return; }
    const cd = r.headers.get('Content-Disposition') || ''; const m = /filename="?([^";]+)"?/.exec(cd);
    const url = URL.createObjectURL(await r.blob());
    const a = document.createElement('a'); a.href = url; a.download = m ? m[1] : 'unit-test.' + format; document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  renderTests(f) {
    const s = this.state; const t = this.testsOf(f); const dirty = this.dirty();
    const audited = f.audit === 'ON' || (f.audit === 'INHERIT' && s.catalog.auditEnabled);
    const upd = (id, fn) => this.setTests(f, x => { const c = x.cases.find(y => y.id === id); if (c) fn(c, x); });
    const runIdx = id => (t.run && t.runCaseIds ? t.runCaseIds.indexOf(id) : -1);
    const enabled = t.cases.filter(c => c.enabled).length;
    const jsonErr = (txt, emptyOk) => (!txt.trim() && emptyOk ? '' : jsonError(txt));
    const chip = (label, color, bg) => html`<span style=${`font:600 10.5px ${MONO};color:${color};background:${bg};padding:2px 7px;border-radius:4px;white-space:nowrap`}>${label}</span>`;
    const verdict = r => (r.passed == null ? chip('RECORDED', '#6A6D75', '#EFEDE6') : r.passed ? chip('PASS', 'oklch(0.4 0.12 155)', 'oklch(0.94 0.05 155)') : chip('FAIL', 'oklch(0.45 0.17 25)', 'oklch(0.94 0.04 25)'));
    const btn = (label, onClick, dark, disabled, title) => html`<button class=${dark ? 'btn-dark' : 'btn-line'} disabled=${disabled} title=${title || ''} onClick=${onClick} style=${`border:1px solid ${dark ? '#17181C' : '#E4E1D8'};background:${dark ? '#17181C' : '#fff'};color:${dark ? '#fff' : '#17181C'};border-radius:7px;padding:7px 12px;cursor:pointer;font-weight:500;font-size:12.5px`}>${label}</button>`;
    const http = m => !m ? '(none)' : (m.method ? m.method + ' ' + m.url : 'HTTP ' + m.status) + '\n' + Object.keys(m.headers || {}).map(k => k + ': ' + m.headers[k]).join('\n') + (m.body ? '\n\n' + m.body : '');
    const pre = txt => html`<pre style=${`margin:6px 0 0;padding:10px 12px;background:#22242A;color:#E9E7E1;border-radius:7px;font:11px/1.5 ${MONO};white-space:pre-wrap;word-break:break-all;max-height:320px;overflow:auto`}>${txt}</pre>`;
    const sec = (title, body) => html`<div style="margin-top:12px"><div style=${`font:600 10px ${MONO};letter-spacing:.08em;color:#6A6D75;text-transform:uppercase`}>${title}</div>${body}</div>`;
    const evidence = r => html`
      <div style="padding:4px 14px 14px;border-top:1px solid #EFEDE6">
        ${r.error && html`<div style="margin-top:10px;color:oklch(0.5 0.18 25);font-size:12px">${r.error}</div>`}
        ${sec('1 · Incoming request (client → gateway)', pre(http(r.incomingRequest)))}
        ${sec('2 · Outgoing request (gateway → downstream)', r.downstream.length ? r.downstream.map((d, i) => html`<div style=${`margin-top:6px;font:11px ${MONO};color:#6A6D75`}>call ${i + 1} · ${d.targetSystem}</div>${pre(http(d.request))}`) : html`<div style="margin-top:6px;font-size:12px;color:#9A9CA2">No downstream call was made.</div>`)}
        ${sec('3 · Incoming response (downstream → gateway)', r.downstream.length ? r.downstream.map((d, i) => html`<div style=${`margin-top:6px;font:11px ${MONO};color:#6A6D75`}>call ${i + 1} · ${d.targetSystem} · ${d.durationMs} ms</div>${d.response ? pre(http(d.response)) : html`<div style="font-size:12px;color:oklch(0.5 0.18 25)">No response: ${d.error}</div>`}`) : html`<div style="margin-top:6px;font-size:12px;color:#9A9CA2">No downstream call was made.</div>`)}
        ${sec('4 · Outgoing response (gateway → client)', pre(http(r.outgoingResponse)))}
        ${sec('5 · Audit trail', !r.audit.enabled || !r.audit.transaction ? html`<div style="margin-top:6px;font-size:12px;color:#6A6D75;line-height:1.45">${r.audit.note}</div>` : html`
          ${r.audit.note && html`<div style="margin-top:6px;font-size:12px;color:#6A6D75">${r.audit.note}</div>`}
          ${pre(Object.keys(r.audit.transaction).filter(k => !/payload/.test(k)).map(k => k + ' = ' + r.audit.transaction[k]).join('\n'))}
          ${r.audit.steps.length > 0 && pre(r.audit.steps.map(x => [x.step_name, x.target_system, x.http_method, x.url, x.http_status, x.outcome, x.duration_ms + ' ms'].join('  ·  ')).join('\n'))}`)}
        ${sec('6 · Logs', r.logs.length ? pre(r.logs.join('\n')) : html`<div style="margin-top:6px;font-size:12px;color:#9A9CA2">No log line carried this correlation ID.</div>`)}
      </div>`;
    const ta = (c, key, label, emptyOk) => {
      const err = jsonErr(c[key], emptyOk);
      return html`<label style="display:flex;flex-direction:column;gap:4px;min-width:0">
        <span style=${`font:10px ${MONO};letter-spacing:.06em;color:#9A9CA2`}>${label}</span>
        <textarea class="inp" spellcheck="false" value=${c[key]} onInput=${e => { const v = e.currentTarget.value; upd(c.id, x => { x[key] = v; }); }} rows=${Math.min(14, Math.max(3, c[key].split('\n').length))} style=${`border:1px solid ${err ? 'oklch(0.75 0.12 25)' : '#E4E1D8'};border-radius:6px;padding:7px 9px;font:11.5px/1.45 ${MONO};background:#FAF9F6;resize:vertical;width:100%`}></textarea>
        ${err && html`<span style=${`font:10.5px ${MONO};color:oklch(0.5 0.18 25)`}>${err}</span>`}
      </label>`;
    };
    return html`
      <div style="flex:1;min-height:0;overflow:auto;padding:22px 28px 60px">
        <div style="max-width:1100px;margin:0 auto;display:flex;flex-direction:column;gap:14px">
          <div style="display:flex;align-items:flex-end;gap:16px;flex-wrap:wrap">
            <div style="flex:1;min-width:300px">
              <div style="font-size:18px;font-weight:600;letter-spacing:-0.01em">Unit tests</div>
              <div style="margin-top:4px;font-size:12px;color:#6A6D75;line-height:1.45">Cases come from this flow's operation in the API description (Swagger): a happy path, required fields only, missing required fields and, with a request schema, wrong types. Edit them freely. <b>Running them sends real requests</b> through this gateway to the configured downstream systems, then collects each call's messages, audit rows and log lines into a unit test document.</div>
            </div>
            ${btn(t.loading ? 'Generating…' : t.cases.length ? 'Regenerate from Swagger' : 'Generate from Swagger', () => this.generateTests(f), false, dirty || t.loading, dirty ? 'Save & reload first: cases come from the live configuration' : '')}
            ${btn('+ Custom case', () => this.setTests(f, x => { x.cases.push({ id: uid('t'), enabled: true, open: true, name: 'Custom case', description: '', method: f.method, path: f.path.replace(/\{([^}]+)\}/g, '1001'), query: '{}', headers: '{}', body: f.method === 'GET' ? '' : '{}', expected: String(f.successStatus) }); }), false, false)}
            ${btn(t.running ? 'Running…' : 'Run ' + enabled + ' case' + (enabled === 1 ? '' : 's'), () => this.runTests(f), true, dirty || t.running || enabled === 0, dirty ? 'Save & reload first: tests run against the live configuration' : '')}
          </div>
          ${dirty && html`<div style="padding:9px 12px;border:1px solid oklch(0.85 0.08 75);background:oklch(0.97 0.03 85);border-radius:8px;font-size:12px;color:oklch(0.42 0.1 70)">You have unsaved changes. Tests generate from and run against the <b>live</b> configuration, so Save & reload first.</div>`}
          <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:12px;color:#6A6D75">
            ${audited ? chip('AUDIT TRAIL ON', 'oklch(0.4 0.12 155)', 'oklch(0.94 0.05 155)') : chip('AUDIT TRAIL OFF', '#7A5B12', '#F6EDD5')}
            <span>${audited ? (f.audit === 'ON' ? "The flow's audit_mode is ON" : 'gateway.audit.enabled is true and audit_mode is INHERIT') + ': audit rows go into the document.' : 'No audit rows will be recorded. Set gateway.audit.enabled=true (GATEWAY_AUDIT_ENABLED) or this flow\'s audit_mode to ON, then save & reload.'}</span>
          </div>
          ${t.error && html`<div style="padding:9px 12px;border:1px solid oklch(0.85 0.06 25);background:oklch(0.97 0.02 25);border-radius:8px;font-size:12px;color:oklch(0.45 0.17 25)">${t.error}</div>`}

          ${t.run && html`
            <div style="background:#fff;border:1px solid #E4E1D8;border-radius:10px;padding:14px 16px;display:flex;align-items:center;gap:12px;flex-wrap:wrap">
              <span style="font-weight:600">Last run</span>
              ${chip(t.run.passed + ' PASSED', 'oklch(0.4 0.12 155)', 'oklch(0.94 0.05 155)')}
              ${t.run.failed > 0 && chip(t.run.failed + ' FAILED', 'oklch(0.45 0.17 25)', 'oklch(0.94 0.04 25)')}
              ${t.run.unverified > 0 && chip(t.run.unverified + ' RECORDED', '#6A6D75', '#EFEDE6')}
              <span style=${`font:11px ${MONO};color:#9A9CA2`}>${t.run.executedAt}</span>
              <span style="flex:1"></span>
              <label style="display:flex;align-items:center;gap:5px;font-size:12px"><input type="checkbox" checked=${t.opts.audit} onChange=${e => { const v = e.currentTarget.checked; this.setTests(f, x => { x.opts.audit = v; }); }}/>audit trail</label>
              <label style="display:flex;align-items:center;gap:5px;font-size:12px"><input type="checkbox" checked=${t.opts.logs} onChange=${e => { const v = e.currentTarget.checked; this.setTests(f, x => { x.opts.logs = v; }); }}/>logs</label>
              <span style="font-size:12px;color:#6A6D75">Download</span>
              ${btn('Word', () => this.downloadReport(f, 'docx'), true)}
              ${btn('PDF', () => this.downloadReport(f, 'pdf'), true)}
              ${btn('Markdown', () => this.downloadReport(f, 'md'), false)}
            </div>`}

          ${t.cases.length === 0 && !t.loading && html`<div style="padding:26px;border:1.5px dashed #C9C6BC;border-radius:10px;color:#6A6D75;text-align:center;font-size:12.5px">No cases yet. <b>Generate from Swagger</b> to start from the flow's documented parameters and body.</div>`}
          ${t.cases.map(c => {
            const i = runIdx(c.id); const r = i >= 0 ? t.run.results[i] : null;
            return html`
            <div style=${`background:#fff;border:1px solid ${r && r.passed === false ? 'oklch(0.85 0.06 25)' : '#E4E1D8'};border-radius:10px;opacity:${c.enabled ? 1 : 0.6}`}>
              <div style="display:flex;align-items:center;gap:10px;padding:10px 14px;flex-wrap:wrap">
                <input type="checkbox" checked=${c.enabled} onChange=${e => { const v = e.currentTarget.checked; upd(c.id, x => { x.enabled = v; }); }} title="include in the run"/>
                <input class="inp inp-ghost" value=${c.name} onInput=${e => { const v = e.currentTarget.value; upd(c.id, x => { x.name = v; }); }} style="flex:1;min-width:220px;height:30px;border:1px solid transparent;border-radius:6px;padding:0 8px;font-weight:600;background:transparent"/>
                <span style=${`font:600 10px ${MONO};color:#fff;background:${MC[c.method] || '#555'};padding:2px 6px;border-radius:4px`}>${c.method}</span>
                <span style=${`font:11.5px ${MONO};color:#3E4047`}>${s.catalog.apiBasePath}${c.path}</span>
                <label style=${`display:flex;align-items:center;gap:5px;font:11px ${MONO};color:#9A9CA2`}>expect<input class="inp" value=${c.expected} onInput=${e => { const v = e.currentTarget.value.replace(/\D/g, ''); upd(c.id, x => { x.expected = v; }); }} placeholder="any" style=${inputStyle(26, 11.5) + ';width:56px'}/></label>
                ${r && html`<span style=${`font:11px ${MONO};color:#6A6D75`}>got ${r.outgoingResponse ? r.outgoingResponse.status : '-'}</span>`}
                ${r && verdict(r)}
                <button class="btn-line" onClick=${() => upd(c.id, x => { x.open = !x.open; })} style="border:1px solid #E4E1D8;background:#FAF9F6;border-radius:6px;padding:4px 9px;cursor:pointer;font-size:12px">${c.open ? 'Hide request' : 'Edit request'}</button>
                ${r && html`<button class="btn-line" onClick=${() => this.setTests(f, x => { x.openResult = x.openResult === c.id ? null : c.id; })} style="border:1px solid #E4E1D8;background:#FAF9F6;border-radius:6px;padding:4px 9px;cursor:pointer;font-size:12px">${t.openResult === c.id ? 'Hide evidence' : 'Evidence'}</button>`}
                <button class="del" onClick=${() => this.setTests(f, x => { x.cases = x.cases.filter(y => y.id !== c.id); })} style="border:0;background:none;color:#ADA99E;cursor:pointer;font-size:15px;padding:0 4px">×</button>
              </div>
              ${c.description && !c.open && html`<div style="padding:0 14px 10px 40px;font-size:12px;color:#6A6D75">${c.description}</div>`}
              ${c.open && html`
                <div style="padding:4px 14px 14px;border-top:1px solid #EFEDE6;display:flex;flex-direction:column;gap:10px">
                  <input class="inp" value=${c.description} onInput=${e => { const v = e.currentTarget.value; upd(c.id, x => { x.description = v; }); }} placeholder="description (goes into the document)" style=${inputStyle(30, 12) + ';margin-top:10px'}/>
                  <div style="display:grid;grid-template-columns:110px 1fr;gap:8px">
                    <select class="inp" value=${c.method} onChange=${e => { const v = e.currentTarget.value; upd(c.id, x => { x.method = v; }); }} style=${inputStyle(30, 12)}>${METHODS.map(m => html`<option value=${m}>${m}</option>`)}</select>
                    <input class="inp" value=${c.path} onInput=${e => { const v = e.currentTarget.value; upd(c.id, x => { x.path = v; }); }} placeholder="/v1/accounts/1001 (after ${s.catalog.apiBasePath})" style=${inputStyle(30, 12)}/>
                  </div>
                  <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
                    ${ta(c, 'query', 'QUERY PARAMETERS (JSON object)', true)}
                    ${ta(c, 'headers', 'HEADERS (JSON object)', true)}
                  </div>
                  ${ta(c, 'body', 'BODY (JSON; empty = no body)', true)}
                </div>`}
              ${r && t.openResult === c.id && evidence(r)}
            </div>`;
          })}
        </div>
      </div>`;
  }

  renderRows(f) {
    const s = this.state;
    const used = [...new Set([...f.steps.flatMap(x => x.rules), ...f.response].map(r => r.lookup).filter(Boolean))].filter(c => s.cfg.lookups[c]).map(c => [c, s.cfg.lookups[c]]);
    const sql = flowSql(f, used);
    const ruleCount = f.steps.reduce((a, x) => a + x.rules.length, 0) + f.response.length;
    const counts = [{ n: 1, t: 'gw_flow' }, { n: f.steps.length, t: 'gw_flow_step' }, { n: ruleCount, t: 'gw_mapping_rule' }];
    const copy = () => { try { navigator.clipboard.writeText(sql); } catch (e) { /* no clipboard */ } this.setState({ copied: true }); setTimeout(() => this.setState({ copied: false }), 1500); };
    return html`
      <div style="flex:1;min-height:0;overflow:auto;padding:22px 28px 60px">
        <div style="max-width:980px;display:flex;flex-direction:column;gap:14px">
          <div style="display:flex;align-items:flex-end;gap:16px">
            <div style="flex:1">
              <div style="font-size:18px;font-weight:600;letter-spacing:-0.01em">Generated rows</div>
              <div style="margin-top:4px;font-size:12px;color:#6A6D75;line-height:1.45">Save & reload writes this flow straight into this gateway's database. To promote it to another environment, commit these INSERTs as a Liquibase changeset, then reload there.</div>
            </div>
            <button class="btn-dark" onClick=${copy} style="border:1px solid #17181C;background:#17181C;color:#fff;border-radius:7px;padding:8px 14px;cursor:pointer;font-weight:500">${s.copied ? 'Copied' : 'Copy SQL'}</button>
          </div>
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            ${counts.map(c => html`<div style="display:flex;align-items:baseline;gap:6px;padding:7px 11px;background:#fff;border:1px solid #E4E1D8;border-radius:7px"><span style=${`font:600 15px ${MONO}`}>${c.n}</span><span style=${`font:11.5px ${MONO};color:#6A6D75`}>${c.t}</span></div>`)}
          </div>
          <pre style=${`margin:0;padding:18px 20px;background:#fff;border:1px solid #E4E1D8;border-radius:9px;font:12px/1.6 ${MONO};white-space:pre-wrap;color:#2B2D33`}>${sql}</pre>
        </div>
      </div>`;
  }
}

/** The allowed target types, plus the rule's own when it is not one of them (so the select shows the truth). */
function withType(types, t) { return t && !types.includes(t) ? [...types, t] : types; }

render(html`<${App} />`, document.getElementById('app'));
