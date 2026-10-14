-- Phase 4A: Collateral AI (FSD "Collateral AI and unified BRE"): collateral, documents, EC, media, valuations,
-- collateral policy, LTV, risk vector, rules CR-01..CR-11, review queue; LAP product; Legal reviewer and Valuer roles

alter table product add column collateral_types varchar(100);           -- RESIDENTIAL,SHOP,PLOT,VEHICLE,GOLD
alter table product add column collateral_required boolean not null default false;
alter table loan_application add column collateral_state varchar(15) not null default 'NOT_REQUIRED'; -- NOT_REQUIRED, PENDING, PASS, REFER, FAIL

update product set collateral_types = 'VEHICLE', collateral_required = true where code = 'CV-SEC';
update product set collateral_types = 'GOLD', collateral_required = true where code = 'GL';

insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs,
                     min_vintage_years, min_monthly_income, min_processing_fee, foreclosure_pct, bounce_charge, penal_rate_pct,
                     collateral_types, collateral_required) values
 ('LAP', 'Loan against property', 'MSME', true, 500000, 5000000, 36, 180, 25, 70, 1.00, 11.00, 18.00, 'ALWAYS', null,
  'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM', 3, 50000, 5000, 4, 500, 24, 'RESIDENTIAL,SHOP,PLOT', true);

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
         'scoringModel', p.scoring_model, 'collateralTypes', p.collateral_types, 'collateralRequired', p.collateral_required)::text,
       'Loan against property with Collateral AI', 'system', 'system', now()
  from product p where p.code = 'LAP';

insert into workflow_version (product_code, version, status, stages_json, notes, created_by, approved_by, approved_at) values ('LAP', 1, 'PUBLISHED',
 '[{"key": "lead", "name": "Lead & application", "role": "SALES", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Sales app"]},
   {"key": "kyc", "name": "Consent & KYC", "role": "OPERATIONS", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI", "KYC from documents"]},
   {"key": "docs", "name": "Documents", "role": "OPERATIONS", "sla": 8, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Document AI"]},
   {"key": "field", "name": "Field visit", "role": "FIELD_OFFICER", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Field app"]},
   {"key": "uw", "name": "Underwriting", "role": "CREDIT_OFFICER", "sla": 2, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Bureau", "AA / bank statement", "Decision engine"]},
   {"key": "cam", "name": "Credit memo", "role": "CREDIT_OFFICER", "sla": 4, "mode": "AUTO", "onFailure": "QUEUE", "skip": "", "integrations": ["Credit memo writer"]},
   {"key": "col", "name": "Collateral AI review", "role": "CREDIT_OFFICER", "sla": 120, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Collateral Document AI", "Collateral Vision AI", "Valuation AI"]},
   {"key": "sanction", "name": "Sanction", "role": "CRO", "sla": 8, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["KFS generator"]},
   {"key": "disb", "name": "Disbursement", "role": "OPERATIONS", "sla": 24, "mode": "MANUAL", "onFailure": "QUEUE", "skip": "", "integrations": ["Penny drop", "Payout", "LMS"]}]',
 'Initial LAP workflow', 'system', 'system', now());

-- loans against property are larger than the unsecured exposure cap
update policy_version set overrides_json = (coalesce(nullif(overrides_json, ''), '{}')::jsonb || '{"product:LAP": {"exposureCap": 5000000, "autoApproveLimit": 50000}}'::jsonb)::text
 where status = 'LIVE';

insert into app_user (username, full_name, password_hash, role, branch_id, email) values
 ('legal1',  'Advocate Neha Joshi', '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'LEGAL_REVIEWER', (select id from branch where code='HO'), 'legal1@example.com'),
 ('valuer1', 'Er. Prakash Deshmukh', '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'VALUER', (select id from branch where code='HO'), 'valuer1@example.com');

-- collateral policy per type (COL-BR-02..06, 09, 11); changes are audited
create table collateral_policy (
  type varchar(15) primary key,
  label varchar(60) not null,
  ec_years int not null default 0,
  title_depth_years int not null default 0,
  haircut numeric(5,4) not null,
  max_ltv numeric(5,4) not null,
  valuation_max_age_months int not null,
  second_valuation_above numeric(14,2),
  variance_limit numeric(5,4) not null default 0.15,
  min_images int not null default 0,
  gps_radius_m int not null default 250,
  coverage_min numeric(5,4) not null default 0.70,
  revalue_months int not null default 12,
  required_docs varchar(300) not null,                     -- mandatory
  optional_docs varchar(300),
  required_photos varchar(300),
  updated_by varchar(60) not null default 'system',
  updated_at timestamptz not null default now()
);
insert into collateral_policy values
 ('RESIDENTIAL', 'Residential house or flat', 13, 30, 0.10, 0.65, 6, 5000000, 0.15, 5, 250, 0.70, 12,
  'SALE_DEED,EC,KHATA,PROPERTY_TAX,VALUATION_REPORT', 'MOTHER_DEED,APPROVED_PLAN,INSURANCE', 'EXTERIOR_FRONT,EXTERIOR_SIDE,INTERIOR_HALL,INTERIOR_KITCHEN,STREET_VIEW', 'system', now()),
 ('SHOP', 'Shop or commercial unit', 13, 30, 0.15, 0.55, 6, 5000000, 0.15, 5, 250, 0.70, 12,
  'SALE_DEED,EC,KHATA,PROPERTY_TAX,VALUATION_REPORT', 'MOTHER_DEED,APPROVED_PLAN,OC,INSURANCE', 'EXTERIOR_FRONT,SIGNAGE,INTERIOR_HALL,STREET_VIEW,NEIGHBOURHOOD', 'system', now()),
 ('PLOT', 'Commercial plot', 30, 30, 0.20, 0.50, 6, 5000000, 0.15, 5, 250, 0.70, 12,
  'SALE_DEED,EC,LAND_RECORD,CONVERSION_ORDER,PROPERTY_TAX,VALUATION_REPORT', 'MOTHER_DEED', 'PLOT_FRONT,PLOT_SIDE,BOUNDARY,ACCESS_ROAD,NEIGHBOURHOOD', 'system', now()),
 ('VEHICLE', 'Used commercial vehicle', 0, 0, 0.00, 0.75, 3, null, 0.15, 5, 2000, 0.70, 12,
  'RC,INSURANCE,VALUATION_REPORT', 'INVOICE,PERMIT,FITNESS', 'FRONT,REAR,SIDE,ODOMETER,CHASSIS_PLATE', 'system', now()),
 ('GOLD', 'Gold jewellery', 0, 0, 0.00, 0.75, 1, null, 0.15, 0, 0, 0.00, 6,
  'APPRAISAL', null, null, 'system', now());

-- illustrative locality rates for the AI value cross-check (₹ per sq ft, by PIN prefix and type); the lender loads its own
create table locality_rate (
  id bigserial primary key,
  pin_prefix varchar(6) not null,
  locality varchar(80) not null,
  type varchar(15) not null,
  rate_per_sqft numeric(10,2) not null,
  as_of date not null,
  source varchar(60) not null default 'ILLUSTRATIVE'
);
insert into locality_rate (pin_prefix, locality, type, rate_per_sqft, as_of) values
 ('422', 'Nashik', 'RESIDENTIAL', 5200, '2026-09-01'), ('422', 'Nashik', 'SHOP', 9500, '2026-09-01'), ('422', 'Nashik', 'PLOT', 2600, '2026-09-01'),
 ('411', 'Pune', 'RESIDENTIAL', 8200, '2026-09-01'), ('411', 'Pune', 'SHOP', 15000, '2026-09-01'), ('411', 'Pune', 'PLOT', 4800, '2026-09-01'),
 ('400', 'Mumbai', 'RESIDENTIAL', 21000, '2026-09-01'), ('400', 'Mumbai', 'SHOP', 38000, '2026-09-01'),
 ('208', 'Kanpur', 'RESIDENTIAL', 4300, '2026-09-01'), ('208', 'Kanpur', 'SHOP', 7800, '2026-09-01'), ('208', 'Kanpur', 'PLOT', 2100, '2026-09-01'),
 ('560', 'Bengaluru', 'RESIDENTIAL', 9800, '2026-09-01'), ('560', 'Bengaluru', 'SHOP', 17000, '2026-09-01'), ('560', 'Bengaluru', 'PLOT', 6200, '2026-09-01');

-- gold rate for 22 carat, ₹ per gram (the 30-day average of these is used); the lender enters the daily rate
create table gold_rate (
  id bigserial primary key,
  rate_date date not null unique,
  rate_22k numeric(10,2) not null,
  source varchar(60) not null default 'ILLUSTRATIVE',
  entered_by varchar(60) not null default 'system'
);
insert into gold_rate (rate_date, rate_22k) values (current_date - 3, 11250), (current_date - 2, 11310), (current_date - 1, 11280);

create table collateral (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  col_no varchar(20) unique,
  type varchar(15) not null,
  description varchar(200),
  owner_name varchar(120),
  address varchar(300),
  pincode varchar(6),
  lat double precision,
  lng double precision,
  survey_no varchar(60),
  municipal_id varchar(60),
  area_sqft numeric(12,2),
  construction_stage varchar(20),       -- COMPLETE, UNDER_CONSTRUCTION, VACANT_LAND
  occupancy varchar(20),                -- SELF, RENTED, VACANT
  condition varchar(20),                -- GOOD, AVERAGE, POOR
  registration_no varchar(20),          -- vehicle
  make_model varchar(80),
  manufacture_year int,
  new_price numeric(14,2),
  insurer varchar(80),
  insurance_policy_no varchar(40),
  insurance_cover numeric(14,2),
  insurance_expiry date,
  insurance_assigned boolean not null default false,
  status varchar(10) not null default 'PENDING',          -- PENDING, PASS, REFER, FAIL
  risk_score int,
  max_exposure numeric(14,2),
  ltv_cap boolean not null default false,
  last_evaluated_at timestamptz,
  created_by varchar(60) not null,
  created_at timestamptz not null default now()
);
create index ix_collateral_app on collateral(application_id);

create table collateral_document (
  id bigserial primary key,
  collateral_id bigint not null references collateral(id),
  doc_type varchar(25) not null,
  doc_number varchar(60),
  issuer varchar(120),
  doc_date date,
  pages int,
  file_name varchar(200),
  storage_path varchar(400),
  file_hash varchar(64),
  version int not null default 1,
  extracted_json text,                  -- fields (and EC transactions)
  read_confidence numeric(4,3),
  engine varchar(30),
  confirmed_by varchar(60),
  confirmed_at timestamptz,
  manipulated boolean not null default false,
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);
create index ix_coldoc_col on collateral_document(collateral_id);

create table collateral_media (
  id bigserial primary key,
  collateral_id bigint not null references collateral(id),
  label varchar(30) not null,
  file_name varchar(200),
  storage_path varchar(400),
  sha256 varchar(64) not null,
  dhash varchar(16),
  lat double precision,
  lng double precision,
  distance_m double precision,
  captured_at timestamptz,
  quality varchar(10),                  -- GOOD, BLURRY, DARK, GLARE, UNKNOWN
  analysis_json text,
  reuse_match varchar(60),
  tamper_score numeric(4,3),
  manipulated boolean not null default false,
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);
create index ix_colmedia_col on collateral_media(collateral_id);
create index ix_colmedia_dhash on collateral_media(dhash);

create table collateral_valuation (
  id bigserial primary key,
  collateral_id bigint not null references collateral(id),
  source varchar(15) not null,          -- EMPANELLED, APPRAISAL (gold), AI (cross-check only)
  valuer varchar(120),
  report_date date not null,
  market_value numeric(14,2) not null,
  realisable_value numeric(14,2),
  distress_value numeric(14,2),
  method varchar(60),
  confidence numeric(4,3),
  details_json text,
  approved boolean not null default true,
  recorded_by varchar(60) not null,
  recorded_at timestamptz not null default now()
);
create index ix_colval_col on collateral_valuation(collateral_id);

create table collateral_evaluation (
  id bigserial primary key,
  collateral_id bigint not null references collateral(id),
  status varchar(10) not null,
  risk_score int not null,
  risk_json text not null,
  rules_json text not null,
  ltv_json text not null,
  ec_json text,
  title_json text,
  vision_json text,
  valuation_json text,
  summary text,
  policy_version varchar(40),
  model_version varchar(40) not null,
  evaluated_by varchar(60) not null,
  evaluated_at timestamptz not null default now()
);
create index ix_coleval_col on collateral_evaluation(collateral_id);

create table collateral_review (
  id bigserial primary key,
  collateral_id bigint not null references collateral(id),
  rule_id varchar(6) not null,
  type varchar(12) not null,            -- LEGAL, VALUATION, FIELD, CREDIT
  role varchar(30) not null,
  reason varchar(500) not null,
  sla_hours int not null,
  due_at timestamptz not null,
  status varchar(10) not null default 'OPEN',   -- OPEN, CLEARED, UPHELD, SUPERSEDED
  reviewer varchar(60),
  note varchar(1000),
  created_at timestamptz not null default now(),
  closed_at timestamptz
);
create index ix_colreview_col on collateral_review(collateral_id);
create index ix_colreview_status on collateral_review(status);
