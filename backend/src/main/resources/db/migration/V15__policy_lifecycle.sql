-- Policy lifecycle (FR-POL-02, FR-POL-05): effective date, per-product / per-segment overrides, rollback
alter table policy_version add column effective_from timestamptz;
alter table policy_version add column overrides_json text;
alter table policy_version add column rollback_of varchar(40);
