begin;

alter table if exists public.resolve_witnesses
  drop constraint if exists resolve_witnesses_receipt_id_fkey;

alter table if exists public.resolve_witnesses
  drop column if exists receipt_id;

with ranked as (
  select id, row_number() over (partition by target_sos_id order by created_at desc, id desc) as rn
  from public.resolve_receipts
)
delete from public.resolve_receipts
where id in (select id from ranked where rn > 1);

alter table if exists public.resolve_receipts
  add constraint resolve_receipts_target_sos_id_unique unique (target_sos_id);

commit;
