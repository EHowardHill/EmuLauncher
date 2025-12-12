package com.cinemint.emulauncher

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import androidx.core.graphics.createBitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    // --- JNI & Constants ---
    external fun setInputState(buttonId: Int, pressed: Boolean)
    external fun loadCore(path: String): Boolean
    external fun loadGame(path: String): Boolean
    external fun runFrame(bitmap: Bitmap, audioBuffer: ShortArray): Int

    private var audioTrack: AudioTrack? = null
    private val sampleRate = 66117

    companion object {
        init { System.loadLibrary("native-lib") }
        const val BUTTON_B = 0; const val BUTTON_Y = 1; const val BUTTON_SELECT = 2
        const val BUTTON_START = 3; const val BUTTON_UP = 4; const val BUTTON_DOWN = 5
        const val BUTTON_LEFT = 6; const val BUTTON_RIGHT = 7; const val BUTTON_A = 8
        const val BUTTON_X = 9; const val BUTTON_L = 10; const val BUTTON_R = 11
    }

    private val emuBitmap = createBitmap(240, 160, Bitmap.Config.RGB_565)
    private val audioBuffer = ShortArray(4096)

    private lateinit var surfaceView: SurfaceView
    private var gameThread: Thread? = null
    @Volatile private var isRunning = false
    private var coreIsReady = false

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        surfaceView = findViewById(R.id.emu_surface)
        surfaceView.holder.addCallback(this)

        // 1. Determine layout based on file extension
        // (In your current code, you force the name "game.gba", so we check that)
        val internalFile = File(cacheDir, "game.gba")
        val extension = internalFile.extension.lowercase()

        // 2. Check for controllers/keyboards
        // !hasPhysicalController() &&
        if (extension == "gba") {
            setupGbaTouchControls()
        } else {
            // Hide controls if controller present or unknown extension
            findViewById<View>(R.id.touch_overlay).visibility = View.GONE
        }

        Thread { setupEmulator(internalFile) }.start()
    }

    // --- Controller Detection Logic ---
    private fun hasPhysicalController(): Boolean {
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            val sources = dev.sources

            // Check for Gamepad or Joystick
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

            // Check for full Keyboard (exclude virtual soft keyboards)
            val isKeyboard = (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                    dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

            if (isGamepad || isJoystick || isKeyboard) {
                return true
            }
        }
        return false
    }

    // --- Touch Control Setup ---
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGbaTouchControls() {
        val overlay = findViewById<View>(R.id.touch_overlay)
        overlay.visibility = View.VISIBLE

        // 1. Standard Bind for standalone buttons (A, B, Start, Select, L, R)
        // These don't need sliding logic usually, so we keep them simple.
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

        // Helper: Checks if a generic (x,y) coordinate is inside a child view
        fun isTouchInsideView(view: View, x: Float, y: Float): Boolean {
            val rect = Rect()
            view.getHitRect(rect) // Gets coordinates relative to the parent (dpadContainer)
            return rect.contains(x.toInt(), y.toInt())
        }

        // State tracking to prevent JNI spam
        var lastUp = false
        var lastDown = false
        var lastLeft = false
        var lastRight = false

        dpadContainer.setOnTouchListener { _, event ->
            // 1. Reset "Current Frame" inputs
            var isUp = false
            var isDown = false
            var isLeft = false
            var isRight = false

            // 2. Loop through ALL active fingers (pointers)
            val action = event.actionMasked
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
                for (i in 0 until event.pointerCount) {
                    // Skip this pointer if it's the one currently being lifted up
                    if (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue

                    // Get coordinates relative to the D-Pad Container
                    val x = event.getX(i)
                    val y = event.getY(i)

                    // Check which button this specific finger is touching
                    if (isTouchInsideView(btnUp, x, y)) isUp = true
                    if (isTouchInsideView(btnDown, x, y)) isDown = true
                    if (isTouchInsideView(btnLeft, x, y)) isLeft = true
                    if (isTouchInsideView(btnRight, x, y)) isRight = true
                }
            }

            // 3. Update Visuals & JNI (Only if state changed)
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

            true // Consume the event
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupEmulator(internalFile: File) {
        val corePath = applicationInfo.nativeLibraryDir + "/libmgba.so"

        try {
            if (!internalFile.exists()) {
                resources.openRawResource(R.raw.rom).use { input ->
                    internalFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            println("ROM Path: ${internalFile.absolutePath}")

            if (loadCore(corePath) && loadGame(internalFile.absolutePath)) {
                coreIsReady = true
                val holder = surfaceView.holder
                if (holder.surface.isValid) {
                    startGameLoop(holder)
                }
            } else {
                println("Failed to load core or game.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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

    // --- The Game Loop ---
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startGameLoop(holder: SurfaceHolder) {
        if (isRunning) return
        isRunning = true

        // Audio Setup (Keep your existing optimized setup)
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
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
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack?.play()

        gameThread = Thread {
            // No destRect needed anymore!

            // FPS Variables
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()

            while (isRunning) {
                // 1. Run Core
                val samplesGenerated = runFrame(emuBitmap, audioBuffer)

                // 2. Draw 1:1 to the low-res surface
                val canvas: Canvas? = holder.lockCanvas() // Software canvas is fine for 240x160
                if (canvas != null) {
                    try {
                        // Draw directly (No Scaling!)
                        canvas.drawBitmap(emuBitmap, 0f, 0f, null)

                        // Draw FPS (Optional)
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            val fps = frameCount * 1000.0 / (now - lastFpsTime)
                            // Log it instead of drawing if text looks ugly on low-res
                            println("FPS: $fps")
                            frameCount = 0
                            lastFpsTime = now
                        }
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }

                // 3. Audio Sync
                if (samplesGenerated > 0) {
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

    // --- Input Handling (Physical) ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val gbaButton = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_Z -> BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_X -> BUTTON_B
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_A -> BUTTON_L
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_S -> BUTTON_R
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> BUTTON_START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DEL -> BUTTON_SELECT
            KeyEvent.KEYCODE_DPAD_UP -> BUTTON_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BUTTON_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BUTTON_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BUTTON_RIGHT
            else -> return super.dispatchKeyEvent(event)
        }
        setInputState(gbaButton, isDown)
        return true
    }
}