package com.meridian.app.core.data

class AppException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
