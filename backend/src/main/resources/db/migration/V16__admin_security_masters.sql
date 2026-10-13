-- Phase 1 admin: branch PIN codes and products, holiday calendar, sanction limits as data,
-- user MFA and lockout, connector configuration, append-only audit trail

alter table branch add column pincodes varchar(2000);
alter table branch add column products varchar(500);
-- PIN codes are set by the admin per branch (Masters); until then the geography rule is not applied

create table holiday (
  id bigserial primary key,
  holiday_date date not null,
  name varchar(120) not null,
  branch_id bigint references branch(id),            -- null: every branch
  created_by varchar(60),
  created_at timestamptz not null default now(),
  unique (holiday_date, branch_id)
);
insert into holiday (holiday_date, name, created_by) values
 ('2026-10-02', 'Gandhi Jayanti', 'system'),
 ('2026-10-20', 'Dussehra', 'system'),
 ('2026-11-08', 'Diwali (Lakshmi Puja)', 'system'),
 ('2026-11-24', 'Guru Nanak Jayanti', 'system'),
 ('2026-12-25', 'Christmas', 'system'),
 ('2027-01-26', 'Republic Day', 'system');

create table sanction_limit (
  level varchar(4) primary key,                     -- L1, L2, L3
  role varchar(30) not null,
  max_amount numeric(14,2),                         -- null: no amount limit
  max_band varchar(2) not null,                     -- worst risk band the level may approve
  max_deviations int not null,
  updated_by varchar(60),
  updated_at timestamptz not null default now()
);
insert into sanction_limit (level, role, max_amount, max_band, max_deviations, updated_by) values
 ('L1', 'CREDIT_OFFICER', 200000, 'C', 0, 'system'),
 ('L2', 'CREDIT_MANAGER', 1000000, 'D', 1, 'system'),
 ('L3', 'CRO', null, 'E', 99, 'system');

alter table app_user add column mfa_secret varchar(64);
alter table app_user add column mfa_enabled boolean not null default false;
alter table app_user add column failed_logins int not null default 0;
alter table app_user add column locked_until timestamptz;
alter table app_user add column last_login_at timestamptz;

create table connector_config (
  connector varchar(30) primary key,                -- the operation family: kyc, aa, bureau, gst, bank, payout, document, statement, vkyc
  name varchar(80) not null,
  mode varchar(10) not null default 'MOCK',          -- MOCK until the integrations phase; LIVE afterwards
  enabled boolean not null default true,
  timeout_ms int not null default 10000,
  max_attempts int not null default 3,
  breaker_threshold int not null default 5,          -- consecutive failures that open the circuit
  breaker_cooldown_s int not null default 60,
  base_url varchar(300),
  notes varchar(500),
  updated_by varchar(60),
  updated_at timestamptz not null default now()
);

insert into connector_config (connector, name, timeout_ms, notes, updated_by) values
 ('kyc', 'KYC (CKYC / PAN / documents)', 10000, 'In-house document KYC; CKYC and DigiLocker in the integrations phase', 'system'),
 ('aa', 'Account Aggregator', 20000, 'Bank data through an AA; mock until the integrations phase', 'system'),
 ('bureau', 'Credit bureau', 15000, 'One CIC report per application', 'system'),
 ('gst', 'GST returns', 15000, 'GSTN through a GSP', 'system'),
 ('bank', 'Penny-drop name check', 10000, 'Bank account verification', 'system'),
 ('payout', 'Disbursement payout', 30000, 'NEFT / IMPS payout', 'system'),
 ('document', 'Document AI (in-house)', 60000, 'Python AI service', 'system'),
 ('statement', 'Statement AI (in-house)', 60000, 'Python AI service', 'system'),
 ('vkyc', 'Video KYC AI (in-house)', 90000, 'Python AI service', 'system');

-- the audit trail is append-only: no updates or deletes, even from the application
create or replace function audit_event_append_only() returns trigger as $$
begin
  raise exception 'audit_event is append-only';
end;
$$ language plpgsql;
create trigger audit_event_no_update before update or delete on audit_event
  for each row execute function audit_event_append_only();
