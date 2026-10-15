-- Video KYC: the country of the customer's IP address (besides GPS), and booked call slots for the officials' queue.
alter table vkyc_session add column ip_country varchar(10);       -- ISO country, PRIVATE, or UNKNOWN
alter table vkyc_session add column slot_at timestamptz;          -- the booked call time, if any
alter table vkyc_session add column booked_by varchar(60);        -- 'customer' or the staff user who booked
alter table vkyc_session add column booked_at timestamptz;
create index ix_vkyc_slot on vkyc_session(slot_at);
