-- Platform schema: the tenant registry and anonymised benchmarking. Each tenant's data lives in its own schema.
create table tenant (
  code varchar(20) primary key,
  name varchar(120) not null,
  schema_name varchar(40) not null unique,
  status varchar(12) not null default 'ONBOARDING',   -- ONBOARDING, LIVE, SUSPENDED
  data_region varchar(20) not null default 'IN',
  benchmarking_opt_in boolean not null default false,
  onboarding_json text,
  sandbox_json text,
  rollback_plan text,
  go_live_requested_by varchar(60),
  go_live_requested_at timestamptz,
  go_live_approved_by varchar(60),
  go_live_approved_at timestamptz,
  created_by varchar(60) not null,
  created_at timestamptz not null default now()
);
insert into tenant (code, name, schema_name, status, created_by, go_live_approved_by, go_live_approved_at)
values ('default', 'Rhythm demo lender', 'public', 'LIVE', 'system', 'system', now());

create table benchmark_metric (
  id bigserial primary key,
  tenant_code varchar(20) not null,
  period varchar(7) not null,             -- YYYY-MM
  metric varchar(40) not null,
  value double precision,
  source varchar(12) not null default 'COMPUTED',   -- COMPUTED, SYNTHETIC
  computed_at timestamptz not null default now(),
  unique (tenant_code, period, metric)
);
