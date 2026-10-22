-- Gold loans: LTV by loan size is configurable (collateral policy, within the RBI caps), and FOIR caps the amount too
-- (credit policy override for the gold product, 50% to start). The loan is the lower of the two.
alter table collateral_policy add column ltv_slabs varchar(200);
update collateral_policy set ltv_slabs = '250000:0.85,500000:0.80,*:0.75', max_ltv = 0.75 where type = 'GOLD';

update policy_version set overrides_json = (coalesce(nullif(overrides_json, ''), '{}')::jsonb
  || jsonb_build_object('product:GL', coalesce(nullif(overrides_json, '')::jsonb -> 'product:GL', '{}'::jsonb) || '{"maxFoir": 0.50}'::jsonb))::text
 where status = 'LIVE';
