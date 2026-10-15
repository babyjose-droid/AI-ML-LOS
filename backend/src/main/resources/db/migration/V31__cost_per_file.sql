-- What a loan file costs: each vendor call (charged per successful call) and each workflow stage a person completes
-- (standard handling time at the role's cost). The lender edits the rates; cost per disbursed file uses them.
create table cost_rate (
  item varchar(40) primary key,          -- an integration operation (kyc.verify) or stage:<key>
  label varchar(120) not null,
  kind varchar(10) not null,             -- VENDOR, STAFF
  unit_cost numeric(10,2) not null,
  updated_by varchar(60) not null default 'system',
  updated_at timestamptz not null default now()
);
insert into cost_rate (item, label, kind, unit_cost) values
 ('kyc.verify', 'KYC check (PAN, identity)', 'VENDOR', 15),
 ('bureau.pull', 'Credit bureau report', 'VENDOR', 35),
 ('aa.fetch', 'Account Aggregator bank data', 'VENDOR', 25),
 ('gst.profile', 'GST profile', 'VENDOR', 10),
 ('document.read', 'Document AI read', 'VENDOR', 3),
 ('statement.analyze', 'Bank statement analysis', 'VENDOR', 12),
 ('field.photos', 'Field photo checks', 'VENDOR', 2),
 ('vkyc.analyze', 'Video KYC analysis', 'VENDOR', 8),
 ('payout.transfer', 'Disbursement transfer', 'VENDOR', 5),
 ('party.kyc.verify', 'Co-applicant/guarantor KYC', 'VENDOR', 15),
 ('party.bureau.pull', 'Co-applicant/guarantor bureau', 'VENDOR', 35),
 ('party.aa.fetch', 'Co-applicant bank data', 'VENDOR', 25),
 ('party.document.read', 'Co-applicant/guarantor document read', 'VENDOR', 3),
 ('party.statement.analyze', 'Co-applicant statement analysis', 'VENDOR', 12),
 ('stage:lead', 'Lead and application (sales, 30 min)', 'STAFF', 125),
 ('stage:kyc', 'Consent and KYC (operations, 10 min)', 'STAFF', 50),
 ('stage:docs', 'Documents (operations, 15 min)', 'STAFF', 75),
 ('stage:field', 'Field visit (field officer, 90 min with travel)', 'STAFF', 375),
 ('stage:uw', 'Underwriting (credit officer, 20 min)', 'STAFF', 135),
 ('stage:cam', 'Credit memo (credit officer, 20 min)', 'STAFF', 135),
 ('stage:sanction', 'Sanction (credit manager, 15 min)', 'STAFF', 150),
 ('stage:disb', 'Disbursement (operations, 15 min)', 'STAFF', 75),
 ('stage:col', 'Collateral review', 'STAFF', 400),
 ('stage:legal', 'Legal and technical (incl. lawyer fee)', 'STAFF', 1500),
 ('stage:val', 'Vehicle valuation (incl. valuer fee)', 'STAFF', 1000),
 ('stage:gold', 'Gold appraisal', 'STAFF', 100),
 ('stage:cgt', 'Group formation (CGT)', 'STAFF', 300),
 ('stage:grt', 'Group recognition test (GRT)', 'STAFF', 200);
