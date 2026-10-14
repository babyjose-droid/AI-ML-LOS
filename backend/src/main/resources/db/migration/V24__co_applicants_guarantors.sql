-- Co-applicants and guarantors: the people on a loan besides the primary applicant, the product rules that control
-- them, their own documents, and the requests credit makes for another party.

alter table product add column party_rules text;
-- applications created before this release keep the old behaviour (no party rules) until they close
alter table product add column party_rules_since timestamptz;
update product set party_rules_since = now();

update product set party_rules = '{"coApplicant":{"mode":"OPTIONAL","max":2,"requiredWhen":""},"guarantor":{"mode":"OPTIONAL","max":1,"requiredWhen":"amount > 500000"},"coOwnersMustJoin":false,"incomeClubbing":{"allowed":true,"maxEarners":2,"relationships":["SPOUSE","BUSINESS_PARTNER"]},"relationships":["SPOUSE","PARENT","CHILD","SIBLING","BUSINESS_PARTNER","DIRECTOR","RELATIVE","FRIEND","OTHER"],"minBureauScore":700,"minAge":18,"maxAgeAtMaturity":70,"note":""}' where code = 'BL-UNS';
update product set party_rules = '{"coApplicant":{"mode":"OPTIONAL","max":1,"requiredWhen":""},"guarantor":{"mode":"NOT_ALLOWED","max":0,"requiredWhen":""},"coOwnersMustJoin":false,"incomeClubbing":{"allowed":true,"maxEarners":2,"relationships":["SPOUSE"]},"relationships":["SPOUSE","PARENT","CHILD","SIBLING"],"minBureauScore":0,"minAge":18,"maxAgeAtMaturity":65,"note":""}' where code = 'PL';
update product set party_rules = '{"coApplicant":{"mode":"OPTIONAL","max":1,"requiredWhen":""},"guarantor":{"mode":"OPTIONAL","max":1,"requiredWhen":"amount > 1000000"},"coOwnersMustJoin":false,"incomeClubbing":{"allowed":true,"maxEarners":2,"relationships":[]},"relationships":["SPOUSE","PARENT","CHILD","SIBLING","BUSINESS_PARTNER","DIRECTOR","RELATIVE","FRIEND","OTHER"],"minBureauScore":680,"minAge":18,"maxAgeAtMaturity":70,"note":""}' where code = 'CV-SEC';
update product set party_rules = '{"coApplicant":{"mode":"NOT_ALLOWED","max":0,"requiredWhen":""},"guarantor":{"mode":"NOT_ALLOWED","max":0,"requiredWhen":""},"coOwnersMustJoin":false,"incomeClubbing":{"allowed":false,"maxEarners":1,"relationships":[]},"relationships":[],"minBureauScore":0,"minAge":18,"maxAgeAtMaturity":75,"note":"Gold is the security: no co-applicants or guarantors"}' where code = 'GL';
update product set party_rules = '{"coApplicant":{"mode":"OPTIONAL","max":3,"requiredWhen":""},"guarantor":{"mode":"OPTIONAL","max":2,"requiredWhen":""},"coOwnersMustJoin":true,"incomeClubbing":{"allowed":true,"maxEarners":3,"relationships":[]},"relationships":["SPOUSE","PARENT","CHILD","SIBLING","BUSINESS_PARTNER","DIRECTOR","RELATIVE","FRIEND","OTHER"],"minBureauScore":700,"minAge":18,"maxAgeAtMaturity":70,"note":"Every co-owner of the property must join as a co-applicant"}' where code = 'LAP';
update product set party_rules = '{"coApplicant":{"mode":"NOT_ALLOWED","max":0,"requiredWhen":""},"guarantor":{"mode":"NOT_ALLOWED","max":0,"requiredWhen":""},"coOwnersMustJoin":false,"incomeClubbing":{"allowed":false,"maxEarners":1,"relationships":[]},"relationships":[],"minBureauScore":0,"minAge":18,"maxAgeAtMaturity":60,"note":"Joint liability group: the group is the guarantee"}' where code = 'JLG';

create table application_party (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  party_no varchar(20),
  role varchar(20) not null,                 -- CO_APPLICANT, GUARANTOR
  relationship varchar(30) not null,
  name varchar(120) not null,
  pan varchar(10) not null,
  mobile varchar(10) not null,
  email varchar(120),
  dob date not null,
  gender varchar(10),
  address varchar(300),
  city varchar(60),
  pincode varchar(6),
  occupation varchar(120),
  monthly_income numeric(14,2),
  existing_emi numeric(14,2),
  income_considered boolean not null default false,
  co_owner boolean not null default false,
  status varchar(20) not null default 'ADDED',     -- ADDED, INVITED, CONSENTED, VERIFIED, REVIEW, FAILED, REMOVED
  added_stage varchar(20) not null default 'APPLICATION',
  requirement_id bigint,
  consent_kyc boolean not null default false,
  consent_bureau boolean not null default false,
  consent_at timestamptz,
  consent_channel varchar(20),
  consent_language varchar(5),
  invite_hash varchar(64),
  invite_expires_at timestamptz,
  invited_at timestamptz,
  otp_hash varchar(64),
  otp_expires_at timestamptz,
  otp_attempts int not null default 0,
  session_hash varchar(64),
  session_expires_at timestamptz,
  kyc_state varchar(20) not null default 'NOT_STARTED',  -- NOT_STARTED, VERIFIED, REVIEW, FAILED
  kyc_score numeric(4,3),
  kyc_json text,
  kyc_note varchar(300),
  kyc_reviewed_by varchar(60),
  bureau_state varchar(20) not null default 'NOT_FETCHED', -- NOT_FETCHED, FETCHED, NO_HIT, FAILED
  bureau_score int,
  bureau_json text,
  signed_at timestamptz,
  signed_channel varchar(20),
  signed_document varchar(30),
  removed_reason varchar(300),
  added_by varchar(60) not null,
  added_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_party_app on application_party(application_id);
create index ix_party_pan on application_party(pan);
create unique index ux_party_invite on application_party(invite_hash) where invite_hash is not null;

create table party_document (
  id bigserial primary key,
  party_id bigint not null references application_party(id),
  application_id bigint not null references loan_application(id),
  doc_type varchar(30) not null,
  file_name varchar(200) not null,
  content_type varchar(100),
  size_bytes bigint not null,
  sha256 varchar(64) not null,
  storage_path varchar(400) not null,
  status varchar(20) not null,               -- VERIFIED, NEEDS_REVIEW, REJECTED, UPLOADED
  extracted_json text,
  read_confidence numeric(4,3),
  masked boolean not null default false,
  remarks varchar(300),
  uploaded_by varchar(60) not null,
  uploaded_at timestamptz not null default now()
);
create index ix_party_doc on party_document(party_id);

create table party_requirement (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  role varchar(20) not null,
  reason varchar(400) not null,
  status varchar(20) not null default 'OPEN',      -- OPEN, MET, WAIVED
  requested_by varchar(60) not null,
  requested_at timestamptz not null default now(),
  met_by_party_id bigint,
  closed_by varchar(60),
  closed_at timestamptz,
  close_note varchar(300)
);
create index ix_party_req on party_requirement(application_id);
