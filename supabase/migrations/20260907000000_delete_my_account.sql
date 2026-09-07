-- Account deletion, invoked by the signed-in student from their own device.
--
-- Google Play requires that an app which lets a user create an account offers
-- an in-app route to delete it. GoTrue's admin delete endpoint needs the
-- service-role key, and that key must never be inside an APK -- an APK is a
-- zip file on a student's phone and anyone can read it. So deletion is a
-- SECURITY DEFINER function invoked as the ordinary `authenticated` role,
-- exactly the shape `redeem_enrolment_code` already uses.
--
-- ## THE CONSTRAINT THIS FILE EXISTS TO HOLD
--
-- The account being deleted is derived from `auth.uid()` and from nothing
-- else. This function takes NO PARAMETERS, so there is no argument for a
-- caller to put another student's user id into, by accident or otherwise.
-- That is a stronger guarantee than a `WHERE user_id = p_user AND p_user =
-- auth.uid()` check, because it removes the value rather than validating it:
-- a future edit cannot loosen a comparison that is not there.
--
-- `SECURITY DEFINER` means this runs with the definer's rights and therefore
-- bypasses RLS, which is the whole reason the id must not be an input. Read
-- the three DELETEs below with that in mind: every one of them is keyed to
-- the local `v_user`, which is assigned once, from `auth.uid()`, before any
-- row is touched.
--
-- `SET search_path = ''` and fully-qualified names throughout: an unqualified
-- name inside a SECURITY DEFINER function can be captured by a table in a
-- schema the caller controls, and Supabase's own linter flags its absence.
--
-- ## WHAT IS DELETED, AND WHAT A FUTURE TABLE MUST DO
--
-- Every row keyed to this user id at the time of writing:
--
--   * `public.usage_events.user_id`  -- aggregate telemetry rows
--   * `public.memberships.user_id`   -- the enrolment itself
--   * `auth.users.id`                -- the GoTrue account
--
-- This assumes nothing else in the database references `auth.users`. If that
-- ever stops being true, the new table must either be deleted from here or be
-- declared `ON DELETE CASCADE`. A table that references `auth.users` with the
-- default `NO ACTION` will make the final DELETE below raise a foreign-key
-- violation, which fails the whole function and deletes nothing -- an
-- acceptable failure, and much better than the silent alternative of a
-- cascade quietly removing a row nobody intended to lose.
--
-- ## WHAT IS DELIBERATELY NOT DELETED
--
-- The redeemed row in `public.enrolment_codes` is left exactly as it is. A
-- single-use code is spent by being redeemed, not by the account outliving
-- it, and returning it to circulation here would make delete-then-re-enrol a
-- way to farm one code into an unlimited number of enrolments. A student who
-- deletes their account and wants back in asks the department office for a
-- code, which is the same conversation they had the first time.
--
-- Nothing on the device is deleted by this function, and nothing here can
-- reach a student's documents or answers: the corpus, the imported files and
-- every answer the app gives are local and are not part of the account. The
-- client clears the enrolment and the session it stored; see
-- `EntitlementStore.clearAccount`.

create or replace function public.delete_my_account()
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid;
  v_existed boolean;
begin
  -- The one and only source of the identity being deleted.
  v_user := auth.uid();

  -- No authenticated user behind the request. 28000 (invalid_authorization_
  -- specification) is the same SQLSTATE `redeem_enrolment_code` raises for
  -- this case and the client already tells it apart from every other refusal:
  -- it means "sign in again", not "something went wrong".
  if v_user is null then
    raise exception 'delete_my_account requires an authenticated user'
      using errcode = '28000';
  end if;

  select exists (select 1 from auth.users u where u.id = v_user)
    into v_existed;

  delete from public.usage_events e where e.user_id = v_user;
  delete from public.memberships m where m.user_id = v_user;
  delete from auth.users u where u.id = v_user;

  -- False means there was nothing left to delete: a second tap, or a session
  -- whose account was already removed by a registrar. The client treats it as
  -- the success it is and clears the device, rather than showing a failure
  -- for a state the student was asking for anyway.
  return v_existed;
end;
$$;

comment on function public.delete_my_account() is
  'Deletes the CALLING user''s account. Identity comes from auth.uid() and the '
  'function takes no parameters, so it cannot be aimed at another user. '
  'Returns true if an auth.users row existed, false if there was nothing left '
  'to delete.';

-- Only a signed-in user may run it. `anon` must not: a function that deletes
-- an account should not even be callable by a request carrying no session,
-- and the 28000 raise above is a second line rather than the first.
revoke all on function public.delete_my_account() from public;
revoke all on function public.delete_my_account() from anon;
grant execute on function public.delete_my_account() to authenticated;
