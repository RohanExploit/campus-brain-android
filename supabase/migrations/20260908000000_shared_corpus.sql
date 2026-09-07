-- The shared institution corpus: what a registrar publishes and every
-- enrolled phone syncs down.
--
-- ## WHAT THIS IS NOT
--
-- This is distribution, not retrieval. Nothing here answers a question.
-- Devices pull rows out of these two tables into their local
-- `user_corpus.db` and go on answering entirely on the phone, offline, from
-- the corpus they already hold. A device that has never synced, or whose sync
-- failed, answers exactly as it did before. There is no path from a question
-- to this schema and there must never be one -- see `data/sync/CorpusSync.kt`,
-- which is called at a lifecycle boundary and from a refresh gesture, and from
-- nowhere on the ask path.
--
-- ## THE CONSTRAINTS THIS FILE EXISTS TO HOLD
--
-- 1. **A device can only ever see its own institution's rows.** Tenancy comes
--    from `public.current_tenant_id()`, which reads `memberships` for
--    `auth.uid()`. It is never a value the client sends: the client does not
--    send one at all, because `tenant_id` DEFAULTs to that function. A caller
--    who supplies one anyway is checked against it by the WITH CHECK clause
--    and refused with 42501. The same pattern is already proven against the
--    live project -- cross-tenant reads returned `[]` and a
--    privilege-escalation write returned 42501.
--
-- 2. **Only `admin` and `registrar` may write.** Enforced by RLS, not by the
--    client. A student's app and a registrar's app are the same APK; the only
--    thing that separates them is what the server will accept from the JWT
--    they hold.
--
-- 3. **A device can always tell what changed.** Every mutation moves
--    `revision` forward, from one sequence, assigned by a trigger that no
--    client can influence. Withdrawal is a row, not an absence -- a phone that
--    was offline when a notice was pulled must be able to LEARN that it was
--    pulled, and an absence is not something anyone can learn.
--
-- ## WHY THE EMBEDDING IS `bytea` AND NOT `vector(384)`
--
-- Retrieval is on-device, so nothing server-side ever searches these vectors;
-- they are freight. The app writes and reads float32 little-endian, 384
-- values, 1536 bytes -- see `UserCorpusDb.encodeVector`, whose format was
-- confirmed against a shipped `brain.db` row. Storing exactly those bytes
-- means the blob that leaves the registrar's phone is the blob that arrives on
-- the student's, with no decode, no re-encode, no float parsing, and therefore
-- no opportunity for precision or byte-order drift between the two halves of a
-- cosine similarity.
--
-- If server-side vector search is ever wanted, add a nullable
-- `embedding_vec vector(384)` beside this column and backfill it from the
-- bytes -- they are a lossless float32 array and the conversion is mechanical.
-- Do NOT change the type of this column: the sync path's correctness argument
-- is that the bytes are never examined.

-- ---------------------------------------------------------------------------
-- Dependencies
-- ---------------------------------------------------------------------------

-- `current_tenant_id()` is not created here. It already exists in this project
-- and is used by the RLS on `memberships`, `tenants` and `usage_events`;
-- redefining it from a migration would silently change tenancy for every one
-- of them. It is only checked for, and the check is loud on purpose: without
-- it every policy below would evaluate to NULL, which denies every row, and a
-- corpus that syncs nothing looks exactly like a corpus with nothing in it.
do $$
begin
  if to_regprocedure('public.current_tenant_id()') is null then
    raise exception
      'public.current_tenant_id() is missing; the corpus RLS policies depend on it';
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Who may publish
-- ---------------------------------------------------------------------------

-- Takes NO PARAMETERS, for the reason `delete_my_account` takes none: there is
-- no argument for a caller to put another user's id, another tenant's id or
-- another role into. The answer is derived from `auth.uid()` and from nothing
-- else.
--
-- SECURITY DEFINER because it reads `memberships`, whose own RLS scopes a
-- caller to their own row -- which is all this needs, but relying on that
-- would make this function's meaning depend on a policy in another file.
-- `SET search_path = ''` and fully-qualified names throughout, so an
-- unqualified name cannot be captured by a table in a schema the caller
-- controls.
create or replace function public.corpus_can_publish()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.memberships m
    where m.user_id = auth.uid()
      and m.status = 'active'
      and m.role in ('admin', 'registrar')
  );
$$;

comment on function public.corpus_can_publish() is
  'True when the CALLING user is an active admin or registrar. Takes no '
  'parameters, so it cannot be aimed at another user or another role.';

revoke all on function public.corpus_can_publish() from public;
revoke all on function public.corpus_can_publish() from anon;
grant execute on function public.corpus_can_publish() to authenticated;

-- ---------------------------------------------------------------------------
-- The revision sequence
-- ---------------------------------------------------------------------------

-- A monotonic counter, NOT a timestamp.
--
-- A timestamp would be read off whichever clock wrote the row. A registrar's
-- phone running two minutes fast publishes a notice stamped in the future; a
-- device that syncs in between records that future stamp as its watermark and
-- never asks for anything older -- so every document published in those two
-- minutes is skipped, silently, on that device only, forever. There is no
-- error, no retry and nothing on screen. A sequence has no clock in it.
--
-- One global sequence rather than one per tenant. Watermarks are per device
-- and are only ever compared against rows the device can see, so the gaps a
-- shared sequence leaves are invisible and harmless; a per-tenant counter
-- would need a lock or a second table to stay unique under concurrent
-- publishes, which is real complexity bought for an aesthetic.
create sequence if not exists public.corpus_revision_seq as bigint;

revoke all on sequence public.corpus_revision_seq from public;
revoke all on sequence public.corpus_revision_seq from anon;
revoke all on sequence public.corpus_revision_seq from authenticated;

-- ---------------------------------------------------------------------------
-- corpus_documents
-- ---------------------------------------------------------------------------

create table if not exists public.corpus_documents (
  -- Declared `uuid` with a foreign key, so that if `public.tenants.tenant_id`
  -- turns out to be `text` this migration fails at apply time with a type
  -- error naming this line, rather than creating a table that can never be
  -- joined. The fix in that case is to change `uuid` to `text` here and in
  -- `corpus_chunks`, and nothing else.
  tenant_id uuid not null default public.current_tenant_id()
    references public.tenants (tenant_id) on delete cascade,

  -- The institution's own identifier for the document. Stable across
  -- revisions: republishing a corrected version keeps the same doc_id, which
  -- is how a device knows to replace rather than to add.
  doc_id text not null,

  title text not null,
  category text,

  -- The source file's size on the registrar's device, for the same reason
  -- `UserCorpusDb.documents.size_bytes` records it: an allowance is bought
  -- against the documents an institution hands the app.
  size_bytes bigint,

  -- How many chunks this document HAS, written before the first one is
  -- uploaded and never patched in afterwards.
  --
  -- This is the guard against a device pulling a document mid-upload. It is
  -- belt and braces behind `published_at` below, and it is the cheaper of the
  -- two to check: a client that has fetched the chunks can compare a count it
  -- already has. `DocumentIngest` has already run on the registrar's phone by
  -- the time anything is uploaded, so the final count is known up front and
  -- writing it up front costs nothing.
  chunk_count integer not null default 0 check (chunk_count >= 0),

  -- Assigned by the trigger below on every insert and every update. Never
  -- supplied by a client: `revision` is revoked from `authenticated` at the
  -- column level, so an UPDATE naming it is refused outright.
  revision bigint not null default 0,

  -- NULL while a publish is in flight. A device pulls only rows where this is
  -- set (or which are withdrawn), so a document is literally unpullable until
  -- every chunk of it is up. This is what makes "a half-synced document never
  -- answers from half its content" true on the SERVER as well as on the
  -- device, which matters because the device's guarantee depends on being
  -- handed a complete set to write.
  -- Display metadata, and never an ordering key. It is written from the
  -- registrar's phone and therefore off the registrar's clock; `revision` is
  -- what every ordering and every watermark uses, which is the whole reason
  -- that column exists. A wrong clock here shows a wrong date on a list. A
  -- wrong clock in a watermark loses documents.
  published_at timestamptz,

  -- Not writable by a client -- see the column-level grants below. A publish
  -- is attributed to whoever made it and to nobody else.
  published_by uuid default auth.uid() references auth.users (id) on delete set null,

  -- Withdrawal is a soft delete and this is the whole reason.
  --
  -- A phone that is offline when a notice is withdrawn cannot observe a row
  -- that is not there. Deleting the row would leave that phone answering from
  -- a withdrawn notice for as long as it stays offline -- and then forever
  -- after it syncs, because a sync only ever asks "what is new". A tombstone
  -- whose revision moves FORWARD is something the device can be told about.
  withdrawn_at timestamptz,

  primary key (tenant_id, doc_id)
);

-- The sync query is exactly this index: one tenant, revision above a
-- watermark, ascending.
create index if not exists idx_corpus_documents_revision
  on public.corpus_documents (tenant_id, revision);

comment on table public.corpus_documents is
  'One published institution document per row, tenant-scoped. Withdrawal is a '
  'soft delete (withdrawn_at) so a device that missed the window can still '
  'learn to stop answering from it.';

comment on column public.corpus_documents.revision is
  'Monotonic, from public.corpus_revision_seq, assigned by trigger on every '
  'insert and update including a withdrawal. Clients read it as a sync '
  'watermark and may not write it.';

-- ---------------------------------------------------------------------------
-- corpus_chunks
-- ---------------------------------------------------------------------------

create table if not exists public.corpus_chunks (
  tenant_id uuid not null default public.current_tenant_id()
    references public.tenants (tenant_id) on delete cascade,
  doc_id text not null,

  -- Position within the document. Together with doc_id this is the idempotency
  -- key: an interrupted upload resumes by re-sending the ordinals it is not
  -- sure about, and the unique constraint plus an upsert makes a re-send a
  -- no-op rather than a duplicate.
  ordinal integer not null check (ordinal >= 0),

  section text,
  content text not null,

  -- float32 little-endian, 384 values, 1536 bytes. See the header. Nullable
  -- because a registrar's device with no ONNX model still produces a document
  -- that is findable by keyword, which is the same degradation the app
  -- already has everywhere else.
  embedding bytea,

  unique (tenant_id, doc_id, ordinal),

  foreign key (tenant_id, doc_id)
    references public.corpus_documents (tenant_id, doc_id) on delete cascade
);

comment on column public.corpus_chunks.embedding is
  'float32 little-endian, 384 values, 1536 bytes -- byte-identical to '
  'UserCorpusDb.encodeVector and to brain.db''s embeddings.vec. Stored as '
  'bytea rather than vector(384) because retrieval is on-device and these '
  'bytes are freight: they are never examined between the registrar''s phone '
  'and the student''s. A vector(384) column may be ADDED beside this one and '
  'backfilled from it if server-side search is ever wanted; this column must '
  'not change type.';

-- ---------------------------------------------------------------------------
-- The revision trigger
-- ---------------------------------------------------------------------------

-- Every insert and every update takes the next revision, whatever the client
-- sent. Three things follow, and all three are load-bearing:
--
--  * a withdrawal (UPDATE ... SET withdrawn_at) moves the revision FORWARD, so
--    a device asking "what changed since N" is told about it. A soft delete
--    that did not move the watermark would be a tombstone nobody visits;
--  * a republish moves it forward too, so a corrected version reaches devices
--    that already have the old one;
--  * a client cannot freeze or rewind a revision. If it could, one buggy
--    registrar build could make every device in the institution skip a
--    document -- the same silent-skip failure the sequence exists to prevent,
--    arriving from the other end.
--
-- SECURITY DEFINER so `nextval` does not require the client to hold USAGE on
-- the sequence. USAGE is revoked from `authenticated` above precisely so that
-- burning revisions is not something a client can do on its own.
-- The guard below is not an optimisation. `published_by` references
-- `auth.users` with ON DELETE SET NULL, and `delete_my_account()` deletes an
-- `auth.users` row -- so a registrar closing their account performs an UPDATE
-- on every document they ever published. A referential action fires row
-- triggers, so without this guard that one deletion would bump the revision of
-- the entire corpus, and every enrolled device would see its whole local copy
-- as out of date and re-download all of it. One account deletion, a fleet-wide
-- re-sync, on a free tier's egress.
--
-- So the revision moves only when something a DEVICE reads has changed.
-- `is not distinct from` rather than `=`, because `=` is null on either side
-- and a null comparison would read as "changed" for every nullable column on
-- every update.
--
-- (`delete_my_account.sql` asked to be told if a new table ever referenced
-- `auth.users`. This one does. It is ON DELETE SET NULL, not NO ACTION, so
-- that function's final DELETE still succeeds and no membership or account
-- deletion is blocked by the corpus.)
create or replace function public.corpus_assign_revision()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and new.doc_id       is not distinct from old.doc_id
     and new.title        is not distinct from old.title
     and new.category     is not distinct from old.category
     and new.size_bytes   is not distinct from old.size_bytes
     and new.chunk_count  is not distinct from old.chunk_count
     and new.published_at is not distinct from old.published_at
     and new.withdrawn_at is not distinct from old.withdrawn_at
  then
    -- Nothing a device syncs on has changed. Keep the revision where it is so
    -- no device is told to re-fetch a document it already has.
    new.revision := old.revision;
    return new;
  end if;
  new.revision := nextval('public.corpus_revision_seq');
  return new;
end;
$$;

drop trigger if exists corpus_documents_revision on public.corpus_documents;
create trigger corpus_documents_revision
  before insert or update on public.corpus_documents
  for each row execute function public.corpus_assign_revision();

-- ---------------------------------------------------------------------------
-- Row level security
-- ---------------------------------------------------------------------------

alter table public.corpus_documents enable row level security;
alter table public.corpus_chunks enable row level security;

-- `FORCE ROW LEVEL SECURITY` is deliberately NOT set. It would subject the
-- table owner to these policies too, which is the stricter reading and would
-- close one real hole (a future SECURITY DEFINER function written against
-- these tables would otherwise bypass them) -- but it also changes what the
-- project's own owner role can do from the SQL editor, which is a bigger
-- decision than this migration is entitled to make on its own. If you want it,
-- it is two statements and it is reversible.

-- READ: any active member of the tenant, student included.
--
-- Note there is no `doc_id` or `tenant_id` filter in the client's query --
-- `CorpusSync` sends `revision=gt.N` and an ordering and nothing else. Scope
-- is decided here and only here, which is the property that makes it
-- impossible for a client bug to widen it.
create policy corpus_documents_read on public.corpus_documents
  for select to authenticated
  using (tenant_id = public.current_tenant_id());

create policy corpus_chunks_read on public.corpus_chunks
  for select to authenticated
  using (tenant_id = public.current_tenant_id());

-- WRITE: admin and registrar, within their own tenant, only.
--
-- USING and WITH CHECK both, and they are not the same check. USING decides
-- which existing rows an UPDATE or DELETE may touch; WITH CHECK decides what
-- an INSERT or the post-image of an UPDATE may say. USING alone would let a
-- registrar INSERT a document into ANOTHER institution's corpus -- there is no
-- existing row for USING to test, so it would not run at all. That is the
-- cross-tenant write this pair rules out.
create policy corpus_documents_write on public.corpus_documents
  for all to authenticated
  using (tenant_id = public.current_tenant_id() and public.corpus_can_publish())
  with check (tenant_id = public.current_tenant_id() and public.corpus_can_publish());

create policy corpus_chunks_write on public.corpus_chunks
  for all to authenticated
  using (tenant_id = public.current_tenant_id() and public.corpus_can_publish())
  with check (tenant_id = public.current_tenant_id() and public.corpus_can_publish());

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

-- RLS narrows table privileges; it does not grant them. Both are needed.
revoke all on table public.corpus_documents from public;
revoke all on table public.corpus_documents from anon;
revoke all on table public.corpus_chunks from public;
revoke all on table public.corpus_chunks from anon;

-- INSERT and UPDATE are granted COLUMN BY COLUMN, and the omissions are the
-- point of doing it that way.
--
--  * `tenant_id` is absent, so a client cannot send one at all. The column
--    DEFAULTs to `current_tenant_id()`, so an INSERT that omits it gets the
--    right answer and an INSERT that names it is refused with 42501. That is
--    stronger than checking a value the caller supplied, and it is the same
--    argument `delete_my_account` makes for taking no parameters.
--  * `revision` is absent, so the sequence is the only thing that can move it.
--    The trigger already overwrites whatever arrives, so this turns a
--    silently-ignored write into a refusal -- the difference between a
--    registrar build that looks like it works and one that says it does not.
--  * `published_by` is absent from INSERT; it DEFAULTs to `auth.uid()`, so a
--    publish cannot be attributed to somebody else.
--
-- Note this must be a column-level GRANT rather than a table-level grant
-- followed by a column-level REVOKE. PostgreSQL treats the two levels as
-- separate: revoking a column privilege that was never granted at the column
-- level does nothing at all, and would leave this looking safe while being
-- open.
grant select, delete on table public.corpus_documents to authenticated;
grant insert (doc_id, title, category, size_bytes, chunk_count, published_at, withdrawn_at)
  on table public.corpus_documents to authenticated;
grant update (title, category, size_bytes, chunk_count, published_at, withdrawn_at)
  on table public.corpus_documents to authenticated;

grant select, delete on table public.corpus_chunks to authenticated;
grant insert (doc_id, ordinal, section, content, embedding)
  on table public.corpus_chunks to authenticated;
grant update (section, content, embedding)
  on table public.corpus_chunks to authenticated;

-- ---------------------------------------------------------------------------
-- A note on corpus_versions, which this migration deliberately does not touch
-- ---------------------------------------------------------------------------
--
-- The plan says "bump `corpus_versions` on publish". That table describes a
-- WHOLE PREBUILT `brain.db` available for download: the client reads
-- `version, built_at_utc, sha256, size_bytes, min_app_version, storage_path`,
-- and `ControlPlane.parseCorpusVersion` REJECTS any row whose `sha256` is not
-- 64 hex characters or whose `size_bytes` is not positive. Publishing one
-- document produces no such artefact, so a row inserted here on publish would
-- have to carry an invented digest of a file that does not exist at a storage
-- path that holds nothing -- in the one column whose entire job is to let a
-- device verify a download it has not made yet.
--
-- Nothing is lost by leaving it alone. "Is there anything new" is already
-- answered exactly, by the query the sync makes anyway:
--
--     GET /corpus_documents?select=revision&revision=gt.<watermark>&limit=1
--
-- which returns `[]` when there is nothing, costs one indexed lookup, and
-- cannot be wrong about it. `corpus_versions` keeps its existing meaning --
-- there is a new bundle to download -- and stays truthful.
