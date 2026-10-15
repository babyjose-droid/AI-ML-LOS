-- Collateral on a live loan: lien, release after closure or a part-payment, substitution; comparable sales for valuation.

alter table collateral add column lien_state varchar(15) not null default 'NONE';  -- NONE (before disbursement), ACTIVE, RELEASED, SUBSTITUTED
alter table collateral add column purpose varchar(12) not null default 'ORIGINAL';  -- ORIGINAL, SUBSTITUTE
alter table collateral add column substitute_for bigint references collateral(id);
alter table collateral add column released_at timestamptz;
update collateral c set lien_state = 'ACTIVE' from loan_application a where a.id = c.application_id and a.app_state = 'DISBURSED';

create table collateral_change (
  id bigserial primary key,
  loan_id bigint not null references live_loan(id),
  application_id bigint not null references loan_application(id),
  kind varchar(12) not null,                    -- RELEASE, SUBSTITUTE
  collateral_id bigint not null references collateral(id),
  new_collateral_id bigint references collateral(id),
  reason varchar(300) not null,
  outstanding_at numeric(14,2) not null,
  ltv_before numeric(7,4),
  ltv_after numeric(7,4),
  check_note varchar(400),
  status varchar(12) not null default 'REQUESTED',   -- REQUESTED, APPROVED, REJECTED, COMPLETED
  due_on date,                                   -- documents back by (30 days after closure)
  requested_by varchar(60) not null,
  requested_at timestamptz not null default now(),
  decided_by varchar(60),
  decided_at timestamptz,
  decision_note varchar(300),
  completed_by varchar(60),
  completed_at timestamptz,
  handed_to varchar(120),
  days_late int,
  compensation numeric(12,2),
  letter text
);
create index ix_colchange_loan on collateral_change(loan_id);

-- Comparable sales for the automated valuation (registered sale deeds nearby). Illustrative rows; the lender imports
-- real ones (registry data, its own past valuations) from Collateral → Comparable sales.
create table property_sale (
  id bigserial primary key,
  type varchar(15) not null,
  pincode varchar(6) not null,
  locality varchar(120),
  lat double precision,
  lng double precision,
  area_sqft numeric(12,2) not null,
  price numeric(14,2) not null,
  sale_date date not null,
  source varchar(40) not null default 'IMPORT',
  document_no varchar(60),
  added_by varchar(60) not null default 'system',
  added_at timestamptz not null default now()
);
create index ix_property_sale_pin on property_sale(type, pincode);
insert into property_sale (type, pincode, locality, lat, lng, area_sqft, price, sale_date, source) values
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 20.0140, 73.7530, 1410, 6545000, '2025-11-01', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 19.9964, 73.7607, 2080, 9585000, '2025-03-09', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 20.0079, 73.7734, 1620, 7658000, '2026-02-01', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 20.0084, 73.7519, 1660, 7320000, '2024-12-20', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 20.0044, 73.7637, 1620, 7782000, '2026-02-02', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 19.9968, 73.7644, 1630, 8256000, '2025-03-01', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422013', 'Gangapur Road, Nashik', 20.0078, 73.7656, 1030, 5595000, '2026-02-22', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 20.0080, 73.7730, 1230, 6428000, '2025-05-22', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 19.9998, 73.7759, 1590, 9267000, '2025-11-28', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 20.0008, 73.7856, 1680, 9486000, '2025-04-24', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 19.9975, 73.7738, 1220, 7417000, '2026-05-18', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 20.0149, 73.7696, 1560, 9245000, '2026-07-31', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 19.9956, 73.7643, 1300, 7638000, '2025-09-17', 'ILLUSTRATIVE'),
('RESIDENTIAL', '422005', 'College Road, Nashik', 20.0114, 73.7695, 1500, 7716000, '2025-12-20', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 20.0015, 73.7783, 430, 3727000, '2025-02-24', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 19.9869, 73.7962, 360, 3013000, '2025-06-27', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 19.9974, 73.7818, 510, 5279000, '2026-04-27', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 19.9958, 73.7910, 430, 3654000, '2025-08-20', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 19.9910, 73.7798, 690, 6138000, '2025-07-27', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 20.0054, 73.7822, 680, 5863000, '2026-04-27', 'ILLUSTRATIVE'),
('SHOP', '422002', 'MG Road, Nashik', 20.0001, 73.7854, 340, 3187000, '2025-12-05', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0168, 73.7510, 5780, 15687000, '2026-04-18', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0015, 73.7552, 3270, 7699000, '2024-11-23', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0029, 73.7482, 1800, 4531000, '2026-07-27', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0014, 73.7487, 1500, 3730000, '2026-07-30', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0026, 73.7461, 5430, 14920000, '2026-08-30', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0194, 73.7638, 3140, 7368000, '2025-09-17', 'ILLUSTRATIVE'),
('PLOT', '422013', 'Gangapur Road, Nashik', 20.0015, 73.7482, 3680, 8430000, '2025-06-23', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.4960, 73.8185, 1440, 12564000, '2025-12-20', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.4960, 73.8084, 790, 7145000, '2025-04-28', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.5042, 73.7997, 1310, 10941000, '2025-01-07', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.5107, 73.8104, 1160, 10919000, '2026-01-17', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.5008, 73.8081, 1430, 14172000, '2026-02-26', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.5067, 73.8003, 1590, 15497000, '2025-10-06', 'ILLUSTRATIVE'),
('RESIDENTIAL', '411038', 'Kothrud, Pune', 18.5191, 73.8186, 1070, 10262000, '2025-01-28', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5094, 73.8344, 330, 4839000, '2025-10-04', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5204, 73.8487, 700, 10352000, '2025-01-20', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5227, 73.8410, 750, 12878000, '2026-06-30', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5239, 73.8528, 670, 10364000, '2026-03-22', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5221, 73.8336, 440, 7395000, '2025-08-23', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5241, 73.8330, 290, 5043000, '2026-05-04', 'ILLUSTRATIVE'),
('SHOP', '411004', 'Deccan, Pune', 18.5179, 73.8326, 590, 8499000, '2025-01-24', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4623, 80.2527, 2620, 5885000, '2026-08-12', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4710, 80.2473, 2170, 4244000, '2026-02-27', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4605, 80.2468, 5270, 11423000, '2025-10-28', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4789, 80.2501, 6890, 13748000, '2025-06-20', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4799, 80.2476, 2910, 6113000, '2026-04-17', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4624, 80.2439, 6660, 11984000, '2025-02-08', 'ILLUSTRATIVE'),
('PLOT', '208023', 'Kalyanpur, Kanpur', 26.4717, 80.2406, 2370, 5176000, '2026-05-17', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4680, 80.2363, 860, 3479000, '2026-05-17', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4685, 80.2418, 830, 3644000, '2025-06-21', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4779, 80.2468, 1310, 5477000, '2025-04-20', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4880, 80.2566, 1740, 7324000, '2025-05-23', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4777, 80.2440, 1690, 6679000, '2025-12-18', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4773, 80.2391, 1120, 4765000, '2025-08-23', 'ILLUSTRATIVE'),
('RESIDENTIAL', '208023', 'Kalyanpur, Kanpur', 26.4842, 80.2498, 1700, 6615000, '2025-12-02', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.8996, 77.5966, 1870, 20336000, '2026-04-26', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.8997, 77.5907, 1080, 10646000, '2025-08-22', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.9019, 77.5910, 1270, 11132000, '2025-04-27', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.8947, 77.5817, 1510, 15142000, '2026-08-22', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.9179, 77.5926, 1460, 12235000, '2025-01-30', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.9130, 77.5802, 1190, 10800000, '2026-06-01', 'ILLUSTRATIVE'),
('RESIDENTIAL', '560078', 'JP Nagar, Bengaluru', 12.9170, 77.5834, 1830, 18840000, '2026-04-21', 'ILLUSTRATIVE');
