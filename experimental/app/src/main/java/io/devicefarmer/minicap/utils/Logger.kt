package io.devicefarmer.minicap.utils

import android.util.Log

class Logger {
    companion object {
        val TAG = "minicap"
    }

    fun debug(message: String) {
        Log.d(Logger.TAG, message)
    }

    fun verbose(message: String) {
        Log.v(Logger.TAG, message)
    }

    fun info(message: String) {
        Log.i(Logger.TAG, message)
    }

    fun warn(message: String) {
        Log.w(Logger.TAG, message)
    }

    fun error(message: String, tr: Throwable? = null) {
        Log.e(Logger.TAG, message, tr)
    }
}