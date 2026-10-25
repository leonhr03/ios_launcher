package com.example.ioslauncher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS-Style Launcher in einer Datei.
 * - Alle Apps direkt auf dem Homescreen (kein App-Drawer), mehrere Seiten mit Wisch-Snap
 * - Dock unten (max. 4 Apps), Seitenpunkte
 * - Ordner (per Lang-Druck-Menue), Blur-Hintergrund beim Oeffnen (Android 12+)
 * - Suche: auf dem Homescreen nach unten wischen
 * - Original-Icons bleiben unveraendert
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {

    private class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pager: Pager
    private lateinit var pagesBox: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var dock: LinearLayout

    private var overlay: View? = null
    private var apps: List<AppItem> = emptyList()
    private var startPage = 0
    private var downX = 0f
    private var downY = 0f

    private val screenW: Int get() = resources.displayMetrics.widthPixels
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun AppItem.copyIcon(): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

    // ---------- Pager mit Snap + Wisch-nach-unten-Suche ----------

    private inner class Pager(c: Context) : HorizontalScrollView(c) {
        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }

        override fun fling(velocityX: Int) {}

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startPage = (scrollX.toFloat() / max(1, width)).roundToInt()
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dy > dp(110) && dy > abs(dx) * 1.5f) {
                        e.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(e)
                        openSearch()
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val r = super.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                snap(e.rawX - downX)
            }
            return r
        }
    }

    private fun snap(dx: Float) {
        val w = pager.width
        if (w == 0) return
        var p = startPage
        if (dx < -w * 0.15f) p++ else if (dx > w * 0.15f) p--
        p = p.coerceIn(0, max(0, pagesBox.childCount - 1))
        pager.smoothScrollTo(p * w, 0)
    }

    // ---------- Lifecycle ----------

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        root = FrameLayout(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        pager = Pager(this)
        pagesBox = LinearLayout(this)
        pager.addView(pagesBox, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        content.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        content.addView(dots, LinearLayout.LayoutParams(MATCH_PARENT, dp(24)))

        dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(0x55FFFFFF, 30)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        content.addView(
            dock,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(88)).apply { setMargins(dp(14), 0, dp(14), dp(10)) }
        )

        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, ins ->
            content.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(root)

        pager.setOnScrollChangeListener { _, x, _, _, _ ->
            updateDots((x.toFloat() / max(1, pager.width)).roundToInt())
        }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        pager.post { render() }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeOverlay(false)
        pager.smoothScrollTo(0, 0)
    }

    override fun onBackPressed() {
        if (overlay != null) closeOverlay() else pager.smoothScrollTo(0, 0)
    }

    // ---------- Daten ----------

    private fun loadApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { r ->
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                AppItem(r.loadLabel(pm).toString(), r.activityInfo.packageName, i, r.loadIcon(pm))
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun defaultDock(): MutableList<String> {
        val ints = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val installed = apps.map { it.pkg }.toSet()
        return ints
            .mapNotNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .filter { it in installed }
            .distinct()
            .toMutableList()
    }

    private fun readDock(): MutableList<String> {
        val s = prefs.getString("dock", null)
        if (s == null) {
            val d = defaultDock()
            saveDock(d)
            return d
        }
        val a = JSONArray(s)
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun saveDock(l: List<String>) {
        prefs.edit().putString("dock", JSONArray(l).toString()).apply()
    }

    private fun readFolders(): LinkedHashMap<String, MutableList<String>> {
        val m = LinkedHashMap<String, MutableList<String>>()
        val s = prefs.getString("folders", null) ?: return m
        val o = JSONObject(s)
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            m[k] = MutableList(a.length()) { a.getString(it) }
        }
        return m
    }

    private fun saveFolders(m: Map<String, List<String>>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray(v))
        prefs.edit().putString("folders", o.toString()).apply()
    }

    // ---------- Rendering ----------

    private fun render() {
        if (pager.width == 0) {
            pager.post { render() }
            return
        }
        loadApps()
        val byPkg = apps.associateBy { it.pkg }
        val folders = readFolders()
        val dockPkgs = readDock().filter { it in byPkg }
        val inFolder = folders.values.flatten().toSet()

        val items = mutableListOf<View>()
        for ((name, pkgs) in folders) {
            val list = pkgs.mapNotNull { byPkg[it] }
            if (list.isNotEmpty()) items += folderCell(name, list)
        }
        for (a in apps) {
            if (a.pkg !in inFolder && a.pkg !in dockPkgs) items += appCell(a, true)
        }

        val cellH = dp(96)
        val rows = max(3, pager.height / cellH)
        val chunks = items.chunked(4 * rows).ifEmpty { listOf(emptyList()) }

        pagesBox.removeAllViews()
        for (chunk in chunks) {
            val g = GridLayout(this).apply {
                columnCount = 4
                setPadding(dp(8), dp(8), dp(8), 0)
            }
            for (v in chunk) g.addView(v, cellParams(cellH))
            pagesBox.addView(g, LinearLayout.LayoutParams(screenW, MATCH_PARENT))
        }

        dock.removeAllViews()
        for (pkg in dockPkgs) {
            byPkg[pkg]?.let { dock.addView(appCell(it, false), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }

        buildDots(chunks.size)
        updateDots((pager.scrollX.toFloat() / max(1, pager.width)).roundToInt())
    }

    private fun cellParams(h: Int) =
        GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            .apply {
                width = 0
                height = h
            }

    private fun buildDots(n: Int) {
        dots.removeAllViews()
        if (n <= 1) return
        repeat(n) {
            val d = View(this)
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            d.alpha = 0.4f
            dots.addView(d, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    private fun updateDots(p: Int) {
        for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == p) 1f else 0.4f
    }

    private fun makeCell(icon: View, label: String?, tap: (View) -> Unit, long: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        c.addView(icon, LinearLayout.LayoutParams(dp(60), dp(60)))
        if (label != null) {
            val t = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
            c.addView(t, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        c.setOnClickListener { tap(c) }
        c.setOnLongClickListener {
            long()
            true
        }
        c.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        return c
    }

    private fun appCell(a: AppItem, label: Boolean): View {
        val icon = ImageView(this).apply { setImageDrawable(a.copyIcon()) }
        return makeCell(icon, if (label) a.label else null, { v -> launch(a, v) }, { appMenu(a) })
    }

    private fun folderCell(name: String, list: List<AppItem>): View {
        val box = GridLayout(this).apply {
            columnCount = 2
            background = rounded(0x66FFFFFF, 14)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        for (a in list.take(4)) {
            box.addView(
                ImageView(this).apply { setImageDrawable(a.copyIcon()) },
                GridLayout.LayoutParams().apply {
                    width = dp(21)
                    height = dp(21)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            )
        }
        return makeCell(box, name, { openFolder(name, list) }, { folderMenu(name) })
    }

    private fun launch(a: AppItem, v: View) {
        try {
            val o = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height)
            startActivity(a.intent, o.toBundle())
            closeOverlay(false)
        } catch (e: Exception) {
            toast("App kann nicht geöffnet werden")
        }
    }

    // ---------- Menues ----------

    private fun appMenu(a: AppItem) {
        val dockList = readDock()
        val folders = readFolders()
        val inFolder = folders.entries.firstOrNull { a.pkg in it.value }?.key
        val opts = mutableListOf<String>()
        opts += if (a.pkg in dockList) "Aus Dock entfernen" else "In Dock legen"
        opts += "In Ordner legen…"
        if (inFolder != null) opts += "Aus Ordner entfernen"
        opts += "App-Info"
        opts += "Deinstallieren"

        AlertDialog.Builder(this).setTitle(a.label).setItems(opts.toTypedArray()) { _, i ->
            when (opts[i]) {
                "In Dock legen" -> {
                    if (dockList.size >= 4) {
                        toast("Dock ist voll (max. 4)")
                    } else {
                        folders.values.forEach { it.remove(a.pkg) }
                        saveFolders(folders)
                        dockList += a.pkg
                        saveDock(dockList)
                        render()
                    }
                }
                "Aus Dock entfernen" -> {
                    dockList -= a.pkg
                    saveDock(dockList)
                    render()
                }
                "In Ordner legen…" -> pickFolder(a)
                "Aus Ordner entfernen" -> {
                    folders[inFolder]?.remove(a.pkg)
                    saveFolders(folders)
                    render()
                }
                "App-Info" -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                )
                "Deinstallieren" -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
            }
        }.show()
    }

    private fun pickFolder(a: AppItem) {
        val names = readFolders().keys.toMutableList()
        names += "Neuer Ordner…"
        AlertDialog.Builder(this).setTitle("Ordner wählen").setItems(names.toTypedArray()) { _, i ->
            if (i == names.size - 1) askName("Neuer Ordner", "") { n -> addToFolder(a.pkg, n) }
            else addToFolder(a.pkg, names[i])
        }.show()
    }

    private fun addToFolder(pkg: String, name: String) {
        val f = readFolders()
        f.values.forEach { it.remove(pkg) }
        f.getOrPut(name) { mutableListOf() }.add(pkg)
        val d = readDock()
        if (d.remove(pkg)) saveDock(d)
        saveFolders(f)
        render()
    }

    private fun folderMenu(name: String) {
        val opts = arrayOf("Umbenennen", "Ordner auflösen")
        AlertDialog.Builder(this).setTitle(name).setItems(opts) { _, i ->
            val f = readFolders()
            if (i == 0) {
                askName("Name", name) { n ->
                    val l = f.remove(name)
                    if (l != null) {
                        f.getOrPut(n) { mutableListOf() }.addAll(l)
                        saveFolders(f)
                        render()
                    }
                }
            } else {
                f.remove(name)
                saveFolders(f)
                render()
            }
        }.show()
    }

    private fun askName(title: String, initial: String, cb: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(initial)
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) cb(n)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Overlays ----------

    private fun showOverlay(v: View, blur: Boolean) {
        closeOverlay(false)
        overlay = v
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
        if (blur && Build.VERSION.SDK_INT >= 31) {
            content.setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP))
        }
    }

    private fun closeOverlay(animate: Boolean = true) {
        val v = overlay ?: return
        overlay = null
        if (Build.VERSION.SDK_INT >= 31) content.setRenderEffect(null)
        hideKeyboard()
        if (animate) v.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(v) }.start()
        else root.removeView(v)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun openFolder(name: String, list: List<AppItem>) {
        val v = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(85, 0, 0, 0))
            setOnClickListener { closeOverlay() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x66FFFFFF, 30)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            isClickable = true
        }
        panel.addView(
            TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
        val g = GridLayout(this).apply { columnCount = 4 }
        for (a in list) g.addView(appCell(a, true), cellParams(dp(96)))
        val rows = (list.size + 3) / 4
        val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val sv = ScrollView(this).apply { addView(g) }
        panel.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, min(rows * dp(96), maxH)))
        v.addView(
            panel,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(dp(20), 0, dp(20), 0)
            }
        )
        showOverlay(v, true)
    }

    private fun openSearch() {
        if (overlay != null) return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(16), content.paddingTop + dp(16), dp(16), dp(16))
            isClickable = true
        }
        val input = EditText(this).apply {
            hint = "Suchen"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setSingleLine()
            background = rounded(0x44FFFFFF, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }
        var first: AppItem? = null

        fun fill(q: String) {
            results.removeAllViews()
            val t = q.trim().lowercase()
            val m = apps.filter { t.isEmpty() || it.label.lowercase().contains(t) }.take(40)
            first = m.firstOrNull()
            for (a in m) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(
                    ImageView(this).apply { setImageDrawable(a.copyIcon()) },
                    LinearLayout.LayoutParams(dp(44), dp(44))
                )
                row.addView(
                    TextView(this).apply {
                        text = a.label
                        setTextColor(Color.WHITE)
                        textSize = 17f
                    },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(14) }
                )
                row.setOnClickListener { launch(a, row) }
                results.addView(row)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                fill(s?.toString() ?: "")
            }
        })
        input.setOnEditorActionListener { v, _, _ ->
            first?.let { launch(it, v) }
            true
        }

        col.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        col.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        fill("")
        showOverlay(col, false)
        input.requestFocus()
        input.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }, 200)
    }
}
