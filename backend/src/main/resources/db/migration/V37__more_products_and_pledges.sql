-- Remaining products: merchant, micro-enterprise working capital, doctor & professional, franchise, school & hospital
-- (configured on the existing flows), rental income-backed loans (lease rental discounting: a RENTAL loan plan plus the
-- property), and loans against securities and fixed deposits (pledges with drawing power, lien and margin monitoring).

-- ---------------------------------------------------------------- pledges

create table security_master (
  isin varchar(12) primary key,
  name varchar(160) not null,
  category varchar(12) not null,                 -- EQUITY, EQUITY_MF, DEBT_MF, BOND
  approved boolean not null default true,
  max_ltv numeric(5,4) not null,                 -- share of market value that counts towards drawing power
  price numeric(14,4) not null,
  price_date date not null,
  updated_by varchar(60) not null default 'system',
  updated_at timestamptz not null default now()
);

create table pledge (
  id bigserial primary key,
  application_id bigint not null unique references loan_application(id),
  kind varchar(12) not null,                     -- SECURITIES, DEPOSIT
  holder_name varchar(160),
  depository varchar(10),                        -- CDSL, NSDL
  demat_id varchar(20),
  issuer varchar(160),                           -- the bank or company that holds the deposit
  deposit_no varchar(40),
  principal numeric(16,2),
  rate_pa numeric(6,3),
  start_date date,
  maturity_date date,
  lien_state varchar(12) not null default 'NONE', -- NONE, MARKED, INVOKED, RELEASED
  lien_ref varchar(80),
  lien_marked_by varchar(60),
  lien_marked_at timestamptz,
  value numeric(16,2),
  drawing_power numeric(16,2),
  status varchar(10) not null default 'PENDING',
  checks_json text,
  rulings_json text,
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table pledge_holding (
  id bigserial primary key,
  pledge_id bigint not null references pledge(id),
  isin varchar(12) not null,
  name varchar(160),
  quantity numeric(16,4) not null,
  added_for_call bigint,
  unique (pledge_id, isin)
);

create table pledge_document (
  id bigserial primary key,
  pledge_id bigint not null references pledge(id),
  doc_type varchar(30) not null,                 -- CAS_STATEMENT, FD_RECEIPT, LIEN_CONFIRMATION
  file_name varchar(200),
  storage_path varchar(400),
  file_hash varchar(64),
  fields_json text,
  read_confidence numeric(5,3),
  engine varchar(30),
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);

create table margin_call (
  id bigserial primary key,
  pledge_id bigint not null references pledge(id),
  application_id bigint not null references loan_application(id),
  outstanding numeric(16,2) not null,
  drawing_power numeric(16,2) not null,
  shortfall numeric(16,2) not null,
  due_by date not null,
  status varchar(10) not null default 'OPEN',    -- OPEN, CURED, INVOKED
  cure_note varchar(300),
  closed_by varchar(60),
  closed_at timestamptz,
  created_at timestamptz not null default now()
);

-- fictional securities for the demo (the lender loads its approved list and a daily price file)
insert into security_master (isin, name, category, approved, max_ltv, price, price_date) values
 ('INE0DM101015', 'Western Ghats Power Ltd (demo)',         'EQUITY',    true,  0.50, 1240.00, current_date),
 ('INE0DM201013', 'Konkan Pharma Ltd (demo)',               'EQUITY',    true,  0.50,  860.50, current_date),
 ('INE0DM301011', 'Deccan Infra Projects Ltd (demo)',       'EQUITY',    true,  0.50,  412.25, current_date),
 ('INE0DM401019', 'Satpuda Speculative Mining Ltd (demo)',  'EQUITY',    false, 0.00,   38.10, current_date),
 ('INF0DM501018', 'Sahyadri Bluechip Equity Fund - Growth (demo)', 'EQUITY_MF', true, 0.50, 152.3400, current_date),
 ('INF0DM601016', 'Godavari Short Term Debt Fund - Growth (demo)', 'DEBT_MF',   true, 0.75,  31.8900, current_date),
 ('INE0DM701014', 'Maharashtra Infra Bond 2031 (demo)',     'BOND',      true,  0.80, 1018.00, current_date);

-- ---------------------------------------------------------------- products

alter table product add column pledge_kind varchar(12);

insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs,
                     min_vintage_years, min_monthly_income, min_processing_fee, foreclosure_pct, bounce_charge, penal_rate_pct,
                     auto_sanction_limit, repayment_frequency, collateral_types, collateral_required, plan_kind, pledge_kind) values
 ('MERCH', 'Merchant & retailer loan',            'MSME',     false,  50000, 1000000,  6, 36, 21, 65, 1.50, 15.00, 24.00, 'ABOVE_AMOUNT', 300000,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 2, 25000, 999, 3, 500, 24, null, 'MONTHLY', null, false, null, null),
 ('MEWC',  'Micro-enterprise working capital',    'MSME',     false,  25000,  300000,  6, 18, 21, 65, 1.00, 16.00, 24.00, 'ABOVE_AMOUNT', 100000,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', 1, 15000, 499, 2, 300, 24, null, 'MONTHLY', null, false, null, null),
 ('PROF',  'Doctor & professional loan',          'MSME',     false, 200000, 5000000, 12, 60, 24, 65, 1.00, 11.50, 16.00, 'NEVER',        null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', 2, 75000, 2500, 3, 500, 24, null, 'MONTHLY', null, false, null, null),
 ('FRAN',  'Franchise finance',                   'MSME',     false, 500000, 5000000, 24, 84, 21, 65, 1.50, 13.00, 19.00, 'ALWAYS',       null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 1, 50000, 2500, 4, 500, 24, null, 'MONTHLY', null, false, null, null),
 ('INST',  'School & hospital finance',           'MSME',     false, 1000000, 5000000, 24, 120, 25, 70, 1.00, 12.00, 17.00, 'ALWAYS',      null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 3, 100000, 5000, 3, 500, 24, null, 'MONTHLY', null, false, null, null),
 ('RENT',  'Rental income-backed loan (LRD)',     'MSME',     true,  1000000, 5000000, 24, 120, 25, 75, 0.50, 10.50, 14.00, 'ALWAYS',      null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', null, 50000, 5000, 2, 500, 24, null, 'MONTHLY', 'SHOP', true, 'RENTAL', null),
 ('LAS',   'Loan against securities',             'SALARIED', true,    50000, 5000000, 12, 12, 21, 75, 0.25, 10.50, 13.50, 'NEVER',       null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', null, 15000, 499, 0, 300, 24, null, 'BULLET', null, false, null, 'SECURITIES'),
 ('FDL',   'Loan against fixed deposit',          'SALARIED', true,    10000, 5000000,  3, 60, 18, 80, 0.00,  9.00, 11.00, 'NEVER',       null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', null, 0, 0, 0, 300, 24, null, 'BULLET', null, false, null, 'DEPOSIT');

-- co-applicants and guarantors optional (the lender can require them by amount in Products)
update product set party_rules = jsonb_set((select party_rules from product where code = 'CV-SEC')::jsonb, '{guarantor,requiredWhen}', '""')::text,
                   vkyc_policy = (select vkyc_policy from product where code = 'BL-UNS')
 where code in ('MERCH', 'MEWC', 'PROF', 'FRAN', 'INST', 'RENT', 'LAS', 'FDL');

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
         'planKind', p.plan_kind, 'pledgeKind', p.pledge_kind)::text,
       'New product', 'system', 'system', now()
  from product p where p.code in ('MERCH', 'MEWC', 'PROF', 'FRAN', 'INST', 'RENT', 'LAS', 'FDL');

-- workflows: the standard business-loan flow for the configured products; a plan and the property for LRD; a pledge stage for LAS and FD loans
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at)
select p.code, 1, 'PUBLISHED', w.stages_json, 'Business-loan workflow', 'system', 'system', now()
  from product p, (select stages_json from workflow_version where product_code = 'BL-UNS' and status = 'PUBLISHED' order by version desc limit 1) w
 where p.code in ('MERCH', 'MEWC', 'PROF', 'FRAN', 'INST');

insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at)
select c.code, 1, 'PUBLISHED', c.stages, 'Initial workflow', 'system', 'system', now() from (values
 ('RENT', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "plan", "name": "Lease & rent", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Lease deed AI", "Escrow bank"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "col", "name": "Collateral AI review", "role": "CREDIT_OFFICER", "sla": 120, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Collateral Document AI", "Collateral Vision AI", "Valuation AI"]},
   {"key": "sanction", "name": "Sanction", "role": "CRO", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS", "Escrow"]}]'),
 ('LAS', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "pledge", "name": "Securities & lien", "role": "OPERATIONS", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["CAS AI", "Depository (CDSL / NSDL)", "Price feed"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]'),
 ('FDL', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "pledge", "name": "Deposit & lien", "role": "OPERATIONS", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["FD receipt AI", "Issuer lien confirmation"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "Decision engine"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]')
) as c(code, stages);

update policy_version set overrides_json = (coalesce(nullif(overrides_json, ''), '{}')::jsonb
  || '{"product:PROF": {"exposureCap": 5000000}, "product:FRAN": {"exposureCap": 5000000}, "product:INST": {"exposureCap": 5000000},
       "product:RENT": {"exposureCap": 5000000}, "product:LAS": {"exposureCap": 5000000}, "product:FDL": {"exposureCap": 5000000}}'::jsonb)::text
 where status = 'LIVE';
