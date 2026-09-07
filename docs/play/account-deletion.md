# Campus Brain — Deleting your account

**App:** Campus Brain (`com.campusbrain.app`)
**Effective date:** `TODO: effective date`
**Contact for deletion requests:** `TODO: contact email`
**Published at:** `TODO: account deletion URL` (Google Play requires a publicly
reachable URL for this page, reachable **without installing the app**)

This page describes how to delete a Campus Brain account, what deleting it
removes, and what it deliberately leaves alone. Like the privacy policy, it is
written to be accurate to the app as built rather than to be reassuring.

---

## 1. What "your account" is here

Campus Brain only has an account if your institution configured identity **and**
you chose to enrol. Enrolment is optional and nothing in the app requires it:
every answer is read from documents already on your phone, and that is true
before you enrol, after you enrol, and after you delete.

If you did enrol, an account is three things:

- a **user record** at Supabase, the identity service your institution uses,
  holding the email address you gave (or a generated `.invalid` one, which
  identifies nobody) and your password;
- a **membership row** linking that user to your institution and to the licence
  grant that comes with it;
- **usage rows**, if your institution ever switched on aggregate telemetry —
  a route label, a latency and a success flag per event, never a question and
  never any part of a document.

That is the whole of it. There is no Campus Brain server holding your questions,
your college's documents, or your academic record; those are on the phone. See
the privacy policy for the full picture.

---

## 2. Deleting it in the app

1. Open Campus Brain.
2. Tap the status pill in the header — the small control at the top of the
   screen showing your institution. It opens **Enrol this device**.
3. At the bottom of that screen, tap **Delete this account**.
4. Read what is removed and what is kept, then tap **Delete this account**.
5. Confirm with **Yes, delete it**.

The app then asks your institution's control plane to delete the account and
tells you what happened. Four outcomes, and each says which:

| What you see | What it means |
|---|---|
| **The account has been deleted** | The user record, the membership and the usage rows are gone, and the phone has forgotten the enrolment. |
| **There is no account on this phone** | Nothing was enrolled here, or the account had already been removed. Nothing failed and nothing changed. |
| **Your sign-in has expired** | Your institution would not accept the request as you. **Nothing was deleted.** Enrol again with the same email and password, then delete. |
| **Your institution could not be reached** | The request got no answer. **Nothing was deleted**, on the phone or at your institution. Try again on a working connection, or use the request route below. |

Deletion needs a network — it is the same one moment of connectivity that
enrolment needs. Nothing is queued for later: if the request does not get an
answer, nothing anywhere has changed and you can try again.

---

## 3. Deleting it without the app

You do not have to have the app installed, or be able to sign in, to have your
account deleted. Send a request to `TODO: contact email` from any address,
including:

- that you are asking for **deletion of a Campus Brain account**;
- the email address the account was enrolled with, if you know it — if you
  enrolled without giving an address, say so and give your institution and
  your roll number instead, and your registrar can identify the enrolment;
- nothing else. Do **not** send your password, and do not send your enrolment
  code.

`TODO: state the handling commitment — who acts on these requests (registrar or
IT office), and the maximum time to complete one. Google Play expects a stated
timeframe.`

The same address can be used to ask what is held for an account before deciding.

---

## 4. What deletion removes

- The Supabase user record for the account.
- The membership row linking it to your institution, and with it the licence
  grant and the offline window that came from it.
- The aggregate usage rows stored against that user id.
- On the phone: the stored session tokens and the entitlement record.

The deletion is derived server-side from the signed-in user's own identity. The
function that performs it takes no user id as an argument, so a request can only
ever delete the account that made it.

## 5. What deletion does **not** remove

**The app keeps working exactly as it did.** This is the part most likely to be
misread, so it is stated plainly:

- your institution's bundled documents and record tables stay on the phone;
- **every answer the app can give, it still gives** — including in airplane
  mode, indefinitely. Answering has never consulted the account, the licence or
  the network, so there is nothing about it for a deletion to change;
- **documents you imported yourself** stay imported and stay searchable. They
  are yours, they were never part of the account, and nothing here touches them;
- the on-device usage counters, the crash log and any licence key on the device
  are likewise untouched.

Two further things are deliberately kept, because they are not yours to delete:

- **the enrolment code you redeemed is not returned to circulation.** A
  single-use code is spent by being used. If you want to enrol again, your
  department office issues a new one.
- **your academic record at your institution.** Campus Brain never held it —
  the records it reads are a copy distributed with the app — and deleting a
  Campus Brain account has no effect on anything your college holds about you.

## 6. Removing the app's data entirely

Deleting the account and removing the app's data are different actions, and you
may want both:

- **Uninstalling** the app removes everything it stored, including imported
  documents and counters. The app sets `android:allowBackup="false"`, so none of
  it is in Android's cloud backup and an uninstall is a real deletion.
- **Settings → Apps → Campus Brain → Storage → Clear data** does the same
  without removing the app.

Neither of those deletes the account at your institution. Only the route in
section 2 or section 3 does that.

---

## 7. Contact

`TODO: contact email`
