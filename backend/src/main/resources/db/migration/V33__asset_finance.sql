-- Asset finance: dealers and suppliers, asset classes and their rules, the asset financed on each application,
-- its quotation / invoice / receipt documents, and six asset-finance products (commercial vehicle, equipment,
-- new vehicle, solar, medical equipment, consumer durable).

create table dealer (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(160) not null,
  kind varchar(20) not null,                       -- DEALER, SUPPLIER, OEM, INSTALLER
  categories varchar(200) not null,                -- asset classes it may sell: CV, CAR, EQUIPMENT, SOLAR, MEDICAL, DURABLE
  oem varchar(80),
  gstin varchar(15),
  pan varchar(10),
  city varchar(80),
  pincode varchar(6),
  account_name varchar(160),
  account_no varchar(30),
  ifsc varchar(11),
  empanelled boolean not null default true,
  status varchar(15) not null default 'ACTIVE',   -- ACTIVE, SUSPENDED, BLACKLISTED
  max_exposure numeric(14,2),
  notes varchar(500),
  created_by varchar(60) not null default 'system',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table asset_policy (
  asset_class varchar(20) primary key,
  label varchar(80) not null,
  max_ltv numeric(5,4) not null,                   -- of the asset cost net of subsidy
  min_down_pct numeric(5,4) not null,
  dep_rate numeric(5,4) not null,                  -- yearly written-down-value depreciation
  max_age_years int not null default 1,            -- a new asset: invoice year at most this old
  insurance_required boolean not null default true,
  registration_required boolean not null default false,
  serial_label varchar(40) not null,
  quote_variance numeric(5,4) not null default 0.05,
  fleet_limit numeric(14,2),                       -- total asset-finance exposure on one borrower
  post_docs varchar(200) not null,                 -- documents due after disbursement
  post_days int not null default 30,
  updated_by varchar(60) not null default 'system',
  updated_at timestamptz not null default now()
);

insert into asset_policy (asset_class, label, max_ltv, min_down_pct, dep_rate, max_age_years, insurance_required, registration_required, serial_label, quote_variance, fleet_limit, post_docs, post_days) values
 ('CV',        'Commercial vehicle (new)',            0.85, 0.15, 0.15, 1, true,  true,  'Chassis number',         0.05, 30000000, 'RC_HP,INSURANCE_HP',          30),
 ('CAR',       'Car or passenger vehicle (new)',      0.85, 0.15, 0.15, 1, true,  true,  'Chassis number',         0.05, null,     'RC_HP,INSURANCE_HP',          30),
 ('EQUIPMENT', 'Construction or industrial equipment',0.80, 0.20, 0.20, 1, true,  false, 'Machine serial number',  0.05, 20000000, 'INSTALLATION_CERT,INSURANCE_HP', 30),
 ('MEDICAL',   'Medical equipment',                   0.85, 0.15, 0.15, 1, true,  false, 'Equipment serial number',0.05, 20000000, 'INSTALLATION_CERT,INSURANCE_HP', 30),
 ('SOLAR',     'Rooftop solar system',                0.90, 0.10, 0.10, 1, false, false, 'Inverter serial number', 0.05, null,     'INSTALLATION_CERT,NET_METER',  45),
 ('DURABLE',   'Consumer durable',                    0.90, 0.10, 0.30, 1, false, false, 'Product serial number',  0.05, null,     'DELIVERY_NOTE',                7);

-- fictional dealers for the demo (the lender loads its own empanelled list)
insert into dealer (code, name, kind, categories, oem, gstin, pan, city, pincode, account_name, account_no, ifsc, empanelled, status, max_exposure) values
 ('DLR-CV01',  'Sahyadri Commercial Motors',   'DEALER',    'CV',        'Tata Motors',  '27AAKCS4512M1Z5', 'AAKCS4512M', 'Nashik', '422010', 'Sahyadri Commercial Motors', '920020045671234', 'UTIB0000123', true,  'ACTIVE', 50000000),
 ('DLR-CAR01', 'Nashik Auto Hub',              'DEALER',    'CAR',       'Maruti Suzuki','27AAGFN7781K1Z2', 'AAGFN7781K', 'Nashik', '422005', 'Nashik Auto Hub',            '50200077881234',  'HDFC0001234', true,  'ACTIVE', 30000000),
 ('DLR-EQ01',  'Deccan Earthmovers',           'DEALER',    'EQUIPMENT', 'JCB India',    '27AACCD3344F1Z9', 'AACCD3344F', 'Nashik', '422007', 'Deccan Earthmovers Pvt Ltd', '0345012000456',   'ICIC0000345', true,  'ACTIVE', 40000000),
 ('SUP-SOL01', 'SunPeak Solar Installers',     'INSTALLER', 'SOLAR',     null,           '27AAPFS9921L1Z4', 'AAPFS9921L', 'Pune',   '411045', 'SunPeak Solar Installers',   '777705559012',    'KKBK0000721', true,  'ACTIVE', 10000000),
 ('SUP-MED01', 'MedEquip India',               'SUPPLIER',  'MEDICAL',   'Siemens',      '27AAECM6612P1Z7', 'AAECM6612P', 'Mumbai', '400072', 'MedEquip India Pvt Ltd',     '912020033445566', 'UTIB0000456', true,  'ACTIVE', 30000000),
 ('DLR-CD01',  'Kulkarni Electronics',         'DEALER',    'DURABLE',   null,           '27ABEPK2231R1Z3', 'ABEPK2231R', 'Nashik', '422002', 'Kulkarni Electronics',       '50100012345678',  'HDFC0001234', true,  'ACTIVE', 5000000),
 ('DLR-X01',   'QuickDeal Traders',            'DEALER',    'CV,CAR,DURABLE', null,      '27AAQFQ1100D1Z1', 'AAQFQ1100D', 'Nashik', '422001', 'QuickDeal Traders',          '112233445566',    'SBIN0000300', false, 'SUSPENDED', null);

create table asset (
  id bigserial primary key,
  application_id bigint not null unique references loan_application(id),
  asset_no varchar(20),
  asset_class varchar(20) not null references asset_policy(asset_class),
  dealer_id bigint references dealer(id),
  make varchar(80),
  model varchar(120),
  variant varchar(80),
  manufacture_year int,
  units int not null default 1,
  serial_no varchar(40),
  engine_no varchar(40),
  registration_no varchar(20),
  quote_no varchar(40),
  quote_date date,
  quote_amount numeric(14,2),
  invoice_no varchar(40),
  invoice_date date,
  base_price numeric(14,2),
  gst_amount numeric(14,2),
  other_charges numeric(14,2),
  invoice_total numeric(14,2),
  invoice_gstin varchar(15),
  down_payment numeric(14,2),
  down_payment_ref varchar(60),
  down_payment_verified_by varchar(60),
  down_payment_verified_at timestamptz,
  insurer varchar(80),
  insurance_policy_no varchar(60),
  insured_value numeric(14,2),
  insurance_expiry date,
  insurance_hypothecated boolean not null default false,
  subsidy_amount numeric(14,2),
  status varchar(10) not null default 'PENDING',  -- PENDING, PASS, REFER, FAIL
  evaluation_json text,
  rulings_json text,                                -- exceptions approved by credit: {"AF-08": {"by", "note", "at"}}
  max_loan numeric(14,2),
  lien_state varchar(20) not null default 'NONE',   -- NONE, PENDING, MARKED, RELEASED
  post_docs_json text,                              -- [{"type", "dueBy", "receivedAt", "by", "docId"}]
  disbursed_to varchar(160),
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_asset_dealer on asset(dealer_id);
create index ix_asset_serial on asset(serial_no);

create table asset_document (
  id bigserial primary key,
  asset_id bigint not null references asset(id),
  doc_type varchar(30) not null,                    -- QUOTATION, INVOICE, DOWN_PAYMENT_RECEIPT, INSURANCE, RC_HP, INSURANCE_HP, INSTALLATION_CERT, NET_METER, DELIVERY_NOTE
  file_name varchar(200) not null,
  storage_path varchar(400) not null,
  file_hash varchar(64) not null,
  fields_json text,
  read_confidence numeric(5,3),
  engine varchar(40),
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);
create index ix_asset_doc on asset_document(asset_id);

alter table product add column asset_classes varchar(100);

-- the six asset-finance products
insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs,
                     min_vintage_years, min_monthly_income, min_processing_fee, foreclosure_pct, bounce_charge, penal_rate_pct,
                     auto_sanction_limit, asset_classes) values
 ('CV-NEW',  'Commercial vehicle finance',      'MSME',     true,  300000, 5000000, 12, 60, 21, 65, 1.50, 11.00, 18.00, 'ALWAYS',       null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 2, 40000, 2500, 4, 500, 24, null, 'CV'),
 ('EQUIP',   'Equipment & machinery finance',   'MSME',     true,  200000, 5000000, 12, 60, 21, 65, 1.50, 12.00, 19.00, 'ALWAYS',       null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 2, 40000, 2500, 4, 500, 24, null, 'EQUIPMENT'),
 ('CAR-NEW', 'New vehicle loan',                'SALARIED', true,  100000, 2500000, 12, 84, 21, 65, 1.00,  9.00, 15.00, 'NEVER',        null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,SALARY_SLIP', null, 25000, 1500, 3, 500, 24, null, 'CAR'),
 ('SOLAR',   'Solar & renewable energy finance','SALARIED', true,   50000, 1000000, 12, 60, 21, 65, 1.00, 10.00, 16.00, 'NEVER',        null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', null, 20000, 1000, 3, 500, 24, null, 'SOLAR'),
 ('MED-EQ',  'Medical equipment finance',       'MSME',     true,  200000, 5000000, 12, 60, 25, 65, 1.00, 11.00, 17.00, 'ABOVE_AMOUNT', 2500000,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', 1, 50000, 2500, 3, 500, 24, null, 'MEDICAL'),
 ('CD',      'Consumer durable loan',           'SALARIED', false,   5000,  200000,  3, 24, 21, 60, 1.00, 12.00, 24.00, 'NEVER',        null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT', null, 15000, 199, 2, 300, 24, 100000, 'DURABLE');

update product set party_rules = (select party_rules from product where code = 'BL-UNS'),
                   vkyc_policy = (select vkyc_policy from product where code = 'PL')
 where code in ('CV-NEW', 'EQUIP', 'CAR-NEW', 'SOLAR', 'MED-EQ', 'CD');

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
         'assetClasses', p.asset_classes)::text,
       'Asset finance product', 'system', 'system', now()
  from product p where p.code in ('CV-NEW', 'EQUIP', 'CAR-NEW', 'SOLAR', 'MED-EQ', 'CD');

-- workflows: the asset and dealer check comes before underwriting
insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at)
select c.code, 1, 'PUBLISHED', c.stages, 'Initial asset-finance workflow', 'system', 'system', now() from (values
 ('CV-NEW', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app", "Dealer portal"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "asset", "name": "Asset & dealer check", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Invoice AI", "Dealer master", "Vahan"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout to dealer", "LMS"]}]'),
 ('EQUIP', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app", "Dealer portal"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "asset", "name": "Asset & dealer check", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Invoice AI", "Dealer master"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout to dealer", "LMS"]}]'),
 ('CAR-NEW', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app", "Dealer portal"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "asset", "name": "Asset & dealer check", "role": "OPERATIONS", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Invoice AI", "Dealer master", "Vahan"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout to dealer", "LMS"]}]'),
 ('SOLAR', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app", "Installer portal"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "asset", "name": "Asset & dealer check", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Quotation AI", "Installer master", "Subsidy portal"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout to installer", "LMS"]}]'),
 ('MED-EQ', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app", "Supplier portal"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "asset", "name": "Asset & dealer check", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Invoice AI", "Supplier master"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout to supplier", "LMS"]}]'),
 ('CD', '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 4, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Point-of-sale app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 1, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 1, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "asset", "name": "Asset & dealer check", "role": "OPERATIONS", "sla": 2, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Invoice AI", "Dealer master"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 1, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "Decision engine"]},
   {"key": "sanction", "name": "Sanction", "role": "CREDIT_MANAGER", "sla": 4, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 4, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout to dealer", "LMS"]}]')
) as c(code, stages);

-- asset-finance loans are larger than the unsecured exposure cap
update policy_version set overrides_json = (coalesce(nullif(overrides_json, ''), '{}')::jsonb
  || '{"product:CV-NEW": {"exposureCap": 5000000}, "product:EQUIP": {"exposureCap": 5000000}, "product:MED-EQ": {"exposureCap": 5000000}, "product:CAR-NEW": {"exposureCap": 2500000}}'::jsonb)::text
 where status = 'LIVE';
