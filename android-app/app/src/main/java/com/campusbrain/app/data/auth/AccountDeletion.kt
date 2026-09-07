package com.campusbrain.app.data.auth

/**
 * The two decisions account deletion makes, with no Android object and no
 * network in sight: what the server's answer MEANS, and whether that meaning
 * is enough to start deleting things off this phone.
 *
 * Both of them are pulled out here for the same reason
 * [EntitlementStore] takes a raw `SQLiteConnection` rather than a `Context`:
 * the expensive thing to get wrong is not the HTTP call, it is deciding that
 * a captive portal's answer justifies wiping a student's enrolment, and that
 * is a decision you want pinned in a unit test rather than first observed on
 * a device.
 *
 * The asymmetry in [clearsLocalState] is deliberate and is the whole safety
 * property of this feature: **clearing is only ever done on a server answer
 * that positively settled the question.** Everything else -- offline, a
 * portal, a refused token, a migration that has not been applied -- leaves
 * this device exactly as it was, still enrolled, still able to try again.
 * Getting that backwards would sign a student out of an account that still
 * exists and tell them it is gone.
 */
object AccountDeletion {

    /**
     * What actually happened, from the student's point of view.
     *
     * Four arms, and each one has a different next move -- which is why
     * "already deleted" is not folded into [Deleted] and why "the server
     * refused the session" is not folded into [Unavailable]. A student told
     * "try again" when the real fix is "sign in first" will try again forever.
     */
    sealed interface Result {
        /** The account is gone from the institution's control plane, and this
         * device has forgotten it. */
        data object Deleted : Result

        /** There was no account to delete: this device never enrolled, or the
         * account had already been removed. Nothing failed. */
        data object NoAccount : Result

        /**
         * The server would not accept the request as this user -- the stored
         * session is dead. Nothing was deleted. The student has to sign in
         * again first, which on this app means enrolling again, and then
         * deleting.
         */
        data object SessionExpired : Result

        /**
         * No usable answer from the institution. **Nothing was deleted**,
         * there or here.
         *
         * Two causes reach this and the copy has to be true of both: no
         * network, and a control plane that has not had the deletion function
         * applied to it yet. The second is not something a student can fix by
         * retrying, so the words must not promise that retrying works.
         */
        data object Unavailable : Result
    }

    /** The server's outcome, in the student's terms. One to one, and kept as
     * a function so the two vocabularies can drift apart later without a
     * screen having to learn about HTTP. */
    fun resultOf(outcome: ControlPlane.DeleteOutcome): Result = when (outcome) {
        ControlPlane.DeleteOutcome.Deleted -> Result.Deleted
        ControlPlane.DeleteOutcome.NoAccount -> Result.NoAccount
        ControlPlane.DeleteOutcome.NotSignedIn -> Result.SessionExpired
        ControlPlane.DeleteOutcome.Unavailable -> Result.Unavailable
    }

    /**
     * Whether this result justifies emptying [EntitlementStore.ACCOUNT_TABLES].
     *
     * True for exactly two arms:
     *
     *  - [Result.Deleted] -- the account is gone, so a session and a grant
     *    that outlived it are stale rows pointing at nothing, and one of them
     *    is a refresh token.
     *  - [Result.NoAccount] -- the server looked and found no user row. Any
     *    session still stored here refers to an account that does not exist;
     *    keeping it would leave a student staring at a licence banner for an
     *    enrolment the institution has no record of.
     *
     * False for [Result.SessionExpired] and [Result.Unavailable], and that is
     * the property the tests exist to hold: **a failed delete must leave this
     * device untouched.** The server still has the account; a device that
     * forgot it would show an un-enrolled phone whose row is still on the
     * control plane, which is the worst of both states.
     */
    fun clearsLocalState(result: Result): Boolean = when (result) {
        Result.Deleted, Result.NoAccount -> true
        Result.SessionExpired, Result.Unavailable -> false
    }

    /**
     * What the app keeps whatever the outcome, named so a test can assert it
     * and a reader can check it against the confirmation copy.
     *
     * These are tables in the same `user_corpus.db` file the account lives in,
     * which is exactly why the list is written down: deleting the file would
     * be one line and would take all of them.
     *
     *  - `documents`, `chunks`, `chunks_fts`, `embeddings` -- the documents
     *    the student imported themselves. Theirs, not the account's.
     *  - `analytics_route`, `analytics_doc_hits`, `analytics_minutes`,
     *    `analytics_settings`, `analytics_query_text` -- the on-device usage
     *    aggregates, which never left the phone in the first place.
     *  - `license`, `install_id` -- the licence is issued to a device by the
     *    vendor, not to an account by the identity service; deleting an
     *    account does not un-buy a licence.
     *
     * And, not being in this database at all and so not at risk from any of
     * this: `brain.db`, the institution's bundled corpus, from which every
     * answer this app gives is read. Retrieval never consults an entitlement,
     * so after a deletion the app answers exactly as it did before it, in
     * airplane mode, forever.
     */
    val KEPT_TABLES = listOf(
        "documents", "chunks", "chunks_fts", "embeddings",
        "analytics_route", "analytics_doc_hits", "analytics_minutes",
        "analytics_settings", "analytics_query_text",
        "license", "install_id",
    )
}
