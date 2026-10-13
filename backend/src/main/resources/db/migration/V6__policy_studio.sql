-- Phase 5: policy studio (drafts, simulation, maker-checker) and a loan book with outcomes for simulation and model training.

alter table policy_version
  add column based_on varchar(40),
  add column submitted_at timestamptz,
  add column checker varchar(60),
  add column decided_at timestamptz,
  add column decision_note varchar(1000),
  add column simulation_json text,
  add column live_from timestamptz,
  add column retired_at timestamptz;
-- statuses: DRAFT, PENDING_APPROVAL, LIVE, RETIRED, REJECTED
update policy_version set live_from = created_at where status = 'LIVE';

-- Past loans with known 12-month outcomes. source = SYNTHETIC (generated demo book) or IMPORTED (the NBFC's own history).
create table historical_loan (
  id bigserial primary key,
  ref varchar(40) not null unique,
  source varchar(20) not null,
  segment varchar(20) not null,
  product_code varchar(20),
  amount numeric(14,2) not null,
  disbursed_on date,
  input_json text not null,
  outcome_bad boolean not null,          -- 90+ days past due or written off within 12 months
  created_at timestamptz not null default now()
);
create index ix_hist_source on historical_loan(source);
