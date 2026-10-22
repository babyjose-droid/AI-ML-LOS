-- Loan plans: what the loan pays for and how it is released or repaid, for three products:
-- affordable housing (construction stages, tranche releases after a site inspection), education (term fees paid to an approved
-- institution) and farm & allied activity (land record, scale of finance, half-yearly repayment after harvest).

create table plan_party (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(160) not null,
  kind varchar(15) not null,                       -- INSTITUTION, BUILDER
  category varchar(40),                            -- e.g. ENGINEERING, NURSING, SCHOOL; RESIDENTIAL
  accreditation varchar(120),                      -- AICTE / UGC / INC / board affiliation; RERA registration
  gstin varchar(15),
  city varchar(80),
  account_name varchar(160),
  account_no varchar(30),
  ifsc varchar(11),
  empanelled boolean not null default true,
  status varchar(15) not null default 'ACTIVE',
  notes varchar(500),
  created_by varchar(60) not null default 'system',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table loan_plan (
  id bigserial primary key,
  application_id bigint not null unique references loan_application(id),
  kind varchar(12) not null,                       -- HOUSING, EDUCATION, AGRI
  details_json text not null default '{}',
  checks_json text,
  rulings_json text,
  status varchar(10) not null default 'PENDING',   -- PENDING, PASS, REFER, FAIL
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table plan_document (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  doc_type varchar(30) not null,
  file_name varchar(200),
  storage_path varchar(400),
  file_hash varchar(64),
  fields_json text,
  read_confidence numeric(5,3),
  engine varchar(30),
  tranche_seq int,
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);

create table loan_tranche (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  seq int not null,
  label varchar(120) not null,
  amount numeric(14,2) not null,
  stage_pct numeric(5,2),                          -- housing: construction complete before release (cumulative %)
  payee_kind varchar(12) not null default 'BORROWER', -- BORROWER, INSTITUTION, BUILDER
  payee_id bigint references plan_party(id),
  status varchar(12) not null default 'PLANNED',   -- PLANNED, READY, PAID, CANCELLED
  certified_pct numeric(5,2),
  certified_by varchar(60),
  certified_at timestamptz,
  certificate_note varchar(500),
  paid_amount numeric(14,2),
  paid_by varchar(60),
  paid_at timestamptz,
  utr varchar(40),
  paid_to varchar(160),
  unique (application_id, seq)
);

alter table product add column plan_kind varchar(12);

insert into plan_party (code, name, kind, category, accreditation, gstin, city, account_name, account_no, ifsc, empanelled, status, notes) values
 ('INS-KINS', 'Kalaram Institute of Nursing Sciences',  'INSTITUTION', 'NURSING',     'INC and state nursing council recognised', null, 'Nashik', 'Kalaram Institute of Nursing Sciences', '33001100224455', 'SBIN0000300', true,  'ACTIVE', 'Fees paid to the institute''s collection account'),
 ('INS-TVCE', 'Trimbak Valley College of Engineering',  'INSTITUTION', 'ENGINEERING', 'AICTE approved, university affiliated',    null, 'Nashik', 'Trimbak Valley College of Engineering', '918020055667788', 'UTIB0000123', true, 'ACTIVE', null),
 ('INS-PIS',  'Panchavati International School',       'INSTITUTION', 'SCHOOL',      'CBSE affiliated',                          null, 'Nashik', 'Panchavati International School', '50200044556677', 'HDFC0001234', true, 'ACTIVE', null),
 ('INS-XYZ',  'Rapid Career Academy',                  'INSTITUTION', 'COACHING',    null,                                       null, 'Nashik', 'Rapid Career Academy', '112200334455', 'SBIN0000300', false, 'SUSPENDED', 'Not recognised; fee refunds disputed'),
 ('BLD-SKY',  'Shubham Homes LLP',                     'BUILDER',     'RESIDENTIAL', 'MahaRERA P51600012345 (fictional)',        '27ABDFS1234K1Z9', 'Nashik', 'Shubham Homes LLP', '50200099887766', 'HDFC0001234', true, 'ACTIVE', 'Approved project for affordable flats');

-- the three products
insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs,
                     min_vintage_years, min_monthly_income, min_processing_fee, foreclosure_pct, bounce_charge, penal_rate_pct,
                     auto_sanction_limit, repayment_frequency, collateral_types, collateral_required, plan_kind) values
 ('AHL',  'Affordable housing loan',      'SALARIED', true,  300000, 3000000, 60, 240, 21, 70, 0.75, 10.50, 14.00, 'ALWAYS', null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,SALARY_SLIP', null, 20000, 3000, 0, 500, 24, null, 'MONTHLY', 'RESIDENTIAL', true, 'HOUSING'),
 ('EDU',  'Education loan',               'SALARIED', false, 50000,  1500000, 12, 84,  21, 65, 1.00, 11.00, 16.00, 'NEVER',  null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,SALARY_SLIP', null, 25000, 1000, 0, 500, 24, null, 'MONTHLY', null, false, 'EDUCATION'),
 ('AGRI', 'Farm & allied activity loan',  'MSME',     false, 50000,  1000000, 12, 60,  21, 70, 0.50, 10.00, 16.00, 'ALWAYS', null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', 2, 20000, 500, 0, 300, 24, null, 'SEASONAL', null, false, 'AGRI');

update product set party_rules = jsonb_set((select party_rules from product where code = 'PL')::jsonb, '{note}', '"Spouse may join as co-applicant; income clubbing allowed"')::text,
                   vkyc_policy = (select vkyc_policy from product where code = 'PL')
 where code in ('AHL', 'EDU', 'AGRI');
update product set party_rules = jsonb_set(party_rules::jsonb, '{note}', '"The student is named in the plan; a parent or guardian borrows"')::text where code = 'EDU';

insert into product_version (product_code, version, status, definition_json, notes, created_by, approved_by, approved_at)
select p.code, p.version, 'PUBLISHED', json_build_object(
         'id', p.id, 'code', p.code, 'name', p.name, 'segment', p.segment, 'secured', p.secured, 'status', p.status, 'version', p.version,
         'minAmount', p.min_amount, 'maxAmount', p.max_amount, 'minTenure', p.min_tenure, 'maxTenure', p.max_tenure,
         'minAge', p.min_age, 'maxAge', p.max_age, 'processingFeePct', p.processing_fee_pct, 'rateMin', p.rate_min, 'rateMax', p.rate_max,
         'fieldVisitRule', p.field_visit_rule, 'fieldVisitThreshold', p.field_visit_threshold, 'requiredDocs', p.required_docs,
         'optionalDocs', p.optional_docs, 'videoKycRule', p.video_kyc_rule, 'videoKycThreshold', p.video_kyc_threshold,
         'bankDataSource', p.bank_data_source, 'autoSanctionLimit', p.auto_sanction_limit,
         'minVintageYears', p.min_vintage_years, 'minMonthlyIncome', p.min_monthly_income,
         'repaymentFrequency', p.repayment_frequency, 'moratoriumMonths', p.moratorium_months, 'minProcessingFee', p.min_processing_fee,
         'foreclosurePct', p.foreclosure_pct, 'bounceCharge', p.bounce_charge, 'penalRatePct', p.penal_rate_pct,
         'scoringModel', p.scoring_model, 'collateralTypes', p.collateral_types, 'collateralRequired', p.collateral_required,
         'planKind', p.plan_kind)::text,
       'Loan plan product', 'system', 'system', now()
  from product p where p.code in ('AHL', 'EDU', 'AGRI');

insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at)
select c.code, 1, 'PUBLISHED', c.stages, 'Initial workflow', 'system', 'system', now() from (values
 ('AHL', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "plan", "name": "Property & stages", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Estimate AI"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "col", "name": "Collateral AI review", "role": "CREDIT_OFFICER", "sla": 120, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Collateral Document AI", "Collateral Vision AI", "Valuation AI"]},
   {"key": "sanction", "name": "Sanction", "role": "CRO", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "First tranche", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]'),
 ('EDU', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app", "Institution referral"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "plan", "name": "Course & fees", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Admission and fee letter AI", "Institution master"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "First term fee", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Payout to institution", "LMS"]}]'),
 ('AGRI', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "plan", "name": "Farm & activity", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Land record AI (7/12)", "Scale of finance"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]')
) as c(code, stages);

update policy_version set overrides_json = (coalesce(nullif(overrides_json, ''), '{}')::jsonb
  || '{"product:AHL": {"exposureCap": 3000000}, "product:EDU": {"exposureCap": 1500000}}'::jsonb)::text
 where status = 'LIVE';
