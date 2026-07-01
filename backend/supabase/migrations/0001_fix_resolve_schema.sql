begin;

alter table if exists public.resolve_witnesses
  drop constraint if exists resolve_witnesses_receipt_id_fkey;

alter table if exists public.resolve_witnesses
  drop column if exists receipt_id;

alter table if exists public.resolve_receipts
  add constraint resolve_receipts_target_sos_id_unique unique (target_sos_id);

commit;
