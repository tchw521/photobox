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
import android.widget.Switch
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
 * 2. **布局与 drawable 中不使用 ?attr/**，颜色全部由 Skin 在代码里提供。
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
    private var tab = 0                  // 0 图库 1 卡片 2 设置 3 回收站

    private var root: View? = null
    private var sidebar: View? = null
    private var libraryView: View? = null
    private var cardsView: View? = null
    private var settingsView: View? = null
    private var trashView: View? = null

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
        const val APP_VERSION = "1.2.1"
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
        albumAdapter = AlbumAdapter { key -> CrashGuard.guard { onPickAlbum(key) } }
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
        val need = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, need) == PackageManager.PERMISSION_GRANTED) {
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
        sidebar?.visibility = if (t == 1 || t == 2) View.GONE else View.VISIBLE
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
            R.id.btnRename, R.id.btnDelete, R.id.btnRescan, R.id.btnEmpty, R.id.restore,
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

    /** 长按单张照片的操作菜单：增删改 + 进入多选。用列表弹窗，不用 PopupMenu。 */
    private fun showPhotoMenu(p: Photo) {
        val fav = Store.favorites(this)
        val isFav = fav.contains(p.id.toString())
        val actions = arrayOf(
            if (isFav) "取消收藏" else "收藏",
            "移动到相册", "重命名", "删除到回收站", "多选更多",
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
                    }
                }
            }
            setNegativeButton("取消", null)
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
    private fun bindSettings(v: View) {
        val s = SkinNow.skin
        val stat = v.findViewById<TextView>(R.id.stat)
        val version = v.findViewById<TextView>(R.id.version)
        stat?.text = "共 ${photos.size} 张照片 · ${photos.map { it.album }.distinct().size} 个图集 · 回收站 ${Store.trash(this).size} 项"
        stat?.setTextColor(s.textDim)
        version?.text = "光影相册 · 原生安卓版 v$APP_VERSION"
        version?.setTextColor(s.textDim)

        v.findViewById<Switch>(R.id.swPreview)?.apply {
            isChecked = Store.previewActions
            setOnCheckedChangeListener { _, b ->
                CrashGuard.guard { Store.previewActions = b; Store.saveSettings(this@MainActivity) }
            }
        }
        v.findViewById<Switch>(R.id.swGrid)?.apply {
            isChecked = Store.defaultGrid
            setOnCheckedChangeListener { _, b ->
                CrashGuard.guard { Store.defaultGrid = b; Store.saveSettings(this@MainActivity) }
            }
        }
        v.findViewById<Switch>(R.id.swCardMode)?.apply {
            isChecked = Store.cardModeMove
            setOnCheckedChangeListener { _, b ->
                CrashGuard.guard { Store.cardModeMove = b; Store.saveSettings(this@MainActivity) }
            }
        }

        val skinList = v.findViewById<RecyclerView>(R.id.skinList)
        skinList?.layoutManager = GridLayoutManager(this, 3)
        skinList?.adapter = SkinAdapter(SkinNow.skin.key) { key ->
            CrashGuard.guard { switchSkin(key) }
        }

        v.findViewById<Button>(R.id.btnRescan)?.setOnClickListener {
            CrashGuard.guard {
                Thumbs.clear()
                loadPhotos()
                Ui.toast(this, "扫描完成")
            }
        }
        styleButtons(v)

        val crash = CrashGuard.read(this)
        if (crash.isNotBlank()) {
            val box = TextView(this).apply {
                text = "最近崩溃记录（长按可复制）\n\n$crash"
                setTextIsSelectable(true)
                textSize = 10f
                setTextColor(s.textDim)
                setPadding(16, 16, 16, 16)
            }
            v.findViewById<LinearLayout>(R.id.settingsBody)?.addView(box)
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
        trashAdapter?.submit(items)

        v.findViewById<Button>(R.id.btnEmpty)?.setOnClickListener {
            Ui.confirm(this, "清空回收站", "将彻底删除 ${items.size} 项，无法恢复。", okText = "清空") {
                items.forEach { runCatching { java.io.File(it.file).delete() } }
                Store.saveTrash(this, emptyList())
                loadPhotos()
            }
        }
        styleButtons(v)
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
