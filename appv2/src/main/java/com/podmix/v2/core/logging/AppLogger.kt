package com.podmix.v2.core.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor() {
    fun info(tag: String, message: String) = Log.i(tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = Log.w(tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = Log.e(tag, message, throwable)
}
