-- Product builder (FSD 20.5): every tab of the prototype, drafts with approval, and versions that applications keep.

alter table product add column repayment_frequency varchar(12) not null default 'MONTHLY';
alter table product add column moratorium_months int not null default 0;
alter table product add column min_processing_fee numeric(12,2);
alter table product add column foreclosure_pct numeric(5,2);
alter table product add column bounce_charge numeric(10,2);
alter table product add column penal_rate_pct numeric(5,2);
alter table product add column optional_docs varchar(500);
alter table product add column scoring_model varchar(20) not null default 'AUTO';

-- status set of the FSD: draft, live, paused, retired
update product set status = 'PAUSED' where status = 'PILOT';

-- illustrative charges for the seeded products (flow into the KFS)
update product set min_processing_fee = 1000, foreclosure_pct = 4, bounce_charge = 500, penal_rate_pct = 24 where code in ('BL-UNS', 'CV-SEC');
update product set min_processing_fee = 1000, foreclosure_pct = 3, bounce_charge = 500, penal_rate_pct = 24 where code = 'PL';
update product set min_processing_fee = 200, bounce_charge = 100, penal_rate_pct = 12, repayment_frequency = 'FORTNIGHTLY' where code = 'JLG';
update product set min_processing_fee = 250, penal_rate_pct = 18, repayment_frequency = 'BULLET' where code = 'GL';
update product set optional_docs = 'BANK_STATEMENT' where code in ('BL-UNS', 'PL');

create table product_version (
  id bigserial primary key,
  product_code varchar(20) not null references product(code),
  version int not null,
  status varchar(20) not null,          -- DRAFT, PENDING_APPROVAL, PUBLISHED, SUPERSEDED, REJECTED
  definition_json text not null,
  notes varchar(1000),
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  submitted_at timestamptz,
  approved_by varchar(60),
  approved_at timestamptz,
  decision_note varchar(1000),
  unique (product_code, version)
);

-- the current definition of every product becomes its published version
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
         'scoringModel', p.scoring_model)::text,
       'Definition in use when product versions were introduced', 'system', 'system', now()
  from product p;

-- every application keeps the product version it was created on
alter table loan_application add column product_version int;
update loan_application a set product_version = p.version from product p where p.code = a.product_code;

create or replace function lock_product_version() returns trigger as $$
begin
  if old.app_state <> 'DRAFT' and new.product_version is distinct from old.product_version then
    raise exception 'product_version of application % is fixed at % once submitted', old.app_no, old.product_version;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger trg_lock_product_version
  before update of product_version on loan_application
  for each row execute function lock_product_version();
