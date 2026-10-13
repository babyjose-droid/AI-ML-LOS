-- Corrections from the review against the prototype and BRD/FSD (13 Oct 2026)

-- product eligibility checked by the decision engine (FSD 14.1 step 3); null = no limit
alter table product add column min_vintage_years numeric(4,1);
alter table product add column min_monthly_income numeric(14,2);

-- an INCOMPLETE decision names the missing minimum inputs (FR-UW-04)
alter table decision_record add column missing_data text;

-- separate trained models for applicants with a bureau record (THICK) and without one (NTC), BR-11
alter table model_version add column kind varchar(10);
update model_version set kind = 'COMBINED' where kind is null;
update model_version set status = 'RETIRED',
       decision_note = coalesce(decision_note, '') || ' [Retired 13 Oct 2026: one model for all applicants is replaced by separate THICK and NTC models]'
 where status in ('CHAMPION', 'CANDIDATE') and kind = 'COMBINED';
