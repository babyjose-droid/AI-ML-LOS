-- Phase 1 closing: consent ledger, borrower language, credit memo sign-off, send back at sanction

alter table loan_application add column preferred_language varchar(5) not null default 'en';

create table consent (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  source varchar(20) not null,              -- KYC, AA, BUREAU, GST, COMMUNICATION
  purpose varchar(300) not null,
  scope varchar(300) not null,
  channel varchar(80) not null,
  language varchar(5) not null,
  status varchar(20) not null,              -- GRANTED, WITHDRAWN
  granted_at timestamptz not null,
  valid_until timestamptz,
  withdrawn_at timestamptz,
  withdrawn_by varchar(60),
  effect varchar(300)
);
create index ix_consent_app on consent(application_id);

-- applications submitted before the ledger: their tick-box consents become ledger records
insert into consent (application_id, source, purpose, scope, channel, language, status, granted_at, valid_until)
select a.id, 'KYC', 'Verify your identity for this loan', 'PAN, Aadhaar (masked), CKYC record and the documents you upload',
       case when a.channel = 'BORROWER_PORTAL' then 'Borrower portal (OTP-verified mobile)' else 'Branch / sales console' end,
       'en', 'GRANTED', coalesce(a.consent_at, a.created_at), coalesce(a.consent_at, a.created_at) + interval '365 days'
  from loan_application a where a.consent_kyc and a.app_state <> 'DRAFT';
insert into consent (application_id, source, purpose, scope, channel, language, status, granted_at, valid_until)
select a.id, 'AA', 'Assess your income and repayment capacity', '12 months of transactions of the bank account you choose, through an Account Aggregator',
       case when a.channel = 'BORROWER_PORTAL' then 'Borrower portal (OTP-verified mobile)' else 'Branch / sales console' end,
       'en', 'GRANTED', coalesce(a.consent_at, a.created_at), coalesce(a.consent_at, a.created_at) + interval '365 days'
  from loan_application a where a.consent_aa and a.app_state <> 'DRAFT';
insert into consent (application_id, source, purpose, scope, channel, language, status, granted_at, valid_until)
select a.id, 'BUREAU', 'Check your credit history', 'One credit report from a credit information company',
       case when a.channel = 'BORROWER_PORTAL' then 'Borrower portal (OTP-verified mobile)' else 'Branch / sales console' end,
       'en', 'GRANTED', coalesce(a.consent_at, a.created_at), coalesce(a.consent_at, a.created_at) + interval '180 days'
  from loan_application a where a.consent_bureau and a.app_state <> 'DRAFT';

-- credit memo: drafted by the engine, edited by the credit officer, locked on sign-off (FR-CAM-03)
create table credit_memo (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  decision_id bigint not null references decision_record(id),
  version int not null,
  status varchar(20) not null,              -- DRAFT, SIGNED, SUPERSEDED
  body text not null,
  deviations_json text,                     -- [{rule, description, justification}]
  edited_by varchar(60) not null,
  edited_at timestamptz not null default now(),
  signed_by varchar(60),
  signer_role varchar(30),
  signed_at timestamptz,
  unique (application_id, version)
);
create index ix_credit_memo_app on credit_memo(application_id);

-- sanction can be approved with extra conditions or sent back to credit (FR-UW-06, FR-SAN-02)
alter table sanction_record add column conditions_json text;
