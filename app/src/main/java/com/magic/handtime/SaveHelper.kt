package com.magic.handtime

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.*

object SaveHelper {

    fun resolveTargetTime(context: Context): Long {
        val prefs = context.getSharedPreferences("handtime_prefs", Context.MODE_PRIVATE)
        val now = Calendar.getInstance()
        return when (prefs.getString("time_setting", "3h")) {
            "3h" -> { now.add(Calendar.HOUR_OF_DAY, -3); now.timeInMillis }
            "10h" -> { now.add(Calendar.HOUR_OF_DAY, -10); now.timeInMillis }
            "24h" -> { now.add(Calendar.HOUR_OF_DAY, -24); now.timeInMillis }
            "3d" -> { now.add(Calendar.DAY_OF_YEAR, -3); now.timeInMillis }
            "custom" -> {
                val customStr = prefs.getString("custom_datetime", "") ?: ""
                try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(customStr)?.time
                        ?: now.timeInMillis
                } catch (e: Exception) { now.timeInMillis }
            }
            else -> now.timeInMillis
        }
    }

    fun saveComposedImage(context: Context, bitmap: Bitmap, timeMillis: Long) {
        val filename = "IMG_${timeMillis}.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, timeMillis / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, timeMillis / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.DATE_TAKEN, timeMillis)
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }

        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(timeMillis))
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                exif.saveAttributes()
            }
        } catch (e: Exception) { /* MediaStore date fields above still apply */ }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }
}
