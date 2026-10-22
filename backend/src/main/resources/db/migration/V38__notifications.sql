-- Alerts for staff: a new task in My work becomes a notification (shown in the app, and pushed to the officer's phone)
create table push_device (
  id bigserial primary key,
  username varchar(60) not null,
  token varchar(255) not null unique,
  platform varchar(20),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);
create index push_device_user on push_device(username);

create table user_notification (
  id bigserial primary key,
  username varchar(60) not null,
  task_key varchar(120) not null,
  application_id bigint,
  title varchar(200) not null,
  body varchar(500),
  created_at timestamptz not null default now(),
  read_at timestamptz,
  pushed_at timestamptz,
  unique (username, task_key)
);
create index user_notification_unread on user_notification(username, read_at);

-- when each officer's alerts were first worked out: tasks already open then are not announced
create table notify_state (
  username varchar(60) primary key,
  first_sweep_at timestamptz not null default now()
);
