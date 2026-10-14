-- Phase 3B: restructuring (maker-checker), collections (contacts, promises to pay), top-up offers

-- a restructured account stays NPA until the specified period of satisfactory performance has passed (IRACP, 7 Jun 2019)
alter table live_loan add column npa_hold_until date;

create table loan_restructure (
  id bigserial primary key,
  loan_id bigint not null references live_loan(id),
  kind varchar(20) not null,                        -- TENURE_EXTENSION, MORATORIUM, RATE_REDUCTION
  new_tenure_months int not null,
  new_rate numeric(6,2) not null,
  moratorium_months int not null default 0,
  new_emi numeric(14,2) not null,
  outstanding_at numeric(14,2) not null,
  reason varchar(500) not null,
  status varchar(10) not null default 'PENDING',    -- PENDING, APPROVED, REJECTED
  requested_by varchar(60) not null,
  requested_at timestamptz not null default now(),
  decided_by varchar(60),
  decided_at timestamptz,
  decision_note varchar(500),
  schedule_json text
);
create index ix_restructure_loan on loan_restructure(loan_id);

create table collection_contact (
  id bigserial primary key,
  loan_id bigint not null references live_loan(id),
  channel varchar(15) not null,                     -- SMS, WHATSAPP, CALL, FIELD_VISIT, NOTICE
  outcome varchar(20) not null,                     -- PROMISE_TO_PAY, PAID, NO_ANSWER, REFUSED, DISPUTE, WRONG_NUMBER, SENT
  contacted_at timestamptz not null,
  dpd_at_contact int not null default 0,
  ptp_date date,
  ptp_amount numeric(14,2),
  ptp_status varchar(10),                           -- OPEN, KEPT, BROKEN
  note varchar(500),
  created_by varchar(60) not null
);
create index ix_contact_loan on collection_contact(loan_id);

create table topup_offer (
  id bigserial primary key,
  loan_id bigint not null references live_loan(id),
  amount numeric(14,2) not null,
  rate numeric(6,2) not null,
  tenure_months int not null,
  status varchar(10) not null default 'OFFERED',    -- OFFERED, APPLIED, DECLINED, EXPIRED
  application_id bigint references loan_application(id),
  expires_on date not null,
  offered_by varchar(60) not null,
  created_at timestamptz not null default now()
);
create index ix_topup_loan on topup_offer(loan_id);
