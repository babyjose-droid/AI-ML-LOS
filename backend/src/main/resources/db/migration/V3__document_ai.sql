-- Phase 2: AI document reading, document review and document-based KYC checks

alter table app_document
  add column ai_status varchar(20),
  add column detected_type varchar(30),
  add column type_confidence numeric(4,3),
  add column extracted_json text,
  add column validations_json text,
  add column quality_json text,
  add column tamper_json text,
  add column review_reasons text,
  add column engine varchar(40),
  add column masked boolean not null default false,
  add column reviewed_by varchar(60),
  add column reviewed_at timestamptz,
  add column review_note varchar(500);

create table kyc_check (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  check_name varchar(60) not null,
  result varchar(10) not null,          -- PASS, WARN, FAIL
  score numeric(4,3) not null,
  expected varchar(200),
  found varchar(200),
  source varchar(80),
  created_at timestamptz not null default now()
);
create index ix_kyc_check_app on kyc_check(application_id);

-- one officially valid document (OVD) of several kinds is accepted: alternatives are separated by |
update product set required_docs = 'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,UDYAM' where code = 'BL-UNS';
update product set required_docs = 'PAN,AADHAAR|VOTER_ID|DRIVING_LICENCE|PASSPORT,SALARY_SLIP' where code = 'PL';
update product set required_docs = 'AADHAAR|VOTER_ID' where code = 'JLG';
