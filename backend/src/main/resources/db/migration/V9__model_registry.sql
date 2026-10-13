-- Phase 5: trained probability-of-default models, with maker-checker before a model is used for decisions.
create table model_version (
  id bigserial primary key,
  name varchar(40) not null,
  version varchar(20) not null,
  status varchar(20) not null,            -- CANDIDATE, CHAMPION, RETIRED, REJECTED
  intercept double precision not null,
  terms_json text not null,               -- [{feature, weight, mean, standardisedWeight, unexpectedSign}]
  metrics_json text not null,             -- hold-out AUC/Gini/KS/Brier, deciles, comparison with the champion
  data_source varchar(20) not null,       -- SYNTHETIC, IMPORTED, MIXED
  rows_used int not null,
  trained_by varchar(60) not null,
  trained_at timestamptz not null default now(),
  decided_by varchar(60),
  decided_at timestamptz,
  decision_note varchar(1000),
  unique (name, version)
);
