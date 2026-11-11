-- REMOVE example (docs/CONFIGURATION-GUIDE.md §4.3b): drop fields and headers after the other rules
--
--   LC_LIST_SLIM  GET /api/v2/lc/applications  → CORE_BANKING GET /core/lc/applications
--
--   The whole answer of the core is copied ($ ← $.steps.lcList.body), then REMOVE rules drop what the client must not
--   see: bniReferenceNo, actionable and submittedDate from every item of data, meta.totalPages and partnerReferenceNo.
--   A header REMOVE on the step keeps the CORE_BANKING default header X-Channel-Id away from the core (the WireMock stub
--   of /core/lc/applications only answers without it, so this flow fails without that rule).
--   REMOVE runs after every other rule: rule 1 removes meta.totalPages although the copy (rule 2) comes after it.
--
-- Self-contained: inserts the target system and its default header when they don't exist yet (WireMock on 8089).
--
-- Run:  docker compose exec -T postgres psql -U gateway -d gateway < docs/examples/remove-fields.sql
-- Then: make reload
-- Try:  the "REMOVE sample" requests in http/gateway.http
--
-- PostgreSQL syntax (the VALUES ... AS r(...) list). On Oracle write one INSERT ... SELECT per rule.

-- 0. The downstream system and its default header (only if they don't exist yet) ---------------------------------
INSERT INTO gw_target_system (code, base_url, connect_timeout_ms, read_timeout_ms)
SELECT 'CORE_BANKING', 'http://localhost:8089', 2000, 5000
WHERE NOT EXISTS (SELECT 1 FROM gw_target_system WHERE code = 'CORE_BANKING');

INSERT INTO gw_target_system_header (target_code, header_name, header_value)
SELECT 'CORE_BANKING', 'X-Channel-Id', 'GATEWAY'
WHERE NOT EXISTS (SELECT 1 FROM gw_target_system_header WHERE target_code = 'CORE_BANKING' AND header_name = 'X-Channel-Id');

-- 1. The endpoint (service_code: unique within the application, DEFAULT here) -------------------------------------
INSERT INTO gw_flow (code, name, http_method, path_pattern, service_code)
VALUES ('LC_LIST_SLIM', 'LC list without internal fields', 'GET', '/v2/lc/applications', '95');

-- 2. The call to the core ------------------------------------------------------------------------------------------
INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template)
SELECT id, 'lcList', 1, 'CORE_BANKING', 'GET', '/core/lc/applications'
FROM gw_flow WHERE code = 'LC_LIST_SLIM';

-- 3. What the call sends: everything as usual, except the target system's default header X-Channel-Id ---------------
--    A header REMOVE names the header (no $); matched without case.
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path)
SELECT s.flow_id, s.id, 'STEP_REQUEST', 1, 'REMOVE', 'X-Channel-Id'
FROM gw_flow_step s JOIN gw_flow f ON f.id = s.flow_id
WHERE f.code = 'LC_LIST_SLIM' AND s.name = 'lcList';

-- 4. What the client gets: the whole answer, minus the removed fields ----------------------------------------------
--    target_path of a body REMOVE: $.a.b, or $.list[*].x for every item. REMOVE rules take only target_path.
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path)
SELECT f.id, 'FLOW_RESPONSE', r.seq, r.target_type, r.target_path, r.source_path
FROM gw_flow f,
     (VALUES (1, 'REMOVE', '$.meta.totalPages',         NULL),   -- runs last although it is first
             (2, 'BODY',   '$',                         '$.steps.lcList.body'),  -- copy everything
             (3, 'REMOVE', '$.data[*].bniReferenceNo',  NULL),
             (4, 'REMOVE', '$.data[*].actionable',      NULL),
             (5, 'REMOVE', '$.data[*].submittedDate',   NULL),
             (6, 'REMOVE', '$.partnerReferenceNo',      NULL),
             (7, 'HEADER', 'X-Total-Items',             '$.steps.lcList.body.meta.totalItems')
     ) AS r(seq, target_type, target_path, source_path)
WHERE f.code = 'LC_LIST_SLIM';
