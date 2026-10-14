-- Phase 4B: co-lending matcher (FSD 17.3) and capital & liquidity planning (FSD 19.5)

create table colending_partner (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(120) not null,
  products varchar(200) not null,              -- product codes the partner co-lends
  max_ticket numeric(14,2) not null,
  allowed_bands varchar(20) not null,          -- e.g. A,B,C
  ntc_allowed boolean not null default false,
  min_vintage_years numeric(4,1) not null default 0,
  psl_only boolean not null default false,
  partner_share numeric(5,4) not null,         -- the partner's share of each loan (e.g. 0.80)
  partner_rate numeric(6,2) not null,          -- the partner's rate on its share
  status varchar(10) not null default 'ACTIVE',
  updated_by varchar(60) not null default 'system',
  updated_at timestamptz not null default now()
);
insert into colending_partner (code, name, products, max_ticket, allowed_bands, ntc_allowed, min_vintage_years, psl_only, partner_share, partner_rate) values
 ('PB-MSME', 'Partner Bank A (MSME co-lending)', 'BL-UNS,LAP', 2500000, 'A,B,C', false, 3, true, 0.80, 9.50),
 ('PB-RETAIL', 'Partner Bank B (retail)', 'PL,GL', 500000, 'A,B', false, 0, false, 0.80, 10.25);

create table colending_assignment (
  id bigserial primary key,
  partner_id bigint not null references colending_partner(id),
  application_id bigint not null references loan_application(id),
  amount numeric(14,2) not null,
  partner_amount numeric(14,2) not null,
  loan_rate numeric(6,2) not null,
  partner_rate numeric(6,2) not null,
  nbfc_yield numeric(6,2) not null,
  status varchar(12) not null default 'SUBMITTED',  -- SUBMITTED, APPROVED, REJECTED, WITHDRAWN
  batch varchar(30) not null,
  partner_ref varchar(60),
  note varchar(500),
  submitted_by varchar(60) not null,
  submitted_at timestamptz not null default now(),
  decided_by varchar(60),
  decided_at timestamptz,
  unique (partner_id, application_id)
);

create table capital_scenario (
  id bigserial primary key,
  name varchar(80) not null,
  inputs_json text not null,
  result_json text not null,
  created_by varchar(60) not null,
  created_at timestamptz not null default now()
);
