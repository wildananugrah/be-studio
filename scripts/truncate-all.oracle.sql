-- Deletes ALL rows from every gateway table (configuration, target systems, audit). Oracle 12c+. Irreversible.
--
-- Oracle refuses a plain TRUNCATE of a table that a foreign key points to (ORA-02266), so parent tables use
-- TRUNCATE ... CASCADE, which works because the gateway's foreign keys are ON DELETE CASCADE.
-- Identity columns are not restarted (new rows continue from the current value).
--
-- Liquibase's tracking tables (gw_db_changelog, gw_db_changelog_lock) are deliberately left alone:
-- emptying them would make Liquibase try to re-create existing tables on the next start.
--
-- Uses the default table names. If you configured gateway.db.tables.* or gateway.db.schema,
-- adjust the names below (schema-qualify them, e.g. GATEWAY.gw_flow).
--
-- Run with SQL*Plus / SQLcl / SQL Developer as the schema owner:  @scripts/truncate-all.oracle.sql
-- The running app keeps its in-memory config until POST /admin/config/reload (or a restart).

WHENEVER SQLERROR EXIT FAILURE

TRUNCATE TABLE gw_audit_transaction CASCADE;
TRUNCATE TABLE gw_flow CASCADE;
TRUNCATE TABLE gw_lookup_entry;
TRUNCATE TABLE gw_json_schema;
TRUNCATE TABLE gw_target_system CASCADE;

SELECT 'gw_flow' AS table_name, COUNT(*) AS row_count FROM gw_flow
UNION ALL SELECT 'gw_flow_step', COUNT(*) FROM gw_flow_step
UNION ALL SELECT 'gw_mapping_rule', COUNT(*) FROM gw_mapping_rule
UNION ALL SELECT 'gw_lookup_entry', COUNT(*) FROM gw_lookup_entry
UNION ALL SELECT 'gw_json_schema', COUNT(*) FROM gw_json_schema
UNION ALL SELECT 'gw_target_system', COUNT(*) FROM gw_target_system
UNION ALL SELECT 'gw_target_system_header', COUNT(*) FROM gw_target_system_header
UNION ALL SELECT 'gw_audit_transaction', COUNT(*) FROM gw_audit_transaction
UNION ALL SELECT 'gw_audit_step', COUNT(*) FROM gw_audit_step;
