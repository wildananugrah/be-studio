-- Example flow using all four example custom classes (docs/CUSTOM-CLASSES.md)
--   GET /api/v3/accounts/{accountNo}    "partner-style" account inquiry
--
--   channelGuard        flow request_handler   rejects requests without an allowed X-Channel (403)
--   requestSigner       step request_handler   adds X-Timestamp + X-Signature to the core banking call
--   idrAmountFormatter  field_handler          balance 1500000.00 -> "Rp1.500.000,00"
--   partnerErrorHandler flow error_handler     errors as HTTP 200 + responseCode (14, 30, 68, 96, ...)
--
-- Portable SQL (PostgreSQL and Oracle). Run, then reload:
--   docker compose exec -T postgres psql -U gateway -d gateway < docs/examples/custom-classes.sql
--   make reload
-- Remove again:  DELETE FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';   then make reload

-- 1. The flow, with its flow-level handlers -------------------------------------------------------
INSERT INTO gw_flow (code, name, http_method, path_pattern, request_handler, error_handler)
VALUES ('PARTNER_ACCOUNT_INQUIRY', 'Partner account inquiry', 'GET', '/v3/accounts/{accountNo}',
        'channelGuard', 'partnerErrorHandler');

-- 2. The target system, only if it doesn't exist yet (it does with the dev demo data) -------------
INSERT INTO gw_target_system (code, base_url, connect_timeout_ms, read_timeout_ms)
SELECT 'CORE_BANKING', 'http://localhost:8089', 2000, 5000
FROM gw_flow
WHERE code = 'PARTNER_ACCOUNT_INQUIRY'
  AND NOT EXISTS (SELECT 1 FROM gw_target_system WHERE code = 'CORE_BANKING');

-- 3. The step, with its request handler ------------------------------------------------------------
INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template, request_handler)
SELECT id, 'inquiry', 1, 'CORE_BANKING', 'GET', '/core/accounts/{acc}', 'requestSigner'
FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';

-- required = '1' is written as text so the same script works for PostgreSQL boolean and Oracle NUMBER(1)
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path, required)
SELECT f.id, s.id, 'STEP_REQUEST', 1, 'PATH', 'acc', '$.request.path.accountNo', '1'
FROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id
WHERE f.code = 'PARTNER_ACCOUNT_INQUIRY' AND s.name = 'inquiry';

-- 4. The response; the balance goes through the field handler -------------------------------------
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, constant_value)
SELECT id, 'FLOW_RESPONSE', 1, 'BODY', '$.responseCode', '00' FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';

INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, constant_value)
SELECT id, 'FLOW_RESPONSE', 2, 'BODY', '$.responseMessage', 'Approved' FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';

INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path)
SELECT id, 'FLOW_RESPONSE', 3, 'BODY', '$.accountNo', '$.steps.inquiry.body.acctNo' FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';

INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path)
SELECT id, 'FLOW_RESPONSE', 4, 'BODY', '$.accountName', '$.steps.inquiry.body.acctName' FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';

INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, field_handler)
SELECT id, 'FLOW_RESPONSE', 5, 'BODY', '$.balance', '$.steps.inquiry.body.availBal', 'idrAmountFormatter'
FROM gw_flow WHERE code = 'PARTNER_ACCOUNT_INQUIRY';
