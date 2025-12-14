package com.cinemint.emulauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs
import androidx.core.net.toUri
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import androidx.core.graphics.createBitmap

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private var mediaPlayer: MediaPlayer? = null

    // We keep a reference to the layout manager for scrolling logic
    private lateinit var layoutManager: LinearLayoutManager
    private val contentList = mutableListOf<LauncherItem>()
    private lateinit var adapter: LauncherAdapter

    // Helper to snap items to center
    private val snapHelper = LinearSnapHelper()

    private lateinit var rootLayout: View
    private var currentBackgroundColor: Int = 0xFF121212.toInt()
    private var lastCenterPosition: Int = -1
    private var colorAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        rootLayout = findViewById(R.id.root_layout)

        mediaPlayer = MediaPlayer.create(this, R.raw.music)
        mediaPlayer?.isLooping = true

        recyclerView = findViewById(R.id.recycler_view)
        layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recyclerView.layoutManager = layoutManager

        adapter = LauncherAdapter(contentList, packageManager)
        recyclerView.adapter = adapter

        // 1. Attach SnapHelper to force items to stop in the center
        snapHelper.attachToRecyclerView(recyclerView)

        // 2. Add the "Rolodex" Visual Effect
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                applyRolodexEffect()

                // NEW: Check which item is in the center and update color
                checkCenterItemColor()
            }
        })

        // Initial check
        checkPermissionsAndLoad()

        // Apply effect immediately after layout so the initial state looks correct
        recyclerView.post { applyRolodexEffect() }
    }

    private fun checkCenterItemColor() {
        val centerView = snapHelper.findSnapView(layoutManager) ?: return
        val pos = layoutManager.getPosition(centerView)

        // Only trigger animation if the selected item has changed
        if (pos != lastCenterPosition && pos >= 0 && pos < contentList.size) {
            lastCenterPosition = pos
            val targetColor = contentList[pos].color
            animateBackgroundColor(targetColor)
        }
    }

    private fun animateBackgroundColor(toColor: Int) {
        // Cancel any running animation so we don't fight over colors
        colorAnimator?.cancel()

        // Create a new smooth transition
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentBackgroundColor, toColor)
        colorAnimator?.duration = 500 // 500ms fade duration
        colorAnimator?.addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            currentBackgroundColor = color // Update our tracker
            rootLayout.setBackgroundColor(color)
            // Optional: You can also darken the color slightly if it's too bright
        }
        colorAnimator?.start()
    }

    // --- The Visual "Rolodex" Math ---
    private fun applyRolodexEffect() {
        val parentCenterY = recyclerView.height / 2f
        val parentHeight = recyclerView.height.toFloat()

        // 1. Loop through children
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)

            // 2. Calculate the distance from the center
            val childCenterY = (child.top + child.bottom) / 2f
            val distFromCenter = childCenterY - parentCenterY

            // 3. Normalized position: -1 (top) to 0 (center) to 1 (bottom)
            // We divide by half the height to get the range
            val fraction = distFromCenter / (parentHeight / 2f)

            // Clamp strictly between -1 and 1 to prevent weird math at edges
            val clampedFraction = fraction.coerceIn(-1f, 1f)

            // --- A. THE CURVE (Translation X) ---
            // Push items right based on how far they are from center.
            // Using a quadratic curve (x^2) gives a nice round parabolic shape.
            // 400f is the depth of the curve.
            val translationX = (clampedFraction * clampedFraction) * 32f
            child.translationX = translationX

            // --- B. THE WHEEL ROTATION (Rotation X) ---
            // Rotate items around the X axis to look like a drum.
            // -45 degrees at the top, 45 degrees at the bottom.
            val rotationX = -clampedFraction * 5f
            child.rotationX = rotationX

            // --- C. STACKING (Translation Y) ---
            // We pull items closer to the center to stack them.
            // If item is above center (negative fraction), push it DOWN (positive Y).
            // If item is below center (positive fraction), push it UP (negative Y).
            // The factor 0.3f determines how much they overlap.
            val squeezeFactor = 24f
            val translationY = -clampedFraction * squeezeFactor
            child.translationY = translationY

            // --- D. SCALE & ALPHA ---
            val scale = 1f - (abs(clampedFraction) * 0.4f)
            child.scaleX = scale
            child.scaleY = scale

            // Fade out items at the very edges
            child.alpha = 1f - (abs(clampedFraction) * 0.8f)

            // --- E. Z-ORDER (Elevation) ---
            // Crucial for stacking! Closer to center = Higher Z index.
            // We use standard Elevation API (Android 5.0+).
            child.elevation = (1f - abs(clampedFraction)) * 50f
        }
    }

    // --- Keypad / D-Pad Control ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    scrollNext()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    scrollPrevious()
                    return true
                }
                // Handle "A" button, Enter, or D-Pad Center
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    launchCenteredItem()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scrollNext() {
        // Find current center view
        val centerView = snapHelper.findSnapView(layoutManager) ?: return
        val pos = layoutManager.getPosition(centerView)
        if (pos < adapter.itemCount - 1) {
            // Smooth scroll to the next position ensures the snap helper catches it
            recyclerView.smoothScrollToPosition(pos + 1)
        }
    }

    private fun scrollPrevious() {
        val centerView = snapHelper.findSnapView(layoutManager) ?: return
        val pos = layoutManager.getPosition(centerView)
        if (pos > 0) {
            recyclerView.smoothScrollToPosition(pos - 1)
        }
    }

    private fun launchCenteredItem() {
        // 1. Find the view currently snapped to the center
        val centerView = snapHelper.findSnapView(layoutManager)

        if (centerView != null) {
            // 2. Get the data position of that view
            val position = layoutManager.getPosition(centerView)
            if (position != RecyclerView.NO_POSITION) {
                // 3. Trigger the launch logic manually
                val item = contentList[position]

                // Add a small visual feedback animation for the press
                centerView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).withEndAction {
                    centerView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }.start()

                when (item) {
                    is LauncherItem.Rom -> launchEmulator(item.file)
                    is LauncherItem.App -> launchApp(item.resolveInfo)
                }
            }
        }
    }

    // --- Boilerplate LifeCycle Methods ---

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) loadAllContent()
        if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
    }

    override fun onPause() {
        super.onPause()
        if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun checkPermissionsAndLoad() {
        if (hasStoragePermission()) {
            loadAllContent()
        } else {
            requestStoragePermission()
        }
    }

    // --- Loading Logic ---

    private fun loadAllContent() {
        contentList.clear()

        // 1. Load Data
        if (hasStoragePermission()) {
            val roms = getRomList()
            contentList.addAll(roms.map { file ->
                // Assign a color based on extension
                val color = when {
                    file.name.endsWith(".gba") -> 0xFF3F51B5.toInt() // Indigo
                    file.name.endsWith(".nes") -> 0xFFB71C1C.toInt() // Nintendo Red
                    file.name.endsWith(".sfc") -> 0xFF4A148C.toInt() // SNES Purple
                    file.name.endsWith(".md") -> 0xFF212121.toInt()  // Sega Black
                    else -> 0xFF455A64.toInt() // Generic Grey
                }
                LauncherItem.Rom(file, color)
            })
        }

        val apps = getAppList()
        contentList.addAll(apps.map { info ->
            val icon = info.loadIcon(packageManager)
            val extractedColor = getDominantColor(icon)
            LauncherItem.App(info, info.loadLabel(packageManager).toString(), extractedColor)
        })

        // Sort
        contentList.sortWith { o1, o2 ->
            o1.label.lowercase().compareTo(o2.label.lowercase())
        }

        // 2. Notify Adapter
        adapter.notifyDataSetChanged()

        // 3. Force "Selection" of the first item
        recyclerView.post {
            // A. Calculate padding to center the first item perfectly
            // (We assume the item height is roughly 100-150dp, but centering the parent padding is safer)
            val centerPadding = recyclerView.height / 2

            // Set padding so the top item can be pushed down to the center
            // and the bottom item can be pushed up to the center.
            recyclerView.setPadding(0, centerPadding, 0, centerPadding)

            // B. Scroll to the first item (Index 0)
            // Because of the padding, "Position 0" is now visually in the middle of the screen.
            layoutManager.scrollToPositionWithOffset(0, 0)

            // C. Run the visual math immediately
            // This ensures the first item starts "Big" and "Flat" without needing a touch event first.
            applyRolodexEffect()
        }
    }

    private fun getDominantColor(drawable: Drawable): Int {
        val bitmap = drawableToBitmap(drawable)
        val palette = Palette.from(bitmap).generate()

        // Try to get a "Vibrant" color first, fallback to "Muted", then Gray
        return palette.getVibrantColor(
            palette.getDominantColor(0xFF333333.toInt())
        )
    }

    // Helper to convert Vector/Adaptive icons to Bitmap safely
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap

        val bitmap = createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1)
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun getRomList(): List<File> {
        val romList = mutableListOf<File>()
        val romsDir = File(Environment.getExternalStorageDirectory(), "ROMs")

        if (!romsDir.exists()) romsDir.mkdirs()

        if (romsDir.exists() && romsDir.isDirectory) {
            romsDir.listFiles()?.forEach { file ->
                if (!file.isDirectory) {
                    val name = file.name.lowercase()
                    if (name.endsWith(".gba") || name.endsWith(".nes") ||
                        name.endsWith(".sfc") || name.endsWith(".md") ||
                        name.endsWith(".iso") || name.endsWith(".zip")) {
                        romList.add(file)
                    }
                }
            }
        }
        return romList
    }

    private fun getAppList(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
        return apps.filter { it.activityInfo.packageName != packageName }
    }

    // --- Permissions Logic ---

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = String.format("package:%s", applicationContext.packageName).toUri()
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }

    // --- Launch Logic ---

    fun launchEmulator(file: File) {
        val intent = Intent(this, EmulatorActivity::class.java)
        intent.data = Uri.fromFile(file)
        intent.putExtra("FILE_NAME", file.name)
        startActivity(intent)
    }

    fun launchApp(info: ResolveInfo) {
        val launchIntent = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Cannot launch this app", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Unified Adapter ---

    inner class LauncherAdapter(
        private val items: List<LauncherItem>,
        private val pm: PackageManager
    ) : RecyclerView.Adapter<LauncherAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(R.id.rom_icon)
            val nameText: TextView = view.findViewById(R.id.rom_name)

            // Notice: No OnClickListener here anymore!
            // The Activity handles the click via the "A" button logic.
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_rom, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            when (item) {
                is LauncherItem.Rom -> {
                    holder.nameText.text = item.label
                    holder.iconView.setImageResource(android.R.drawable.ic_menu_save)
                    holder.iconView.setBackgroundColor(0xFF333333.toInt())
                }
                is LauncherItem.App -> {
                    holder.nameText.text = item.label
                    holder.iconView.setImageDrawable(item.resolveInfo.loadIcon(pm))
                    holder.iconView.background = null
                }
            }

            holder.itemView.setOnClickListener {
                // (Optional) Smooth scroll to this item so it's centered when you return
                recyclerView.smoothScrollToPosition(position)

                // Trigger the launch logic directly
                when (item) {
                    is LauncherItem.Rom -> launchEmulator(item.file)
                    is LauncherItem.App -> launchApp(item.resolveInfo)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}

// Data Structure remains the same
sealed class LauncherItem(val label: String, val color: Int) {
    // ROMs get a color based on their console type
    class Rom(val file: File, color: Int) : LauncherItem(file.name, color)

    // Apps get a color extracted from their icon
    class App(val resolveInfo: ResolveInfo, label: String, color: Int)
        : LauncherItem(label, color)
}