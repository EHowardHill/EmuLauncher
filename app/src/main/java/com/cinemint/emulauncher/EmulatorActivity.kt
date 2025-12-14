package com.cinemint.emulauncher

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream

class EmulatorActivity : AppCompatActivity(), SurfaceHolder.Callback {

    // --- JNI Methods ---
    external fun setInputState(buttonId: Int, pressed: Boolean)
    external fun loadCore(path: String): Boolean
    external fun loadGame(path: String): Boolean
    external fun runFrame(bitmap: Bitmap, audioBuffer: ShortArray): Int
    external fun getCoreSampleRate(): Double

    // --- Audio & Video ---
    private var audioTrack: AudioTrack? = null
    // 66117 was your hardcoded rate, but we will calculate the exact sync rate dynamically
    private val emuBitmap = createBitmap(240, 160, Bitmap.Config.RGB_565)
    private val audioBuffer = ShortArray(4096)

    private lateinit var surfaceView: SurfaceView
    private var gameThread: Thread? = null
    @Volatile private var isRunning = false
    private var coreIsReady = false

    companion object {
        init { System.loadLibrary("native-lib") }

        // Standard Libretro Button Map
        const val BUTTON_B = 0
        const val BUTTON_Y = 1
        const val BUTTON_SELECT = 2
        const val BUTTON_START = 3
        const val BUTTON_UP = 4
        const val BUTTON_DOWN = 5
        const val BUTTON_LEFT = 6
        const val BUTTON_RIGHT = 7
        const val BUTTON_A = 8
        const val BUTTON_X = 9
        const val BUTTON_L = 10
        const val BUTTON_R = 11
        const val BUTTON_L2 = 12
        const val BUTTON_R2 = 13
        const val BUTTON_L3 = 14
        const val BUTTON_R3 = 15
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.emulator_activity)

        surfaceView = findViewById(R.id.emu_surface)
        surfaceView.holder.addCallback(this)

        // 1. Get Data from Intent (Passed from MainActivity)
        val romUri: Uri? = intent.data
        val romName: String = intent.getStringExtra("FILE_NAME") ?: "rom.gba"

        // 2. Check for physical controllers
        if (!hasPhysicalController()) {
            setupGbaTouchControls()
        } else {
            // Hide touch controls if physical controller is present
            findViewById<View>(R.id.touch_overlay).visibility = View.GONE
        }

        // 3. Start setup in a background thread (File I/O is heavy)
        Thread { setupEmulator(romUri, romName) }.start()
    }

    // --- Controller Detection ---
    private fun hasPhysicalController(): Boolean {
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            val sources = dev.sources

            // Check for Gamepad or Joystick
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

            if (isGamepad || isJoystick) {
                return true
            }
        }
        return false
    }

    // --- Touch Control Setup (Multitouch Support) ---
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGbaTouchControls() {
        val overlay = findViewById<View>(R.id.touch_overlay)
        overlay.visibility = View.VISIBLE

        // 1. Standard Bind for standalone buttons
        fun bind(viewId: Int, emuBtn: Int) {
            findViewById<View>(viewId).setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        setInputState(emuBtn, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        setInputState(emuBtn, false)
                    }
                }
                true
            }
        }

        bind(R.id.btn_a, BUTTON_A)
        bind(R.id.btn_b, BUTTON_B)
        bind(R.id.btn_start, BUTTON_START)
        bind(R.id.btn_select, BUTTON_SELECT)
        bind(R.id.btn_l, BUTTON_L)
        bind(R.id.btn_r, BUTTON_R)

        // 2. D-Pad Multitouch Logic
        val dpadContainer = findViewById<View>(R.id.dpad_container)
        val btnUp = findViewById<View>(R.id.btn_up)
        val btnDown = findViewById<View>(R.id.btn_down)
        val btnLeft = findViewById<View>(R.id.btn_left)
        val btnRight = findViewById<View>(R.id.btn_right)

        // Helper: Checks if coordinates are inside a view
        fun isTouchInsideView(view: View, x: Float, y: Float): Boolean {
            val rect = Rect()
            view.getHitRect(rect)
            return rect.contains(x.toInt(), y.toInt())
        }

        // State tracking
        var lastUp = false
        var lastDown = false
        var lastLeft = false
        var lastRight = false

        dpadContainer.setOnTouchListener { _, event ->
            var isUp = false
            var isDown = false
            var isLeft = false
            var isRight = false

            val action = event.actionMasked
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
                // Iterate through all pointers (fingers)
                for (i in 0 until event.pointerCount) {
                    if (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue

                    val x = event.getX(i)
                    val y = event.getY(i)

                    if (isTouchInsideView(btnUp, x, y)) isUp = true
                    if (isTouchInsideView(btnDown, x, y)) isDown = true
                    if (isTouchInsideView(btnLeft, x, y)) isLeft = true
                    if (isTouchInsideView(btnRight, x, y)) isRight = true
                }
            }

            // Update State & JNI
            if (isUp != lastUp) {
                btnUp.isPressed = isUp
                setInputState(BUTTON_UP, isUp)
                lastUp = isUp
            }
            if (isDown != lastDown) {
                btnDown.isPressed = isDown
                setInputState(BUTTON_DOWN, isDown)
                lastDown = isDown
            }
            if (isLeft != lastLeft) {
                btnLeft.isPressed = isLeft
                setInputState(BUTTON_LEFT, isLeft)
                lastLeft = isLeft
            }
            if (isRight != lastRight) {
                btnRight.isPressed = isRight
                setInputState(BUTTON_RIGHT, isRight)
                lastRight = isRight
            }

            true
        }
    }

    // --- Emulator Setup (File Copy + Core Loading) ---
    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupEmulator(romUri: Uri?, romName: String) {
        // Define target file in internal cache
        val internalFile = File(cacheDir, romName)

        try {
            // 1. Copy file from Content URI to Cache
            if (romUri != null) {
                contentResolver.openInputStream(romUri)?.use { input ->
                    FileOutputStream(internalFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else if (!internalFile.exists()) {
                // Fallback: Legacy res/raw extraction
                println("No URI provided. Checking for legacy raw resource...")
                val rawResName = internalFile.nameWithoutExtension.lowercase().replace("[^a-z0-9_]".toRegex(), "_")
                val resourceId = resources.getIdentifier(rawResName, "raw", packageName)
                if (resourceId != 0) {
                    resources.openRawResource(resourceId).use { input ->
                        internalFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            if (!internalFile.exists()) {
                println("Error: ROM file could not be created or found.")
                return
            }

            // 2. Select Core based on Extension
            val fileName = internalFile.name
            val coreLibName = when {
                // Nintendo
                fileName.endsWith(".nes", true) || fileName.endsWith(".fds", true) -> "libfceumm.so"
                fileName.endsWith(".sfc", true) || fileName.endsWith(".smc", true) -> "libsnes9x.so"
                fileName.endsWith(".gb", true) || fileName.endsWith(".gbc", true) || fileName.endsWith(".dmg", true) -> "libgambatte.so"
                fileName.endsWith(".gba", true) -> "libmgba.so"
                fileName.endsWith(".n64", true) || fileName.endsWith(".z64", true) -> "libmupen64plus_next.so"
                fileName.endsWith(".nds", true) -> "libmelonds.so"
                fileName.endsWith(".vb", true) -> "libmednafen_vb.so"

                // Sega
                fileName.endsWith(".sms", true) || fileName.endsWith(".gg", true) || fileName.endsWith(".md", true) || fileName.endsWith(".gen", true) -> "libgenesis_plus_gx.so"
                fileName.endsWith(".gdi", true) || fileName.endsWith(".cdi", true) -> "libflycast.so"
                fileName.endsWith(".sat", true) -> "libmednafen_saturn.so"

                // Sony
                fileName.endsWith(".pbp", true) || fileName.endsWith(".chd", true) -> "libswanstation.so"
                fileName.endsWith(".iso", true) || fileName.endsWith(".cso", true) -> "libppsspp.so"

                // Arcade/Other
                fileName.endsWith(".zip", true) || fileName.endsWith(".7z", true) -> "libfbneo.so"
                fileName.endsWith(".pce", true) || fileName.endsWith(".sgx", true) -> "libmednafen_pce_fast.so"
                fileName.endsWith(".a26", true) -> "libstella.so"
                fileName.endsWith(".ws", true) || fileName.endsWith(".wsc", true) -> "libmednafen_wswan.so"
                fileName.endsWith(".ngp", true) || fileName.endsWith(".ngc", true) -> "libmednafen_ngp.so"

                // Fallback
                fileName.endsWith(".cue", true) || fileName.endsWith(".bin", true) -> "libswanstation.so"

                else -> {
                    println("Error: Unknown ROM type for $fileName")
                    return
                }
            }

            val corePath = applicationInfo.nativeLibraryDir + "/" + coreLibName

            println("ROM Path: ${internalFile.absolutePath}")
            println("Core Path: $corePath")

            // 3. Load JNI
            if (loadCore(corePath) && loadGame(internalFile.absolutePath)) {
                coreIsReady = true
                val holder = surfaceView.holder
                if (holder.surface.isValid) {
                    startGameLoop(holder)
                }
            } else {
                println("Failed to load core ($coreLibName) or game.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- SurfaceHolder Callbacks ---
    @RequiresApi(Build.VERSION_CODES.O)
    override fun surfaceCreated(holder: SurfaceHolder) {
        holder.setFixedSize(240, 160)
        if (coreIsReady) {
            startGameLoop(holder)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopGameLoop()
    }

    // --- Game Loop (Audio/Video Sync) ---
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startGameLoop(holder: SurfaceHolder) {
        if (isRunning) return
        isRunning = true

        // 1. Get native core rate (e.g., 32768 or 44100)
        val coreBaseRate = getCoreSampleRate()

        // 2. Calculate Sync Rate
        // CoreRate * (AndroidScreenHz / EmuHz)
        // Usually: CoreRate * (60.0 / 59.97) -> speeds up audio slightly to fill buffer
        val syncSampleRate = (coreBaseRate * (60.0 / 59.7275)).toInt()

        println("Core Rate: $coreBaseRate | Synced Rate: $syncSampleRate")

        // 3. Init AudioTrack
        val minBufferSize = AudioTrack.getMinBufferSize(
            syncSampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(syncSampleRate) // Use calculated rate!
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()

        gameThread = Thread {
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()

            while (isRunning) {
                // 1. Run Core Frame
                val samplesGenerated = runFrame(emuBitmap, audioBuffer)

                // 2. Draw Video
                val canvas: Canvas? = holder.lockCanvas()
                if (canvas != null) {
                    try {
                        // Draw 1:1 or scaled by SurfaceView automatically
                        canvas.drawBitmap(emuBitmap, 0f, 0f, null)

                        // FPS Calculation (Optional logging)
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            val fps = frameCount * 1000.0 / (now - lastFpsTime)
                            println("FPS: $fps")
                            frameCount = 0
                            lastFpsTime = now
                        }
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }

                // 3. Play Audio
                if (samplesGenerated > 0) {
                    // Blocking write ensures we don't run too fast (Dynamic Rate Control)
                    audioTrack?.write(audioBuffer, 0, samplesGenerated)
                }

                Thread.yield()
            }

            // Cleanup
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

        }.apply { start() }
    }

    private fun stopGameLoop() {
        isRunning = false
        try { gameThread?.join() } catch (e: InterruptedException) {}
        gameThread = null
    }

    // --- Physical Controller Handling ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN

        val retroId = when (event.keyCode) {
            // Face Buttons
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_BUTTON_A -> BUTTON_A
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_BUTTON_B -> BUTTON_B
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_BUTTON_X -> BUTTON_X
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_BUTTON_Y -> BUTTON_Y

            // Shoulders
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_BUTTON_L1 -> BUTTON_L
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_BUTTON_R1 -> BUTTON_R
            KeyEvent.KEYCODE_BUTTON_L2 -> BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R2 -> BUTTON_R2

            // System
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_START -> BUTTON_START
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BUTTON_SELECT -> BUTTON_SELECT

            // D-Pad
            KeyEvent.KEYCODE_DPAD_UP -> BUTTON_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BUTTON_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BUTTON_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BUTTON_RIGHT

            else -> return super.dispatchKeyEvent(event)
        }

        setInputState(retroId, isDown)
        return true
    }
}