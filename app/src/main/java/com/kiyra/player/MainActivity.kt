package com.kiyra.player

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private data class VideoItem(
        val uri: Uri,
        val title: String,
        val folderName: String,
        val durationMs: Long
    )

    private data class FolderGroup(
        val folderName: String,
        val videos: List<VideoItem>,
        val previewUri: Uri
    )

    private enum class TabMode { SERIES, ALL_FILES, SETTINGS }

    private lateinit var rootContainer: FrameLayout
    private lateinit var mainScreen: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsScroll: ScrollView

    private var allVideos: List<VideoItem> = emptyList()
    private var folderGroups: List<FolderGroup> = emptyList()
    private var activeFolder: FolderGroup? = null
    private var currentTab: TabMode = TabMode.SERIES

    private val prefs by lazy { getSharedPreferences("kiyra_prefs", Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadVideos() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildMainUI()
        checkPermissionAndLoad()
    }

    private fun buildMainUI() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }

        mainScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 24)
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }

        val tvLogo = TextView(this).apply {
            text = "KIYRA"
            textSize = 26f
            setTextColor(Color.parseColor("#E50914"))
            paint.isFakeBoldText = true
        }

        tvSubtitle = TextView(this).apply {
            text = "المسلسلات والمجلدات"
            textSize = 13f
            setTextColor(Color.parseColor("#A6A6B2"))
        }

        etSearch = EditText(this).apply {
            hint = "ابحث عن مسلسل أو حلقة أو ملف..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A8A96"))
            setBackgroundColor(Color.parseColor("#18181F"))
            setPadding(32, 24, 32, 24)
            textSize = 14f
            isSingleLine = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 20
            layoutParams = lp
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderContent()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        header.addView(tvLogo)
        header.addView(tvSubtitle)
        header.addView(etSearch)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(24, 8, 24, 16)
        }

        settingsScroll = buildSettingsView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            visibility = View.GONE
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#14141A"))
            setPadding(12, 16, 12, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeTabBtn(title: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = title
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1F1F28"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(8, 0, 8, 0)
                }
                setOnClickListener { onClick() }
            }
        }

        bottomBar.addView(makeTabBtn("المسلسلات") {
            activeFolder = null
            currentTab = TabMode.SERIES
            tvSubtitle.text = "المسلسلات والمجلدات"
            renderContent()
        })

        bottomBar.addView(makeTabBtn("كل الملفات") {
            activeFolder = null
            currentTab = TabMode.ALL_FILES
            tvSubtitle.text = "جميع ملفات الفيديو"
            renderContent()
        })

        bottomBar.addView(makeTabBtn("الإعدادات") {
            activeFolder = null
            currentTab = TabMode.SETTINGS
            tvSubtitle.text = "تحسين الصوت 3D والإعدادات"
            renderContent()
        })

        mainScreen.addView(header)
        mainScreen.addView(recyclerView)
        mainScreen.addView(settingsScroll)
        mainScreen.addView(bottomBar)

        rootContainer.addView(mainScreen)
        setContentView(rootContainer)
    }

    private fun buildSettingsView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setPadding(40, 24, 40, 24)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "الصوت ثلاثي الأبعاد (3D) وتحسين المشاهدة"
            textSize = 18f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 24)
        }

        val sw3D = Switch(this).apply {
            text = "تفعيل صوت 3D محيطي للسماعات"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("spatial_3d", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("spatial_3d", checked).apply()
            }
        }

        val swGlow = Switch(this).apply {
            text = "تشغيل التوهج التفاعلي مع المشاهد تلقائياً"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("glow_auto", true)
            setPadding(0, 20, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("glow_auto", checked).apply()
            }
        }

        val swResume = Switch(this).apply {
            text = "استئناف الفيديو من آخر مكان توقفت عنده"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("auto_resume", true)
            setPadding(0, 20, 0, 20)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("auto_resume", checked).apply()
            }
        }

        val currentBoost = prefs.getInt("boost_pct", 120)
        val tvBoost = TextView(this).apply {
            text = "تعزيز مستوى الصوت: $currentBoost%"
            setTextColor(Color.parseColor("#C7C7D4"))
        }

        val seekBoost = SeekBar(this).apply {
            max = 100
            progress = (currentBoost - 100).coerceIn(0, 100)
            setPadding(0, 16, 0, 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val pct = 100 + p
                    tvBoost.text = "تعزيز مستوى الصوت: $pct%"
                    prefs.edit().putInt("boost_pct", pct).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val tvEq = TextView(this).apply {
            text = "وضع معادل الصوت (Equalizer):"
            setTextColor(Color.parseColor("#C7C7D4"))
            setPadding(0, 0, 0, 12)
        }

        fun eqBtn(label: String, mode: String): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#22222E"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 6, 0, 6)
                layoutParams = lp
                setOnClickListener {
                    prefs.edit().putString("eq_mode", mode).apply()
                    tvEq.text = "وضع معادل الصوت (المحدد: $label)"
                }
            }
        }

        box.addView(title)
        box.addView(sw3D)
        box.addView(swGlow)
        box.addView(swResume)
        box.addView(tvBoost)
        box.addView(seekBoost)
        box.addView(tvEq)
        box.addView(eqBtn("سينما (Cinema)", "cinema"))
        box.addView(eqBtn("أكشن وباس عميق (Bass)", "bass"))
        box.addView(eqBtn("وضوح الحوار (Voice)", "voice"))

        scroll.addView(box)
        return scroll
    }

    private fun checkPermissionAndLoad() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val granted = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) loadVideos() else permissionLauncher.launch(perms)
    }

    private fun loadVideos() {
        Thread {
            val list = mutableListOf<VideoItem>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATA
            )
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "فيديو"
                    val dur = c.getLong(durCol)
                    val path = c.getString(pathCol) ?: ""
                    val folder = File(path).parentFile?.name?.takeIf { it.isNotBlank() } ?: "مجلد عام"
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    list.add(VideoItem(uri, name, folder, dur))
                }
            }

            val groups = list.groupBy { it.folderName }.map { (folder, items) ->
                val sorted = items.sortedBy { it.title }
                FolderGroup(folder, sorted, sorted.first().uri)
            }.sortedBy { it.folderName.lowercase() }

            runOnUiThread {
                allVideos = list
                folderGroups = groups
                renderContent()
            }
        }.start()
    }

    private fun renderContent() {
        if (currentTab == TabMode.SETTINGS) {
            recyclerView.visibility = View.GONE
            settingsScroll.visibility = View.VISIBLE
            return
        }

        settingsScroll.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        val q = etSearch.text?.toString()?.trim().orEmpty()

        if (currentTab == TabMode.SERIES && activeFolder == null) {
            val filtered = if (q.isEmpty()) folderGroups else folderGroups.filter {
                it.folderName.contains(q, true) || it.videos.any { v -> v.title.contains(q, true) }
            }
            recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(makeCardView(parent.context)) {}

                override fun getItemCount() = filtered.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val g = filtered[position]
                    bindCard(holder.itemView, g.folderName, "${g.videos.size} حلقة / ملف", g.previewUri) {
                        activeFolder = g
                        tvSubtitle.text = "المسلسل/المجلد: ${g.folderName} (رجوع من زر الرجوع)"
                        renderContent()
                    }
                }
            }
        } else {
            val base = if (currentTab == TabMode.SERIES && activeFolder != null) activeFolder!!.videos else allVideos
            val filtered = if (q.isEmpty()) base else base.filter { it.title.contains(q, true) }
            recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(makeCardView(parent.context)) {}

                override fun getItemCount() = filtered.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val v = filtered[position]
                    val mins = (v.durationMs / 1000) / 60
                    val secs = (v.durationMs / 1000) % 60
                    bindCard(holder.itemView, v.title, "${v.folderName} • $mins:${String.format("%02d", secs)}", v.uri) {
                        openVideoPlayer(v)
                    }
                }
            }
        }
    }

    private fun makeCardView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#16161D"))
            setPadding(20, 20, 20, 20)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }

            addView(ImageView(ctx).apply {
                id = View.generateViewId()
                tag = "thumb"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#0F0F13"))
                layoutParams = LinearLayout.LayoutParams(260, 160)
            })

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 0, 12, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(ctx).apply {
                    tag = "title"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                    maxLines = 2
                })

                addView(TextView(ctx).apply {
                    tag = "meta"
                    textSize = 12f
                    setTextColor(Color.parseColor("#A6A6B2"))
                    setPadding(0, 8, 0, 0)
                })
            })
        }
    }

    private fun bindCard(row: View, title: String, meta: String, thumbUri: Uri, onClick: () -> Unit) {
        val iv = row.findViewWithTag<ImageView>("thumb")
        val tvT = row.findViewWithTag<TextView>("title")
        val tvM = row.findViewWithTag<TextView>("meta")
        tvT.text = title
        tvM.text = meta
        iv.setImageBitmap(null)
        row.setOnClickListener { onClick() }

        Thread {
            val bmp = extractFrameBitmap(row.context, thumbUri)
            row.post {
                if (bmp != null) iv.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun extractFrameBitmap(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(2_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun openVideoPlayer(item: VideoItem) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val ambientGlow = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(12, 60, 12, 60)
            }
            alpha = 0.65f
        }

        val playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(36, 110, 36, 110)
            }
        }

        val exo = ExoPlayer.Builder(this).build()
        playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(item.uri))
        exo.prepare()

        val savedKey = "pos_${item.uri}"
        if (prefs.getBoolean("auto_resume", true)) {
            val pos = prefs.getLong(savedKey, 0L)
            if (pos > 0L) exo.seekTo(pos)
        }
        exo.playWhenReady = true

        var loudness: LoudnessEnhancer? = null
        var virt: Virtualizer? = null
        var eq: Equalizer? = null
        try {
            val sid = exo.audioSessionId
            if (sid != 0) {
                val boost = prefs.getInt("boost_pct", 120)
                val gain = ((boost - 100) * 15).coerceAtLeast(0)
                loudness = LoudnessEnhancer(sid).apply {
                    setTargetGain(gain)
                    enabled = gain > 0
                }
                virt = Virtualizer(0, sid).apply {
                    enabled = prefs.getBoolean("spatial_3d", true)
                    if (strengthSupported) setStrength(850.toShort())
                }
                eq = Equalizer(0, sid).apply {
                    enabled = true
                    val mode = prefs.getString("eq_mode", "cinema") ?: "cinema"
                    val bands = numberOfBands.toInt()
                    for (i in 0 until bands) {
                        val lvl = when (mode) {
                            "bass" -> if (i < 2) 600 else 150
                            "voice" -> if (i in 2..3) 550 else 0
                            else -> if (i == 0 || i == bands - 1) 350 else 200
                        }
                        setBandLevel(
                            i.toShort(),
                            lvl.coerceIn(bandLevelRange[0].toInt(), bandLevelRange[1].toInt()).toShort()
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        var glowActive = prefs.getBoolean("glow_auto", true)
        var currentColor = Color.parseColor("#FF5A36")
        var colorAnimator: ValueAnimator? = null

        fun applyGlowGradient(c1: Int, c2: Int) {
            val gd = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c1, Color.TRANSPARENT, c2)
            ).apply {
                cornerRadius = 56f
            }
            ambientGlow.background = gd
        }

        applyGlowGradient(currentColor, Color.parseColor("#3B82F6"))
        ambientGlow.visibility = if (glowActive) View.VISIBLE else View.GONE

        val glowHandler = Handler(Looper.getMainLooper())
        val glowRunnable = object : Runnable {
            override fun run() {
                if (glowActive && exo.isPlaying) {
                    Thread {
                        val r = MediaMetadataRetriever()
                        var nextColor = currentColor
                        try {
                            r.setDataSource(this@MainActivity, item.uri)
                            val frame = r.getFrameAtTime(exo.currentPosition * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (frame != null) {
                                val tiny = Bitmap.createScaledBitmap(frame, 8, 8, true)
                                var red = 0
                                var green = 0
                                var blue = 0
                                for (x in 0 until 8) {
                                    for (y in 0 until 8) {
                                        val px = tiny.getPixel(x, y)
                                        red += Color.red(px)
                                        green += Color.green(px)
                                        blue += Color.blue(px)
                                    }
                                }
                                nextColor = Color.rgb(
                                    (red / 64).coerceIn(30, 255),
                                    (green / 64).coerceIn(30, 255),
                                    (blue / 64).coerceIn(30, 255)
                                )
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { r.release() } catch (_: Exception) {}
                        }

                        runOnUiThread {
                            colorAnimator?.cancel()
                            colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, nextColor).apply {
                                duration = 650L
                                addUpdateListener { anim ->
                                    val c = anim.animatedValue as Int
                                    currentColor = c
                                    val secondary = Color.argb(
                                        150,
                                        Color.blue(c),
                                        Color.red(c),
                                        Color.green(c)
                                    )
                                    applyGlowGradient(c, secondary)
                                }
                                start()
                            }
                            ambientGlow.animate()
                                .scaleX(1.03f)
                                .scaleY(1.02f)
                                .alpha(0.75f)
                                .setDuration(600L)
                                .withEndAction {
                                    ambientGlow.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.55f).setDuration(600L).start()
                                }.start()
                        }
                    }.start()
                }
                glowHandler.postDelayed(this, 850L)
            }
        }
        glowHandler.post(glowRunnable)

        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#88000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val btnGlow = Button(this).apply {
            text = if (glowActive) "التوهج: يعمل" else "التوهج: متوقف"
            textSize = 11f
            setOnClickListener {
                glowActive = !glowActive
                ambientGlow.visibility = if (glowActive) View.VISIBLE else View.GONE
                text = if (glowActive) "التوهج: يعمل" else "التوهج: متوقف"
            }
        }

        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        var sIdx = 1
        val btnSpeed = Button(this).apply {
            text = "1.0x"
            textSize = 11f
            setOnClickListener {
                sIdx = (sIdx + 1) % speeds.size
                exo.setPlaybackSpeed(speeds[sIdx])
                text = "${speeds[sIdx]}x"
            }
        }

        val btnClose = Button(this).apply {
            text = "إغلاق"
            textSize = 11f
            setOnClickListener {
                if (prefs.getBoolean("auto_resume", true)) {
                    prefs.edit().putLong(savedKey, exo.currentPosition).apply()
                }
                glowHandler.removeCallbacks(glowRunnable)
                colorAnimator?.cancel()
                loudness?.release()
                virt?.release()
                eq?.release()
                exo.release()
                rootContainer.removeView(overlay)
            }
        }

        topControls.addView(btnGlow)
        topControls.addView(btnSpeed)
        topControls.addView(btnClose)

        overlay.addView(ambientGlow)
        overlay.addView(playerView)
        overlay.addView(topControls)
        rootContainer.addView(overlay)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (rootContainer.childCount > 1) {
            rootContainer.removeViewAt(rootContainer.childCount - 1)
        } else if (currentTab == TabMode.SERIES && activeFolder != null) {
            activeFolder = null
            tvSubtitle.text = "المسلسلات والمجلدات"
            renderContent()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
