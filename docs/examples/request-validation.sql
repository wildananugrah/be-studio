-- Request validation examples (docs/CONFIGURATION-GUIDE.md §4.1a): gw_flow.request_validation
--
--   A. LC_SUBMIT  POST /api/v2/lc/submissions
--      "purpose" is checked first (new / modify / delete), then the body is validated with the schema that purpose
--      picks. Errors use the standard error body (defaultErrorHandler).
--   B. LC_AMEND   POST /api/v2/lc/amendments
--      Every kind of check, the schema first (SCHEMA_FIRST), and a mapped error response (error_handler
--      RESPOND:invalid) that reads $.error.validation.
--
-- Neither flow calls a downstream system: each answers {"accepted": true, ...} when the request passes, so they work
-- on any database. Self-contained: inserts the schemas and the lookup they need (skipping rows that already exist).
--
-- Run:  docker compose exec -T postgres psql -U gateway -d gateway < docs/examples/request-validation.sql
-- Then: make reload
-- Try:  the "Request validation" requests in http/gateway.http
--
-- PostgreSQL syntax. On Oracle the JSON goes into the CLOB column the same way (a plain string literal).

-- ================================================================================================================
-- A. LC_SUBMIT: purpose picks the schema
-- ================================================================================================================

-- A1. One schema per purpose --------------------------------------------------------------------------------------
INSERT INTO gw_json_schema (code, description, schema_text)
SELECT v.code, v.description, v.schema_text
FROM (VALUES
        ('LC_SUBMIT_NEW', 'LC submission: new',
         '{"type":"object","required":["purpose","applicantName","beneficiaryName","amount"],
           "properties":{"purpose":{"const":"new"},
                         "applicantName":{"type":"string","minLength":1},
                         "beneficiaryName":{"type":"string","minLength":1},
                         "amount":{"type":"number","exclusiveMinimum":0}}}'),
        ('LC_SUBMIT_MODIFY', 'LC submission: modify',
         '{"type":"object","required":["purpose","applicationNo","amount"],
           "properties":{"purpose":{"const":"modify"},
                         "applicationNo":{"type":"string","minLength":1},
                         "amount":{"type":"number","exclusiveMinimum":0}}}'),
        ('LC_SUBMIT_DELETE', 'LC submission: delete',
         '{"type":"object","required":["purpose","applicationNo"],
           "properties":{"purpose":{"const":"delete"},
                         "applicationNo":{"type":"string","minLength":1},
                         "reason":{"type":"string","maxLength":200}}}')
     ) AS v(code, description, schema_text)
WHERE NOT EXISTS (SELECT 1 FROM gw_json_schema s WHERE s.code = v.code);

-- A2. The endpoint with its request validation ---------------------------------------------------------------------
--   check 1: purpose must be new / modify / delete  → 400 GW-400-VALIDATION, validationCode PURPOSE_INVALID
--   schemaBy: new → LC_SUBMIT_NEW, modify → LC_SUBMIT_MODIFY, delete → LC_SUBMIT_DELETE  → 400 GW-400-SCHEMA
-- service_code is required and unique within the flow's application (DEFAULT here)
INSERT INTO gw_flow (code, name, http_method, path_pattern, service_code, request_validation)
VALUES ('LC_SUBMIT', 'LC submission (schema picked by purpose)', 'POST', '/v2/lc/submissions', '93',
'{
  "order": "CHECKS_FIRST",
  "checks": [
    {"field": "$.request.body.purpose", "check": "ENUM", "values": ["new", "modify", "delete"],
     "message": "purpose must be new, modify or delete", "code": "PURPOSE_INVALID"}
  ],
  "schemaBy": {
    "field": "$.request.body.purpose",
    "schemas": {"new": "LC_SUBMIT_NEW", "modify": "LC_SUBMIT_MODIFY", "delete": "LC_SUBMIT_DELETE"}
  }
}');

-- A3. What the client gets when the request passes ----------------------------------------------------------------
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, constant_value)
SELECT f.id, 'FLOW_RESPONSE', r.seq, 'BODY', r.target_path, r.source_path, r.constant_value
FROM gw_flow f,
     (VALUES (1, '$.accepted', NULL, 'true'),
             (2, '$.purpose', '$.request.body.purpose', NULL)
     ) AS r(seq, target_path, source_path, constant_value)
WHERE f.code = 'LC_SUBMIT';

-- ================================================================================================================
-- B. LC_AMEND: every kind of check, schema first, mapped error response
-- ================================================================================================================

-- B1. The allowed currencies, kept as a lookup: add a currency without touching the flow ---------------------------
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value)
SELECT v.lookup_code, v.source_value, v.target_value
FROM (VALUES ('LC_CURRENCY', 'IDR', 'Rupiah'),
             ('LC_CURRENCY', 'USD', 'US Dollar'),
             ('LC_CURRENCY', 'EUR', 'Euro')
     ) AS v(lookup_code, source_value, target_value)
WHERE NOT EXISTS (SELECT 1 FROM gw_lookup_entry e
                  WHERE e.lookup_code = v.lookup_code AND e.source_value = v.source_value);

-- B2. The request schema, checked first (SCHEMA_FIRST) -------------------------------------------------------------
INSERT INTO gw_json_schema (code, description, schema_text)
SELECT 'LC_AMEND_REQUEST', 'LC amendment request',
       '{"type":"object","required":["applicationNo","currency","amount"],
         "properties":{"applicationNo":{"type":"string"},"currency":{"type":"string"},
                       "amount":{"type":"number"},"newExpiryDate":{"type":"string"},
                       "expiryDate":{"type":"string"},"remarks":{"type":"string"}}}'
WHERE NOT EXISTS (SELECT 1 FROM gw_json_schema WHERE code = 'LC_AMEND_REQUEST');

-- B3. The endpoint: the schema first, then the checks top to bottom; failures answer with the response "invalid" ---
INSERT INTO gw_flow (code, name, http_method, path_pattern, request_schema_code, error_handler, service_code,
                     request_validation)
VALUES ('LC_AMEND', 'LC amendment (every kind of check)', 'POST', '/v2/lc/amendments', 'LC_AMEND_REQUEST',
        'RESPOND:invalid', '94',
'{
  "order": "SCHEMA_FIRST",
  "checks": [
    {"field": "$.request.headers.x-channel", "check": "REQUIRED",
     "message": "header X-Channel is required", "code": "CHANNEL_MISSING"},
    {"field": "$.request.body.applicationNo", "check": "PATTERN", "pattern": "EL[A-Z]{3}[0-9]{5}S[0-9]{2}",
     "message": "applicationNo must look like ELJPU07631S26", "code": "APPLICATION_NO_FORMAT"},
    {"field": "$.request.body.currency", "check": "LOOKUP", "lookup": "LC_CURRENCY",
     "message": "currency is not supported", "code": "CURRENCY_UNSUPPORTED"},
    {"field": "$.request.body.amount", "check": "RANGE", "min": 1, "max": 10000000000,
     "message": "amount must be from 1 to 10,000,000,000", "code": "AMOUNT_RANGE"},
    {"field": "$.request.body.remarks", "check": "LENGTH", "max": 50, "optional": true,
     "message": "remarks can be at most 50 characters", "code": "REMARKS_TOO_LONG"},
    {"check": "EXPR", "expr": "${request.body.newExpiryDate} == null || ${request.body.newExpiryDate} > ${request.body.expiryDate}",
     "message": "newExpiryDate must be after expiryDate", "code": "EXPIRY_ORDER"}
  ]
}');

-- B4. The error response "invalid": HTTP 400, built from $.error ---------------------------------------------------
INSERT INTO gw_flow_response (flow_id, name, status_code, description)
SELECT id, 'invalid', 400, 'A request check or the schema failed'
FROM gw_flow WHERE code = 'LC_AMEND';

INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, constant_value,
                             default_value, response_name)
SELECT f.id, 'FLOW_RESPONSE', r.seq, 'BODY', r.target_path, r.source_path, r.constant_value, r.default_value,
       'invalid'
FROM gw_flow f,
     (VALUES (1, '$.status',  NULL,                         'REJECTED', NULL),
             -- the check's own code; a schema error has none, so it gets SCHEMA_INVALID
             (2, '$.code',    '$.error.validation.code',    NULL,       'SCHEMA_INVALID'),
             (3, '$.field',   '$.error.validation.field',   NULL,       NULL),
             (4, '$.message', '$.error.details[0]',         NULL,       NULL),
             (5, '$.gwCode',  '$.error.code',               NULL,       NULL)
     ) AS r(seq, target_path, source_path, constant_value, default_value)
WHERE f.code = 'LC_AMEND';

-- B5. What the client gets when the request passes ----------------------------------------------------------------
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, constant_value)
SELECT f.id, 'FLOW_RESPONSE', r.seq, 'BODY', r.target_path, r.source_path, r.constant_value
FROM gw_flow f,
     (VALUES (1, '$.accepted', NULL, 'true'),
             (2, '$.applicationNo', '$.request.body.applicationNo', NULL)
     ) AS r(seq, target_path, source_path, constant_value)
WHERE f.code = 'LC_AMEND';
