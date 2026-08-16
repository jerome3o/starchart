package io.github.jerome3o.starchart

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE)
            ?: applicationContext.getString(R.string.app_name)
        val text = inputData.getString(KEY_TEXT)
            ?: applicationContext.getString(R.string.default_reminder_text)
        Notifications.show(applicationContext, title, text)
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
    }
}
