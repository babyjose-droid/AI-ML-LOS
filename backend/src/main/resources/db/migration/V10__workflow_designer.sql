-- Phase 5: workflow designer. Each product has a versioned sequence of stages; applications keep the version they started on.

create table workflow_version (
  id bigserial primary key,
  product_code varchar(20) not null,
  version int not null,
  status varchar(20) not null,             -- DRAFT, PENDING_APPROVAL, PUBLISHED, RETIRED, REJECTED
  stages_json text not null,               -- [{key, name, role, sla, mode, onFailure, skip, integrations}]
  notes varchar(500),
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  submitted_at timestamptz,
  approved_by varchar(60),
  approved_at timestamptz,
  decision_note varchar(1000),
  unique (product_code, version)
);

-- progress of each application through its workflow stages
create table app_stage (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  stage_key varchar(20) not null,
  status varchar(20) not null,             -- ACTIVE, DONE, SKIPPED, FAILED
  started_at timestamptz,
  due_at timestamptz,
  completed_at timestamptz,
  completed_by varchar(60),
  outcome varchar(10),                     -- PASS or FAIL for stages a person completes
  note varchar(1000),
  data_json text,                          -- stage details, e.g. valuation amount
  skip_reason varchar(300),
  reviewed_by varchar(60),
  reviewed_at timestamptz,
  breached_at timestamptz,
  breach_action varchar(20),
  escalated_to varchar(30),
  unique (application_id, stage_key)
);
create index ix_app_stage_status on app_stage(status, due_at);

alter table loan_application add column workflow_version int;

-- field officers do field visits, group formation and vehicle valuation
insert into app_user (username, full_name, password_hash, role, branch_id, email) values
 ('field1', 'Ganesh Pawar', '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'FIELD_OFFICER', (select id from branch where code='BNSK'), 'field1@example.com');

-- the two products of the prototype that need their own stages
insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs) values
 ('CV-SEC', 'Used commercial vehicle loan (secured)', 'MSME', true, 100000, 2000000, 12, 48, 21, 65, 1.50, 12.00, 22.00, 'ALWAYS', null, 'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT'),
 ('GL', 'Gold loan', 'SALARIED', true, 10000, 1000000, 3, 12, 18, 70, 0.50, 10.00, 18.00, 'NEVER', null, 'AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,PAN');

insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at) values ('BL-UNS', 1, 'PUBLISHED', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Borrower portal", "Sales app"]}, {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]}, {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]}, {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]}, {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]}, {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]}, {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]}, {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]', 'Initial workflow', 'system', 'system', now());
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at) values ('PL', 1, 'PUBLISHED', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Borrower portal", "Sales app"]}, {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]}, {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]}, {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]}, {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]}, {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]}, {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]}, {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]', 'Initial workflow', 'system', 'system', now());
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at) values ('JLG', 1, 'PUBLISHED', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Borrower portal", "Sales app"]}, {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]}, {"key": "cgt", "name": "Group formation (CGT)", "role": "FIELD_OFFICER", "sla": 72, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]}, {"key": "grt", "name": "Group recognition test (GRT)", "role": "CREDIT_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "REJECT", "skip": "", "integrations": ["Field app"]}, {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]}, {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]}, {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]}, {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]', 'Initial workflow', 'system', 'system', now());
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at) values ('CV-SEC', 1, 'PUBLISHED', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Borrower portal", "Sales app"]}, {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]}, {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]}, {"key": "val", "name": "Vehicle valuation", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Valuation partner", "Vahan RC check"]}, {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]}, {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]}, {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]}, {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]}, {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]', 'Initial workflow', 'system', 'system', now());
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at) values ('GL', 1, 'PUBLISHED', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Borrower portal", "Sales app"]}, {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]}, {"key": "gold", "name": "Gold appraisal", "role": "CREDIT_OFFICER", "sla": 1, "mode": "MANUAL", "onFailure": "REJECT", "skip": "", "integrations": ["Purity analyser"]}, {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]}, {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]}, {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]', 'Initial workflow', 'system', 'system', now());

-- applications already in progress follow version 1
update loan_application set workflow_version = 1;
