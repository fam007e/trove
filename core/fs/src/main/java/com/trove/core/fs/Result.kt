package com.trove.core.fs

sealed class Result<out T> {
    data class Success<out T>(val value: T) : Result<T>()
    data class Failure(val exception: Throwable) : Result<Nothing>()

    inline fun <R> map(transform: (T) -> R): Result<R> {
        return when (this) {
            is Success -> Success(transform(value))
            is Failure -> this
        }
    }

    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> {
        return when (this) {
            is Success -> transform(value)
            is Failure -> this
        }
    }

    fun getOrNull(): T? {
        return when (this) {
            is Success -> value
            is Failure -> null
        }
    }

    fun exceptionOrNull(): Throwable? {
        return when (this) {
            is Success -> null
            is Failure -> exception
        }
    }
}
