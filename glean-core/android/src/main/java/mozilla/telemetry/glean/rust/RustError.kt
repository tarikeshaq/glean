package mozilla.telemetry.glean.rust

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */


import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * This should be considered private, but it needs to be public for JNA.
 */
@Structure.FieldOrder("code", "message")
open class RustError : Structure() {

    class ByReference : RustError(), Structure.ByReference

    @JvmField var code: Int = 0
    @JvmField var message: Pointer? = null

    /**
     * Does this represent failure?
     */
    fun isFailure(): Boolean {
        return code != 0
    }

    @Suppress("ReturnCount", "TooGenericExceptionThrown", "ComplexMethod")
    fun intoException(): Exception {
            // It's probably a bad idea to throw here! We're probably leaking something if this is
            // ever hit! (But we shouldn't ever hit it?)
            throw RuntimeException("[Bug] intoException called on non-failure!")
    }

    /**
     * Get and consume the error message, or null if there is none.
     */
    @Synchronized
    fun consumeErrorMessage(): String {
        val result = this.message?.getAndConsumeRustString()
        this.message = null
        if (result == null) {
            throw NullPointerException("consumeErrorMessage called with null message!")
        }
        return result
    }

    @Synchronized
    fun ensureConsumed() {
        this.message?.getAndConsumeRustString()
        this.message = null
    }

    /**
     * Get the error message or null if there is none.
     */
    fun getMessage(): String? {
        return this.message?.getRustString()
    }
}
