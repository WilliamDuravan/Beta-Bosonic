package com.example.bosondiag

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/** Level 1: live Y16 preview straight from the Boson over UVC bulk. */
class LiveActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var usb: UsbManager
    private lateinit var surface: SurfaceView
    private lateinit var statsView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var polBtn: Button

    private var streamer: UvcBulkStreamer? = null
    private var statsSource: UvcBulkStreamer? = null
    private var fps60 = true
    @Volatile private var invert = false

    private val surfaceLock = Any()
    private var surfaceReady = false

    private val bmp: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(W * H)
    private val vals = IntArray(W * H)
    private val hist = IntArray(16384)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var haveRange = false
    @Volatile private var lo = 0f
    @Volatile private var hi = 0f

    private val logLines = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var prevT = 0L
    private var prevOk = 0L
    private var prevBytes = 0L

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log("USB device detached")
            stopStream()
            updateButtons()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = streamer
            if (s != null && !s.isAlive) {
                streamer = null
                updateButtons()
            }
            updateStats()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startStream()
        ui.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(ticker)
        stopStream()
        unregisterReceiver(detachReceiver)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val act = this

        fun btn(label: String, onClick: () -> Unit): Button {
            val b = Button(act)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            return b
        }

        surface = SurfaceView(act)
        surface.holder.addCallback(act)

        statsView = TextView(act)
        statsView.typeface = Typeface.MONOSPACE
        statsView.textSize = 10f

        toggleBtn = btn("Stop") {
            if (streamer == null) startStream() else stopStream()
            updateButtons()
        }
        fpsBtn = btn("Rate: 60") {
            fps60 = !fps60
            if (streamer != null) {
                stopStream()
                startStream()
            }
            updateButtons()
        }
        polBtn = btn("White-hot") {
            invert = !invert
            updateButtons()
        }

        val row = LinearLayout(act)
        row.addView(toggleBtn)
        row.addView(fpsBtn)
        row.addView(polBtn)

        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(statsView)
        root.addView(row)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left + dp(8), b.top + dp(8), b.right + dp(8), b.bottom + dp(8))
            WindowInsetsCompat.CONSUMED
        }
        updateButtons()
    }

    private fun updateButtons() {
        toggleBtn.text = if (streamer == null) "Start" else "Stop"
        fpsBtn.text = if (fps60) "Rate: 60" else "Rate: 30"
        polBtn.text = if (invert) "Black-hot" else "White-hot"
    }

    private fun log(msg: String) {
        synchronized(logLines) {
            logLines.add(msg)
            while (logLines.size > 14) logLines.removeAt(0)
        }
    }

    private fun findDevice(): UsbDevice? {
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass == 14 && itf.interfaceSubclass == 2) return d
            }
        }
        return null
    }

    private fun startStream() {
        if (streamer != null) return
        val dev = findDevice()
        if (dev == null) {
            log("No UVC device attached")
            return
        }
        if (!usb.hasPermission(dev)) {
            log("No USB permission. Go back and tap 'Grant USB permission'.")
            return
        }
        haveRange = false
        prevT = 0L
        prevOk = 0L
        prevBytes = 0L
        log("starting Y16 320x256 @ " + (if (fps60) 60 else 30) + " fps")
        val s = UvcBulkStreamer(
            usb, dev,
            2, 1,
            if (fps60) 166666 else 333333,
            W * H * 2,
            { processFrame(it) },
            { log(it) }
        )
        streamer = s
        statsSource = s
        s.start()
    }

    private fun stopStream() {
        val s = streamer ?: return
        s.running = false
        try { s.join(2500) } catch (_: InterruptedException) {}
        streamer = null
    }

    // Runs on the streamer thread.
    private fun processFrame(f: ByteArray) {
        java.util.Arrays.fill(hist, 0)
        var j = 0
        val total = W * H
        for (p in 0 until total) {
            val v = (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
            j += 2
            vals[p] = v
            hist[v shr 2]++
        }
        val lowTarget = total / 100
        val highTarget = total - total / 100
        var acc = 0
        var loBin = 0
        var hiBin = hist.size - 1
        var gotLo = false
        for (b in hist.indices) {
            acc += hist[b]
            if (!gotLo && acc >= lowTarget) {
                loBin = b
                gotLo = true
            }
            if (acc >= highTarget) {
                hiBin = b
                break
            }
        }
        val loV = loBin * 4f
        val hiV = hiBin * 4f + 3f
        if (!haveRange) {
            lo = loV
            hi = hiV
            haveRange = true
        } else {
            lo += (loV - lo) * 0.1f
            hi += (hiV - hi) * 0.1f
        }
        val range = maxOf(hi - lo, 64f)
        val scale = 255f / range
        val inv = invert
        val l = lo
        for (p in 0 until total) {
            var g = ((vals[p] - l) * scale).toInt()
            if (g < 0) g = 0 else if (g > 255) g = 255
            if (inv) g = 255 - g
            pixels[p] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        bmp.setPixels(pixels, 0, W, 0, 0, W, H)
        drawBitmap()
    }

    private fun drawBitmap() {
        synchronized(surfaceLock) {
            if (!surfaceReady) return
            val holder = surface.holder
            val c = holder.lockCanvas() ?: return
            try {
                c.drawColor(Color.BLACK)
                val vw = c.width.toFloat()
                val vh = c.height.toFloat()
                val s = minOf(vw / W, vh / H)
                val dw = W * s
                val dh = H * s
                val left = (vw - dw) / 2f
                val top = (vh - dh) / 2f
                c.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }

    private fun updateStats() {
        val s = statsSource
        val sb = StringBuilder()
        if (s == null) {
            sb.appendLine("not started")
        } else {
            val now = SystemClock.elapsedRealtime()
            val ok = s.framesOk
            val bytes = s.bytesTotal
            var fps = 0.0
            var mbs = 0.0
            if (prevT != 0L && now > prevT) {
                val dt = (now - prevT) / 1000.0
                fps = (ok - prevOk) / dt
                mbs = (bytes - prevBytes) / dt / 1e6
            }
            prevT = now
            prevOk = ok
            prevBytes = bytes
            sb.appendLine(
                "state: " + (if (s.streaming) "STREAMING" else "stopped") +
                    String.format(Locale.US, "   %.1f fps   %.2f MB/s", fps, mbs)
            )
            sb.appendLine(
                "frames ok=$ok bad=${s.framesBad}  bad hdrs=${s.badHeaders}  read errs=${s.readErrors}"
            )
            sb.appendLine(String.format(Locale.US, "tone range lo=%.0f hi=%.0f", lo, hi))
        }
        sb.appendLine("--- log ---")
        synchronized(logLines) {
            for (line in logLines) sb.appendLine(line)
        }
        statsView.text = sb.toString()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surfaceReady = true }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surfaceReady = false }
    }

    companion object {
        private const val W = 320
        private const val H = 256
    }
}
