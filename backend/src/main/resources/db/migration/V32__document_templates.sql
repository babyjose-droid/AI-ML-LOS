-- Loan document templates (application form, sanction letter, KFS, agreement, deeds…), the clause library, which documents
-- each product uses, and the documents made for each application with their signatures.

create table doc_template (
  id bigserial primary key,
  code varchar(40) not null,                -- e.g. LOAN_AGREEMENT, CL_INTEREST
  name varchar(120) not null,
  doc_type varchar(30) not null,            -- APPLICATION_FORM, SANCTION_LETTER, KFS, LOAN_AGREEMENT, GUARANTEE_DEED, DPN, HYPOTHECATION, MORTGAGE, WELCOME, NOC, OTHER, CLAUSE
  language varchar(5) not null default 'en',
  version int not null,
  status varchar(20) not null,              -- DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, RETIRED
  body text not null,
  source varchar(10) not null default 'EDITOR',   -- EDITOR, WORD, SYSTEM
  notes varchar(500),
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  submitted_at timestamptz,
  decided_by varchar(60),
  decided_at timestamptz,
  decision_note varchar(500),
  unique (code, language, version)
);
create index ix_doc_template_code on doc_template(code, language, status);

create table product_document (
  id bigserial primary key,
  product_code varchar(20) not null,
  template_code varchar(40) not null,
  stage varchar(15) not null,               -- APPLICATION, SANCTION, PRE_DISBURSE, POST_DISBURSE, CLOSURE
  signers varchar(100) not null default '', -- BORROWER, CO_APPLICANT, GUARANTOR (comma separated)
  condition varchar(20) not null default 'ALWAYS',  -- ALWAYS, HAS_CO_APPLICANT, HAS_GUARANTOR, HAS_COLLATERAL, UNSECURED
  required boolean not null default true,
  sort int not null default 0,
  unique (product_code, template_code)
);
alter table product add column doc_gate boolean not null default false;   -- disbursement waits for the pack to be signed

create table loan_doc (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  template_code varchar(40) not null,
  template_id bigint not null references doc_template(id),
  template_version int not null,
  language varchar(5) not null,
  stage varchar(15) not null,
  name varchar(160) not null,
  html text not null,
  storage_path varchar(400),
  sha256 varchar(64) not null,
  data_hash varchar(64) not null,         -- fingerprint of the values the document uses
  fields text not null default '',          -- the data paths it uses (comma separated)
  bytes int not null default 0,
  signers varchar(100) not null default '',
  status varchar(12) not null default 'CURRENT',   -- CURRENT, SUPERSEDED
  missing varchar(1000),
  generated_by varchar(60) not null,
  generated_at timestamptz not null default now()
);
create index ix_loan_doc_app on loan_doc(application_id, status);

create table loan_doc_signature (
  id bigserial primary key,
  loan_doc_id bigint not null references loan_doc(id),
  signer_role varchar(15) not null,         -- BORROWER, CO_APPLICANT, GUARANTOR
  party_id bigint,
  signer_name varchar(160) not null,
  channel varchar(30) not null,             -- OTP, AADHAAR_ESIGN (when integrated), WET_INK
  doc_sha256 varchar(64) not null,
  recorded_by varchar(60) not null,
  signed_at timestamptz not null default now()
);
create index ix_loan_doc_sig on loan_doc_signature(loan_doc_id);
