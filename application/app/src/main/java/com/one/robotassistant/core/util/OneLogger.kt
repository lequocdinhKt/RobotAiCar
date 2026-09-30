package com.one.robotassistant.core.util

import android.util.Log

interface OneLogger {
    fun debug(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

class AndroidOneLogger : OneLogger {
    override fun debug(tag: String, message: String) {
        Log.d("ONE/$tag", message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e("ONE/$tag", message, throwable)
    }
}
