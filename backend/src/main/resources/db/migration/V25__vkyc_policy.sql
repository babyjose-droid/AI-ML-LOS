-- Product-wise video KYC (V-CIP): who needs a call besides the applicant, how strict the AI checks are, call hours,
-- link validity, attempts, who may conduct the call and the questions the official must ask. Sessions can now be for a
-- co-applicant or guarantor, and keep the settings and the questions they were run with.

alter table product add column vkyc_policy text;

update product set vkyc_policy = '{"parties":"ON_KYC_REVIEW","minFaceMatch":0.363,"requireLiveness":true,"requirePanOnCamera":true,"linkHours":24,"callFrom":"00:00","callTo":"23:59","maxAttempts":3,"officerRoles":["OPERATIONS"],"questions":["Confirm your full name and date of birth","What is the business and how long has it run?","What is the loan for?"],"languages":["en","hi","mr"]}' where code = 'BL-UNS';
update product set vkyc_policy = '{"parties":"ON_KYC_REVIEW","minFaceMatch":0.363,"requireLiveness":true,"requirePanOnCamera":true,"linkHours":24,"callFrom":"00:00","callTo":"23:59","maxAttempts":3,"officerRoles":["OPERATIONS"],"questions":["Confirm your full name and date of birth","Who is your employer?","What is the loan for?"],"languages":["en","hi"]}' where code = 'PL';
update product set vkyc_policy = '{"parties":"ON_KYC_REVIEW","minFaceMatch":0.363,"requireLiveness":true,"requirePanOnCamera":true,"linkHours":48,"callFrom":"00:00","callTo":"23:59","maxAttempts":3,"officerRoles":["OPERATIONS"],"questions":["Confirm your full name and date of birth","Which vehicle are you buying and for what work?"],"languages":["en","hi"]}' where code = 'CV-SEC';
update product set vkyc_policy = '{"parties":"NONE","minFaceMatch":0.363,"requireLiveness":true,"requirePanOnCamera":true,"linkHours":24,"callFrom":"00:00","callTo":"23:59","maxAttempts":3,"officerRoles":["OPERATIONS"],"questions":["Confirm your full name and date of birth"],"languages":["en","hi"]}' where code = 'GL';
update product set vkyc_policy = '{"parties":"ALL","minFaceMatch":0.45,"requireLiveness":true,"requirePanOnCamera":true,"linkHours":48,"callFrom":"00:00","callTo":"23:59","maxAttempts":3,"officerRoles":["OPERATIONS","CREDIT_OFFICER"],"questions":["Confirm your full name and date of birth","Confirm the address of the property offered as security","Do you agree to the property being mortgaged for this loan?"],"languages":["en","hi","mr"]}' where code = 'LAP';
update product set vkyc_policy = '{"parties":"NONE","minFaceMatch":0.363,"requireLiveness":true,"requirePanOnCamera":false,"linkHours":24,"callFrom":"00:00","callTo":"23:59","maxAttempts":3,"officerRoles":["OPERATIONS"],"questions":["Confirm your full name and the name of your group"],"languages":["en","hi","ta"]}' where code = 'JLG';

alter table vkyc_session add column party_id bigint references application_party(id);
alter table vkyc_session add column person_name varchar(120);
alter table vkyc_session add column policy_json text;
alter table vkyc_session add column questions_json text;
create index ix_vkyc_party on vkyc_session(party_id);
