-- Phase 2A: device and industry on the application, address location, household cash flow, industry scorecards

alter table loan_application add column device_id varchar(64);
alter table loan_application add column industry varchar(20);
alter table loan_application add column address_lat double precision;
alter table loan_application add column address_lng double precision;
create index ix_app_device on loan_application(device_id);
create index ix_app_account on loan_application(bank_account_no);
update loan_application set industry = 'SALARIED' where segment = 'SALARIED' and industry is null;

create table household (
  application_id bigint primary key references loan_application(id),
  members int not null,
  monthly_expenses numeric(14,2) not null default 0,
  existing_emi numeric(14,2) not null default 0,
  updated_by varchar(60),
  updated_at timestamptz not null default now()
);
create table household_member (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  name varchar(120) not null,
  relation varchar(30) not null,
  occupation varchar(80),
  monthly_income numeric(14,2) not null default 0,
  included boolean not null default true
);
create index ix_hh_member_app on household_member(application_id);

-- industry scorecards (FSD 14.3): points per characteristic band; used as challenger next to the PD model
create table scorecard (
  code varchar(30) primary key,
  name varchar(120) not null,
  industry varchar(20) not null,
  definition_json text not null,
  active boolean not null default true,
  updated_by varchar(60),
  updated_at timestamptz not null default now()
);

alter table decision_record add column scorecard_json text;
alter table decision_record add column network_score int;
