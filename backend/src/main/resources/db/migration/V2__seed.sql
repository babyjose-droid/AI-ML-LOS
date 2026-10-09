-- Reference data for a demo tenant. Password for every seeded user: Rhythm@123

insert into branch (code, name, level, parent_id, city) values
 ('HO',   'Head Office',        'HEAD_OFFICE', null, 'Mumbai');
insert into branch (code, name, level, parent_id, city) values
 ('ZW',   'West Zone',          'ZONE',   (select id from branch where code='HO'), 'Mumbai');
insert into branch (code, name, level, parent_id, city) values
 ('RPN',  'Pune Region',        'REGION', (select id from branch where code='ZW'), 'Pune');
insert into branch (code, name, level, parent_id, city) values
 ('BNSK', 'Nashik Branch',      'BRANCH', (select id from branch where code='RPN'), 'Nashik'),
 ('BPUN', 'Pune City Branch',   'BRANCH', (select id from branch where code='RPN'), 'Pune');

insert into app_user (username, full_name, password_hash, role, branch_id, email) values
 ('admin',  'Tenant Admin',          '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'ADMIN',           (select id from branch where code='HO'),   'admin@example.com'),
 ('sales1', 'Sameer Kulkarni',       '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'SALES',           (select id from branch where code='BNSK'), 'sales1@example.com'),
 ('ops1',   'Neha Joshi',            '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'OPERATIONS',      (select id from branch where code='BNSK'), 'ops1@example.com'),
 ('co1',    'Rahul Deshmukh',        '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'CREDIT_OFFICER',  (select id from branch where code='BNSK'), 'co1@example.com'),
 ('cm1',    'Anita Rao',             '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'CREDIT_MANAGER',  (select id from branch where code='RPN'),  'cm1@example.com'),
 ('cro1',   'Vikram Mehta',          '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'CRO',             (select id from branch where code='HO'),   'cro1@example.com'),
 ('fraud1', 'Imran Shaikh',          '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'FRAUD_ANALYST',   (select id from branch where code='HO'),   'fraud1@example.com'),
 ('comp1',  'Kavita Iyer',           '$2a$10$KYGKeMCCNcUYNj6YH3w4We9r6GWbZx8EWOvmUmvRaRJdgvVlYcYo6', 'COMPLIANCE',      (select id from branch where code='HO'),   'comp1@example.com');

insert into product (code, name, segment, secured, min_amount, max_amount, min_tenure, max_tenure, min_age, max_age,
                     processing_fee_pct, rate_min, rate_max, field_visit_rule, field_visit_threshold, required_docs) values
 ('BL-UNS', 'Business loan (unsecured)', 'MSME',         false, 50000,  1000000, 6, 36, 21, 65, 2.00, 13.00, 26.00, 'ABOVE_AMOUNT', 500000, 'PAN,AADHAAR,UDYAM'),
 ('PL',     'Personal loan',             'SALARIED',     false, 50000,  500000,  6, 48, 21, 58, 2.00, 13.00, 24.00, 'NEVER',        null,   'PAN,AADHAAR,SALARY_SLIP'),
 ('JLG',    'JLG income-generation loan','MICROFINANCE', false, 10000,  100000,  6, 24, 18, 60, 1.00, 13.00, 24.00, 'ALWAYS',       null,   'AADHAAR');

insert into policy_version (version, status, params_json, notes, created_by) values
 ('POL-2026.10-v4', 'LIVE',
  '{"version":"POL-2026.10-v4","minAge":21,"maxAge":65,"kycFail":0.60,"kycReview":0.80,"fraudReview":35,"fraudBlock":70,"maxFoir":0.50,"safety":0.70,"approvePd":0.07,"referPd":0.15,"maxEnq6":6,"dpdReject":90,"minBankMonths":6,"baseRate":13.0,"opexRate":3.0,"maxRate":26.0,"lgdUnsecured":0.65,"lgdSecured":0.35,"exposureCap":1500000,"autoApproveLimit":500000}',
  'Initial board-approved credit policy', 'system');
