package app.pejman.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView
import java.io.File

/** Full-screen layer that closes on a double tap. */
class TouchRoot(c: Context, private val onDouble: () -> Unit) : FrameLayout(c) {
    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDouble()
            return true
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return true
    }
}

class OverlayService : Service() {

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        removeView()
        val files = Core.mediaList(this)
        if (files.isEmpty() || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val p = Core.prefs(this)
        val f: File
        if (p.getBoolean("random", false)) {
            f = files.random()
        } else {
            val idx = p.getInt("idx", 0) % files.size
            p.edit().putInt("idx", idx + 1).apply()
            f = files[idx]
        }
        show(f, p.getInt("dur", 10), p.getBoolean("sound", false))
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("ov", "نمایش تصویر", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ov")
            .setContentTitle("نمایش تصویر")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(1, n)
        }
    }

    private fun show(f: File, durSec: Int, sound: Boolean) {
        val root = TouchRoot(this) { close() }
        root.setBackgroundColor(0xE6000000.toInt())
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        if (Core.isVideo(f)) {
            val vv = VideoView(this)
            if (!sound) vv.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            vv.setVideoURI(Uri.fromFile(f))
            vv.setOnPreparedListener { mp ->
                mp.isLooping = true
                if (!sound) mp.setVolume(0f, 0f)
                mp.start()
            }
            vv.setOnErrorListener { _, _, _ ->
                close()
                true
            }
            root.addView(vv, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
        } else {
            val bmp = Core.decodeImage(f, 2048)
            if (bmp == null) {
                close()
                return
            }
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageBitmap(bmp)
            root.addView(iv, FrameLayout.LayoutParams(match, match))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(root, lp)
            view = root
            handler.postDelayed({ close() }, durSec * 1000L)
        } catch (e: Exception) {
            close()
        }
    }

    private fun removeView() {
        handler.removeCallbacksAndMessages(null)
        val v = view
        if (v != null) {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (e: Exception) {
                // already removed
            }
        }
        view = null
    }

    private fun close() {
        removeView()
        stopSelf()
    }

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }
}
