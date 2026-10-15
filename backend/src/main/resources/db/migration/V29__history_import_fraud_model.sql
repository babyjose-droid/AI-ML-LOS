-- The lender's own past loans for model training (imported), with fraud outcomes for the separate fraud model.
alter table historical_loan add column outcome_fraud boolean not null default false;
alter table historical_loan add column max_dpd int;
alter table historical_loan add column imported_by varchar(60);
alter table historical_loan add column batch varchar(40);
