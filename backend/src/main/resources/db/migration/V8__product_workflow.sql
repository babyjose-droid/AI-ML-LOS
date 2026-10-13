-- Phase 5: workflow settings per product.
alter table product
  add column video_kyc_rule varchar(20) not null default 'ON_KYC_REVIEW',   -- ON_KYC_REVIEW, ALWAYS, ABOVE_AMOUNT
  add column video_kyc_threshold numeric(14,2),
  add column bank_data_source varchar(20) not null default 'AA_OR_STATEMENT', -- AA_OR_STATEMENT, STATEMENT_ONLY, AA_ONLY
  add column auto_sanction_limit numeric(14,2);                                -- null = never; else engine APPROVE up to this amount is sanctioned automatically

-- microfinance (JLG) loans up to ₹50,000 that the engine approves cleanly are sanctioned without waiting for a person
update product set auto_sanction_limit = 50000 where code = 'JLG';
