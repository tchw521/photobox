package cn.photobox.app

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : ComponentActivity() {

    // ---------- 状态
    private var photos: List<Photo> = emptyList()
    private var albumKey = KEY_ALL
    private var month: String? = null
    private var query = ""
    private var sort = 0                 // 0 日期新→旧 1 旧→新 2 名称 3 大小
    private var gridView = true
    private var tab = 0                  // 0 图库 1 卡片 2 设置 3 回收站

    private lateinit var sidebar: View
    private var libraryView: View? = null
    private var cardsView: View? = null
    private var settingsView: View? = null
    private var trashView: View? = null

    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var chipAdapter: ChipAdapter
    private var photoAdapter: PhotoAdapter? = null
    private var trashAdapter: TrashAdapter? = null

    private lateinit var cards: CardsPage

    companion object {
        val SORT_LABELS = arrayOf("日期新→旧", "日期旧→新", "名称", "大小")
        val SORT_ICONS = intArrayOf(
            R.drawable.ic_sort_time,
            R.drawable.ic_sort_time_asc,
            R.drawable.ic_sort_name,
            R.drawable.ic_sort_size,
        )
        const val APP_VERSION = "1.6.0"
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_BLOCKED = "\u0000blocked"
        const val KEY_TRASH = "\u0000trash"
    }

    // ---------- 生命周期
    override fun onCreate(s: Bundle?) {
        // setTheme 必须在 super.onCreate 前；此处只用 applicationContext 读偏好，
        // 避免在 Activity 尚未完成初始化时触碰自身 Context。
        SkinNow.load(applicationContext)
        setTheme(Skins.style(SkinNow.skin.key))
        super.onCreate(s)
        CrashGuard.install(applicationContext)
        setContentView(R.layout.activity_main)
        Store.loadSettings(this)
        gridView = Store.defaultGrid

        sidebar = findViewById(R.id.sidebar)
        val albumList = findViewById<RecyclerView>(R.id.albumList)
        albumAdapter = AlbumAdapter { key -> onPickAlbum(key) }
        albumList.layoutManager = LinearLayoutManager(this)
        albumList.adapter = albumAdapter

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener {
            CrashGuard.guard {
                when (it.itemId) {
                    R.id.nav_library -> switchTab(0)
                    R.id.nav_cards -> switchTab(1)
                    R.id.nav_settings -> switchTab(2)
                }
            }
            true
        }

        applyBars()
        ensurePermission { loadPhotos() }
    }

    /** 状态栏与导航栏半透明，让背景渐变透上来，形成整体通透感。 */
    private fun applyBars() {
        val v = TypedValue()
        theme.resolveAttribute(R.attr.bgTopColor, v, true)
        val top = v.data
        theme.resolveAttribute(R.attr.bgBottomColor, v, true)
        window.statusBarColor = top
        window.navigationBarColor = v.data
    }

    /** 换肤：写入偏好 → 重建 Activity，主题属性自动生效。 */
    private fun switchSkin(key: String) {
        if (key == SkinNow.skin.key) return
        CrashGuard.safe(this, "换肤失败") {
            SkinNow.apply(applicationContext, key)
            Thumbs.clear()
            recreate()
        }
    }

    private fun ensurePermission(after: () -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, need) == PackageManager.PERMISSION_GRANTED) after()
        else permLauncher.launch(arrayOf(need))
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.any { it }) loadPhotos()
        else Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show()
    }

    private val deleteConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { loadPhotos() }

    // ---------- 数据
    private fun loadPhotos() {
        CrashGuard.safe(this, "扫描照片失败") { loadPhotosInner() }
    }

    private fun loadPhotosInner() {
        photos = Repo.scan(this)
        val blocked = Store.blocked(this)
        photos = photos.filter { it.album !in blocked }
        switchTab(tab)
    }

    private fun visible(): List<Photo> {
        val fav = Store.favorites(this)
        var l = when (albumKey) {
            KEY_ALL -> photos
            KEY_FAV -> photos.filter { fav.contains(it.id.toString()) }
            else -> photos.filter { it.album == albumKey }
        }
        if (month != null) l = l.filter { it.month == month }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            l = l.filter { it.name.lowercase().contains(q) || it.album.lowercase().contains(q) }
        }
        return when (sort) {
            1 -> l.sortedBy { it.dateSec }
            2 -> l.sortedBy { it.name }
            3 -> l.sortedByDescending { it.size }
            else -> l.sortedByDescending { it.dateSec }
        }
    }

    // ---------- 侧边栏
    private fun renderSidebar() {
        val fav = Store.favorites(this)
        val rows = ArrayList<AlbumAdapter.Row>()
        rows.add(AlbumAdapter.Row(KEY_ALL, getString(R.string.all_photos), photos.size, albumKey == KEY_ALL && tab == 0))
        rows.add(AlbumAdapter.Row(KEY_FAV, getString(R.string.favorites), fav.size, albumKey == KEY_FAV))
        val groups = photos.groupBy { it.album }.toList().sortedByDescending { it.second.size }
        groups.forEach { (name, list) ->
            rows.add(AlbumAdapter.Row(name, name, list.size, albumKey == name))
        }
        rows.add(AlbumAdapter.Row(KEY_TRASH, getString(R.string.trash), Store.trash(this).size, tab == 3))
        albumAdapter.submit(rows)
    }

    private fun onPickAlbum(key: String) {
        when (key) {
            KEY_TRASH -> switchTab(3)
            KEY_FAV -> { albumKey = KEY_FAV; month = null; switchTab(0) }
            else -> { albumKey = key; month = null; switchTab(0) }
        }
    }

    // ---------- 页面切换
    private fun switchTab(t: Int) {
        CrashGuard.safe(this, "页面切换失败") { switchTabInner(t) }
    }

    private fun switchTabInner(t: Int) {
        tab = t
        val holder = findViewById<android.widget.FrameLayout>(R.id.content)
        holder.removeAllViews()
        sidebar.visibility = if (t == 1 || t == 2) View.GONE else View.VISIBLE
        when (t) {
            0 -> { libraryView = layoutInflater.inflate(R.layout.page_library, holder, false); holder.addView(libraryView); bindLibrary(libraryView!!) }
            1 -> { cardsView = layoutInflater.inflate(R.layout.page_cards, holder, false); holder.addView(cardsView); bindCards(cardsView!!) }
            2 -> { settingsView = layoutInflater.inflate(R.layout.page_settings, holder, false); holder.addView(settingsView); bindSettings(settingsView!!) }
            3 -> { trashView = layoutInflater.inflate(R.layout.page_trash, holder, false); holder.addView(trashView); bindTrash(trashView!!) }
        }
        renderSidebar()
    }

    // ---------- 图库
    private fun bindLibrary(v: View) {
        val list = v.findViewById<RecyclerView>(R.id.photos)
        val months = v.findViewById<RecyclerView>(R.id.months)
        val search = v.findViewById<EditText>(R.id.search)
        val selBar = v.findViewById<LinearLayout>(R.id.selBar)
        val tip = v.findViewById<TextView>(R.id.tip)

        chipAdapter = ChipAdapter { m -> month = m; refreshLibrary() }
        months.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        months.adapter = chipAdapter

        photoAdapter = PhotoAdapter(
            this, gridView,
            onClick = { p, _ ->
                if (photoAdapter?.selectMode == true) { toggleSelect(p); refreshLibrary() }
                else preview(p)
            },
            onLongClick = { p, _, anchor ->
                // 不能在长按回调里同步刷新：notifyDataSetChanged 会重建 ViewHolder
                // 使 anchor 失效并掐断长按事件，菜单就弹不出来。延后一帧再处理。
                photoAdapter?.selectMode = true
                photoAdapter?.selected?.add(p.id)
                anchor.post {
                    refreshLibrary()
                    // 再延一帧，并确认视图仍附着、Activity 仍存活，避免 BadTokenException
                    anchor.post {
                        if (anchor.isAttachedToWindow && !isFinishing && !isDestroyed) {
                            showPhotoMenu(p, anchor)
                        }
                    }
                }
                true
            }
        )
        applyLayoutManager(list)
        list.adapter = photoAdapter
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(12)
        list.recycledViewPool.setMaxRecycledViews(0, 24)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; refreshLibrary() }
        })

        v.findViewById<ImageButton>(R.id.btnView).setOnClickListener {
            gridView = !gridView
            Store.defaultGrid = gridView; Store.saveSettings(this)
            switchTab(0)
            Toast.makeText(this, if (gridView) "已切换为宫格" else "已切换为列表", Toast.LENGTH_SHORT).show()
        }
        v.findViewById<ImageButton>(R.id.btnSort).setOnClickListener {
            sort = (sort + 1) % 4
            applyToolbarIcons(v)
            refreshLibrary()
            Toast.makeText(this, "排序：${SORT_LABELS[sort]}", Toast.LENGTH_SHORT).show()
        }

        applyToolbarIcons(v)

        v.findViewById<Button>(R.id.btnSelAll).setOnClickListener {
            photoAdapter?.selected?.addAll(visible().map { it.id }); refreshLibrary()
        }
        v.findViewById<Button>(R.id.btnSelExit).setOnClickListener { exitSelect() }
        v.findViewById<Button>(R.id.btnFav).setOnClickListener { selFav() }
        v.findViewById<Button>(R.id.btnMove).setOnClickListener { selMove() }
        v.findViewById<Button>(R.id.btnRename).setOnClickListener { selRename() }
        v.findViewById<Button>(R.id.btnDelete).setOnClickListener { selDelete() }

        refreshLibrary()
    }

    /** 右上角两个按钮的图标随当前视图 / 排序实时变化。 */
    private fun applyToolbarIcons(v: View) {
        v.findViewById<ImageButton>(R.id.btnView).setImageResource(
            if (gridView) R.drawable.ic_view_grid else R.drawable.ic_view_list
        )
        v.findViewById<ImageButton>(R.id.btnSort).apply {
            setImageResource(SORT_ICONS[sort])
        }
        tintIcons(v)
    }

    /** 工具栏图标按当前主题的强调色着色。 */
    private fun tintIcons(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.accentColor, tv, true)
        v.findViewById<ImageButton>(R.id.btnView).setColorFilter(tv.data)
        v.findViewById<ImageButton>(R.id.btnSort).setColorFilter(tv.data)
    }

    /** 长按单张照片弹出的操作菜单：增删改 + 进入多选。 */
    private fun showPhotoMenu(p: Photo, anchor: View) {
        CrashGuard.safe(this, "打开菜单失败") { showPhotoMenuInner(p, anchor) }
    }

    private fun showPhotoMenuInner(p: Photo, anchor: View) {
        if (isFinishing || isDestroyed || !anchor.isAttachedToWindow) return
        val fav = Store.favorites(this)
        val isFav = fav.contains(p.id.toString())
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, if (isFav) "取消收藏" else "收藏")
        menu.menu.add(0, 2, 0, "移动到相册")
        menu.menu.add(0, 3, 0, "重命名")
        menu.menu.add(0, 4, 0, "删除到回收站")
        menu.menu.add(0, 5, 0, "多选更多")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    if (isFav) fav.remove(p.id.toString()) else fav.add(p.id.toString())
                    Store.setFavorites(this, fav)
                    Toast.makeText(this, if (isFav) "已取消收藏" else "已收藏", Toast.LENGTH_SHORT).show()
                    exitSelect()
                }
                2 -> { Ui.albumSheet(this, allAlbumNames()) { a -> moveOne(p, a) } }
                3 -> renameOne(p)
                4 -> confirmDelete(listOf(p))
                5 -> Toast.makeText(this, "已进入多选，可继续点选更多", Toast.LENGTH_SHORT).show()
            }
            true
        }
        menu.show()
    }

    private fun moveOne(p: Photo, album: String) {
        Ui.async(this, io = {
            val move = Store.cardModeMove
            var ok = false
            if (move) {
                ok = Ui.write(this, { Repo.moveToAlbum(this, p, album) }) { e -> requestDeleteConsent(e) }
                if (!ok) ok = Repo.copyToAlbum(this, p, album)
            } else {
                ok = Repo.copyToAlbum(this, p, album)
            }
            ok
        }, ui = { ok ->
            Ui.toast(this, if (ok) "已移动到「$album」" else "移动失败")
            exitSelect(); loadPhotos()
        })
    }

    private fun applyLayoutManager(list: RecyclerView) {
        list.layoutManager = if (gridView) {
            val w = resources.displayMetrics.widthPixels
            val span = (w * 0.8f / 104.dp).toInt().coerceIn(3, 6)
            GridLayoutManager(this, span)
        } else LinearLayoutManager(this)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun refreshLibrary() {
        val v = libraryView ?: return
        val list = v.findViewById<RecyclerView>(R.id.photos)
        val selBar = v.findViewById<LinearLayout>(R.id.selBar)
        val selText = v.findViewById<TextView>(R.id.selText)
        val btnRename = v.findViewById<Button>(R.id.btnRename)

        val data = visible()
        photoAdapter?.submit(data)
        photoAdapter?.notifyDataSetChanged()

        chipAdapter.submit(data.map { it.month }.distinct().sortedDescending(), month)

        val sel = photoAdapter?.selected?.size ?: 0
        val mode = photoAdapter?.selectMode == true && sel > 0
        selBar.visibility = if (mode) View.VISIBLE else View.GONE
        selText.text = "已选 $sel 项"
        btnRename.visibility = if (sel == 1) View.VISIBLE else View.GONE
    }

    private fun toggleSelect(p: Photo) {
        val s = photoAdapter?.selected ?: return
        if (!s.add(p.id)) s.remove(p.id)
        if (s.isEmpty()) photoAdapter?.selectMode = false
    }

    private fun exitSelect() {
        photoAdapter?.selectMode = false
        photoAdapter?.selected?.clear()
        refreshLibrary()
    }

    private fun selectedPhotos(): List<Photo> {
        val ids = photoAdapter?.selected ?: return emptyList()
        return photos.filter { ids.contains(it.id) }
    }

    // ---------- 多选操作
    private fun selFav() {
        val list = selectedPhotos()
        val fav = Store.favorites(this)
        val allFav = list.all { fav.contains(it.id.toString()) }
        list.forEach { if (allFav) fav.remove(it.id.toString()) else fav.add(it.id.toString()) }
        Store.setFavorites(this, fav)
        Toast.makeText(this, if (allFav) "已取消收藏" else "已收藏 ${list.size} 张", Toast.LENGTH_SHORT).show()
        exitSelect()
    }

    private fun selMove() {
        val list = selectedPhotos()
        if (list.isEmpty()) return
        Ui.albumSheet(this, allAlbumNames()) { album ->
            Ui.async(this, io = {
                var ok = 0
                list.forEach {
                    if (Ui.write(this, { Repo.copyToAlbum(this, it, album) }) { e -> requestDeleteConsent(e) }) ok++
                }
                ok
            }, ui = { ok ->
                Ui.toast(this, "已移动 $ok 张到「$album」")
                loadPhotos(); exitSelect()
            })
        }
    }

    private fun selRename() { renameOne(selectedPhotos().firstOrNull() ?: return) }

    private fun renameOne(p: Photo) {
        val input = EditText(this).apply { setText(p.name.substringBeforeLast('.')) }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val new = input.text.toString().trim()
                if (new.isBlank()) return@setPositiveButton
                val ext = p.name.substringAfterLast('.', "")
                val cv = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "$new.$ext") }
                val ok = Ui.write(this, {
                    contentResolver.update(Repo.uriOf(p), cv, null, null) > 0
                }) { e -> requestDeleteConsent(e) }
                Ui.toast(this, if (ok) "已重命名" else "重命名失败，可能无权修改该文件")
                if (ok) { exitSelect(); loadPhotos() }
            }.show()
    }

    private fun selDelete() { confirmDelete(selectedPhotos()) }

    /** 删除确认：单张与批量共用，先入回收站。 */
    private fun confirmDelete(list: List<Photo>) {
        if (list.isEmpty()) return
        Ui.confirm(this, "删除", "这 ${list.size} 张会先移入回收站，可还原。", okText = "删除") {
            trashPhotos(list)
        }
    }

    private fun trashPhotos(list: List<Photo>) {
        if (list.isEmpty()) return
        Ui.async(this, io = {
            val items = Store.trash(this).toMutableList()
            var n = 0
            list.forEach { p ->
                Ui.write(this, {
                    Repo.moveToTrash(this, p) { e -> Ui.main { requestDeleteConsent(e) } }?.let { items.add(it); n++ }
                    true
                }) { e -> Ui.main { requestDeleteConsent(e) } }
            }
            Store.saveTrash(this, items)
            n
        }, ui = { n ->
            Ui.toast(this, "已清理 $n 张到回收站")
            exitSelect(); loadPhotos()
        })
    }

    private fun preview(p: Photo) {
        CrashGuard.safe(this, "预览失败") { previewInner(p) }
    }

    private fun previewInner(p: Photo) {
        val fav = Store.favorites(this)
        val isFav = fav.contains(p.id.toString())
        Ui.dialog(this) {
            setTitle(p.name)
            setMessage("${p.album} · ${p.dateText} · ${formatSize(p.size)}")
            setNegativeButton("关闭", null)
            if (Store.previewActions) {
                setNeutralButton(if (isFav) "取消收藏" else "收藏") { _, _ ->
                    if (isFav) fav.remove(p.id.toString()) else fav.add(p.id.toString())
                    Store.setFavorites(this@MainActivity, fav); renderSidebar()
                }
                setPositiveButton("清理") { _, _ -> trashPhotos(listOf(p)) }
            }
        }
    }

    // ---------- 卡片页
    private fun bindCards(v: View) {
        cards = CardsPage(this, v)
        cards.bind()
    }

    fun cardPhotos(): List<Photo> = visible()

    /** 全量相册名：归类目标必须来自完整列表，不能受当前筛选影响。 */
    fun allAlbumNames(): List<String> = photos.map { it.album }.distinct().sorted()

    fun afterCardAction() {
        loadPhotos()
    }

    // ---------- 设置页
    private fun bindSettings(v: View) {
        val stat = v.findViewById<TextView>(R.id.stat)
        stat.text = "共 ${photos.size} 张照片 · ${photos.map { it.album }.distinct().size} 个图集 · 回收站 ${Store.trash(this).size} 项"
        val swPreview = v.findViewById<Switch>(R.id.swPreview)
        val swGrid = v.findViewById<Switch>(R.id.swGrid)
        swPreview.isChecked = Store.previewActions
        swGrid.isChecked = Store.defaultGrid
        swPreview.setOnCheckedChangeListener { _, b -> CrashGuard.guard { Store.previewActions = b; Store.saveSettings(this) } }
        swGrid.setOnCheckedChangeListener { _, b -> CrashGuard.guard { Store.defaultGrid = b; Store.saveSettings(this) } }
        v.findViewById<TextView>(R.id.version).text = "光影相册 · 原生安卓版 v${APP_VERSION}"
        val skinList = v.findViewById<RecyclerView>(R.id.skinList)
        skinList.layoutManager = GridLayoutManager(this, 3)
        skinList.adapter = SkinAdapter(
            current = SkinNow.skin.key,
            picked = { k -> k != SkinNow.skin.key },
            onPick = { switchSkin(it.key) },
        )

        val crash = CrashGuard.read(this)
        if (crash.isNotBlank()) {
            val dim = TypedValue().let { theme.resolveAttribute(R.attr.textColorDim, it, true); it.data }
            val box = TextView(this).apply {
                text = "最近崩溃记录（长按可复制）\n\n$crash"
                setTextIsSelectable(true)
                textSize = 10f
                setTextColor(dim)
                setPadding(16, 16, 16, 16)
            }
            v.findViewById<LinearLayout>(R.id.settingsBody)?.addView(box)
        }

        v.findViewById<Button>(R.id.btnRescan).setOnClickListener {
            Thumbs.clear(); loadPhotos()
            Toast.makeText(this, "扫描完成", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 回收站
    private fun bindTrash(v: View) {
        val list = v.findViewById<RecyclerView>(R.id.trashList)
        val count = v.findViewById<TextView>(R.id.trashCount)
        val items = Store.trash(this).sortedByDescending { it.at }
        count.text = "回收站 ${items.size} 项"
        trashAdapter = TrashAdapter(this) { item ->
            Ui.async(this, io = {
                val ok = Ui.write(this, { Repo.restore(this, item) }) { e -> requestDeleteConsent(e) }
                if (ok) {
                    val left = Store.trash(this).toMutableList().apply { removeAll { it.id == item.id } }
                    Store.saveTrash(this, left)
                }
                ok
            }, ui = { ok ->
                Ui.toast(this, if (ok) "已还原" else "还原失败")
                loadPhotos()
            })
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = trashAdapter
        trashAdapter?.submit(items)
        v.findViewById<Button>(R.id.btnEmpty).setOnClickListener {
            Ui.confirm(this, "清空回收站", "将彻底删除 ${items.size} 项，无法恢复。", okText = "清空") {
                items.forEach { runCatching { java.io.File(it.file).delete() } }
                Store.saveTrash(this, emptyList())
                loadPhotos()
            }
        }
    }

    /** Android 11+ 删除他人应用媒体需要授权时，交给系统弹窗。 */
    fun requestDeleteConsent(e: RecoverableSecurityException) {
        deleteConsent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
    }
}
