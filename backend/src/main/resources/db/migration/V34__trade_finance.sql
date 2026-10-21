-- Trade finance: anchors (vendor and dealer programmes) and rated buyers, revolving limits with buyer sublimits,
-- invoices drawn against the limit (verified, confirmed, funded, repaid), and three products:
-- invoice & receivables finance (INV-FIN), supply chain / vendor finance (SCF), dealer & distributor finance (DDF).

create table trade_counterparty (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(160) not null,
  kind varchar(10) not null,                        -- ANCHOR (runs a programme) or BUYER (a debtor whose invoices are financed)
  programme varchar(10),                            -- VENDOR (the anchor's suppliers) or DEALER (the anchor's dealers); anchors only
  gstin varchar(15) not null,
  pan varchar(10),
  city varchar(80),
  industry varchar(80),
  rating varchar(10),                               -- external rating or the lender's internal grade
  programme_limit numeric(16,2),                    -- anchors: all limits under the programme; buyers: all financed invoices on the buyer
  max_tenor_days int not null default 90,
  advance_rate numeric(5,4) not null default 0.80,  -- share of the invoice funded
  grace_days int not null default 7,
  recourse varchar(10) not null default 'WITH',     -- WITH (borrower repays if the buyer does not) or WITHOUT
  fldg_pct numeric(5,4),                            -- anchor's first-loss default guarantee
  confirmation_required boolean not null default true,
  concentration_cap numeric(5,4) not null default 0.60, -- max share of one borrower's limit on this buyer
  repaid_by varchar(10) not null default 'BUYER',   -- who pays on the due date: BUYER, ANCHOR or BORROWER
  account_name varchar(160),
  account_no varchar(30),
  ifsc varchar(11),
  empanelled boolean not null default true,
  status varchar(15) not null default 'ACTIVE',     -- ACTIVE, SUSPENDED
  notes varchar(500),
  created_by varchar(60) not null default 'system',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table trade_limit (
  id bigserial primary key,
  limit_no varchar(20) unique,
  application_id bigint not null unique references loan_application(id),
  product_code varchar(20) not null,
  anchor_id bigint references trade_counterparty(id),
  borrower_gstin varchar(15),
  sanctioned_limit numeric(16,2),
  status varchar(12) not null default 'PROPOSED',   -- PROPOSED, ACTIVE, FROZEN, EXPIRED, CLOSED
  valid_from date,
  valid_to date,
  rate_pa numeric(6,3),
  structure_ok boolean not null default false,
  checks_json text,
  frozen_reason varchar(300),
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table trade_sublimit (
  id bigserial primary key,
  limit_id bigint not null references trade_limit(id),
  counterparty_id bigint not null references trade_counterparty(id),
  amount numeric(16,2) not null,
  unique (limit_id, counterparty_id)
);

create table trade_invoice (
  id bigserial primary key,
  ref_no varchar(20) unique,
  limit_id bigint not null references trade_limit(id),
  counterparty_id bigint references trade_counterparty(id),
  invoice_no varchar(60),
  invoice_date date,
  due_date date,
  po_no varchar(60),
  seller_gstin varchar(15),
  buyer_gstin varchar(15),
  base_amount numeric(16,2),
  gst_amount numeric(16,2),
  total numeric(16,2),
  advance_amount numeric(16,2),
  file_name varchar(200),
  storage_path varchar(400),
  file_hash varchar(64),
  fields_json text,
  read_confidence numeric(5,3),
  engine varchar(30),
  status varchar(15) not null default 'SUBMITTED',  -- SUBMITTED, CONFIRMED, FUNDED, PART_PAID, REPAID, REJECTED
  checks_json text,
  rulings_json text,
  confirmation_ref varchar(120),
  confirmed_by varchar(60),
  confirmed_at timestamptz,
  funded_amount numeric(16,2),
  interest_amount numeric(16,2),
  funded_by varchar(60),
  funded_at timestamptz,
  utr varchar(40),
  paid_to varchar(160),
  repaid_amount numeric(16,2) not null default 0,
  repaid_at timestamptz,
  repayment_ref varchar(120),
  rejected_reason varchar(300),
  submitted_by varchar(60) not null,
  submitted_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index trade_invoice_dup on trade_invoice (seller_gstin, invoice_no);
create index trade_invoice_limit on trade_invoice (limit_id);

-- fictional anchors and buyers for the demo (the lender loads its own)
insert into trade_counterparty (code, name, kind, programme, gstin, pan, city, industry, rating, programme_limit, max_tenor_days, advance_rate, grace_days,
                                recourse, fldg_pct, confirmation_required, concentration_cap, repaid_by, account_name, account_no, ifsc, empanelled, status, notes) values
 ('ANC-GAF', 'Godavari Agro Foods Ltd',       'ANCHOR', 'VENDOR', '27AAGCG5521H1Z6', 'AAGCG5521H', 'Nashik', 'Food processing', 'A',   200000000, 90, 0.90, 5,
  'WITH',    null, true,  1.00, 'ANCHOR',   'Godavari Agro Foods Ltd', '918020011223344', 'UTIB0000123', true, 'ACTIVE', 'Vendor programme: the anchor accepts its suppliers'' invoices and pays the lender on the due date'),
 ('ANC-SPL', 'Sahyadri Paints Ltd',           'ANCHOR', 'DEALER', '27AABCS8890M1Z2', 'AABCS8890M', 'Pune',   'Paints',          'A+',  150000000, 60, 1.00, 7,
  'WITH',    0.05, true,  1.00, 'BORROWER', 'Sahyadri Paints Ltd',     '50200088990011',  'HDFC0001234', true, 'ACTIVE', 'Dealer programme: the lender pays the anchor for the dealer''s purchases; the dealer repays; 5% first-loss guarantee; stop-supply on overdue'),
 ('BYR-WAC', 'Western Auto Components Ltd',   'BUYER',  null,     '27AAWCW7731B1Z8', 'AAWCW7731B', 'Pune',   'Auto components', 'A',   50000000,  90, 0.80, 7,
  'WITH',    null, true,  0.70, 'BUYER',    null, null, null, true, 'ACTIVE', 'Rated buyer; confirms invoices on its vendor portal'),
 ('BYR-DRM', 'Deccan Retail Mart Pvt Ltd',    'BUYER',  null,     '27AADCD6610Q1Z4', 'AADCD6610Q', 'Nashik', 'Retail',          'BBB', 20000000,  60, 0.75, 7,
  'WITH',    null, false, 0.40, 'BUYER',    null, null, null, true, 'ACTIVE', 'Buyer confirmation not needed below the advance rate'),
 ('BYR-NEW', 'Nashik Engineering Works',      'BUYER',  null,     '27AAFFN4471C1Z9', 'AAFFN4471C', 'Nashik', 'Engineering',     null,  5000000,   60, 0.70, 7,
  'WITH',    null, true,  0.30, 'BUYER',    null, null, null, false,'ACTIVE', 'Not yet empanelled: invoices refer to credit'),
 ('BYR-XSP', 'Quickline Traders',             'BUYER',  null,     '27AAQFQ2200E1Z5', 'AAQFQ2200E', 'Nashik', 'Trading',         null,  null,      60, 0.70, 7,
  'WITH',    null, true,  0.30, 'BUYER',    null, null, null, false,'SUSPENDED', 'Suspended after a disputed invoice');

-- the three products: revolving limits for 12 months
insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs,
                     min_vintage_years, min_monthly_income, min_processing_fee, foreclosure_pct, bounce_charge, penal_rate_pct,
                     auto_sanction_limit, repayment_frequency) values
 ('INV-FIN', 'Invoice & receivables finance',    'MSME', false, 200000, 20000000, 12, 12, 21, 70, 0.50, 11.00, 17.00, 'ABOVE_AMOUNT', 5000000,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 2, 50000, 2500, 0, 500, 24, null, 'REVOLVING'),
 ('SCF',     'Supply chain finance (vendor)',    'MSME', false, 100000, 20000000, 12, 12, 21, 70, 0.25, 10.00, 15.00, 'NEVER',        null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 1, 30000, 1500, 0, 500, 24, null, 'REVOLVING'),
 ('DDF',     'Dealer & distributor finance',     'MSME', false, 200000, 20000000, 12, 12, 21, 70, 0.50, 11.00, 16.00, 'NEVER',        null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 2, 40000, 2500, 0, 500, 24, null, 'REVOLVING');

alter table product add column trade_kind varchar(15);   -- RECEIVABLES, VENDOR, DEALER
update product set trade_kind = 'RECEIVABLES' where code = 'INV-FIN';
update product set trade_kind = 'VENDOR' where code = 'SCF';
update product set trade_kind = 'DEALER' where code = 'DDF';

-- parties: optional; the receivables are the primary source of repayment
update product set party_rules = jsonb_set((select party_rules from product where code = 'CV-SEC')::jsonb, '{guarantor,requiredWhen}', '""')::text,
                   vkyc_policy = (select vkyc_policy from product where code = 'BL-UNS')
 where code in ('INV-FIN', 'SCF', 'DDF');
update product set party_rules = jsonb_set(party_rules::jsonb, '{note}', '"Receivables repay the limit; a promoter guarantee is optional"')::text
 where code in ('INV-FIN', 'SCF', 'DDF');

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
         'tradeKind', p.trade_kind)::text,
       'Trade finance product', 'system', 'system', now()
  from product p where p.code in ('INV-FIN', 'SCF', 'DDF');

-- workflows: the programme and limit structure is set before underwriting; "disbursement" activates the limit
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at)
select c.code, 1, 'PUBLISHED', c.stages, 'Initial trade-finance workflow', 'system', 'system', now() from (values
 ('INV-FIN', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "GST returns"]},
   {"key": "limit", "name": "Programme & limit", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Buyer master", "GST e-invoice"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Limit activation", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Trade finance"]}]'),
 ('SCF', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Anchor referral"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "limit", "name": "Programme & limit", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Anchor programme", "Anchor ERP"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Limit activation", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Trade finance"]}]'),
 ('DDF', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Anchor referral"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "limit", "name": "Programme & limit", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Anchor programme", "Anchor ERP"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Limit activation", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Trade finance"]}]')
) as c(code, stages);

update policy_version set overrides_json = (coalesce(nullif(overrides_json, ''), '{}')::jsonb
  || '{"product:INV-FIN": {"exposureCap": 20000000}, "product:SCF": {"exposureCap": 20000000}, "product:DDF": {"exposureCap": 20000000}}'::jsonb)::text
 where status = 'LIVE';
