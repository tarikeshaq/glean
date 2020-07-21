package mozilla.telemetry.glean

import com.sun.jna.Pointer
import mozilla.telemetry.glean.rust.LibGleanFFI
import mozilla.telemetry.glean.rust.RustError
import java.util.concurrent.atomic.AtomicLong

// A LOT OF THIS IS COPIED FOR THE SAKE OF THE PROTOTYPE, NOT COMPLETE

open class ExperimentsInternalAPI internal constructor () {
    private var raw: AtomicLong = AtomicLong(0)

    fun initialize(baseUrl: String, collectionName: String, bucketName: String, dbPath: String) {
        raw.set( rustCall { error ->
            LibGleanFFI.INSTANCE.experiments_new(baseUrl, collectionName, bucketName, dbPath, error)
        })
    }

    fun getBranch(experimentName: String): String {
        var ptr = rustCall { error ->
            LibGleanFFI.INSTANCE.experiments_get_branch(raw.get(), experimentName, error)
        }
        return ptr.getAndConsumeRustString()
    }

    /**
     * Helper to read a null terminated String out of the Pointer and free it.
     *
     * Important: Do not use this pointer after this! For anything!
     */
    internal fun Pointer.getAndConsumeRustString(): String {
        return this.getRustString()
        // PLEASE INSERT A FREE HERE!!!!!!!
    }

    /**
     * Helper to read a null terminated string out of the pointer.
     *
     * Important: doesn't free the pointer, use [getAndConsumeRustString] for that!
     */
    internal fun Pointer.getRustString(): String {
        return this.getString(0, "utf8")
    }

    // In practice we usually need to be synchronized to call this safely, so it doesn't
    // synchronize itself
    private inline fun <U> nullableRustCall(callback: (RustError.ByReference) -> U?): U? {
        val e = RustError.ByReference()
        try {
            val ret = callback(e)
            if (e.isFailure()) {
                // We ignore it for now, although we shouldn't just cuz protoype
                //throw e.intoException()
            }
            return ret
        } finally {
            // This only matters if `callback` throws (or does a non-local return, which
            // we currently don't do)
            e.ensureConsumed()
        }
    }

    private inline fun <U> rustCall(callback: (RustError.ByReference) -> U?): U {
        return nullableRustCall(callback)!!
    }
}

/**
 * The main experiments object
 * ```
 */
object Experiments : ExperimentsInternalAPI()