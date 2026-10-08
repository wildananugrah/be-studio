-- Tutorial flow from docs/CONFIGURATION-GUIDE.md
--   GET /api/v2/accounts/{accountNo}/overview
-- Calls core banking twice in parallel (account details + recent history) and merges the results.
-- Self-contained: inserts the target system and lookups it needs (skipping rows that already exist, e.g. from
-- the dev demo seed), so it also works on an empty database (after make db-truncate).
--
-- Run:  docker compose exec -T postgres psql -U gateway -d gateway < docs/examples/account-overview.sql
-- Then: make reload
--
-- PostgreSQL syntax (the VALUES ... AS r(...) lists). On Oracle write one INSERT ... SELECT per rule
-- and use 1/0 instead of TRUE/FALSE.

-- 0a. The downstream system the steps call (only if it doesn't exist yet) ------------------------
-- IP/host and port live here; change them with an UPDATE + make reload. Here: WireMock on localhost:8089.
INSERT INTO gw_target_system (code, base_url, connect_timeout_ms, read_timeout_ms)
SELECT 'CORE_BANKING', 'http://localhost:8089', 2000, 5000
WHERE NOT EXISTS (SELECT 1 FROM gw_target_system WHERE code = 'CORE_BANKING');

-- 0b. Lookup tables used by the response rules (only rows that don't exist yet) ------------------
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value)
SELECT v.lookup_code, v.source_value, v.target_value
FROM (VALUES
        ('ACCOUNT_STATUS', 'A', 'ACTIVE'),
        ('ACCOUNT_STATUS', 'B', 'BLOCKED'),
        ('ACCOUNT_STATUS', 'C', 'CLOSED'),
        ('ACCOUNT_STATUS', '*', 'UNKNOWN'),
        ('DEBIT_CREDIT',   'D', 'DEBIT'),
        ('DEBIT_CREDIT',   'C', 'CREDIT')
     ) AS v(lookup_code, source_value, target_value)
WHERE NOT EXISTS (SELECT 1 FROM gw_lookup_entry e
                  WHERE e.lookup_code = v.lookup_code AND e.source_value = v.source_value);

-- 1. The endpoint ---------------------------------------------------------------------------------
INSERT INTO gw_flow (code, name, http_method, path_pattern)
VALUES ('ACCOUNT_OVERVIEW', 'Account overview', 'GET', '/v2/accounts/{accountNo}/overview');

-- 2. The downstream calls (same step_order = run in parallel) ------------------------------------
INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template)
SELECT id, 'account', 1, 'CORE_BANKING', 'GET', '/core/accounts/{acc}'
FROM gw_flow WHERE code = 'ACCOUNT_OVERVIEW';

INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template, on_failure)
SELECT id, 'history', 1, 'CORE_BANKING', 'GET', '/core/accounts/{acc}/history', 'CONTINUE'
FROM gw_flow WHERE code = 'ACCOUNT_OVERVIEW';

-- 3. What each call sends (phase STEP_REQUEST; r.step picks the step by name) ---------------------
--   account: path variable {acc} and a header with a default
--   history: path variable {acc} and a constant query parameter ?limit=3
-- PATH rules are required: a missing path variable fails clearly instead of producing an empty URL segment.
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path, constant_value, default_value, converter, lookup_code, required)
SELECT f.id, s.id, 'STEP_REQUEST', r.seq, r.target_type, r.target_path, r.source_path, r.constant_value, r.default_value, r.converter, r.lookup_code, r.required
FROM gw_flow f
JOIN gw_flow_step s ON s.flow_id = f.id
JOIN (VALUES
        ('account', 1, 'PATH',   'acc',       '$.request.path.accountNo',   NULL, NULL,     NULL, NULL, TRUE),
        ('account', 2, 'HEADER', 'X-Channel', '$.request.headers.x-channel', NULL, 'MOBILE', NULL, NULL, FALSE),
        ('history', 1, 'PATH',   'acc',       '$.request.path.accountNo',   NULL, NULL,     NULL, NULL, TRUE),
        ('history', 2, 'QUERY',  'limit',     NULL,                         '3',  NULL,     NULL, NULL, FALSE)
     ) AS r(step, seq, target_type, target_path, source_path, constant_value, default_value, converter, lookup_code, required)
  ON r.step = s.name
WHERE f.code = 'ACCOUNT_OVERVIEW';

-- 4. What the client gets back (phase FLOW_RESPONSE, no step_id) -----------------------------------
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, constant_value, default_value, converter, lookup_code)
SELECT id, 'FLOW_RESPONSE', r.seq, r.target_type, r.target_path, r.source_path, r.constant_value, r.default_value, r.converter, r.lookup_code
FROM gw_flow,
     (VALUES
        (1, 'BODY',   '$.account.number',     '$.steps.account.body.acctNo',            NULL,           NULL,        NULL,                             NULL),
        (2, 'BODY',   '$.account.holder',     '$.steps.account.body.acctName',          NULL,           NULL,        NULL,                             NULL),
        (3, 'BODY',   '$.account.status',     '$.steps.account.body.statusCd',          NULL,           NULL,        NULL,                             'ACCOUNT_STATUS'),
        (4, 'BODY',   '$.account.balance',    '$.steps.account.body.availBal',          NULL,           NULL,        'DECIMAL_SCALE:2',                NULL),
        (5, 'BODY',   '$.recent[*].date',     '$.steps.history.body.items[*].trxDt',    NULL,           NULL,        'DATE_FORMAT:yyyyMMdd:dd/MM/yyyy', NULL),
        (6, 'BODY',   '$.recent[*].amount',   '$.steps.history.body.items[*].amt',      NULL,           NULL,        'TO_NUMBER',                      NULL),
        (7, 'BODY',   '$.recent[*].direction','$.steps.history.body.items[*].dc',       NULL,           NULL,        NULL,                             'DEBIT_CREDIT'),
        (8, 'BODY',   '$.historyStatus',      '$.steps.history.outcome',                NULL,           NULL,        NULL,                             NULL),
        (9, 'BODY',   '$.requestedBy',        '$.request.headers.x-user-id',            NULL,           'anonymous', NULL,                             NULL),
        (10,'HEADER', 'X-Data-Source',        NULL,                                     'CORE_BANKING', NULL,        NULL,                             NULL)
     ) AS r(seq, target_type, target_path, source_path, constant_value, default_value, converter, lookup_code)
WHERE code = 'ACCOUNT_OVERVIEW';
