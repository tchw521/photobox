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
import com.google.android.material.bottomsheet.BottomSheetDialog

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
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_BLOCKED = "\u0000blocked"
        const val KEY_TRASH = "\u0000trash"
    }

    // ---------- 生命周期
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        Store.loadSettings(this)
        gridView = Store.defaultGrid

        sidebar = findViewById(R.id.sidebar)
        val albumList = findViewById<RecyclerView>(R.id.albumList)
        albumAdapter = AlbumAdapter { key -> onPickAlbum(key) }
        albumList.layoutManager = LinearLayoutManager(this)
        albumList.adapter = albumAdapter
        albumList.setHasFixedSize(true)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_library -> switchTab(0)
                R.id.nav_cards -> switchTab(1)
                R.id.nav_settings -> switchTab(2)
            }
            true
        }

        ensurePermission { loadPhotos() }
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
        months.setHasFixedSize(true)

        photoAdapter = PhotoAdapter(
            this, gridView,
            onClick = { p, _ ->
                if (photoAdapter?.selectMode == true) { toggleSelect(p); refreshLibrary() }
                else preview(p)
            },
            onLongClick = { p, _ ->
                photoAdapter?.selectMode = true
                photoAdapter?.selected?.add(p.id)
                Toast.makeText(this, "已进入多选，可继续点选更多", Toast.LENGTH_SHORT).show()
                refreshLibrary(); true
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
        }
        v.findViewById<ImageButton>(R.id.btnSort).setOnClickListener {
            sort = (sort + 1) % 4; refreshLibrary()
            Toast.makeText(this, "排序：${arrayOf("日期新→旧", "日期旧→新", "名称", "大小")[sort]}", Toast.LENGTH_SHORT).show()
        }

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
        showAlbumSheet("移动 ${list.size} 张到相册") { album ->
            Thread {
                var ok = 0
                list.forEach { if (Repo.copyToAlbum(this, it, album)) ok++ }
                runOnUiThread {
                    Toast.makeText(this, "已移动 $ok 张到「$album」", Toast.LENGTH_SHORT).show()
                    loadPhotos(); exitSelect()
                }
            }.start()
        }
    }

    private fun selRename() {
        val p = selectedPhotos().firstOrNull() ?: return
        val input = EditText(this).apply { setText(p.name.substringBeforeLast('.')) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val new = input.text.toString().trim()
                if (new.isBlank()) return@setPositiveButton
                val ext = p.name.substringAfterLast('.', "")
                val cv = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "$new.$ext") }
                contentResolver.update(Repo.uriOf(p), cv, null, null)
                exitSelect(); loadPhotos()
            }.show()
    }

    private fun selDelete() {
        val list = selectedPhotos()
        if (list.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("删除所选")
            .setMessage("这 ${list.size} 张会先移入回收站，可还原。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> trashPhotos(list) }
            .show()
    }

    private fun trashPhotos(list: List<Photo>) {
        Thread {
            val items = Store.trash(this).toMutableList()
            list.forEach { p -> Repo.moveToTrash(this, p) { e -> runOnUiThread { requestDeleteConsent(e) } }?.let { items.add(it) } }
            Store.saveTrash(this, items)
            runOnUiThread {
                Toast.makeText(this, "已清理 ${list.size} 张到回收站", Toast.LENGTH_SHORT).show()
                exitSelect(); loadPhotos()
            }
        }.start()
    }

    /** 相册选择底部弹窗。 */
    private fun showAlbumSheet(title: String, onPick: (String) -> Unit) {
        val dialog = BottomSheetDialog(this)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(12, 12, 12, 12)
        }
        val names = photos.map { it.album }.distinct().sorted()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val tv = TextView(this@MainActivity).apply {
                    setPadding(24, 28, 24, 28); textSize = 14f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val n = names[i]
                (h.itemView as TextView).text = "📁 $n"
                h.itemView.setOnClickListener { dialog.dismiss(); onPick(n) }
            }

            override fun getItemCount() = names.size
        }
        rv.adapter = adapter
        dialog.setContentView(rv)
        dialog.show()
    }

    private fun preview(p: Photo) {
        val fav = Store.favorites(this)
        val isFav = fav.contains(p.id.toString())
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setMessage("${p.album} · ${p.dateText} · ${formatSize(p.size)}")
            .setNegativeButton("关闭", null)
        if (Store.previewActions) {
            b.setNeutralButton(if (isFav) "取消收藏" else "收藏") { _, _ ->
                if (isFav) fav.remove(p.id.toString()) else fav.add(p.id.toString())
                Store.setFavorites(this, fav); renderSidebar()
            }
            b.setPositiveButton("清理") { _, _ -> trashPhotos(listOf(p)) }
        }
        b.show()
    }

    // ---------- 卡片页
    private fun bindCards(v: View) {
        cards = CardsPage(this, v)
        cards.bind()
    }

    fun cardPhotos(): List<Photo> = visible()

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
        swPreview.setOnCheckedChangeListener { _, b -> Store.previewActions = b; Store.saveSettings(this) }
        swGrid.setOnCheckedChangeListener { _, b -> Store.defaultGrid = b; Store.saveSettings(this) }
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
            Thread {
                val ok = Repo.restore(this, item)
                val left = Store.trash(this).toMutableList().apply { removeAll { it.id == item.id } }
                Store.saveTrash(this, left)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "已还原" else "还原失败", Toast.LENGTH_SHORT).show()
                    loadPhotos()
                }
            }.start()
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = trashAdapter
        trashAdapter?.submit(items)
        v.findViewById<Button>(R.id.btnEmpty).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("清空回收站")
                .setMessage("将彻底删除 ${items.size} 项，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    items.forEach { java.io.File(it.file).delete() }
                    Store.saveTrash(this, emptyList())
                    loadPhotos()
                }.show()
        }
    }

    /** Android 11+ 删除他人应用媒体需要授权时，交给系统弹窗。 */
    fun requestDeleteConsent(e: RecoverableSecurityException) {
        deleteConsent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
    }
}
