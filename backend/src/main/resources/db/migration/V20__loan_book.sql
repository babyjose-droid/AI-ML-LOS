-- Phase 3A: live-loan book from the LMS feed, IRACP classification, Ind AS 109 ECL, early warning

create table live_loan (
  id bigserial primary key,
  loan_ref varchar(40) not null unique,            -- the LMS loan id
  application_id bigint references loan_application(id),
  app_no varchar(20),
  borrower_name varchar(120) not null,
  segment varchar(20) not null,
  product_code varchar(20) not null,
  branch_id bigint references branch(id),
  sanctioned_amount numeric(14,2) not null,
  disbursed_on date not null,
  tenure_months int not null,
  rate numeric(6,2) not null,
  emi numeric(14,2) not null,
  secured boolean not null default false,
  source varchar(10) not null,                     -- RHYTHM (disbursed here), LMS (feed only), DEMO
  status varchar(15) not null default 'ACTIVE',    -- ACTIVE, CLOSED, WRITTEN_OFF
  app_pd numeric(8,6),
  restructured boolean not null default false,
  restructured_on date,
  last_feed_date date,
  outstanding numeric(14,2) not null,
  overdue numeric(14,2) not null default 0,
  dpd int not null default 0,
  max_dpd int not null default 0,
  bounces_3m int not null default 0,
  paid_emis int not null default 0,
  last_payment_date date,
  classification varchar(10) not null default 'STANDARD',
  npa_since date,
  ecl_stage int not null default 1,
  ecl numeric(14,2) not null default 0,
  provision_iracp numeric(14,2) not null default 0,
  behaviour_pd numeric(8,6),
  ews_score int not null default 0,
  ews_reasons_json text,
  updated_at timestamptz not null default now()
);
create index ix_live_loan_class on live_loan(classification);
create index ix_live_loan_app on live_loan(application_id);

create table loan_snapshot (
  id bigserial primary key,
  loan_id bigint not null references live_loan(id),
  feed_date date not null,
  outstanding numeric(14,2) not null,
  overdue numeric(14,2) not null,
  dpd int not null,
  classification varchar(10) not null,
  ecl_stage int not null,
  ecl numeric(14,2) not null,
  unique (loan_id, feed_date)
);

create table lms_feed (
  id bigserial primary key,
  feed_date date not null,
  source varchar(20) not null,                     -- API, CSV, DEMO
  rows_total int not null,
  accepted int not null,
  rejected int not null,
  errors_json text,
  created_by varchar(60),
  created_at timestamptz not null default now()
);
