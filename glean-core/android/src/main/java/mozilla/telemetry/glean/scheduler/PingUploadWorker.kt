/* This Source Code Form is subject to the terms of the Mozilla Public
* License, v. 2.0. If a copy of the MPL was not distributed with this
* file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.telemetry.glean.scheduler

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import mozilla.telemetry.glean.rust.LibGleanFFI
import mozilla.telemetry.glean.Glean
import mozilla.telemetry.glean.net.FfiPingUploadTask
import mozilla.telemetry.glean.utils.testFlushWorkManagerJob
import mozilla.telemetry.glean.net.PingUploadTask
import mozilla.telemetry.glean.rust.Constants

/**
 * Build the constraints around which the worker can be run, such as whether network
 * connectivity is required.
 *
 * @return [Constraints] object containing the required work constraints
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun buildConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

/**
 * Build the [OneTimeWorkRequest] for enqueueing in the [WorkManager].  This also adds a tag
 * by which enqueued requests can be identified.
 *
 * @return [OneTimeWorkRequest] representing the task for the [WorkManager] to enqueue and run
 */
internal inline fun <reified W : Worker> buildWorkRequest(tag: String): OneTimeWorkRequest {
    return OneTimeWorkRequestBuilder<W>()
        .addTag(tag)
        .setConstraints(buildConstraints())
        .build()
}

/**
 * This class is the worker class used by [WorkManager] to handle uploading the ping to the server.
 * @suppress This is internal only, don't show it in the docs.
 */
class PingUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        internal const val PING_WORKER_TAG = "mozac_service_glean_ping_upload_worker"

        internal const val MAX_RETRIES = 3

        /**
         * Function to aid in properly enqueuing the worker in [WorkManager]
         *
         * @param context the application [Context] to get the [WorkManager] instance for
         */
        internal fun enqueueWorker(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                PING_WORKER_TAG,
                ExistingWorkPolicy.KEEP,
                buildWorkRequest<PingUploadWorker>(PING_WORKER_TAG)
            )

            // Only flush pings immediately if sending to a test endpoint,
            // which means we're probably in instrumented tests.
            if (Glean.isSendingToTestEndpoint) {
                testFlushWorkManagerJob(context, PING_WORKER_TAG)
            }
        }

        /**
         * Function to cancel any pending ping upload workers
         *
         * @param context the application [Context] to get the [WorkManager] instance for
         */
        internal fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PING_WORKER_TAG)
        }
    }

    /**
     * This method is called on a background thread - you are required to **synchronously** do your
     * work and return the [androidx.work.ListenableWorker.Result] from this method.  Once you
     * return from this method, the Worker is considered to have finished what its doing and will be
     * destroyed.
     *
     * A Worker is given a maximum of ten minutes to finish its execution and return a
     * [androidx.work.ListenableWorker.Result].  After this time has expired, the Worker will
     * be signalled to stop.
     *
     * @return The [androidx.work.ListenableWorker.Result] of the computation
     */
    @Suppress("ReturnCount")
    override fun doWork(): Result {
        // We counte the number of failures,
        // if we get more than three failures in upload we will stop this worker.
        //
        // This is a hack before a more robust mechanism is implemented in Rust.
        var uploadFailures = 0
        do {
            // Create a slot of memory for the task: glean-core will write data into
            // the allocated memory.
            val incomingTask = FfiPingUploadTask.ByReference()
            LibGleanFFI.INSTANCE.glean_get_upload_task(incomingTask)
            when (val action = incomingTask.toPingUploadTask()) {
                is PingUploadTask.Upload -> {
                    // Upload the ping request.
                    // If the status is `null` there was some kind of unrecoverable error
                    // so we return a known unrecoverable error status code
                    // which will ensure this gets treated as such.
                    val result = Glean.httpClient.doUpload(
                        action.request.path,
                        action.request.body,
                        action.request.headers,
                        Glean.configuration
                    ).toFfi()

                    if (result == Constants.UPLOAD_RESULT_RECOVERABLE) {
                        uploadFailures++
                    }

                    // Process the upload response
                    LibGleanFFI.INSTANCE.glean_process_ping_upload_response(incomingTask, result)
                }
                PingUploadTask.Wait -> return Result.retry()
                PingUploadTask.Done -> return Result.success()
            }
        } while (uploadFailures < MAX_RETRIES)
        return Result.failure()
    }
}
