-- Phase 5: borrower self-service portal. Borrowers sign in with their mobile number and a one-time password.

create table borrower_otp (
  id bigserial primary key,
  mobile varchar(10) not null,
  code_hash varchar(64) not null,          -- SHA-256 of mobile + code; the code itself is never stored
  expires_at timestamptz not null,
  attempts int not null default 0,
  used boolean not null default false,
  created_at timestamptz not null default now()
);
create index ix_borrower_otp_mobile on borrower_otp(mobile, id);

alter table loan_application add column channel varchar(20) not null default 'BRANCH';   -- BRANCH or BORROWER_PORTAL
