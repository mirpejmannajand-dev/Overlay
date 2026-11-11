package app.pejman.overlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private var files: List<File> = emptyList()
    private var startH = 9
    private var startM = 0

    private lateinit var permText: TextView
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var etInterval: EditText
    private lateinit var etTimes: EditText
    private lateinit var etDays: EditText
    private lateinit var etDur: EditText
    private lateinit var btnTime: Button
    private lateinit var cbRandom: CheckBox
    private lateinit var cbSound: CheckBox
    private lateinit var adapter: MediaAdapter
    private lateinit var list: ListView

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 15f
        v.setPadding(0, dp(12), 0, dp(2))
        return v
    }

    private fun field(value: Int): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value.toString())
        return e
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun num(e: EditText, def: Int, min: Int, max: Int): Int {
        val s = e.text.toString().map { ch ->
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        }.joinToString("")
        val n = s.trim().toIntOrNull() ?: def
        return n.coerceIn(min, max)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val p = Core.prefs(this)
        startH = p.getInt("startH", 9)
        startM = p.getInt("startM", 0)

        val h = LinearLayout(this)
        h.orientation = LinearLayout.VERTICAL
        h.setPadding(dp(16), dp(28), dp(16), dp(16))
        h.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val title = TextView(this)
        title.text = "نمایشگر تصویر روی صفحه"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        h.addView(title)

        permText = label("")
        h.addView(permText)
        h.addView(button("فعال‌سازی اجازه نمایش روی سایر برنامه‌ها") { openOverlaySettings() })
        h.addView(button("خاموش کردن بهینه‌سازی باتری برای این اپ") { openBatterySettings() })

        h.addView(label("فاصله بین دو نمایش (دقیقه)"))
        etInterval = field(p.getInt("interval", 60))
        h.addView(etInterval)

        h.addView(label("تعداد دفعات نمایش در هر روز"))
        etTimes = field(p.getInt("times", 3))
        h.addView(etTimes)

        h.addView(label("تعداد روزها"))
        etDays = field(p.getInt("days", 7))
        h.addView(etDays)

        h.addView(label("مدت ماندن روی صفحه (ثانیه)"))
        etDur = field(p.getInt("dur", 10))
        h.addView(etDur)

        btnTime = button("") {
            TimePickerDialog(
                this,
                TimePickerDialog.OnTimeSetListener { _, hh, mm ->
                    startH = hh
                    startM = mm
                    updateTimeButton()
                },
                startH, startM, true
            ).show()
        }
        h.addView(label("ساعت شروع نمایش در هر روز"))
        h.addView(btnTime)
        updateTimeButton()

        cbRandom = CheckBox(this)
        cbRandom.text = "نمایش به‌صورت تصادفی (در غیر این صورت به ترتیب)"
        cbRandom.isChecked = p.getBoolean("random", false)
        h.addView(cbRandom)

        cbSound = CheckBox(this)
        cbSound.text = "پخش صدای کلیپ‌ها"
        cbSound.isChecked = p.getBoolean("sound", false)
        h.addView(cbSound)

        statusText = label("")
        h.addView(statusText)
        h.addView(button("شروع برنامه‌ی نمایش") { startSchedule() })
        h.addView(button("توقف") { stopSchedule() })
        h.addView(button("نمایش آزمایشی همین الان") { testNow() })

        val sep = label("عکس‌ها و کلیپ‌ها")
        sep.textSize = 18f
        sep.setTypeface(null, Typeface.BOLD)
        sep.setPadding(0, dp(24), 0, dp(4))
        h.addView(sep)
        h.addView(button("افزودن عکس / کلیپ از گالری") { pick() })
        countText = label("")
        h.addView(countText)

        list = ListView(this)
        list.layoutDirection = View.LAYOUT_DIRECTION_RTL
        list.addHeaderView(h, null, false)
        adapter = MediaAdapter()
        list.adapter = adapter
        setContentView(list)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun updateTimeButton() {
        btnTime.text = String.format(Locale.US, "%02d:%02d", startH, startM)
    }

    private fun saveFields() {
        Core.prefs(this).edit()
            .putInt("interval", num(etInterval, 60, 1, 1440))
            .putInt("times", num(etTimes, 3, 1, 500))
            .putInt("days", num(etDays, 7, 1, 3650))
            .putInt("dur", num(etDur, 10, 1, 3600))
            .putInt("startH", startH)
            .putInt("startM", startM)
            .putBoolean("random", cbRandom.isChecked)
            .putBoolean("sound", cbSound.isChecked)
            .apply()
    }

    private fun updateStatus() {
        permText.text = if (Settings.canDrawOverlays(this))
            "اجازه نمایش روی سایر برنامه‌ها: فعال ✓"
        else
            "اجازه نمایش روی سایر برنامه‌ها: غیرفعال ✗ (دکمه‌ی زیر را بزن)"
        val p = Core.prefs(this)
        statusText.text = if (p.getBoolean("active", false)) {
            "وضعیت: فعال — نمایش بعدی: " +
                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(p.getLong("next", 0L)))
        } else {
            "وضعیت: غیرفعال"
        }
    }

    private fun refresh() {
        files = Core.mediaList(this)
        adapter.notifyDataSetChanged()
        countText.text = "تعداد عکس و کلیپ ذخیره‌شده: " + files.size
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startSchedule() {
        if (!Settings.canDrawOverlays(this)) {
            toast("اول اجازه‌ی نمایش روی سایر برنامه‌ها را فعال کن")
            openOverlaySettings()
            return
        }
        if (files.isEmpty()) {
            toast("اول حداقل یک عکس یا کلیپ اضافه کن")
            return
        }
        saveFields()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        Core.prefs(this).edit()
            .putLong("baseDay", cal.timeInMillis)
            .putBoolean("active", true)
            .apply()
        Core.schedule(this)
        if (!Core.prefs(this).getBoolean("active", false)) {
            toast("طبق این تنظیمات نوبتی باقی نمانده (ساعت شروع امروز گذشته یا تنظیمات درست نیست)")
        }
        updateStatus()
    }

    private fun stopSchedule() {
        Core.prefs(this).edit().putBoolean("active", false).apply()
        Core.schedule(this)
        updateStatus()
    }

    private fun testNow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("اول اجازه‌ی نمایش روی سایر برنامه‌ها را فعال کن")
            return
        }
        if (files.isEmpty()) {
            toast("اول حداقل یک عکس یا کلیپ اضافه کن")
            return
        }
        saveFields()
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, 10)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
        } else {
            val single = data.data
            if (single != null) uris.add(single)
        }
        if (uris.isEmpty()) return
        toast("در حال ذخیره‌ی " + uris.size + " فایل... کمی صبر کن")
        Thread {
            var n = 0
            var ok = 0
            for (u in uris) {
                try {
                    val mime = contentResolver.getType(u) ?: ""
                    val video = mime.startsWith("video")
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        ?: (if (video) "mp4" else "jpg")
                    val name = (if (video) "v_" else "i_") + System.currentTimeMillis() + "_" + n + "." + ext
                    n++
                    val dest = File(Core.mediaDir(this), name)
                    val input = contentResolver.openInputStream(u) ?: continue
                    input.use { inp ->
                        FileOutputStream(dest).use { out -> inp.copyTo(out) }
                    }
                    ok++
                } catch (e: Exception) {
                    // skip the broken file
                }
            }
            runOnUiThread {
                refresh()
                toast("$ok فایل ذخیره شد")
            }
        }.start()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setMessage("این مورد حذف شود؟")
            .setPositiveButton("حذف") { _, _ ->
                f.delete()
                refresh()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    inner class MediaAdapter : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int) = position.toLong()

        private fun buildRow(): LinearLayout {
            val r = LinearLayout(this@MainActivity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(16), dp(6), dp(16), dp(6))
            r.layoutDirection = View.LAYOUT_DIRECTION_RTL
            val iv = ImageView(this@MainActivity)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(Color.LTGRAY)
            r.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)))
            val tv = TextView(this@MainActivity)
            tv.setPadding(dp(12), 0, dp(12), 0)
            r.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val d = Button(this@MainActivity)
            d.text = "حذف"
            r.addView(
                d,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return r
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = files[position]
            val row = (convertView as? LinearLayout) ?: buildRow()
            val iv = row.getChildAt(0) as ImageView
            val tv = row.getChildAt(1) as TextView
            val del = row.getChildAt(2) as Button
            tv.text = (if (Core.isVideo(f)) "کلیپ " else "عکس ") + (position + 1)
            iv.setImageBitmap(null)
            iv.tag = f.path
            pool.execute {
                val bmp = Core.thumb(f, 160)
                ui.post {
                    if (iv.tag == f.path) iv.setImageBitmap(bmp)
                }
            }
            del.setOnClickListener { confirmDelete(f) }
            return row
        }
    }
}
