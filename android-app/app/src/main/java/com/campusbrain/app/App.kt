package com.campusbrain.app

import android.app.Application
import com.campusbrain.app.diag.CrashLog

/**
 * Nothing is initialised here except the crash handler, and that is the point.
 *
 * The corpus, the embedder and the entitlement store are all opened off the
 * main thread by the activity, so `onCreate` stays empty of work that could
 * make a cold start slower. [CrashLog.install] is the exception because it has
 * to be first: a crash during the corpus open is exactly the failure nobody
 * here can see, and a handler installed afterwards would miss it. It reads a
 * package version, sets one field on `Thread`, and touches no file until
 * something actually crashes.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
