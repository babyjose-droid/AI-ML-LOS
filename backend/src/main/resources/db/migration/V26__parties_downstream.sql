-- Co-applicants and guarantors after sanction and beyond: company and firm guarantors, checking a co-applicant's
-- income against their own bank data, the people on a live loan (for collections and credit bureau reporting).

alter table application_party add column party_type varchar(20) not null default 'INDIVIDUAL';  -- INDIVIDUAL, COMPANY, LLP, PARTNERSHIP, PROPRIETORSHIP, TRUST, HUF
alter table application_party add column signatory_name varchar(120);
alter table application_party add column signatory_designation varchar(80);
alter table application_party add column registration_no varchar(30);   -- CIN, LLPIN or firm registration
alter table application_party add column gstin varchar(15);
alter table application_party add column consent_aa boolean not null default false;
alter table application_party add column verified_income numeric(14,2);
alter table application_party add column income_source varchar(20);     -- AA, STATEMENT
alter table application_party add column income_checked_at timestamptz;
alter table application_party add column income_json text;

-- company and firm guarantors for business, vehicle and property loans; counted income must be checked against bank data
update product set party_rules = jsonb_set(party_rules::jsonb, '{entityGuarantors}', 'true')::text where code in ('BL-UNS', 'CV-SEC', 'LAP') and party_rules is not null;
update product set party_rules = jsonb_set(party_rules::jsonb, '{incomeClubbing,verify}', 'true')::text where party_rules is not null and (party_rules::jsonb -> 'incomeClubbing' ->> 'allowed') = 'true';

create table loan_party (
  id bigserial primary key,
  loan_id bigint not null references live_loan(id),
  application_party_id bigint references application_party(id),
  role varchar(20) not null,               -- CO_APPLICANT, GUARANTOR
  party_type varchar(20) not null default 'INDIVIDUAL',
  name varchar(120) not null,
  pan varchar(10),
  mobile varchar(10),
  dob date,
  relationship varchar(30),
  signatory_name varchar(120),
  created_at timestamptz not null default now()
);
create index ix_loan_party_loan on loan_party(loan_id);

alter table collection_contact add column party_id bigint references loan_party(id);
alter table collection_contact add column contact_name varchar(120);
