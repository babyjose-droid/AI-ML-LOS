-- Wording the lender controls: sanction letter and KFS text (overrides of the built-in wording, per language) and
-- customer messages (SMS, WhatsApp, email). Every save is a new version; the latest active one is used.
create table message_template (
  id bigserial primary key,
  channel varchar(10) not null,          -- LETTER (sanction letter and KFS), SMS, WHATSAPP, EMAIL
  code varchar(40) not null,             -- the wording key, e.g. intro or PARTY_INVITE
  language varchar(5) not null default 'en',
  subject varchar(200),
  body text not null,
  version int not null default 1,
  active boolean not null default true,
  note varchar(300),
  updated_by varchar(60) not null default 'system',
  updated_at timestamptz not null default now()
);
create index ix_template_key on message_template(channel, code, language);

insert into message_template (channel, code, language, body, note) values
 ('SMS', 'OTP_LOGIN', 'en', '{otp} is your OTP for {lender}. It is valid for {minutes} minutes. Do not share it with anyone.', 'Default'),
 ('SMS', 'PARTY_INVITE', 'en', '{lender}: {applicantFirstName} has named you as {role} on a loan application. Open {link} to read what this means, give your consent and upload your documents.', 'Default'),
 ('SMS', 'VKYC_LINK', 'en', 'Dear {name}, complete your video KYC with {lender} at {link}. The link is valid for {hours} hours. Keep your original PAN card ready.', 'Default'),
 ('SMS', 'SANCTION_SMS', 'en', 'Dear {name}, your loan {appNo} of {amount} is sanctioned by {lender}. Read the Key Fact Statement before you accept.', 'Default'),
 ('SMS', 'DISBURSED_SMS', 'en', 'Dear {name}, {amount} of loan {loanRef} has been credited to your account. EMI {emi} from {firstEmiDate}. {lender}', 'Default'),
 ('SMS', 'EMI_REMINDER', 'en', 'Dear {name}, your EMI of {emi} for loan {loanRef} is due on {dueDate}. Please keep the balance in your account. {lender}', 'Default'),
 ('SMS', 'PTP_REMINDER', 'en', 'Dear {name}, as agreed, please pay {amount} on loan {loanRef} by {date}. {lender}', 'Default');
