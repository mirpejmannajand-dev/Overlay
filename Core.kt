package app.pejman.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.util.Calendar

object Core {

    fun prefs(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun mediaDir(c: Context): File {
        val d = File(c.filesDir, "media")
        d.mkdirs()
        return d
    }

    fun mediaList(c: Context): List<File> =
        (mediaDir(c).listFiles() ?: emptyArray<File>()).sortedBy { it.name }

    // Files whose name starts with "v_" are videos, "i_" are images.
    fun isVideo(f: File) = f.name.startsWith("v_")

    fun decodeImage(f: File, maxPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / s > maxPx * 2 || o.outHeight / s > maxPx * 2) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = BitmapFactory.decodeFile(f.path, o2) ?: return null
            val ori = ExifInterface(f.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val deg = when (ori) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) {
                bmp
            } else {
                val m = Matrix()
                m.postRotate(deg)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun thumb(f: File, size: Int): Bitmap? {
        if (!isVideo(f)) return decodeImage(f, size)
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.path)
            val b = r.getFrameAtTime(0)
            r.release()
            b
        } catch (e: Throwable) {
            null
        }
    }

    private fun alarmIntent(c: Context): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next scheduled moment strictly after [after], or -1 when nothing is left. */
    fun nextTime(c: Context, after: Long): Long {
        val p = prefs(c)
        val base = p.getLong("baseDay", 0L)
        if (base == 0L) return -1L
        val interval = maxOf(1, p.getInt("interval", 60))
        val times = maxOf(1, p.getInt("times", 1))
        val days = maxOf(1, p.getInt("days", 1))
        val startMin = p.getInt("startH", 9) * 60 + p.getInt("startM", 0)
        for (d in 0 until days) {
            for (i in 0 until times) {
                val m = startMin + i * interval
                if (m >= 1440) break // stay inside the same day
                val cal = Calendar.getInstance()
                cal.timeInMillis = base
                cal.add(Calendar.DAY_OF_YEAR, d)
                cal.set(Calendar.HOUR_OF_DAY, m / 60)
                cal.set(Calendar.MINUTE, m % 60)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis > after) return cal.timeInMillis
            }
        }
        return -1L
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(c)
        am.cancel(pi)
        val p = prefs(c)
        if (!p.getBoolean("active", false)) return
        val t = nextTime(c, System.currentTimeMillis() + 500)
        if (t < 0) {
            p.edit().putBoolean("active", false).apply()
            return
        }
        p.edit().putLong("next", t).apply()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }
}
