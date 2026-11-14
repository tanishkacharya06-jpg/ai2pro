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
import org.json.JSONObject

private const val QUEUE_PREFS = "enhancement_queue_metadata"
private const val QUEUE_META_PREFIX = "task_"

data class EnhancementTaskMetadata(
    val sourceFile: String,
    val promptId: Int,
    val requestId: String,
    val createdAt: Long,
    val accountName: String
)

fun loadEnhancementTaskMetadata(context: Context, workId: UUID): EnhancementTaskMetadata? {
    val raw = context.applicationContext.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        .getString(QUEUE_META_PREFIX + workId, null) ?: return null
    return try {
        val value = JSONObject(raw)
        EnhancementTaskMetadata(
            sourceFile = value.getString(KEY_SOURCE_FILE),
            promptId = value.getInt(KEY_PROMPT_ID),
            requestId = value.getString(KEY_REQUEST_ID),
            createdAt = value.getLong(KEY_CREATED_AT),
            accountName = value.optString(KEY_ACCOUNT_NAME)
        )
    } catch (_: Exception) { null }
}

const val ENHANCEMENT_WORK_NAME = "aicamera-enhancement-fifo"
const val ENHANCEMENT_WORK_TAG = "aicamera-enhancement-task"
const val KEY_SOURCE_FILE = "source_file"
const val KEY_PROMPT_ID = "prompt_id"
const val KEY_REQUEST_ID = "request_id"
const val KEY_CREATED_AT = "created_at"
const val KEY_ACCOUNT_NAME = "account_name"
const val KEY_STAGE = "stage"
const val KEY_ERROR = "error"
const val KEY_TASK_STATUS = "task_status"
const val KEY_PENDING_ENHANCED_FILE = "pending_enhanced_file"

/**
 * WorkManager persists this FIFO chain across process death and app restarts.
 * A CONNECTED constraint means offline jobs remain queued until a network is available.
 */
fun enqueueEnhancement(
    context: Context,
    source: File,
    promptId: Int,
    requestId: String = UUID.randomUUID().toString(),
    accountName: String = Api.user
): UUID {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val createdAt = System.currentTimeMillis()
    val request = OneTimeWorkRequestBuilder<EnhancementWorker>()
        .setInputData(workDataOf(
            KEY_SOURCE_FILE to source.absolutePath,
            KEY_PROMPT_ID to promptId,
            KEY_REQUEST_ID to requestId,
            KEY_CREATED_AT to createdAt,
            KEY_ACCOUNT_NAME to accountName
        ))
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        .addTag(ENHANCEMENT_WORK_TAG)
        .addTag("request-$requestId")
        .build()

    val metadata = JSONObject().apply {
        put(KEY_SOURCE_FILE, source.absolutePath)
        put(KEY_PROMPT_ID, promptId)
        put(KEY_REQUEST_ID, requestId)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_ACCOUNT_NAME, accountName)
    }
    context.applicationContext.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        .edit().putString(QUEUE_META_PREFIX + request.id, metadata.toString()).apply()

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
            return Result.success(workDataOf(KEY_TASK_STATUS to "failed", KEY_ERROR to "The saved original photo could not be found"))
        }

        Api.initialize(applicationContext)
        val expectedAccount = inputData.getString(KEY_ACCOUNT_NAME).orEmpty()
        if (expectedAccount.isNotBlank() && Api.user != expectedAccount) {
            setProgress(workDataOf(KEY_STAGE to "Waiting for account $expectedAccount to be selected on this device"))
            return Result.retry()
        }
        return try {
            setProgress(workDataOf(KEY_STAGE to "Connecting to enhancement server"))
            val outputs = Api.process(source, promptId, applicationContext.cacheDir, requestId) { message ->
                setProgressAsync(workDataOf(KEY_STAGE to message))
            }
            if (outputs.isEmpty()) throw IOException("The enhancement server returned no images")
            // Keep the original visible until the user chooses Original, Enhanced, or Both.
            val pending = GalleryStorage.storePendingEnhancement(applicationContext, source, outputs.first())
            outputs.forEach { it.delete() }
            Result.success(workDataOf(
                KEY_TASK_STATUS to "completed",
                KEY_PENDING_ENHANCED_FILE to pending.absolutePath,
                KEY_SOURCE_FILE to source.absolutePath,
                "result_count" to 1
            ))
        } catch (e: AuthExpired) {
            Result.success(workDataOf(KEY_TASK_STATUS to "failed", KEY_ERROR to "Your server session expired. Sign in again, then retry this task."))
        } catch (e: ApiHttpException) {
            if (e.retryable && runAttemptCount < 4) {
                setProgress(workDataOf(KEY_STAGE to "Temporary server error; retrying"))
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_TASK_STATUS to "failed", KEY_ERROR to (e.message ?: "Server request failed")))
            }
        } catch (e: IOException) {
            if (runAttemptCount < 4) {
                setProgress(workDataOf(KEY_STAGE to "Network problem; waiting to retry"))
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_TASK_STATUS to "failed", KEY_ERROR to (e.message ?: "Network request failed")))
            }
        } catch (e: Exception) {
            Result.success(workDataOf(KEY_TASK_STATUS to "failed", KEY_ERROR to (e.message ?: "Enhancement failed")))
        }
    }
}