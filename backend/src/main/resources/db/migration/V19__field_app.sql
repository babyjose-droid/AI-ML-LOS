-- Phase 2C: field officer app - visits captured offline, geo-tagged photos and AI photo checks

create table field_visit (
  id bigserial primary key,
  application_id bigint not null references loan_application(id),
  client_id varchar(64) not null unique,           -- created on the phone; makes a re-sent sync idempotent
  officer varchar(60) not null,
  checkin_at timestamptz not null,
  checkin_lat double precision,
  checkin_lng double precision,
  checkin_accuracy_m double precision,
  distance_m double precision,                     -- from the business location saved on the application
  location_check varchar(20) not null,             -- OK, FAR, NO_ADDRESS, NO_GPS
  captured_offline boolean not null default false,
  checklist_json text not null,
  data_json text,
  references_json text,
  remarks varchar(2000) not null,
  photo_checks_json text,
  outcome varchar(20) not null,                    -- POSITIVE, NEGATIVE, REFER
  synced_at timestamptz not null default now()
);
create index ix_field_visit_app on field_visit(application_id);

create table field_photo (
  id bigserial primary key,
  visit_id bigint not null references field_visit(id),
  application_id bigint not null references loan_application(id),
  kind varchar(20) not null,
  file_path varchar(300) not null,
  content_type varchar(60) not null,
  size_bytes bigint not null,
  sha256 varchar(64) not null,
  dhash varchar(16),
  taken_at timestamptz,
  lat double precision,
  lng double precision,
  checks_json text,
  created_at timestamptz not null default now()
);
create index ix_field_photo_app on field_photo(application_id);
create index ix_field_photo_sha on field_photo(sha256);

insert into connector_config (connector, name, timeout_ms, notes, updated_by)
values ('field', 'Field photo AI (in-house)', 60000, 'Python AI service: scene, shelf fill, owner face vs KYC', 'system');
