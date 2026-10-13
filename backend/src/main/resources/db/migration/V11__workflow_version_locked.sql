-- Once an application is submitted its workflow version can never change: old applications keep the old workflow,
-- new applications take the version published when they were created. Enforced in the database as well as the code.
create or replace function lock_workflow_version() returns trigger as $$
begin
  if old.app_state <> 'DRAFT' and new.workflow_version is distinct from old.workflow_version then
    raise exception 'workflow_version of application % is fixed at % once submitted', old.app_no, old.workflow_version;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger trg_lock_workflow_version
  before update of workflow_version on loan_application
  for each row execute function lock_workflow_version();

-- applications still without a version get the version their product has published now (created before V10 seeded it)
update loan_application a set workflow_version = w.version
  from workflow_version w
 where a.workflow_version is null and w.product_code = a.product_code and w.status = 'PUBLISHED'
   and a.app_state = 'DRAFT';
