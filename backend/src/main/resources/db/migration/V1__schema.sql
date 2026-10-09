-- Rhythm LOS Phase 1 schema

create table branch (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(120) not null,
  level varchar(20) not null,          -- HEAD_OFFICE, ZONE, REGION, BRANCH
  parent_id bigint references branch(id),
  city varchar(80),
  active boolean not null default true
);

create table app_user (
  id bigserial primary key,
  username varchar(60) not null unique,
  full_name varchar(120) not null,
  password_hash varchar(100) not null,
  role varchar(30) not null,
  branch_id bigint references branch(id),
  email varchar(120),
  mobile varchar(15),
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table product (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(120) not null,
  segment varchar(30) not null,        -- MSME, SALARIED, MICROFINANCE
  secured boolean not null default false,
  status varchar(20) not null default 'LIVE',
  version int not null default 1,
  min_amount numeric(14,2) not null,
  max_amount numeric(14,2) not null,
  min_tenure int not null,
  max_tenure int not null,
  min_age int not null,
  max_age int not null,
  processing_fee_pct numeric(5,2) not null,
  rate_min numeric(5,2) not null,
  rate_max numeric(5,2) not null,
  field_visit_rule varchar(30) not null,   -- ALWAYS, NEVER, ABOVE_AMOUNT
  field_visit_threshold numeric(14,2),
  required_docs varchar(300) not null,     -- comma separated document types
  updated_at timestamptz not null default now()
);

create table policy_version (
  id bigserial primary key,
  version varchar(40) not null unique,
  status varchar(20) not null,          -- DRAFT, LIVE, RETIRED
  params_json text not null,
  notes varchar(500),
  created_by varchar(60),
  created_at timestamptz not null default now()
);

create table loan_application (
  id bigserial primary key,
  app_no varchar(20) unique,
  product_code varchar(20) not null,
  branch_id bigint references branch(id),
  created_by varchar(60) not null,
  -- applicant
  applicant_name varchar(120) not null,
  pan varchar(10) not null,
  mobile varchar(10) not null,
  email varchar(120),
  dob date not null,
  gender varchar(10),
  address varchar(300),
  city varchar(80),
  pincode varchar(6),
  segment varchar(30) not null,
  business_name varchar(160),
  business_vintage_years numeric(5,1) not null default 0,
  declared_monthly_income numeric(14,2) not null,
  essential_expenses numeric(14,2) not null,
  -- loan
  loan_amount numeric(14,2) not null,
  tenure_months int not null,
  purpose varchar(200),
  bank_account_no varchar(20),
  bank_ifsc varchar(11),
  -- consents
  consent_bureau boolean not null default false,
  consent_aa boolean not null default false,
  consent_kyc boolean not null default false,
  consent_at timestamptz,
  -- states (one column per state-machine domain)
  app_state varchar(30) not null,
  kyc_state varchar(30) not null,
  data_state varchar(30) not null,
  docs_state varchar(30) not null,
  field_state varchar(30) not null,
  decision_state varchar(30) not null,
  fraud_state varchar(30) not null,
  sanction_state varchar(30) not null,
  disb_state varchar(30) not null,
  rejection_reason varchar(300),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
create index ix_app_pan on loan_application(pan);
create index ix_app_mobile on loan_application(mobile);
create index ix_app_state on loan_application(app_state);

create table state_history (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  domain varchar(20) not null,
  from_state varchar(30) not null,
  to_state varchar(30) not null,
  event varchar(60) not null,
  actor varchar(60) not null,
  note varchar(500),
  created_at timestamptz not null default now()
);
create index ix_hist_app on state_history(application_id);

create table app_document (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  doc_type varchar(30) not null,
  file_name varchar(200) not null,
  content_type varchar(100),
  size_bytes bigint not null,
  sha256 varchar(64) not null,
  storage_path varchar(400) not null,
  status varchar(20) not null,          -- UPLOADED, VERIFIED, REJECTED
  remarks varchar(300),
  tamper_flag boolean not null default false,
  read_confidence numeric(4,3),
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);
create index ix_doc_app on app_document(application_id);

create table data_snapshot (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  kind varchar(20) not null,            -- KYC, AA, BUREAU, GST
  vendor varchar(60) not null,
  payload_json text not null,
  fetched_at timestamptz not null default now()
);
create index ix_snap_app on data_snapshot(application_id, kind);

create table integration_log (
  id bigserial primary key,
  application_id bigint references loan_application(id),
  vendor varchar(60) not null,
  operation varchar(60) not null,
  attempt int not null,
  status varchar(20) not null,          -- SUCCESS, FAILED, DLQ, RESOLVED
  latency_ms bigint not null,
  request_ref varchar(80) not null,
  response_summary varchar(500),
  error_message varchar(500),
  retry_of bigint,
  created_at timestamptz not null default now()
);
create index ix_int_app on integration_log(application_id);
create index ix_int_status on integration_log(status);

create table decision_record (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  decision varchar(30) not null,
  pd numeric(8,6) not null,
  score int not null,
  risk_band varchar(1) not null,
  fraud_score int not null,
  fraud_status varchar(10) not null,
  kyc_status varchar(10) not null,
  sustainable_income numeric(14,2) not null,
  max_emi numeric(14,2) not null,
  requested_emi numeric(14,2) not null,
  recommended_amount numeric(14,2) not null,
  recommended_emi numeric(14,2) not null,
  rate numeric(5,2) not null,
  foir_post numeric(6,4) not null,
  expected_loss numeric(14,2) not null,
  delegation_level varchar(5),
  rules_json text not null,
  contributions_json text not null,
  steps_json text not null,
  reason_codes varchar(300) not null,
  conditions_json text not null,
  narrative varchar(2000) not null,
  credit_memo text not null,
  model_version varchar(40) not null,
  policy_version varchar(40) not null,
  input_hash varchar(64) not null,
  input_json text not null,
  created_by varchar(60) not null,
  created_at timestamptz not null default now()
);
create index ix_dec_app on decision_record(application_id);

create table sanction_record (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  decision_id bigint references decision_record(id),
  action varchar(20) not null,          -- APPROVED, DECLINED, ESCALATED
  level varchar(5) not null,
  amount numeric(14,2),
  rate numeric(5,2),
  tenure_months int,
  override_flag boolean not null default false,
  note varchar(500),
  kfs_json text,
  kfs_accepted_at timestamptz,
  actor varchar(60) not null,
  created_at timestamptz not null default now()
);
create index ix_sanc_app on sanction_record(application_id);

create table disbursement (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  status varchar(20) not null,          -- SUCCESS, FAILED
  gross_amount numeric(14,2) not null,
  deductions numeric(14,2) not null,
  net_amount numeric(14,2) not null,
  beneficiary_account varchar(20),
  ifsc varchar(11),
  name_match_score numeric(4,3),
  utr varchar(40),
  failure_reason varchar(300),
  lms_payload text,
  actor varchar(60) not null,
  created_at timestamptz not null default now()
);
create index ix_disb_app on disbursement(application_id);

create table audit_event (
  id bigserial primary key,
  actor varchar(60) not null,
  actor_role varchar(30),
  action varchar(80) not null,
  entity_type varchar(40) not null,
  entity_id varchar(40),
  application_id bigint,
  details varchar(1000),
  created_at timestamptz not null default now()
);
create index ix_audit_app on audit_event(application_id);
