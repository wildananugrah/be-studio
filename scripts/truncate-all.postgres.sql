-- Deletes ALL rows from every gateway table (configuration, target systems, audit) and restarts the id sequences.
-- PostgreSQL. Irreversible.
--
-- Liquibase's tracking tables (gw_db_changelog, gw_db_changelog_lock) are deliberately left alone:
-- emptying them would make Liquibase try to re-create existing tables on the next start.
-- Consequence: the dev demo flows are NOT re-seeded after this. To get them back, use `make db-reset`.
--
-- Uses the default table names. If you configured gateway.db.tables.* or gateway.db.schema,
-- adjust the names below (schema-qualify them, e.g. gateway.gw_flow).
--
-- Run:  make db-truncate
--  or:  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U gateway -d gateway < scripts/truncate-all.postgres.sql
-- The running app keeps its in-memory config until `make reload` (or a restart).

TRUNCATE TABLE
    gw_audit_step,
    gw_audit_transaction,
    gw_mapping_rule,
    gw_flow_step,
    gw_flow,
    gw_lookup_entry,
    gw_json_schema,
    gw_target_system_header,
    gw_target_system
RESTART IDENTITY;

SELECT 'gw_flow' AS table_name, count(*) AS row_count FROM gw_flow
UNION ALL SELECT 'gw_flow_step', count(*) FROM gw_flow_step
UNION ALL SELECT 'gw_mapping_rule', count(*) FROM gw_mapping_rule
UNION ALL SELECT 'gw_lookup_entry', count(*) FROM gw_lookup_entry
UNION ALL SELECT 'gw_json_schema', count(*) FROM gw_json_schema
UNION ALL SELECT 'gw_target_system', count(*) FROM gw_target_system
UNION ALL SELECT 'gw_target_system_header', count(*) FROM gw_target_system_header
UNION ALL SELECT 'gw_audit_transaction', count(*) FROM gw_audit_transaction
UNION ALL SELECT 'gw_audit_step', count(*) FROM gw_audit_step;
