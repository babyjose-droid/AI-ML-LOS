-- Phase 2B: model governance (baseline distributions, shadow scores), KFS acceptance language

alter table model_version add column baseline_json text;

create table model_shadow_score (
  id bigserial primary key,
  decision_id bigint not null references decision_record(id),
  application_id bigint not null references loan_application(id),
  model_version_id bigint not null references model_version(id),
  pd numeric(8,6) not null,
  band varchar(2) not null,
  decision varchar(30) not null,
  champion_pd numeric(8,6) not null,
  champion_band varchar(2) not null,
  champion_decision varchar(30) not null,
  created_at timestamptz not null default now()
);
create index ix_shadow_model on model_shadow_score(model_version_id);

alter table sanction_record add column kfs_language varchar(5);
