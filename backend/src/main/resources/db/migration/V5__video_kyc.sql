-- Phase 4: Video KYC (V-CIP). A session links the customer (by a one-time link) with an operations official.

create table vkyc_session (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  token_hash varchar(64) not null unique,      -- SHA-256 of the customer link token; the token itself is never stored
  status varchar(20) not null,                 -- WAITING, JOINED, IN_CALL, COMPLETED, APPROVED, REJECTED, CANCELLED, EXPIRED
  created_by varchar(60) not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  joined_at timestamptz,
  consent_at timestamptz,
  customer_lat numeric(9,6),
  customer_lng numeric(9,6),
  customer_accuracy_m numeric(10,1),
  in_india boolean,
  customer_ip varchar(64),
  customer_agent varchar(300),
  officer varchar(60),
  call_started_at timestamptz,
  call_ended_at timestamptz,
  challenge_code varchar(8),
  frames_json text,                            -- [{step, path, sha256}]
  recording_path varchar(300),
  recording_sha256 varchar(64),
  recording_bytes bigint,
  ai_status varchar(20),                       -- PASS, REVIEW, ERROR
  analysis_json text,
  decision varchar(20),
  decision_note varchar(1000),
  code_heard boolean,
  ai_override boolean not null default false,
  decided_by varchar(60),
  decided_at timestamptz
);
create index ix_vkyc_app on vkyc_session(application_id);

create table vkyc_signal (
  id bigserial primary key,
  session_id bigint not null references vkyc_session(id),
  sender varchar(10) not null,                 -- officer or customer
  kind varchar(20) not null,                   -- offer, answer, ice, instruction, hangup
  payload text not null,
  created_at timestamptz not null default now()
);
create index ix_vkyc_signal on vkyc_signal(session_id, id);
