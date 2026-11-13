package com.example.aicamera

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

const val ENHANCEMENT_WORK_NAME = "aicamera-enhancement-fifo"
const val ENHANCEMENT_WORK_TAG = "aicamera-enhancement-task"
const val KEY_SOURCE_FILE = "source_file"
const val KEY_PROMPT_ID = "prompt_id"
const val KEY_REQUEST_ID = "request_id"
const val KEY_CREATED_AT = "created_at"
const val KEY_STAGE = "stage"
const val KEY_ERROR = "error"

/**
 * WorkManager persists this FIFO chain across process death and app restarts.
 * A CONNECTED constraint means offline jobs remain queued until a network is available.
 */
fun enqueueEnhancement(
    context: Context,
    source: File,
    promptId: Int,
    requestId: String = UUID.randomUUID().toString()
): UUID {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequestBuilder<EnhancementWorker>()
        .setInputData(workDataOf(
            KEY_SOURCE_FILE to source.absolutePath,
            KEY_PROMPT_ID to promptId,
            KEY_REQUEST_ID to requestId,
            KEY_CREATED_AT to System.currentTimeMillis()
        ))
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        .addTag(ENHANCEMENT_WORK_TAG)
        .addTag("request-$requestId")
        .build()

    WorkManager.getInstance(context.applicationContext)
        .beginUniqueWork(ENHANCEMENT_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        .enqueue()
    return request.id
}

class EnhancementWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_SOURCE_FILE).orEmpty()
        val source = File(path)
        val promptId = inputData.getInt(KEY_PROMPT_ID, 0)
        val requestId = inputData.getString(KEY_REQUEST_ID).orEmpty()
        if (path.isBlank() || requestId.isBlank() || !source.isFile) {
            return Result.failure(workDataOf(KEY_ERROR to "The saved original photo could not be found"))
        }

        Api.initialize(applicationContext)
        return try {
            setProgress(workDataOf(KEY_STAGE to "Connecting to enhancement server"))
            val outputs = Api.process(source, promptId, applicationContext.cacheDir, requestId) { message ->
                setProgressAsync(workDataOf(KEY_STAGE to message))
            }
            if (outputs.isEmpty()) throw IOException("The enhancement server returned no images")
            var savedCount = 0
            outputs.forEach { output ->
                GalleryStorage.save(applicationContext, output, "enhanced")
                savedCount++
                output.delete()
            }
            Result.success(workDataOf("result_count" to savedCount))
        } catch (e: AuthExpired) {
            Result.failure(workDataOf(KEY_ERROR to "Your server session expired. Sign in again, then retry this task."))
        } catch (e: ApiHttpException) {
            if (e.retryable && runAttemptCount < 4) {
                setProgress(workDataOf(KEY_STAGE to "Temporary server error; retrying"))
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Server request failed")))
            }
        } catch (e: IOException) {
            if (runAttemptCount < 4) {
                setProgress(workDataOf(KEY_STAGE to "Network problem; waiting to retry"))
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Network request failed")))
            }
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Enhancement failed")))
        }
    }
}