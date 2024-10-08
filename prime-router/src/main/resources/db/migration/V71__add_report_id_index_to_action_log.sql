/*
* The Flyway tool applies this migration to create the database.
*
* Follow this style guide https://about.gitlab.com/handbook/business-ops/data-team/platform/sql-style-guide/
* use VARCHAR(63) for names in organization and schema
*
* Copy a version of this comment into the next migration
*
*/

CREATE INDEX CONCURRENTLY IF NOT EXISTS action_log_report_id_idx
    on action_log (report_id);