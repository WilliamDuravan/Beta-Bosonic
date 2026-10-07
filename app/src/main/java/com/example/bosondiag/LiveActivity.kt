package com.example.bosondiag

import android.app.Activity
import android.app.AlertDialog
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Live Y16 preview + MP4 recording + FSLP serial commands.
 * Threads: USB reader (copies frames only) -> render thread (display) and record thread (encoder),
 * each with its own tone mapper so neither can stall USB reads or each other.
 */
class LiveActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var usb: UsbManager
    private lateinit var surface: SurfaceView
    private lateinit var statsView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var palBtn: Button
    private lateinit var minBtn: Button
    private lateinit var fpnBtn: Button
    private lateinit var rawBtn: Button
    private lateinit var recBtn: Button

    private var streamer: UvcBulkStreamer? = null
    private var statsSource: UvcBulkStreamer? = null
    private var fps60 = true
    @Volatile private var paletteIdx = 0
    @Volatile private var minRange = 64f
    @Volatile private var fpnEnabled = true
    @Volatile private var fpnOffset: IntArray? = null
    @Volatile private var calSum: IntArray? = null
    @Volatile private var calRemaining = 0
    private val minRangeChoices = listOf(64f, 150f, 300f, 600f)

    // Tone curve.
    @Volatile private var curveEnabled = false
    private var curveT = ToneCurve.DEF_T
    private var curveM = ToneCurve.DEF_M
    private var curveS = ToneCurve.DEF_S
    @Volatile private var curveLut: IntArray? = null
    private lateinit var toneView: ToneCurveView
    private lateinit var curveBtn: Button
    private val toneHist = IntArray(ToneMapper.HBINS)

    private val surfaceLock = Any()
    private var surfaceReady = false

    // Frame hand-off from USB thread.
    private val frameLock = ReentrantLock()
    private val frameCond = frameLock.newCondition()
    private val latest = ByteArray(W * H * 2)
    private var seq = 0L

    // Display.
    private var renderThread: Thread? = null
    @Volatile private var renderRunning = false
    @Volatile private var renderedFrames = 0L
    @Volatile private var pendingSnap = false
    private val bmp: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(W * H)
    private val mapper = ToneMapper(W, H, true)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Recording.
    private var recThread: Thread? = null
    @Volatile private var recRunning = false
    @Volatile private var recorder: Mp4Recorder? = null
    @Volatile private var recFrames = 0L
    private var recStartMs = 0L

    // Serial.
    private val serialExec = Executors.newSingleThreadExecutor()
    private var serial: BosonSerial? = null

    // Raw recording.
    @Volatile private var rawRec: RawRecorder? = null
    private var rawStartMs = 0L

    private val logLines = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var prevT = 0L
    private var prevOk = 0L
    private var prevBytes = 0L
    private var prevRendered = 0L

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log("USB device detached")
            stopStream()
            closeSerial()
            updateButtons()
        }
    }

    private val toneTicker = object : Runnable {
        override fun run() {
            if (curveEnabled) {
                mapper.copyHistogram(toneHist)
                toneView.setData(toneHist, mapper.rangeBase, mapper.rangeSpan)
            }
            ui.postDelayed(this, 150)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val rr = rawRec
            if (rr != null && (rr.lowSpace || rr.writeError != null)) {
                log(
                    if (rr.lowSpace) "raw recording stopped: storage nearly full"
                    else "raw recording stopped: write error ${rr.writeError}"
                )
                stopRawRecording()
                updateButtons()
            }
            val s = streamer
            if (s != null && !s.isAlive) {
                streamer = null
                stopRawRecording()
                stopRecording()
                stopRender()
                updateButtons()
            }
            val rt = recThread
            if (rt != null && !rt.isAlive) {
                recThread = null
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
        loadTone()
        rebuildCurve()
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
        ui.post(toneTicker)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(toneTicker)
        stopStream()
        closeSerial()
        unregisterReceiver(detachReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        serialExec.shutdown()
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
        palBtn = btn("White-hot") {
            paletteIdx = (paletteIdx + 1) % Palettes.names.size
            saveTone()
            updateButtons()
        }
        val snapBtn = btn("Snap") {
            if (streamer == null) {
                Toast.makeText(act, "Start streaming first", Toast.LENGTH_SHORT).show()
            } else {
                pendingSnap = true
            }
        }
        recBtn = btn("Rec") {
            if (recThread == null) startRecording() else stopRecording()
            updateButtons()
        }
        val ffcBtn = btn("FFC") {
            runCommand("FFC", BosonSerial.FN_RUN_FFC, ByteArray(0)) {
                if (fpnOffset != null) {
                    fpnOffset = null
                    log("FFC changed the camera's offsets: software FPN correction cleared, recalibrate")
                    runOnUiThread { updateButtons() }
                }
            }
        }
        val snBtn = btn("Serial #") {
            runCommand("Get serial number", BosonSerial.FN_GET_CAMERA_SN, ByteArray(0))
        }
        val consoleBtn = btn("Console") { showConsole() }

        rawBtn = btn("Raw rec") {
            if (rawRec == null) startRawRecording() else stopRawRecording()
            updateButtons()
        }
        toneView = ToneCurveView(act)
        toneView.t = curveT
        toneView.m = curveM
        toneView.s = curveS
        toneView.visibility = if (curveEnabled) View.VISIBLE else View.GONE
        toneView.onChange = {
            curveT = toneView.t
            curveM = toneView.m
            curveS = toneView.s
            rebuildCurve()
            saveTone()
        }
        curveBtn = btn("Curve: off") {
            curveEnabled = !curveEnabled
            rebuildCurve()
            toneView.visibility = if (curveEnabled) View.VISIBLE else View.GONE
            saveTone()
            updateButtons()
        }
        val resetCurveBtn = btn("Reset curve") { toneView.resetHandles() }
        val calBtn = btn("Cal FPN") { startCalibration() }
        fpnBtn = btn("FPN: none") {
            if (fpnOffset == null) {
                Toast.makeText(act, "Run Cal FPN first", Toast.LENGTH_SHORT).show()
            } else {
                fpnEnabled = !fpnEnabled
            }
            updateButtons()
        }
        minBtn = btn("Min range: 64") {
            val i = minRangeChoices.indexOf(minRange)
            minRange = minRangeChoices[(i + 1) % minRangeChoices.size]
            saveTone()
            updateButtons()
        }

        val row1 = LinearLayout(act)
        row1.addView(toggleBtn)
        row1.addView(fpsBtn)
        row1.addView(palBtn)
        row1.addView(snapBtn)

        val row2 = LinearLayout(act)
        row2.addView(recBtn)
        row2.addView(ffcBtn)
        row2.addView(snBtn)
        row2.addView(consoleBtn)

        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(statsView)
        root.addView(toneView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)))
        root.addView(row1)
        root.addView(row2)
        val row3 = LinearLayout(act)
        row3.addView(calBtn)
        row3.addView(fpnBtn)
        row3.addView(minBtn)
        row3.addView(rawBtn)
        root.addView(row3)
        val row4 = LinearLayout(act)
        row4.addView(curveBtn)
        row4.addView(resetCurveBtn)
        root.addView(row4)
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
        palBtn.text = Palettes.names[paletteIdx]
        minBtn.text = "Min range: " + minRange.toInt()
        rawBtn.text = if (rawRec == null) "Raw rec" else "Stop raw"
        curveBtn.text = if (curveEnabled) "Curve: on" else "Curve: off"
        fpnBtn.text = "FPN: " + (if (fpnOffset == null) "none" else if (fpnEnabled) "on" else "off")
        recBtn.text = if (recThread == null) "Rec" else "Stop rec"
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

    // ---------------------------------------------------------------- streaming

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
        mapper.reset()
        frameLock.withLock { seq = 0L }
        prevT = 0L
        prevOk = 0L
        prevBytes = 0L
        prevRendered = 0L
        renderedFrames = 0L
        log("starting Y16 320x256 @ " + (if (fps60) 60 else 30) + " fps")
        val s = UvcBulkStreamer(
            usb, dev,
            2, 1,
            if (fps60) 166666 else 333333,
            W * H * 2,
            { f, n, pts -> offerFrame(f, n, pts) },
            { log(it) }
        )
        streamer = s
        statsSource = s
        startRender()
        s.start()
    }

    private fun stopStream() {
        stopRawRecording()
        stopRecording()
        val s = streamer
        if (s != null) {
            s.running = false
            try { s.join(2500) } catch (_: InterruptedException) {}
            streamer = null
        }
        stopRender()
    }

    // USB thread: copy only.
    private fun offerFrame(f: ByteArray, usbFrame: Long, pts: Long) {
        val rr = rawRec
        if (rr != null) rr.offer(f, usbFrame, SystemClock.elapsedRealtimeNanos() / 1000, pts)
        val sum = calSum
        if (sum != null && calRemaining > 0) {
            var j = 0
            for (p in 0 until W * H) {
                sum[p] += (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
                j += 2
            }
            calRemaining -= 1
            if (calRemaining == 0) finishCalibration(sum)
        }
        frameLock.withLock {
            System.arraycopy(f, 0, latest, 0, latest.size)
            seq++
            frameCond.signalAll()
        }
    }

    /** Blocks until a frame newer than [lastSeq] exists; copies it to [dst]. Returns its seq, or -1 if no longer alive. */
    private fun awaitFrame(lastSeq: Long, dst: ByteArray, alive: () -> Boolean): Long {
        var result = -1L
        frameLock.withLock {
            while (alive() && seq == lastSeq) {
                try {
                    frameCond.await(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
            }
            if (alive()) {
                System.arraycopy(latest, 0, dst, 0, dst.size)
                result = seq
            }
        }
        return result
    }

    // ---------------------------------------------------------------- display

    private fun startRender() {
        if (renderThread != null) return
        renderRunning = true
        val t = Thread {
            val local = ByteArray(W * H * 2)
            var lastSeq = 0L
            while (renderRunning) {
                val s = awaitFrame(lastSeq, local) { renderRunning }
                if (s < 0) continue
                lastSeq = s
                mapper.map(local, Palettes.luts[paletteIdx], if (fpnEnabled) fpnOffset else null, minRange, curveLut, pixels)
                bmp.setPixels(pixels, 0, W, 0, 0, W, H)
                drawBitmap()
                renderedFrames++
                if (pendingSnap) {
                    pendingSnap = false
                    doSnapshot(local)
                }
            }
        }
        t.name = "render"
        renderThread = t
        t.start()
    }

    private fun stopRender() {
        val t = renderThread ?: return
        renderRunning = false
        frameLock.withLock { frameCond.signalAll() }
        try { t.join(2000) } catch (_: InterruptedException) {}
        renderThread = null
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

    private fun doSnapshot(raw: ByteArray) {
        val rawCopy = raw.copyOf()
        val bmpCopy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val appCtx = applicationContext
        Thread {
            try {
                val name = Capture.saveSnapshot(appCtx, rawCopy, W, H, bmpCopy)
                log("saved Pictures/BosonThermal/$name (.png + _raw16.tif)")
                runOnUiThread {
                    Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                log("snapshot failed: $e")
            }
        }.start()
    }

    // ---------------------------------------------------------------- recording

    private fun startRecording() {
        if (recThread != null) return
        if (streamer == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        val rec = Mp4Recorder(applicationContext, 640, 512, if (fps60) 60 else 30)
        try {
            rec.start()
        } catch (e: Exception) {
            log("record start failed: $e")
            rec.abort()
            return
        }
        recorder = rec
        recFrames = 0L
        recStartMs = SystemClock.elapsedRealtime()
        recRunning = true
        log("recording -> Movies/BosonThermal/${rec.name}")
        val t = Thread {
            val local = ByteArray(W * H * 2)
            val px = IntArray(W * H)
            val rb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val recMapper = ToneMapper(W, H)
            var lastSeq = 0L
            try {
                while (recRunning) {
                    val s = awaitFrame(lastSeq, local) { recRunning }
                    if (s < 0) continue
                    lastSeq = s
                    recMapper.map(local, Palettes.luts[paletteIdx], if (fpnEnabled) fpnOffset else null, minRange, curveLut, px)
                    rb.setPixels(px, 0, W, 0, 0, W, H)
                    rec.addFrame(rb)
                    recFrames++
                }
            } catch (e: Exception) {
                log("recording error: $e")
            }
            val kept = rec.finish()
            log(
                if (kept) "recording saved: ${rec.name} (${rec.framesWritten} frames, " +
                    String.format(Locale.US, "%.1f MB)", rec.bytesWritten / 1e6)
                else "recording discarded (no frames written)"
            )
            recorder = null
        }
        t.name = "record"
        recThread = t
        t.start()
    }

    private fun stopRecording() {
        val t = recThread ?: return
        recRunning = false
        frameLock.withLock { frameCond.signalAll() }
        try { t.join(8000) } catch (_: InterruptedException) {}
        recThread = null
    }

    // ---------------------------------------------------------------- tone settings

    private fun rebuildCurve() {
        curveLut = if (curveEnabled) ToneCurve.buildLut(curveT, curveM, curveS) else null
    }

    private fun loadTone() {
        val p = getSharedPreferences("tone", Context.MODE_PRIVATE)
        paletteIdx = p.getInt("palette", 0).coerceIn(0, Palettes.names.size - 1)
        minRange = p.getFloat("minRange", 64f)
        curveEnabled = p.getBoolean("curveOn", false)
        curveT = p.getFloat("curveT", ToneCurve.DEF_T)
        curveM = p.getFloat("curveM", ToneCurve.DEF_M)
        curveS = p.getFloat("curveS", ToneCurve.DEF_S)
        if (!(curveT >= ToneCurve.T_MIN && curveT + ToneCurve.GAP <= curveM &&
                curveM + ToneCurve.GAP <= curveS && curveS <= ToneCurve.S_MAX)
        ) {
            curveT = ToneCurve.DEF_T
            curveM = ToneCurve.DEF_M
            curveS = ToneCurve.DEF_S
        }
    }

    private fun saveTone() {
        getSharedPreferences("tone", Context.MODE_PRIVATE).edit()
            .putInt("palette", paletteIdx)
            .putFloat("minRange", minRange)
            .putBoolean("curveOn", curveEnabled)
            .putFloat("curveT", curveT)
            .putFloat("curveM", curveM)
            .putFloat("curveS", curveS)
            .apply()
    }

    // ---------------------------------------------------------------- raw recording

    private fun startRawRecording() {
        if (rawRec != null) return
        val dev = findDevice()
        if (streamer == null || dev == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        val serialNo: String? = try { dev.serialNumber } catch (_: Exception) { null }
        val r = RawRecorder(applicationContext, W, H, if (fps60) 60 else 30, serialNo, filesDir)
        try {
            r.start()
        } catch (e: Exception) {
            log("raw start failed: $e")
            r.abort()
            return
        }
        rawStartMs = SystemClock.elapsedRealtime()
        rawRec = r
        log("raw recording -> Download/BosonThermal/${r.rawName} (+ .csv)")
    }

    private fun stopRawRecording() {
        val r = rawRec ?: return
        rawRec = null
        Thread {
            val kept = r.finish()
            log(
                if (kept) String.format(
                    Locale.US, "raw saved: %s, %d frames, %.0f MB, %d dropped in app",
                    r.rawName, r.framesWritten, r.bytesWritten / 1e6, r.framesDropped
                ) else "raw recording discarded (no frames)"
            )
            runOnUiThread { updateButtons() }
        }.start()
    }

    // ---------------------------------------------------------------- FPN calibration

    private fun startCalibration() {
        if (streamer == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Calibrate fixed-pattern noise")
            .setMessage(
                "1. Optional: run FFC first and let it finish.\n" +
                    "2. Cover the lens completely with a uniform surface (lens cap, or cardboard pressed against the lens).\n" +
                    "3. Hold still and tap Start. It averages $CAL_FRAMES frames (about 1 second).\n\n" +
                    "Recalibrate after any FFC (manual or automatic) or whenever the pattern comes back."
            )
            .setPositiveButton("Start") { _, _ ->
                calSum = IntArray(W * H)
                calRemaining = CAL_FRAMES
                log("FPN calibration: averaging $CAL_FRAMES frames...")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Runs on the USB thread.
    private fun finishCalibration(sum: IntArray) {
        val n = CAL_FRAMES
        var tot = 0L
        for (v in sum) tot += v
        val mean = tot.toDouble() / (n.toDouble() * sum.size)
        val off = IntArray(sum.size)
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        var sq = 0.0
        for (p in sum.indices) {
            val d = Math.round(sum[p].toDouble() / n - mean).toInt()
            off[p] = d
            if (d < mn) mn = d
            if (d > mx) mx = d
            sq += d.toDouble() * d
        }
        val rms = Math.sqrt(sq / sum.size)
        fpnOffset = off
        fpnEnabled = true
        calSum = null
        log(
            String.format(
                Locale.US, "FPN cal done: offsets %d..%d counts, rms %.1f, mean level %.0f", mn, mx, rms, mean
            )
        )
        if (rms > 150.0) log("WARNING: large offsets, lens probably not fully covered or target not uniform")
        runOnUiThread { updateButtons() }
    }

    // ---------------------------------------------------------------- serial (FSLP)

    private fun runCommand(label: String, fn: Int, data: ByteArray, onOk: (() -> Unit)? = null) {
        val dev = findDevice()
        if (dev == null) {
            log("No device attached")
            return
        }
        if (!usb.hasPermission(dev)) {
            log("No USB permission")
            return
        }
        var s = serial
        if (s == null) {
            s = BosonSerial(usb, dev)
            serial = s
        }
        val ser = s
        serialExec.execute {
            val r = ser.transact(fn, data)
            log("$label: " + BosonSerial.describe(r))
            if (r.ok && onOk != null) onOk()
        }
    }

    private fun closeSerial() {
        val s = serial ?: return
        serial = null
        try {
            serialExec.execute { s.close() }
        } catch (_: Exception) {
        }
    }

    private fun parseHexBytes(text: String): ByteArray {
        val tokens = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val out = ByteArray(tokens.size)
        for ((i, tk) in tokens.withIndex()) {
            val t = tk.removePrefix("0x").removePrefix("0X").removePrefix("x").removePrefix("X")
            out[i] = t.toInt(16).toByte()
        }
        return out
    }

    private fun showConsole() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(0))
        val fn = EditText(this)
        fn.hint = "Function ID (hex), e.g. 00050007"
        fn.setSingleLine()
        val data = EditText(this)
        data.hint = "Data bytes (hex, optional), e.g. 00 00 00 01"
        data.setSingleLine()
        box.addView(fn)
        box.addView(data)
        AlertDialog.Builder(this)
            .setTitle("Send FSLP command")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                try {
                    val id = fn.text.toString().trim().removePrefix("0x").removePrefix("0X").toLong(16).toInt()
                    val bytes = parseHexBytes(data.text.toString())
                    runCommand(String.format(Locale.US, "cmd 0x%08X", id), id, bytes)
                } catch (e: Exception) {
                    log("bad console input: $e")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- stats

    private fun updateStats() {
        val s = statsSource
        val sb = StringBuilder()
        if (s == null) {
            sb.appendLine("not started")
        } else {
            val now = SystemClock.elapsedRealtime()
            val ok = s.framesOk
            val bytes = s.bytesTotal
            val rendered = renderedFrames
            var fps = 0.0
            var mbs = 0.0
            var dfps = 0.0
            if (prevT != 0L && now > prevT) {
                val dt = (now - prevT) / 1000.0
                fps = (ok - prevOk) / dt
                mbs = (bytes - prevBytes) / dt / 1e6
                dfps = (rendered - prevRendered) / dt
            }
            prevT = now
            prevOk = ok
            prevBytes = bytes
            prevRendered = rendered
            sb.appendLine(
                "state: " + (if (s.streaming) "STREAMING" else "stopped") +
                    String.format(Locale.US, "   USB %.1f fps   %.2f MB/s   display %.1f fps", fps, mbs, dfps)
            )
            sb.appendLine(
                "frames ok=$ok bad=${s.framesBad}  bad hdrs=${s.badHeaders}  read errs=${s.readErrors}"
            )
            sb.appendLine(String.format(Locale.US, "tone range lo=%.0f hi=%.0f", mapper.lo, mapper.hi))
            if (calRemaining > 0) sb.appendLine("calibrating... $calRemaining frames left")
        }
        val rec = recorder
        if (recThread != null && rec != null) {
            val secs = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            sb.appendLine(
                String.format(
                    Locale.US, "REC %02d:%02d  fed=%d  encoded=%d  %.1f MB",
                    secs / 60, secs % 60, recFrames, rec.framesWritten, rec.bytesWritten / 1e6
                )
            )
        }
        val rr = rawRec
        if (rr != null) {
            val secs = (SystemClock.elapsedRealtime() - rawStartMs) / 1000
            val bytesPerSec = W * H * 2.0 * (if (fps60) 60 else 30)
            val freeBytes = filesDir.usableSpace.toDouble()
            sb.appendLine(
                String.format(
                    Locale.US,
                    "RAW %02d:%02d  written=%d (%.0f MB)  dropped=%d  queued=%d  free %.1f GB (~%.0f min)",
                    secs / 60, secs % 60, rr.framesWritten, rr.bytesWritten / 1e6,
                    rr.framesDropped, rr.queued(), freeBytes / 1e9, freeBytes / bytesPerSec / 60.0
                )
            )
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
        private const val CAL_FRAMES = 64
    }
}
