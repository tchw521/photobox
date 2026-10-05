package cn.photobox.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 主界面：左侧相册栏（1/5）+ 内容区（4/5）+ 底部三导航。
 *
 * 设计原则（本版重点）：
 * 1. **不使用任何 Material / AppCompat 组件**，只用系统控件，依赖极少。
 * 2. **布局与 drawable 中不使用主题属性 ?attr**，颜色全部由 Skin 在代码里提供。
 *    此前多次崩溃都源于主题属性在资源解析阶段的时序问题。
 * 3. 所有弹窗在 show 前校验 Activity 存活，避免 BadTokenException。
 * 4. 媒体库写操作统一走 Ui.write，捕获 RecoverableSecurityException。
 */
class MainActivity : Activity() {

    // ---------- 状态
    private var photos: List<Photo> = emptyList()
    private var albumKey = KEY_ALL
    private var month: String? = null
    private var query = ""
    private var sort = 0                 // 0 日期新→旧 1 旧→新 2 名称 3 大小
    private var gridView = true
    private var tab = 0                  // 0 图库 1 卡片 2 设置 3 回收站 4 查重

    private var root: View? = null
    private var sidebar: View? = null
    private var libraryView: View? = null
    private var cardsView: View? = null
    private var settingsView: View? = null
    private var trashView: View? = null
    private var dedupView: View? = null

    private var albumAdapter: AlbumAdapter? = null
    private var chipAdapter: ChipAdapter? = null
    private var photoAdapter: PhotoAdapter? = null
    private var trashAdapter: TrashAdapter? = null

    private var cards: CardsPage? = null

    companion object {
        val SORT_LABELS = arrayOf("日期新→旧", "日期旧→新", "名称", "大小")
        val SORT_ICONS = intArrayOf(
            R.drawable.ic_sort_time,
            R.drawable.ic_sort_time_asc,
            R.drawable.ic_sort_name,
            R.drawable.ic_sort_size,
        )
        const val APP_VERSION = "1.3.0"
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_TRASH = "\u0000trash"
    }

    // ---------- 生命周期
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        CrashGuard.install(applicationContext)
        SkinNow.load(applicationContext)
        Store.loadSettings(this)
        gridView = Store.defaultGrid
        sort = Store.sortDefault

        CrashGuard.guard { initUi() }
        // 先渲染主页，界面立即可见；权限与扫描并行进行
        CrashGuard.guard { switchTab(tab) }
        CrashGuard.guard { ensurePermission { loadPhotos() } }
    }

    private fun initUi() {
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        sidebar = findViewById(R.id.sidebar)

        // 背景与分隔线
        skin {
            root?.background = Glass.background(it)
            findViewById<View>(R.id.divider)?.setBackgroundColor(it.stroke)
            findViewById<TextView>(R.id.sidebarTitle)?.setTextColor(it.text)
        }

        val albumList = findViewById<RecyclerView>(R.id.albumList)
        albumAdapter = AlbumAdapter(
            onClick = { key -> CrashGuard.guard { onPickAlbum(key) } },
            onLongClick = { key -> CrashGuard.guard { albumActions(key) } },
        )
        albumList?.layoutManager = LinearLayoutManager(this)
        albumList?.adapter = albumAdapter

        bindNav()
        applyBars()
    }

    /** 底栏三个按钮：自绘图标 + 文字，选中态用强调色。 */
    private fun bindNav() {
        val s = SkinNow.skin
        findViewById<View>(R.id.bottomNav)?.background = Glass.card(s, 0f, strong = true)
        val items = listOf(
            Triple(R.id.navLibrary, R.id.navLibraryIcon, R.id.navLibraryText) to R.drawable.ic_tab_library,
            Triple(R.id.navCards, R.id.navCardsIcon, R.id.navCardsText) to R.drawable.ic_tab_cards,
            Triple(R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsText) to R.drawable.ic_tab_settings,
        )
        val targets = listOf(0, 1, 2)
        items.forEachIndexed { i, (ids, icon) ->
            val box = findViewById<View>(ids.first)
            val iv = findViewById<ImageView>(ids.second)
            val tv = findViewById<TextView>(ids.third)
            iv?.setImageResource(icon)
            box?.setOnClickListener { CrashGuard.guard { switchTab(targets[i]) } }
            Unit
        }
    }

    /** 底栏选中态高亮。 */
    private fun updateNavState() {
        val s = SkinNow.skin
        val map = listOf(
            Triple(R.id.navLibraryIcon, R.id.navLibraryText, 0),
            Triple(R.id.navCardsIcon, R.id.navCardsText, 1),
            Triple(R.id.navSettingsIcon, R.id.navSettingsText, 2),
        )
        map.forEach { (iconId, textId, t) ->
            val on = (t == tab)
            findViewById<ImageView>(iconId)?.setColorFilter(if (on) s.accent else s.textDim)
            findViewById<TextView>(textId)?.setTextColor(if (on) s.accent else s.textDim)
        }
    }

    private fun applyBars() {
        CrashGuard.guard {
            val s = SkinNow.skin
            window.statusBarColor = s.bgTop
            window.navigationBarColor = s.bgBottom
        }
    }

    /** 读取当前皮肤并执行。 */
    private inline fun skin(block: (Skin) -> Unit) = CrashGuard.guard { block(SkinNow.skin) }

    // ---------- 权限
    private fun ensurePermission(after: () -> Unit) {
        // N4 兼容：Android 13+ 用 READ_MEDIA_IMAGES，12 及以下用 READ_EXTERNAL_STORAGE
        val need = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        // Android 14 起支持"部分授权"（用户只勾选部分照片），此时也视为可用，
        // 只是扫描结果会少一些，不应阻断进入主页。
        val partial = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        if (partial ||
            ContextCompat.checkSelfPermission(this, need) == PackageManager.PERMISSION_GRANTED
        ) {
            CrashGuard.guard(after)
        } else {
            CrashGuard.guard {
                requestPermissions(arrayOf(need), 1001)
            }
            pendingAfterPermission = after
        }
    }

    private var pendingAfterPermission: (() -> Unit)? = null

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, res: IntArray,
    ) {
        super.onRequestPermissionsResult(code, perms, res)
        val granted = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
        // 无论是否授权都进入主页，避免停在空白页
        CrashGuard.guard { loadPhotos() }
        if (!granted) Ui.toast(this, getString(R.string.need_permission))
        pendingAfterPermission = null
    }

    // ---------- 数据
    private fun loadPhotos() {
        Ui.io.execute {
            val list = CrashGuard.result({ Repo.scan(this@MainActivity) }, emptyList())
            val blocked = Store.blocked(this@MainActivity)
            val keep = CrashGuard.result({ list.filter { it.album !in blocked } }, list)
            cleanTrashIfNeeded()
            Ui.main {
                CrashGuard.guard {
                    if (!isFinishing) {
                        photos = keep
                        switchTab(tab)
                    }
                }
            }
        }
    }

    /** 回收站超期自动清理：开启后，超过 30 天的项目及其文件一并删除。 */
    private fun cleanTrashIfNeeded() {
        if (!Store.autoCleanTrash) return
        CrashGuard.guard {
            val all = Store.trash(this)
            val deadline = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val keep = all.filter { it.at >= deadline }
            if (keep.size == all.size) return
            all.filter { it.at < deadline }.forEach {
                runCatching { java.io.File(it.file).delete() }
            }
            Store.saveTrash(this, keep)
        }
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
        val s = SkinNow.skin
        val fav = Store.favorites(this)
        val rows = ArrayList<AlbumAdapter.Row>()
        rows.add(AlbumAdapter.Row(KEY_ALL, getString(R.string.all_photos), photos.size, albumKey == KEY_ALL && tab == 0))
        rows.add(AlbumAdapter.Row(KEY_FAV, getString(R.string.favorites), fav.size, albumKey == KEY_FAV))
        photos.groupBy { it.album }.toList().sortedByDescending { it.second.size }.forEach { (name, list) ->
            rows.add(AlbumAdapter.Row(name, name, list.size, albumKey == name))
        }
        rows.add(AlbumAdapter.Row(KEY_TRASH, getString(R.string.trash), Store.trash(this).size, tab == 3))
        albumAdapter?.submit(rows)
        Unit
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
        val holder = findViewById<FrameLayout>(R.id.content) ?: return
        holder.removeAllViews()
        // 卡片页与设置页不显示侧栏
        sidebar?.visibility = if (t == 1 || t == 2 || t == 4) View.GONE else View.VISIBLE
        when (t) {
            0 -> {
                libraryView = layoutInflater.inflate(R.layout.page_library, holder, false)
                holder.addView(libraryView)
                bindLibrary(libraryView!!)
            }
            1 -> {
                cardsView = layoutInflater.inflate(R.layout.page_cards, holder, false)
                holder.addView(cardsView)
                bindCards(cardsView!!)
            }
            2 -> {
                settingsView = layoutInflater.inflate(R.layout.page_settings, holder, false)
                holder.addView(settingsView)
                bindSettings(settingsView!!)
            }
            3 -> {
                trashView = layoutInflater.inflate(R.layout.page_trash, holder, false)
                holder.addView(trashView)
                bindTrash(trashView!!)
            }
            4 -> {
                dedupView = layoutInflater.inflate(R.layout.page_dedup, holder, false)
                holder.addView(dedupView)
                DedupPage(this, dedupView!!).bind()
            }
        }
        renderSidebar()
        updateNavState()
    }

    // ---------- 图库
    private fun bindLibrary(v: View) {
        val s = SkinNow.skin
        val list = v.findViewById<RecyclerView>(R.id.photos)
        val months = v.findViewById<RecyclerView>(R.id.months)
        val search = v.findViewById<EditText>(R.id.search)
        val selBar = v.findViewById<LinearLayout>(R.id.selBar)
        val tip = v.findViewById<TextView>(R.id.tip)

        search?.setTextColor(s.text)
        search?.setHintTextColor(s.textDim)
        tip?.setTextColor(s.textDim)

        chipAdapter = ChipAdapter { m -> month = m; refreshLibrary() }
        months?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        months?.adapter = chipAdapter

        photoAdapter = PhotoAdapter(
            this, gridView,
            onClick = { p, _ ->
                CrashGuard.guard {
                    if (photoAdapter?.selectMode == true) { toggleSelect(p); refreshLibrary() }
                    else preview(p)
                }
            },
            onLongClick = { p, _, anchor ->
                // 不能在长按回调里同步刷新：notifyDataSetChanged 会重建 ViewHolder，
                // 使 anchor 失效并掐断长按序列，菜单就弹不出来。延后一帧再处理。
                photoAdapter?.selectMode = true
                photoAdapter?.selected?.add(p.id)
                anchor.post {
                    refreshLibrary()
                    anchor.post {
                        if (anchor.isAttachedToWindow && !isFinishing) {
                            CrashGuard.guard { showPhotoMenu(p) }
                        }
                    }
                }
                true
            }
        )
        applyLayoutManager(list)
        list?.adapter = photoAdapter
        // 滑动多选：已进入多选模式后，按住划过即可批量选中
        list?.let { rv ->
            Ui.swipeSelect(
                rv,
                isSelectMode = { photoAdapter?.selectMode == true },
                pick = { i ->
                    val p = photoAdapter?.item(i)
                    if (p != null) photoAdapter?.selected?.add(p.id)
                },
                changed = { refreshSelectionOnly() }
            )
        }
        list?.setHasFixedSize(true)
        list?.setItemViewCacheSize(12)
        list?.recycledViewPool?.setMaxRecycledViews(0, 24)

        search?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                query = e?.toString() ?: ""
                CrashGuard.guard { refreshLibrary() }
            }
        })

        v.findViewById<ImageButton>(R.id.btnView)?.setOnClickListener {
            CrashGuard.guard {
                gridView = !gridView
                Store.defaultGrid = gridView
                Store.saveSettings(this)
                switchTab(0)
                Ui.toast(this, if (gridView) "已切换为宫格" else "已切换为列表")
            }
        }
        v.findViewById<ImageButton>(R.id.btnSort)?.setOnClickListener {
            CrashGuard.guard {
                sort = (sort + 1) % 4
                applyToolbarIcons(v)
                refreshLibrary()
                Ui.toast(this, "排序：${SORT_LABELS[sort]}")
            }
        }

        applyToolbarIcons(v)

        v.findViewById<Button>(R.id.btnSelAll)?.setOnClickListener {
            CrashGuard.guard {
                photoAdapter?.selected?.addAll(visible().map { it.id })
                refreshLibrary()
            }
        }
        v.findViewById<Button>(R.id.btnSelExit)?.setOnClickListener { CrashGuard.guard { exitSelect() } }
        v.findViewById<Button>(R.id.btnFav)?.setOnClickListener { CrashGuard.guard { selFav() } }
        v.findViewById<Button>(R.id.btnMove)?.setOnClickListener { CrashGuard.guard { selMove() } }
        v.findViewById<Button>(R.id.btnRename)?.setOnClickListener { CrashGuard.guard { selRename() } }
        v.findViewById<Button>(R.id.btnDelete)?.setOnClickListener { CrashGuard.guard { selDelete() } }

        styleButtons(v)
        refreshLibrary()
    }

    /** 右上角两个按钮的图标随当前视图 / 排序实时变化。 */
    private fun applyToolbarIcons(v: View) {
        val s = SkinNow.skin
        v.findViewById<ImageButton>(R.id.btnView)?.apply {
            setImageResource(if (gridView) R.drawable.ic_view_grid else R.drawable.ic_view_list)
            setColorFilter(s.accent)
        }
        v.findViewById<ImageButton>(R.id.btnSort)?.apply {
            setImageResource(SORT_ICONS[sort])
            setColorFilter(s.accent)
        }
    }

    /** 页面内按钮统一皮肤着色（系统 Button 默认样式与深色底不搭）。 */
    private fun styleButtons(v: View) {
        val s = SkinNow.skin
        val ids = listOf(
            R.id.btnSelAll, R.id.btnSelExit, R.id.btnFav, R.id.btnMove,
            R.id.btnRename, R.id.btnDelete, R.id.btnEmpty, R.id.restore,
        )
        ids.forEach { id ->
            val b = v.findViewById<Button>(id)
            if (b != null) {
                b.setTextColor(s.text)
                b.background = Glass.solid(s.glass, 8f)
            }
        }
    }

    private fun applyLayoutManager(list: RecyclerView?) {
        list?.layoutManager = if (gridView) {
            val w = resources.displayMetrics.widthPixels
            val span = (w * 0.8f / 104.dp).toInt().coerceIn(3, 6)
            GridLayoutManager(this, span)
        } else LinearLayoutManager(this)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun refreshLibrary() {
        val v = libraryView ?: return
        val selBar = v.findViewById<LinearLayout>(R.id.selBar)
        val selText = v.findViewById<TextView>(R.id.selText)
        val btnRename = v.findViewById<Button>(R.id.btnRename)
        val tip = v.findViewById<TextView>(R.id.tip)

        val data = visible()
        photoAdapter?.submit(data)

        chipAdapter?.submit(data.map { it.month }.distinct().sortedDescending(), month)

        val sel = photoAdapter?.selected?.size ?: 0
        val mode = photoAdapter?.selectMode == true && sel > 0
        selBar?.visibility = if (mode) View.VISIBLE else View.GONE
        selText?.text = "已选 $sel 项"
        btnRename?.visibility = if (sel == 1) View.VISIBLE else View.GONE

        tip?.text = when {
            photos.isEmpty() -> "暂无照片，可在设置里重新扫描，或先授予照片权限"
            data.isEmpty() -> "当前筛选下没有照片"
            else -> "${data.size} 张"
        }
    }

    /**
     * 滑动多选过程中的轻量刷新：只更新选中标记与计数。
     * 不走 notifyDataSetChanged，避免连续划过时列表跳动与卡顿。
     */
    private fun refreshSelectionOnly() {
        val v = libraryView ?: return
        val rv = v.findViewById<RecyclerView>(R.id.photos) ?: return
        val sel = photoAdapter?.selected ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(child)
            val p = CrashGuard.result({ photoAdapter?.item(pos) }, null) ?: continue
            val on = sel.contains(p.id)
            child.findViewById<View>(R.id.mask)?.visibility =
                if (on) View.VISIBLE else View.GONE
            child.findViewById<View>(R.id.check)?.visibility =
                if (on) View.VISIBLE else View.GONE
        }
        val n = sel.size
        v.findViewById<LinearLayout>(R.id.selBar)?.visibility =
            if (photoAdapter?.selectMode == true && n > 0) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.selText)?.text = "已选 $n 项"
        v.findViewById<Button>(R.id.btnRename)?.visibility =
            if (n == 1) View.VISIBLE else View.GONE
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
        Ui.toast(this, if (allFav) "已取消收藏" else "已收藏 ${list.size} 张")
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
        val input = EditText(this).apply {
            setText(p.name.substringBeforeLast('.'))
            setTextColor(SkinNow.skin.text)
        }
        Ui.dialog(this) {
            setTitle("重命名")
            setView(input)
            setNegativeButton("取消", null)
            setPositiveButton("确定") { _, _ ->
                val new = input.text.toString().trim()
                if (new.isBlank()) return@setPositiveButton
                val ext = p.name.substringAfterLast('.', "")
                val cv = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "$new.$ext") }
                val ok = Ui.write(this@MainActivity, {
                    contentResolver.update(Repo.uriOf(p), cv, null, null) > 0
                }) { e -> requestDeleteConsent(e) }
                Ui.toast(this@MainActivity, if (ok) "已重命名" else "重命名失败，可能无权修改该文件")
                if (ok) { exitSelect(); loadPhotos() }
            }
        }
    }

    private fun selDelete() { confirmDelete(selectedPhotos()) }

    private fun confirmDelete(list: List<Photo>) {
        if (list.isEmpty()) return
        Ui.confirm(this, "删除", "这 ${list.size} 张会先移入回收站，可还原。", okText = "删除") {
            trashPhotos(list)
        }
    }

    /** 供查重页复用：批量清理并在真正完成后回调数量。 */
    fun trashPhotosPublic(list: List<Photo>, done: ((Int) -> Unit)? = null) {
        CrashGuard.guard { trashPhotos(list, done) }
    }

    private fun trashPhotos(list: List<Photo>, done: ((Int) -> Unit)? = null) {
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
            done?.let { CrashGuard.guard { it(n) } }
        })
    }

    /** 长按单张照片的操作菜单：增删改 + 进入多选。用列表弹窗，不用 PopupMenu。 */
    private fun showPhotoMenu(p: Photo) {
        val fav = Store.favorites(this)
        val isFav = fav.contains(p.id.toString())
        val actions = arrayOf(
            if (isFav) "取消收藏" else "收藏",
            "移动到相册", "重命名", "删除到回收站", "多选更多",
            "批量整理「${p.album}」", "相似照片查重",
        )
        Ui.dialog(this) {
            setTitle(p.name)
            setItems(actions) { _, which ->
                CrashGuard.guard {
                    when (which) {
                        0 -> {
                            if (isFav) fav.remove(p.id.toString()) else fav.add(p.id.toString())
                            Store.setFavorites(this@MainActivity, fav)
                            Ui.toast(this@MainActivity, if (isFav) "已取消收藏" else "已收藏")
                            exitSelect()
                        }
                        1 -> moveOne(p)
                        2 -> renameOne(p)
                        3 -> confirmDelete(listOf(p))
                        4 -> Ui.toast(this@MainActivity, "已进入多选，可继续点选更多")
                        5 -> batchArrange(p.album)
                        6 -> switchTab(4)
                    }
                }
            }
            setNegativeButton("取消", null)
        }
    }

    /**
     * 按图集批量整理：把某图集内全部照片整组移到目标图集。
     * 一次处理几十上百张，免去逐张过卡片的繁琐。
     */
    private fun batchArrange(from: String) {
        val count = photos.count { it.album == from }
        if (count == 0) {
            Ui.toast(this, "该图集没有照片")
            return
        }
        Ui.albumSheet(this, allAlbumNames().filter { it != from }) { to ->
            Ui.confirm(
                this, "批量整理",
                "把「$from」的 $count 张整组移动到「$to」，移动后原图集将空出。",
                okText = "开始"
            ) {
                Ui.async(this, io = {
                    CrashGuard.result({ Repo.moveAlbum(this, from, to) }, intArrayOf(0, 0, 0))
                }, ui = { r ->
                    val moved = r.getOrNull(0) ?: 0
                    val copied = r.getOrNull(1) ?: 0
                    Ui.toast(this, "已移动 $moved 张${if (copied > 0) "，另有 $copied 张以复制方式完成" else ""}")
                    exitSelect()
                    loadPhotos()
                })
            }
        }
    }

    /**
     * 图集操作：重命名 / 合并到其他图集 / 删除整个图集。
     * 侧栏长按图集时触发。
     */
    private fun albumActions(name: String) {
        if (name == KEY_ALL || name == KEY_FAV || name == KEY_TRASH) return
        val count = photos.count { it.album == name }
        val others = allAlbumNames().filter { it != name }
        val actions = mutableListOf("重命名图集", "合并到其他图集", "批量整理到其他图集")
        if (others.isNotEmpty()) actions.add("删除整个图集（$count 张入回收站）")
        Ui.dialog(this) {
            setTitle("图集：$name")
            setItems(actions.toTypedArray()) { _, which ->
                CrashGuard.guard {
                    when (which) {
                        0 -> Ui.input(this@MainActivity, "重命名图集", name) { newName ->
                            RenameAlbumTask(newName).run(name)
                        }
                        1 -> Ui.listSheet(this@MainActivity, "合并到", others) { i ->
                            RenameAlbumTask(others[i]).run(name)
                        }
                        2 -> batchArrange(name)
                        3 -> confirmDelete(photos.filter { it.album == name })
                    }
                }
            }
            setNegativeButton("取消", null)
        }
    }

    /** 图集改名 / 合并的执行体，复用同一段逻辑。 */
    private inner class RenameAlbumTask(private val to: String) {
        fun run(from: String) {
            if (to.isBlank() || to == from) return
            Ui.async(this@MainActivity, io = {
                CrashGuard.result({ Repo.renameAlbum(this@MainActivity, from, to) }, 0)
            }, ui = { n ->
                Ui.toast(
                    this@MainActivity,
                    if (allAlbumNames().contains(from)) "已处理 $n 张，改为「$to」" else "已处理 $n 张"
                )
                loadPhotos()
            })
        }
    }

    private fun moveOne(p: Photo) {
        Ui.albumSheet(this, allAlbumNames()) { album ->
            Ui.async(this, io = {
                var ok = false
                if (Store.cardModeMove) {
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
    }

    // ---------- 预览
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
                    Store.setFavorites(this@MainActivity, fav)
                    renderSidebar()
                }
                setPositiveButton("清理") { _, _ -> trashPhotos(listOf(p)) }
            }
        }
    }

    // ---------- 卡片页
    private fun bindCards(v: View) {
        cards = CardsPage(this, v)
        cards?.bind()
        styleButtons(v)
    }

    fun cardPhotos(): List<Photo> = visible()

    /** 全量相册名：归类目标必须来自完整列表，不能受当前筛选影响。 */
    fun allAlbumNames(): List<String> = photos.map { it.album }.distinct().sorted()

    fun afterCardAction() {
        CrashGuard.guard { loadPhotos() }
    }

    // ---------- 设置页
    /**
     * 设置页全部由代码构建：顶栏右上角一个皮肤按钮，点开弹列表选皮肤；
     * 下方分组列出开关与操作项，全部复用 Ui.switchRow / Ui.actionRow。
     */
    private fun bindSettings(v: View) {
        val s = SkinNow.skin
        val body = v.findViewById<LinearLayout>(R.id.settingsRoot) ?: return

        // 右上角皮肤按钮
        v.findViewById<ImageButton>(R.id.btnSkin)?.apply {
            setImageResource(R.drawable.ic_palette)
            setColorFilter(s.accent)
            setOnClickListener { CrashGuard.guard { showSkinPicker() } }
        }

        body.removeAllViews()

        // ---- 外观
        body.addView(Ui.section(this, "外观"))
        body.addView(Ui.actionRow(this, "皮肤", SkinNow.skin.name) { showSkinPicker() })
        body.addView(Ui.switchRow(this, "默认宫格视图", Store.defaultGrid) {
            Store.defaultGrid = it; Store.saveSettings(this); gridView = it
        })
        body.addView(Ui.actionRow(this, "默认排序", SORT_LABELS[Store.sortDefault]) {
            Ui.listSheet(this, "默认排序", SORT_LABELS.toList()) {
                Store.sortDefault = it
                Store.saveSettings(this)
                sort = it
                recreate()
            }
        })
        body.addView(Ui.switchRow(this, "相册名自动滚动（10 秒一轮）", Store.nameMarquee) {
            Store.nameMarquee = it; Store.saveSettings(this); renderSidebar()
        })

        // ---- 图库
        body.addView(Ui.section(this, "图库"))
        body.addView(Ui.switchRow(this, "预览弹窗显示快捷操作", Store.previewActions) {
            Store.previewActions = it; Store.saveSettings(this)
        })
        body.addView(Ui.switchRow(this, "长按直接进入多选", Store.longPressSelect) {
            Store.longPressSelect = it; Store.saveSettings(this)
        })

        // ---- 卡片页
        body.addView(Ui.section(this, "卡片页"))
        body.addView(Ui.switchRow(this, "归档用移动（关闭则复制）", Store.cardModeMove) {
            Store.cardModeMove = it; Store.saveSettings(this)
        })
        body.addView(Ui.switchRow(this, "显示手势提示", Store.cardHint) {
            Store.cardHint = it; Store.saveSettings(this)
        })

        // ---- 回收站
        body.addView(Ui.section(this, "回收站"))
        body.addView(Ui.switchRow(this, "自动清理超 30 天的项目", Store.autoCleanTrash) {
            Store.autoCleanTrash = it; Store.saveSettings(this)
        })
        body.addView(Ui.switchRow(this, "清空时输入「删除」二次确认", Store.trashGuard) {
            Store.trashGuard = it; Store.saveSettings(this)
        })

        // ---- 工具
        body.addView(Ui.section(this, "工具"))
        body.addView(Ui.actionRow(this, "相似照片查重", "检测重复并清理") { switchTab(4) })
        body.addView(Ui.actionRow(this, "按图集批量整理", "整组移动") {
            Ui.listSheet(this, "选择要整理的图集", allAlbumNames()) { i ->
                CrashGuard.guard { batchArrange(allAlbumNames()[i]) }
            }
        })
        body.addView(Ui.actionRow(this, "图集重命名 / 合并", "") {
            Ui.listSheet(this, "选择图集", allAlbumNames()) { i ->
                CrashGuard.guard { albumActions(allAlbumNames()[i]) }
            }
        })

        // ---- 操作
        body.addView(Ui.section(this, "操作"))
        body.addView(Ui.actionRow(this, "重新扫描照片", "") {
            Thumbs.clear(); loadPhotos(); Ui.toast(this, "扫描完成")
        })
        body.addView(Ui.actionRow(this, "清除缩略图缓存", "") {
            Thumbs.clear(); Ui.toast(this, "缓存已清除")
        })
        body.addView(Ui.actionRow(this, "查看崩溃记录", if (CrashGuard.read(this).isBlank()) "无" else "有记录") {
            showCrashLog()
        })

        // ---- 关于
        body.addView(Ui.section(this, "关于"))
        val stat = TextView(this).apply {
            text = "共 ${photos.size} 张照片 · ${photos.map { it.album }.distinct().size} 个图集 · 回收站 ${Store.trash(this@MainActivity).size} 项"
            textSize = 11f; setTextColor(s.textDim); setPadding(0, 8, 0, 4)
        }
        body.addView(stat)
        val ver = TextView(this).apply {
            text = "光影相册 · 原生安卓版 v$APP_VERSION"
            textSize = 11f; setTextColor(s.textDim); setPadding(0, 4, 0, 8)
        }
        body.addView(ver)
    }

    /** 皮肤选择：收纳在设置页右上角按钮内，点开弹列表。 */
    private fun showSkinPicker() {
        Ui.listSheet(this, "选择皮肤", Skins.ALL.map { it.name }) { i ->
            CrashGuard.guard { switchSkin(Skins.ALL[i].key) }
        }
    }

    /** 崩溃记录：可长按复制。 */
    private fun showCrashLog() {
        val log = CrashGuard.read(this)
        if (log.isBlank()) {
            Ui.toast(this, "暂无崩溃记录")
            return
        }
        val tv = TextView(this).apply {
            text = log
            textSize = 10f
            setTextColor(SkinNow.skin.textDim)
            setTextIsSelectable(true)
            setPadding(20, 20, 20, 20)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        Ui.dialog(this) {
            setTitle("崩溃记录")
            setView(sv)
            setNegativeButton("关闭", null)
            setPositiveButton("清空") { _: android.content.DialogInterface, _: Int ->
                CrashGuard.clear(this@MainActivity)
            }
        }
    }

    private fun switchSkin(key: String) {
        if (key == SkinNow.skin.key) return
        CrashGuard.safe(this, "换肤失败") {
            SkinNow.apply(applicationContext, key)
            Thumbs.clear()
            recreate()
        }
    }

    // ---------- 回收站
    private fun bindTrash(v: View) {
        val s = SkinNow.skin
        val list = v.findViewById<RecyclerView>(R.id.trashList)
        val count = v.findViewById<TextView>(R.id.trashCount)
        val items = Store.trash(this).sortedByDescending { it.at }
        count?.text = "回收站 ${items.size} 项"
        count?.setTextColor(s.text)

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
        list?.layoutManager = LinearLayoutManager(this)
        list?.adapter = trashAdapter
        // N3：复用池与缓存尺寸，避免长列表滚动时频繁创建 ViewHolder
        list?.setHasFixedSize(false)
        list?.setItemViewCacheSize(8)
        list?.recycledViewPool?.setMaxRecycledViews(0, 16)
        trashAdapter?.submit(items)

        v.findViewById<Button>(R.id.btnEmpty)?.setOnClickListener {
            CrashGuard.guard { confirmEmptyTrash(items) }
        }
        styleButtons(v)
    }

    /**
     * 清空回收站的双重保护：
     * 第一层常规确认，第二层要求手动输入「删除」二字才能执行。
     * 开启 trashGuard 时生效，避免误触造成不可恢复的丢失。
     */
    private fun confirmEmptyTrash(items: List<TrashItem>) {
        if (items.isEmpty()) {
            Ui.toast(this, "回收站是空的")
            return
        }
        val doDelete = {
            items.forEach { runCatching { java.io.File(it.file).delete() } }
            Store.saveTrash(this, emptyList())
            Ui.toast(this, "已清空 ${items.size} 项")
            loadPhotos()
        }
        if (!Store.trashGuard) {
            Ui.confirm(this, "清空回收站", "将彻底删除 ${items.size} 项，无法恢复。", okText = "清空", onOk = doDelete)
            return
        }
        // 第二层：输入确认
        val input = android.widget.EditText(this).apply {
            hint = "输入「删除」以确认"
            setTextColor(SkinNow.skin.text)
            setHintTextColor(SkinNow.skin.textDim)
        }
        Ui.dialog(this) {
            setTitle("清空回收站")
            setMessage("将彻底删除 ${items.size} 项，无法恢复。请输入「删除」确认。")
            setView(input)
            setNegativeButton("取消", null)
            setPositiveButton("清空") { _: android.content.DialogInterface, _: Int ->
                if (input.text.toString().trim() == "删除") doDelete()
                else Ui.toast(this@MainActivity, "输入不匹配，已取消")
            }
        }
    }

    /** Android 11+ 删除他人应用媒体需要授权时，交给系统弹窗。 */
    fun requestDeleteConsent(e: RecoverableSecurityException) {
        CrashGuard.guard {
            if (Build.VERSION.SDK_INT >= 29) {
                startIntentSenderForResult(
                    e.userAction.actionIntent.intentSender, 2002, null, 0, 0, 0
                )
            }
        }
    }

    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 2002) CrashGuard.guard { loadPhotos() }
    }
}
