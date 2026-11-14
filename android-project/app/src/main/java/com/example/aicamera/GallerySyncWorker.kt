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
import java.io.IOException
import java.util.concurrent.TimeUnit

const val GALLERY_SYNC_WORK_NAME = "aicamera-gallery-sync"

fun enqueueGallerySync(context: Context) {
    val request = OneTimeWorkRequestBuilder<GallerySyncWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
        .addTag(GALLERY_SYNC_WORK_NAME)
        .build()
    WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork(GALLERY_SYNC_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}

class GallerySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Api.initialize(applicationContext)
        if (Api.sid.isBlank()) return Result.success()
        return try {
            Api.validate()
            Api.syncGallery(applicationContext)
            Result.success()
        } catch (_: AuthExpired) {
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
