package com.makewheels.dashcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context

import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
  internal lateinit var store: Store
  internal lateinit var connectivity: TransferEngine
  lateinit var prefs: SharedPreferences
  internal lateinit var ui: Ui
  internal lateinit var root: LinearLayout
  internal lateinit var list: LinearLayout
  internal lateinit var cloudList: LinearLayout
  internal lateinit var batchList: LinearLayout
  internal var otgState: TextView? = null
  internal var otgHelp: TextView? = null
  internal lateinit var copyButton: TextView
  internal lateinit var uploadButton: TextView
  internal var homePause: TextView? = null
  internal lateinit var liveHint: TextView
  internal var batchStatus: TextView? = null
  internal lateinit var deleteCardButton: TextView
  internal lateinit var recycleButton: TextView
  internal lateinit var chooseButton: TextView
  internal lateinit var abandonButton: TextView
  internal lateinit var steps: LinearLayout
  internal lateinit var firstStep: TextView
  internal lateinit var secondStep: TextView
  internal var stepConnector: ProgressBar? = null
  internal val batchEstimate = BatchEstimate()
  internal var focusedGeneration = Long.MIN_VALUE
  internal var progressWasVisible = false
  internal var awaitingStart = false
  internal var requestedKind = ""
  internal var requestedGeneration = Long.MIN_VALUE
  internal var storageCheckedAt = 0L
  internal var inventoryEpoch = 0L
  internal var inventoryLoading = false
  internal var inventoryWasBusy = false
  internal var inventoryJob = Long.MIN_VALUE
  internal var inventoryOutcome = ""
  var inventorySummary = ""
    internal set
  internal var inventorySignature = ""
  var inventoryDialog: AlertDialog? = null
    internal set
  internal lateinit var hintBar: LinearLayout
  internal lateinit var netBar: LinearLayout
  internal lateinit var netText: TextView
  internal lateinit var netIcon: ImageView
  @Volatile private var cellularLabel = "移动网络"
  internal var phoneListener: PhoneStateListener? = null
  internal var updateDialog: AlertDialog? = null
  @Volatile private var updateCancelled = false
  internal var remoteBatches = JSONArray()
  internal var batchesLoading = false
  internal var readerPresent = false
  internal var batchNext: String? = null
  internal val executor: ExecutorService = Executors.newSingleThreadExecutor()
  internal val handler = Handler(Looper.getMainLooper())
  internal val pages = arrayOfNulls<View>(4)
  internal lateinit var queueControls: LinearLayout
  internal lateinit var queueAction: TextView
  internal var queuePause: TextView? = null
  internal var heading: TextView? = null
  internal lateinit var source: TextView
  internal lateinit var space: TextView
  internal lateinit var queueTitle: TextView
  internal lateinit var queueHint: TextView
  internal lateinit var cloudCount: TextView
  internal lateinit var homeTask: TaskCard
  internal lateinit var uploadTask: TaskCard
  internal var sourceCard: LinearLayout? = null
  var resumed = false
    internal set
  internal var loading = false
  var scanning = false
    internal set
  var cardPresent = false
    internal set
  internal var cardName = ""
  internal var lastRows = ""
  internal var next: String? = null
  internal var sourceName = ""
  var tab = 0
    internal set
  internal var cloudItems = JSONArray()
  internal val fileRows = HashMap<String, FileRow>()

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    store = Store(this)
    connectivity = TransferEngine(this)
    prefs = getSharedPreferences("settings", 0)
    ui = Ui(this)
    build()
    UploadWorker.schedule(this)
    registerMedia()
    registerCellularDisplay()
    checkStorage(false)
    prefs.edit().putBoolean("intro_seen", true).apply()
    // Check on every launch; a newer release pops the update dialog, otherwise nothing shows.
    checkUpdates(true)
  }

  internal fun dp(n: Int): Int = ui.dp(n)

  internal fun text(value: String, size: Int, color: Int): TextView = ui.text(value, size, color)

  internal fun button(name: String, run: Runnable): TextView = ui.button(name, false, run)

  internal fun build() {
    root = ui.column()
    root.setBackgroundColor(Ui.BG)
    insets(root)
    setContentView(root)
    val header = ui.column()
    header.setPadding(dp(24), dp(16), dp(24), dp(12))
    val line = ui.row()
    heading = ui.bold("开始", 22, Ui.INK)
    line.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
    val more = ui.bold("⋯", 24, Ui.INK)
    more.setPadding(dp(12), dp(4), 0, dp(4))
    more.contentDescription = "更多：历史与更新"
    more.setOnClickListener { more() }
    line.addView(more)
    header.addView(line)
    root.addView(header)
    steps = ui.row()
    steps.setPadding(dp(24), 0, dp(24), dp(14))
    firstStep = ui.bold("① 拷贝到手机", 18, Ui.BLUE)
    firstStep.setPadding(dp(4), dp(10), dp(12), dp(10))
    firstStep.setOnClickListener { selectTab(0) }
    secondStep = ui.bold("② 上传云端", 18, Ui.MUTED)
    secondStep.setPadding(dp(12), dp(10), dp(4), dp(10))
    secondStep.setOnClickListener { selectTab(1) }
    steps.addView(firstStep)
    stepConnector = ui.bar()
    val connector = LinearLayout.LayoutParams(0, dp(4), 1f)
    connector.setMargins(dp(10), 0, dp(10), 0)
    steps.addView(stepConnector, connector)
    steps.addView(secondStep)
    root.addView(steps)
    val container = FrameLayout(this)
    root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
    pages[0] = homePage()
    pages[1] = queuePage()
    pages[2] = batchPage()
    pages[3] = cloudPage()
    for (page in pages) container.addView(page, FrameLayout.LayoutParams(-1, -1))
    selectTab(0)
    refresh()
  }

  internal fun scroll(content: LinearLayout): ScrollView {
    val s = ScrollView(this)
    s.isFillViewport = true
    s.clipToPadding = false
    s.setPadding(dp(24), 0, dp(24), dp(24))
    s.addView(content)
    return s
  }

  internal fun homePage(): View {
    val content = ui.column()
    homeTask = TaskCard("整批拷贝进度")
    ui.add(content, homeTask.card, 0)
    val card = ui.card()
    sourceCard = card
    otgState = ui.bold("检测 TF 卡…", 22, Ui.INK)
    card.addView(otgState)
    otgHelp = text("插入读卡器后，选择视频目录", 13, Ui.MUTED)
    ui.add(card, otgHelp!!, 8)
    source = text("", 14, Ui.INK)
    source.setTextIsSelectable(true)
    ui.add(card, source, 18)
    chooseButton = ui.button("选择视频文件夹", true) { choose() }
    ui.add(card, chooseButton, 14)
    space = text("", 12, Ui.MUTED)
    ui.add(card, space, 12)
    ui.add(content, card, 0)
    liveHint = ui.bold("", 15, Ui.INK)
    hintBar = LinearLayout(this)
    hintBar.orientation = LinearLayout.VERTICAL
    hintBar.background = ui.shape(0xfff4f6f8.toInt(), 14)
    hintBar.setPadding(dp(16), dp(12), dp(16), dp(12))
    hintBar.addView(liveHint)
    ui.add(content, hintBar, 22)
    copyButton = ui.button("拷贝到手机", true) { scan() }
    ui.add(content, copyButton, 18)
    deleteCardButton = ui.button("删除卡上已拷贝的视频", false) { deleteCard() }
    ui.add(content, deleteCardButton, 12)
    abandonButton = ui.button("放弃上次任务，重新开始", false) { abandonPending() }
    ui.add(content, abandonButton, 12)
    abandonButton.visibility = View.GONE
    homePause = ui.button("暂停", false) { TransferEngine.stop(this) }
    return scroll(content)
  }

  internal fun abandonPending() {
    val pending = store.files("state IN ('ready','copy_error','uploading','upload_error')")
    if (pending.isEmpty()) return
    var bytes = 0L
    for (item in pending) bytes += item.size
    AlertDialog.Builder(this)
        .setTitle("放弃上次任务？")
        .setMessage(
            "上次拷贝的 " + pending.size + " 个视频（" + TransferEngine.bytes(bytes) + "）的手机副本会被删除（存储在应用私有目录，云端与 TF 卡原视频都不受影响）。放弃后可重新插卡拷贝。")
        .setNegativeButton("保留", null)
        .setPositiveButton("放弃并重新开始") { _, _ -> explicitAction("abandon") }
        .show()
  }

  /** Android 11+ reports the cellular generation (5G/4G) without any permission. */
  internal fun registerCellularDisplay() {
    if (Build.VERSION.SDK_INT < 30) return
    val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager? ?: return
    phoneListener = object : PhoneStateListener() {
      override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
        val nr = info.networkType == TelephonyManager.NETWORK_TYPE_NR
        cellularLabel = if (nr) "5G 网络" else "4G 网络"
        handler.post { refresh() }
      }
    }
    tm.listen(phoneListener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
  }

  internal fun more() {
    AlertDialog.Builder(this)
        .setTitle("更多")
        .setItems(
            arrayOf(
                "历史批次",
                "云端视频",
                "更换视频文件夹",
                "安全弹出 TF 卡",
                "检查更新 · " + BuildConfig.VERSION_NAME,
                "通知与后台运行设置")) { _, index ->
              if (index == 0) selectTab(2)
              else if (index == 1) selectTab(3)
              else if (index == 2) choose()
              else if (index == 3) ejectCard()
              else if (index == 4) updates()
              else backgroundSettings()
            }
        .setNegativeButton("关闭", null)
        .show()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (tab != 0) selectTab(0) else super.onBackPressed()
  }

  internal fun explicitAction(action: String) {
    if (TransferEngine.BUSY.get()) {
      toast("请先暂停当前任务")
      return
    }
    requestNotification()
    val intent = Intent(this, TransferService::class.java).setAction(action)
    intent.putExtra("tree", prefs.getString("source_tree", ""))
    requestedGeneration = TransferEngine.generation
    awaitingStart = true
    requestedKind = if (action == "delete-source" || action == "abandon") action else "cleanup"
    startForegroundService(intent)
    refresh()
    handler.postDelayed({
      awaitingStart = false
      refresh()
    }, 5000)
  }

  internal fun deleteCard() {
    AlertDialog.Builder(this)
        .setTitle("删除卡上已拷贝的视频？")
        .setMessage("只删除已经整批复制校验、并再次核对手机副本与卡上内容的视频。未完成或内容变化的文件保留，不格式化 TF 卡。")
        .setNegativeButton("保留", null)
        .setPositiveButton("删除") { _, _ -> explicitAction("delete-source") }
        .show()
  }

  internal fun ejectCard() {
    if (TransferEngine.BUSY.get() || scanning) {
      toast("请先暂停拷贝或删除，再卸载 TF 卡")
      return
    }
    AlertDialog.Builder(this)
        .setTitle("安全弹出 TF 卡")
        .setMessage("接下来打开系统存储页。请选择 TF 卡并点「卸载 / 弹出」，系统确认完成后再拔出读卡器。")
        .setNegativeButton("取消", null)
        .setPositiveButton("打开系统存储页") { _, _ ->
          val intent = Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)
          val manager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
          var selectedVolume = ""
          try {
            selectedVolume = DocumentsContract.getTreeDocumentId(
                Uri.parse(prefs.getString("source_tree", ""))).split(":", limit = 2)[0]
          } catch (_: RuntimeException) {
          }
          for (volume in manager.storageVolumes) {
            if (volume.isRemovable && selectedVolume.equals(volume.uuid, ignoreCase = true)) {
              intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, volume)
              break
            }
          }
          try {
            startActivity(intent)
          } catch (_: ActivityNotFoundException) {
            try {
              startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: ActivityNotFoundException) {
              toast("请在手机系统设置的存储页面卸载 TF 卡")
            }
          }
        }
        .show()
  }

  internal fun recyclePhone() {
    AlertDialog.Builder(this)
        .setTitle("删除手机副本？")
        .setMessage("仅处理云端已核验完成的视频：手机上的副本会从应用私有目录中删除，云端视频不受影响。")
        .setNegativeButton("保留", null)
        .setPositiveButton("删除副本") { _, _ -> explicitAction("recycle-phone") }
        .show()
  }

  internal fun batchPage(): View {
    val content = ui.column()
    val intro = ui.card()
    val row = ui.row()
    row.addView(ui.bold("之前的批次", 21, Ui.INK), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(button("同步") { loadBatches(false) })
    intro.addView(row)
    batchStatus = text("本机导入记录一直保留；联网同步所有手机已上传的批次。", 13, Ui.MUTED)
    ui.add(intro, batchStatus!!, 10)
    ui.add(content, intro, 0)
    batchList = ui.column()
    ui.add(content, batchList, 18)
    return scroll(content)
  }

  internal var lastBatchRows = ""

  internal fun timeLabel(value: String?): String =
      if (value == null) "时间未知"
      else value.replace('_', ' ').replace(Regex(" (\\d{2})-(\\d{2})-(\\d{2})$"), " $1:$2:$3")

  internal inner class BatchView {
    var id: String = ""
    var time: String? = null
    var size = 0L
    var count = 0
    var copied = 0
    var uploaded = 0
    var cloud = 0
    var missing = 0
    var local = ArrayList<Store.Item>()
  }

  internal fun loadBatches(more: Boolean) {
    if (batchesLoading) return
    batchesLoading = true
    batchStatus!!.text = "正在同步云端批次，本机记录可以查看…"
    renderBatches()
    executor.execute {
      try {
        val result = Api.call(
            "GET",
            "/batches" + (if (more && batchNext != null)
              "?before=" + URLEncoder.encode(batchNext, "UTF-8") else ""),
            null)
        runOnUiThread {
          if (!more) remoteBatches = JSONArray()
          val fetched = result.optJSONArray("batches")
          for (i in 0 until fetched.length()) remoteBatches.put(fetched.optJSONObject(i))
          batchNext = if (result.isNull("next")) null else result.optString("next", null)
          batchesLoading = false
          batchStatus!!.text = "本机记录已保留 · 云端批次已同步"
          lastBatchRows = ""
          renderBatches()
        }
      } catch (e: Exception) {
        runOnUiThread {
          batchesLoading = false
          batchStatus!!.text = "本机记录可查看。云端未同步：" + TransferEngine.readable(e)
          renderBatches()
        }
      }
    }
  }

  internal fun renderBatches() {
    if (!this::batchList.isInitialized) return
    val map = LinkedHashMap<String, BatchView>()
    for (item in store.files("1=1")) {
      if (item.batch == null) continue
      val batch = map.computeIfAbsent(item.batch!!) { k ->
        BatchView().also { it.id = k }
      }
      batch.local.add(item)
      batch.count++
      batch.size += item.size
      batch.time = item.importTime ?: item.date
      if (item.sha != null) batch.copied++
      if (listOf("uploaded", "cleanup_error").contains(item.state)) batch.uploaded++
    }
    for (i in 0 until remoteBatches.length()) {
      val remote = remoteBatches.optJSONObject(i) ?: continue
      val id = remote.optString("id")
      val batch = map.computeIfAbsent(id) { k ->
        BatchView().also { it.id = k }
      }
      batch.cloud = remote.optInt("count")
      batch.missing = remote.optInt("missing")
      if (batch.local.isEmpty()) {
        batch.count = batch.cloud
        batch.size = remote.optLong("size")
        batch.time = remote.optString("import_time", remote.optString("date"))
      }
    }
    val batches = ArrayList(map.values)
    batches.sortWith { a, b -> Objects.toString(b.time, "").compareTo(Objects.toString(a.time, "")) }
    var signature = ""
    for (b in batches)
      signature += b.id + ":" + b.count + ":" + b.copied + ":" + b.uploaded + ":" + b.cloud + ":" +
          b.missing + ";"
    signature += batchNext
    if (signature == lastBatchRows && batchList.childCount > 0) return
    lastBatchRows = signature
    batchList.removeAllViews()
    if (batches.isEmpty()) {
      empty(
          batchList, "history", "还没有导入批次",
          "每次拷贝会生成一个批次。\n完成上传后，记录仍然保留。", "去拷贝视频") { selectTab(0) }
      return
    }
    for (batch in batches) {
      val card = ui.card()
      card.addView(ui.bold(timeLabel(batch.time), 17, Ui.INK))
      ui.add(card, text(batch.count.toString() + " 个视频 · " + TransferEngine.bytes(batch.size), 13, Ui.MUTED), 10)
      var detail = if (batch.local.isEmpty()) "云端已校验 " + batch.cloud + " 个"
      else "本机已导入 " + batch.copied + " / " + batch.count +
          " · 云端已确认 " + maxOf(batch.uploaded, batch.cloud) + " 个"
      if (batch.missing > 0) detail += " · " + batch.missing + " 个云端文件不存在"
      ui.add(card, text(detail, 12, Ui.GREEN), 8)
      ui.add(card, text("点击查看这批视频", 12, Ui.BLUE), 10)
      card.setOnClickListener { showBatch(batch) }
      ui.add(batchList, card, 12)
    }
    if (batchNext != null) ui.add(batchList, button("加载更早的批次") { loadBatches(true) }, 16)
  }

  internal fun showBatch(batch: BatchView) {
    val body = ui.column()
    body.setPadding(dp(20), dp(8), dp(20), dp(20))
    val info = text("本机 " + batch.local.size + " 个记录 · 正在读取云端视频…", 13, Ui.MUTED)
    body.addView(info)
    val rows = ui.column()
    ui.add(body, rows, 12)
    val scroll = ScrollView(this)
    scroll.addView(body)
    AlertDialog.Builder(this)
        .setTitle(timeLabel(batch.time))
        .setView(scroll)
        .setPositiveButton("关闭", null)
        .show()
    val seen = HashSet<String>()
    for (item in batch.local) {
      if (item.sha != null) seen.add(item.sha!!)
      val row = ui.card()
      row.addView(ui.bold(item.name, 14, Ui.INK))
      ui.add(
          row,
          text(
              TransferEngine.bytes(item.size) + " · " +
                  (if ("uploaded" == item.state) "已上传 · 手机副本已删除" else fileState(item.state)),
              12, Ui.MUTED), 10)
      ui.add(rows, row, 10)
      if (listOf("uploaded", "cleanup_error").contains(item.state) && item.sha != null)
        row.setOnClickListener { playCloud(item.sha!!) }
      else if (item.dest != null) row.setOnClickListener { play(Uri.parse(item.dest), "video/*") }
    }
    loadBatchFiles(batch.id, null, rows, info, seen, batch.local.isEmpty())
  }

  internal fun playCloud(id: String) {
    executor.execute {
      try {
        val result = Api.call("GET", "/videos/$id/url", null)
        runOnUiThread { play(Uri.parse(result.optString("url")), "video/*") }
      } catch (e: Exception) {
        runOnUiThread { toast(TransferEngine.readable(e)) }
      }
    }
  }

  internal fun loadBatchFiles(
      id: String, before: String?, rows: LinearLayout, info: TextView,
      seen: MutableSet<String>, onlyCloud: Boolean
  ) {
    executor.execute {
      try {
        val result = Api.call(
            "GET",
            "/videos?batch=" + URLEncoder.encode(id, "UTF-8") +
                (if (before != null) "&before=" + URLEncoder.encode(before, "UTF-8") else ""),
            null)
        runOnUiThread {
          val files = result.optJSONArray("videos")
          for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val sha = f.optString("_id")
            if (!seen.add(sha)) continue
            val row = ui.card()
            row.addView(ui.bold(f.optString("name"), 14, Ui.INK))
            ui.add(
                row,
                text(
                    TransferEngine.bytes(f.optLong("size")) + " · " +
                        (if ("missing" == f.optString("state")) "云端文件已不存在" else "已上传 · 云端已校验"),
                    12, Ui.MUTED), 10)
            ui.add(rows, row, 10)
            if ("missing" != f.optString("state")) row.setOnClickListener { playCloud(sha) }
          }
          info.text = "已展示本机记录与此批云端视频"
          val next = if (result.isNull("next")) null else result.optString("next", null)
          if (next != null) {
            val moreButton = button("加载此批更多视频") {
              loadBatchFiles(id, next, rows, info, seen, onlyCloud)
            }
            moreButton.setOnClickListener {
              rows.removeView(moreButton)
              loadBatchFiles(id, next, rows, info, seen, onlyCloud)
            }
            ui.add(rows, moreButton, 12)
          }
        }
      } catch (e: Exception) {
        runOnUiThread {
          info.text = (if (onlyCloud) "云端暂时无法读取" else "本机记录可查看，云端暂未同步") + "：" +
              TransferEngine.readable(e)
        }
      }
    }
  }

  internal fun insets(view: View) {
    view.setOnApplyWindowInsetsListener { v, w ->
      if (Build.VERSION.SDK_INT >= 30) {
        val i = w.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(i.left, i.top, i.right, i.bottom)
      } else {
        v.setPadding(
            w.systemWindowInsetLeft, w.systemWindowInsetTop,
            w.systemWindowInsetRight, w.systemWindowInsetBottom)
      }
      w
    }
    view.requestApplyInsets()
  }

  internal fun addInstruction(parent: LinearLayout, number: String, title: String, description: String) {
    val row = ui.row()
    val n = ui.chip(number, Ui.BLUE, Ui.TINT)
    n.gravity = Gravity.CENTER
    row.addView(n, LinearLayout.LayoutParams(dp(30), dp(30)))
    val copy = ui.column()
    copy.addView(ui.bold(title, 14, Ui.INK))
    ui.add(copy, text(description, 12, Ui.MUTED), 5)
    val p = LinearLayout.LayoutParams(0, -2, 1f)
    p.leftMargin = dp(12)
    row.addView(copy, p)
    ui.add(parent, row, 18)
  }

  internal fun queuePage(): View {
    val content = ui.column()
    netBar = LinearLayout(this)
    netBar.orientation = LinearLayout.HORIZONTAL
    netBar.gravity = Gravity.CENTER_VERTICAL
    netBar.background = ui.shape(0xffe7f5ed.toInt(), 14)
    netBar.setPadding(dp(16), dp(12), dp(16), dp(12))
    netIcon = ui.icon("wifi", 0xff167d6e.toInt(), 20)
    netBar.addView(netIcon)
    netText = ui.bold("检查网络…", 16, 0xff167d6e.toInt())
    netBar.addView(netText, LinearLayout.LayoutParams(0, -2, 1f))
    (netText.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(12)
    ui.add(content, netBar, 0)
    uploadTask = TaskCard("整批上传进度")
    ui.add(content, uploadTask.card, 0)
    val intro = ui.card()
    queueTitle = ui.bold("", 23, Ui.INK)
    intro.addView(queueTitle)
    queueHint = text("", 13, Ui.MUTED)
    ui.add(intro, queueHint, 10)
    val actions = ui.row()
    queueControls = actions
    val start = ui.button("开始上传", true) { upload() }
    queueAction = start
    uploadButton = start
    actions.addView(start, LinearLayout.LayoutParams(0, -2, 1f))
    val pause = button("暂停") { TransferEngine.stop(this) }
    queuePause = pause
    val p = LinearLayout.LayoutParams(-2, -2)
    p.leftMargin = dp(10)
    actions.addView(pause, p)
    ui.add(intro, actions, 18)
    ui.add(content, intro, 0)
    recycleButton = ui.button("删除手机副本", false) { recyclePhone() }
    ui.add(content, recycleButton, 12)
    list = ui.column()
    ui.add(content, list, 20)
    return scroll(content)
  }

  internal fun cloudPage(): View {
    val content = ui.column()
    val card = ui.card()
    val top = ui.row()
    cloudCount = ui.bold("云端视频", 23, Ui.INK)
    top.addView(cloudCount, LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(button("刷新") { loadCloud(false) })
    card.addView(top)
    ui.add(card, text("所有手机共享同一份列表。点视频可用手机播放器打开。", 13, Ui.MUTED), 10)
    ui.add(content, card, 0)
    cloudList = ui.column()
    ui.add(content, cloudList, 20)
    return scroll(content)
  }

  internal fun backgroundSettings() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
      return
    }
    try {
      startActivity(
          Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:$packageName")))
    } catch (_: Exception) {
      toast("请在系统设置中允许后台运行")
    }
  }

  internal fun selectTab(selected: Int) {
    tab = selected
    val titles = arrayOf("① 拷贝到手机", "② 上传云端", "历史批次", "云端")
    for (i in 0 until 4) pages[i]!!.visibility = if (i == tab) View.VISIBLE else View.GONE
    steps.visibility = if (tab < 2) View.VISIBLE else View.GONE
    // Steps double as the page title; the heading only shows on non-step pages.
    // Invisible (not gone) keeps the spacer so the more button stays on the right.
    heading?.visibility = if (tab < 2) View.INVISIBLE else View.VISIBLE
    firstStep.setTextColor(if (tab == 0) Ui.BLUE else Ui.GREEN)
    secondStep.setTextColor(if (tab == 1) Ui.BLUE else Ui.MUTED)
    stepConnector!!.progress = if (tab == 1) 1000 else 0
    heading?.text = titles[tab]
    if (tab == 3 && !loading) loadCloud(false)
    if (tab == 2 && !batchesLoading) loadBatches(false)
    refresh()
  }

  internal fun onboarding() {
    val dialog = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
    val page = ui.column()
    page.setBackgroundColor(Ui.BG)
    page.setPadding(dp(28), dp(36), dp(28), dp(30))
    val skip = ui.bold("稍后再看", 14, Ui.MUTED)
    skip.gravity = Gravity.END
    skip.setPadding(0, dp(12), 0, dp(12))
    page.addView(skip)
    page.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
    val illustration = ui.icon("folder", Ui.BLUE, 88)
    page.addView(illustration)
    val step = ui.chip("", Ui.BLUE, Ui.TINT)
    ui.add(page, step, 30)
    val title = ui.bold("", 30, Ui.INK)
    ui.add(page, title, 20)
    val body = text("", 17, Ui.MUTED)
    ui.add(page, body, 16)
    page.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
    val action = ui.button("下一步", true) { }
    page.addView(action)
    val footer = text("进度会保存，可暂停后继续", 12, Ui.MUTED)
    footer.gravity = Gravity.CENTER
    ui.add(page, footer, 14)
    val current = intArrayOf(0)
    val titles = arrayOf("先把 TF 卡接上手机", "把视频完整导入手机", "回家连上 Wi-Fi 上传")
    val bodies = arrayOf(
        "将 TF 卡插进读卡器，再连接手机。\n检测到读卡器后，选择存放视频的文件夹。",
        "点击「开始导入」，查看复制和校验进度。\n整批校验成功后，才清理 TF 卡原视频。",
        "点击上传后开始备份。\n云端核验完成后，可手动删除手机副本。")
    val icons = arrayOf("folder", "check", "cloud")
    val render = Runnable {
      val i = current[0]
      step.text = "第 " + (i + 1) + " 步 / 3"
      title.text = titles[i]
      body.text = bodies[i]
      illustration.setImageDrawable(Ui.Icon(icons[i], Ui.BLUE))
      action.text = if (i == 2) "开始使用" else "下一步"
    }
    action.setOnClickListener {
      if (current[0] < 2) {
        current[0]++
        render.run()
      } else {
        prefs.edit().putBoolean("intro_seen", true).apply()
        dialog.dismiss()
        requestNotification()
      }
    }
    skip.setOnClickListener {
      prefs.edit().putBoolean("intro_seen", true).apply()
      dialog.dismiss()
    }
    render.run()
    val wrapper = ui.column()
    wrapper.setBackgroundColor(Ui.BG)
    wrapper.addView(page, LinearLayout.LayoutParams(-1, -1))
    insets(wrapper)
    dialog.setContentView(wrapper)
    dialog.show()
    dialog.window?.setLayout(-1, -1)
  }

  internal fun requestNotification() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    handler.post(tick)
    storageCheckedAt = 0
    checkStorage(true)
    refresh()
  }

  override fun onPause() {
    resumed = false
    handler.removeCallbacks(tick)
    super.onPause()
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    phoneListener?.let {
      val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager?
      tm?.listen(it, PhoneStateListener.LISTEN_NONE)
    }
    phoneListener = null
    unregisterReceiver(mediaReceiver)
    unregisterReceiver(usbReceiver)
    executor.shutdownNow()
    store.close()
    connectivity.store.close()
    super.onDestroy()
  }

  internal val tick = object : Runnable {
    override fun run() {
      if (!resumed) return
      val busy = TransferEngine.BUSY.get()
      if (inventoryWasBusy && !busy) storageCheckedAt = 0
      inventoryWasBusy = busy
      if (inventoryJob != TransferEngine.generation ||
          inventoryOutcome != TransferEngine.outcome) {
        inventoryJob = TransferEngine.generation
        inventoryOutcome = TransferEngine.outcome
        if (!busy) storageCheckedAt = 0
      }
      if (SystemClock.elapsedRealtime() - storageCheckedAt >= 5000) checkStorage(true)
      refresh()
      handler.postDelayed(this, 1000)
    }
  }

  internal val mediaReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      if (Intent.ACTION_MEDIA_MOUNTED != i.action && cardPresent) {
        cardPresent = false
        clearInventory()
        toast("读卡器已断开，重新连接后可继续导入")
        if (TransferEngine.BUSY.get()
            && listOf("import", "delete-source").contains(TransferEngine.taskKind)) {
          TransferEngine.pauseReason = "读卡器已断开，重新连接后继续导入"
          TransferEngine.stopped = true
        }
        refresh()
      }
      handler.postDelayed({ checkStorage(true) }, 500)
    }
  }

  internal val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      handler.postDelayed({ checkStorage(true) }, 350)
    }
  }

  internal fun registerMedia() {
    val filter = IntentFilter()
    filter.addAction(Intent.ACTION_MEDIA_MOUNTED)
    filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED)
    filter.addAction(Intent.ACTION_MEDIA_REMOVED)
    filter.addAction(Intent.ACTION_MEDIA_EJECT)
    filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
    filter.addDataScheme("file")
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else registerReceiver(mediaReceiver, filter)
    val usb = IntentFilter()
    usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
    usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(usbReceiver, usb, Context.RECEIVER_NOT_EXPORTED)
    else registerReceiver(usbReceiver, usb)
  }

  internal fun checkStorage(notify: Boolean) {
    storageCheckedAt = SystemClock.elapsedRealtime()
    val before = cardPresent
    cardPresent = false
    cardName = ""
    val manager = getSystemService(STORAGE_SERVICE) as StorageManager?
    if (manager != null)
      for (volume in manager.storageVolumes) {
        if (volume.isRemovable && Environment.MEDIA_MOUNTED == volume.state) {
          cardPresent = true
          cardName = volume.getDescription(this)
          break
        }
      }
    readerPresent = cardPresent
    val usb = getSystemService(USB_SERVICE) as UsbManager?
    if (usb != null)
      for (device in usb.deviceList.values)
        for (i in 0 until device.interfaceCount) {
          if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE)
            readerPresent = true
        }
    if (notify && cardPresent && !before) {
      toast("检测到读卡器，请选择 TF 卡的视频文件夹")
    } else if (notify && !cardPresent && before) {
      toast("读卡器已断开，重新连接后可继续导入")
    }
    if (!cardPresent) {
      if (before || inventorySummary.isNotEmpty()) clearInventory()
      if (before && TransferEngine.BUSY.get()
          && listOf("import", "delete-source").contains(TransferEngine.taskKind)) {
        TransferEngine.pauseReason = "TF 卡已断开，重新连接后继续"
        TransferEngine.stopped = true
      }
    } else if (resumed) inspectCard(notify)
    refresh()
  }

  internal fun clearInventory() {
    inventoryEpoch++
    inventorySummary = ""
    inventorySignature = ""
    inventoryDialog?.dismiss()
  }

  internal fun showInventory(signature: String, title: String, detail: String, canCopy: Boolean) {
    if (!resumed || TransferEngine.BUSY.get() || scanning || awaitingStart
        || signature == inventorySignature) return
    inventorySignature = signature
    inventoryDialog?.dismiss()
    val dialog = AlertDialog.Builder(this)
        .setTitle(title).setMessage(detail).setNegativeButton("稍后", null)
    if (canCopy) dialog.setPositiveButton("拷贝到手机") { _, _ -> scan() }
    else if (title == "手机空间不足") dialog.setPositiveButton("知道了", null)
    else dialog.setPositiveButton("选择视频文件夹") { _, _ -> choose() }
    inventoryDialog = dialog.create()
    inventoryDialog!!.show()
  }

  internal fun inspectCard(notify: Boolean) {
    if (!cardPresent || inventoryLoading || TransferEngine.BUSY.get() || scanning || awaitingStart)
      return
    val tree = prefs.getString("source_tree", "")!!
    if (tree.isEmpty()) {
      inventorySummary = "请先选择视频文件夹，授权后自动统计数量和大小"
      if (notify) showInventory("choose", "已检测到 TF 卡", inventorySummary, false)
      return
    }
    val epoch = inventoryEpoch
    inventoryLoading = true
    if (inventorySummary.isEmpty()) inventorySummary = "正在检查视频数量和大小…"
    executor.execute {
      var count = 0
      var size = 0L
      var signature: String
      var error: String? = null
      try {
        val directory = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(tree))
        if (directory == null || !directory.exists() || !directory.canRead())
          throw IOException("记住的目录暂时无法读取，请确认是这张 TF 卡，或重新选择视频文件夹。")
        val entries = ArrayList<String>()
        for (file in directory.listFiles()) {
          if (!file.isFile) continue
          val length = file.length()
          count++
          size += length
          entries.add(file.uri.toString() + ":" + length + ":" + file.lastModified())
        }
        entries.sort()
        if (!directory.exists() || !directory.canRead()) throw IOException("TF 卡已断开")
        signature = "$tree|${entries.toString()}"
      } catch (failure: Exception) {
        error = TransferEngine.readable(failure)
        signature = "$tree|unavailable"
      }
      val videos = count
      val bytes = size
      val resultSignature = signature
      val resultError = error
      runOnUiThread {
        inventoryLoading = false
        if (isDestroyed || epoch != inventoryEpoch || !cardPresent
            || tree != prefs.getString("source_tree", "")) return@runOnUiThread
        inventorySummary = if (resultError == null)
          "所选目录有 $videos 个视频 · " + TransferEngine.bytes(bytes) else resultError
        refresh()
        if (!notify) return@runOnUiThread
        if (resultError != null) {
          showInventory(resultSignature, "TF 卡目录需要确认", inventorySummary, false)
        } else if (videos == 0) {
          showInventory(
              resultSignature, "TF 卡视频已更新",
              "所选目录没有视频。可以更换文件夹，或继续上传手机里的视频。", false)
        } else {
          val free = TransferEngine.free(this@MainActivity)
          val enough = free >= bytes + TransferEngine.RESERVE
          val detail = SourcePath.display(this@MainActivity, Uri.parse(tree)) + "\n\n" +
              inventorySummary + "\n手机可用 " + TransferEngine.bytes(free) +
              "，拷贝后需保留 5 GB。" +
              (if (enough) "\n点「拷贝到手机」开始，卡上原视频会保留。" else "\n空间不足，请先清理手机空间。")
          showInventory(
              "$resultSignature|$enough",
              if (enough) "TF 卡视频已检测到" else "手机空间不足", detail, enough)
        }
      }
    }
  }

  internal fun local(): ArrayList<Store.Item> = store.files("state!='uploaded'")

  fun refresh() {
    if (!this::source.isInitialized) return
    val tree = prefs.getString("source_tree", null)
    sourceName = if (tree != null) SourcePath.display(this, Uri.parse(tree)) else "尚未选择文件夹"
    source.text = sourceName
    chooseButton.visibility = if (tree != null) View.GONE else View.VISIBLE
    space.text = "可用 " + TransferEngine.bytes(TransferEngine.free(this)) + " · 始终预留 5 GB"
    val engine = connectivity
    val wf = engine.wifi()
    val conn = engine.connected()
    if (wf) {
      netIcon.setImageDrawable(Ui.Icon("wifi", 0xff167d6e.toInt()))
      netText.text = "Wi-Fi 已连接"
      netText.setTextColor(0xff167d6e.toInt())
      netBar.background = ui.shape(0xffe7f5ed.toInt(), 14)
    } else if (conn) {
      netIcon.setImageDrawable(Ui.Icon("cell", 0xff2b6cb0.toInt()))
      netText.text = "$cellularLabel（流量上传需手动确认）"
      netText.setTextColor(0xff2b6cb0.toInt())
      netBar.background = ui.shape(0xffeaf2ff.toInt(), 14)
    } else {
      netIcon.setImageDrawable(Ui.Icon("wifi", 0xff5f6b76.toInt()))
      netText.text = "未联网"
      netText.setTextColor(0xff5f6b76.toInt())
      netBar.background = ui.shape(0xfff4f6f8.toInt(), 14)
    }
    val files = local()
    var bytes = 0L
    var ready = 0
    for (f in files) {
      bytes += f.size
      if (listOf("ready", "uploading", "upload_error").contains(f.state)) ready++
    }
    queueTitle.text = ready.toString() + " 个待上传 · " +
        store.files("state='cleanup_error'").size + " 个已备份"
    queueHint.text =
        if (files.isEmpty()) "导入后，视频会出现在这里。"
        else TransferEngine.bytes(bytes) +
            (if (prefs.getBoolean("upload_paused", false)) " · 已暂停，点击继续"
             else " · 手动点击上传，完成后手机副本仍保留")
    updateGuide(files, ready, tree)
    sourceCard?.visibility = View.VISIBLE
    queueControls.visibility = View.VISIBLE
    val busy = TransferEngine.BUSY.get()
    queueAction.isEnabled = !busy && ready > 0
    queueAction.alpha = if (busy || ready == 0) .5f else 1f
    queueAction.text = if (busy) "任务进行中" else "上传云端" + (if (ready > 0) " · $ready 个" else "")
    queuePause?.visibility = View.GONE
    val taskFiles = if (homeTask.card.visibility == View.VISIBLE
        || uploadTask.card.visibility == View.VISIBLE) store.files("1=1") else ArrayList()
    homeTask.update(taskFiles)
    uploadTask.update(taskFiles)
    val signature = StringBuilder()
    for (f in files) signature.append(f.id)
    if (signature.toString() != lastRows || list.childCount == 0) {
      lastRows = signature.toString()
      renderLocal(files)
    }
    for (f in files) {
      val row = fileRows[f.id]
      row?.update(f)
    }
  }

  companion object {
    private val HINT_BLUE = 0
    private val HINT_AMBER = 1
    private val HINT_GREEN = 2
    private val HINT_PLAIN = 3
    private val HINT_COLORS = arrayOf(
        intArrayOf(0xffeaf2ff.toInt(), 0xff2b6cb0.toInt()),
        intArrayOf(0xfffdf3e3.toInt(), 0xff8a5a12.toInt()),
        intArrayOf(0xffe7f5ed.toInt(), 0xff167d6e.toInt()),
        intArrayOf(0xfff4f6f8.toInt(), 0xff5f6b76.toInt()))
  }

  internal fun hint(text: String, kind: Int) {
    liveHint.text = text
    liveHint.setTextColor(HINT_COLORS[kind][1])
    hintBar.background = ui.shape(HINT_COLORS[kind][0], 14)
  }

  internal fun updateGuide(files: ArrayList<Store.Item>, ready: Int, tree: String?) {
    val busy = TransferEngine.BUSY.get()
    val wifi = connectivity.wifi()
    otgState!!.text = when {
      cardPresent -> "TF 卡已检测到"
      readerPresent -> "读卡器已连接"
      else -> "未检测到读卡器"
    }
    otgState!!.setTextColor(if (cardPresent) Ui.GREEN else Ui.INK)
    otgHelp!!.text = if (cardPresent)
      cardName + (if (inventorySummary.isEmpty()) "" else "\n" + inventorySummary)
    else if (readerPresent) "请插入 TF 卡或检查挂载" else "请插入 OTG 读卡器"
    copyButton.isEnabled = !busy && !scanning
    copyButton.alpha = if (busy || scanning) .5f else 1f
    copyButton.text = if (scanning) "正在检查视频…" else "拷贝到手机"
    uploadButton.isEnabled = !busy && !scanning && ready > 0
    val deletable = store.files(
        "source_deleted=0 AND tree=? AND batch IN (SELECT id FROM batches WHERE ready=1)",
        tree ?: "").size
    deleteCardButton.isEnabled = !busy && !scanning && deletable > 0 && cardPresent
    deleteCardButton.alpha = if (!busy && deletable > 0 && cardPresent) 1f else .45f
    val pending = ArrayList<Store.Item>()
    for (f in files) {
      if (listOf("ready", "copy_error", "uploading", "upload_error").contains(f.state))
        pending.add(f)
    }
    abandonButton.visibility =
        if (!busy && !scanning && pending.isNotEmpty()) View.VISIBLE else View.GONE
    abandonButton.isEnabled = !busy && !scanning
    abandonButton.alpha = if (busy || scanning || pending.isEmpty()) .5f else 1f
    abandonButton.text = "放弃上次任务（" + pending.size + " 个视频），重新开始"
    val canRecycle = !busy && store.files("state='cleanup_error'").isNotEmpty()
    recycleButton.isEnabled = canRecycle
    recycleButton.alpha = if (canRecycle) 1f else .45f
    homePause?.visibility = if (busy && tab == 0) View.VISIBLE else View.GONE
    uploadButton.alpha = if (busy || scanning || ready == 0) .5f else 1f
    uploadButton.text = "上传云端" + (if (ready > 0) " · $ready 个" else "")
    homePause?.visibility = if (busy) View.VISIBLE else View.GONE
    if (scanning) hint("正在检查文件和空间，完成后开始拷贝。", HINT_BLUE)
    else if (busy) hint(
        if (listOf("import", "delete-source").contains(TransferEngine.taskKind))
          "正在拷贝、核对或删除，请保持 TF 卡连接。"
        else if ("abandon" == TransferEngine.taskKind) "正在放弃上次任务，手机副本将被删除。"
        else "第二页任务正在进行，请到上传页面查看。", HINT_BLUE)
    else if (files.any { f -> !listOf("ready", "uploading", "upload_error", "cleanup_error").contains(f.state) })
      hint(
          "上次拷贝未完成，有 " + pending.size +
              " 个视频待处理。可连接原 TF 卡点「拷贝到手机」继续，或点下方按钮放弃后重新来。",
          HINT_AMBER)
    else if (deletable > 0 && cardPresent)
      hint("拷贝已完成，有 $deletable 个卡上视频可手动删除，也可直接进入下一步。", HINT_GREEN)
    else if (deletable > 0) hint(
        if (ready > 0) "上次拷贝的 $deletable 个视频已在手机上。连接原读卡器才能清理卡上副本，或直接进入②上传云端。"
        else "上次拷贝的 $deletable 个视频还在处理中。连接原读卡器后点「拷贝到手机」继续。",
        HINT_AMBER)
    else if (ready > 0) hint(
        if (prefs.getBoolean("upload_paused", false))
          "视频已在手机上。上传之前暂停过，到②上传云端点继续即可。"
        else if (wifi) "视频已在手机上。进入②上传云端开始备份。"
        else "视频已在手机上。进入②上传云端开始备份（流量会先向你确认）。",
        HINT_GREEN)
    else hint(
        when {
          tree == null -> "先选择 TF 卡的视频文件夹，再点「拷贝到手机」。"
          cardPresent -> "TF 卡已连接。点「拷贝到手机」，完整校验后卡上原视频仍保留。"
          else -> "已记住视频文件夹。连接读卡器后，点「拷贝到手机」。"
        }, HINT_PLAIN)
    if (!busy && !scanning
        && listOf("import", "delete-source").contains(TransferEngine.taskKind)
        && TransferEngine.taskIds.isNotEmpty()) liveHint.append("\n" + TransferEngine.message)
    if (busy || (TransferEngine.generation != requestedGeneration
            && "running" != TransferEngine.outcome)) awaitingStart = false
    val hasTask = TransferEngine.taskIds.isNotEmpty()
    val visibleKind = if (scanning || awaitingStart) requestedKind else TransferEngine.taskKind
    // Abandon is momentary: only show its progress card while running, never as a finished state.
    val show = busy || awaitingStart || scanning || (hasTask && "abandon" != visibleKind)
    homeTask.card.visibility =
        if (show && listOf("import", "delete-source", "abandon").contains(visibleKind))
          View.VISIBLE else View.GONE
    uploadTask.card.visibility =
        if (show && listOf("upload", "cleanup").contains(visibleKind)) View.VISIBLE else View.GONE
    val currentVisible = if (tab == 0) homeTask.card.visibility == View.VISIBLE
    else tab == 1 && uploadTask.card.visibility == View.VISIBLE
    if (currentVisible
        && (!progressWasVisible || (busy && focusedGeneration != TransferEngine.generation))) {
      focusedGeneration = TransferEngine.generation
      val page = pages[tab]
      page!!.post { (page as ScrollView).smoothScrollTo(0, 0) }
    }
    progressWasVisible = currentVisible
    if (!busy && "completed" == TransferEngine.outcome
        && "import" == TransferEngine.taskKind) {
      hint(
          if (cardPresent) "第一步已完成：视频已拷贝并校验。下一步上传云端。卡上原视频可按需手动删除。"
          else "第一步已完成：视频已拷贝并校验。下一步上传云端。",
          HINT_GREEN)
    }
    if (tab == 2 && !batchesLoading) renderBatches()
  }

  internal fun engineWifi(): Boolean = connectivity.wifi()

  internal inner class TaskCard(name: String) {
    val card = ui.card()
    internal val title: TextView
    internal val percent: TextView
    internal val totalLabel: TextView
    internal val phaseLabel: TextView
    internal val fileName: TextView
    internal val currentLabel: TextView
    internal val speed: TextView
    internal val totalEta: TextView
    val action: TextView
    internal val totalBar: ProgressBar
    internal val currentBar: ProgressBar

    init {
      val top = ui.row()
      title = ui.bold(name, 16, Ui.INK)
      top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
      percent = ui.bold("0%", 26, Ui.BLUE)
      top.addView(percent)
      card.addView(top)
      totalBar = ui.bar()
      card.addView(totalBar)
      totalLabel = text("", 13, Ui.MUTED)
      ui.add(card, totalLabel, 8)
      ui.add(card, text("总剩余时间", 12, Ui.MUTED), 14)
      totalEta = ui.bold("正在测量整批速度…", 23, Ui.BLUE)
      ui.add(card, totalEta, 5)
      speed = text("", 12, Ui.MUTED)
      ui.add(card, speed, 6)
      ui.line(card, 12)
      phaseLabel = ui.chip("", Ui.BLUE, Ui.TINT)
      card.addView(phaseLabel)
      fileName = text("", 12, Ui.INK)
      fileName.maxLines = 2
      ui.add(card, fileName, 8)
      currentBar = ui.bar()
      card.addView(currentBar, LinearLayout.LayoutParams(-1, dp(4)))
      currentLabel = text("", 11, Ui.MUTED)
      ui.add(card, currentLabel, 6)
      action = ui.button("暂停", false) { act() }
      ui.add(card, action, 12)
    }

    private fun act() {
      if (TransferEngine.BUSY.get()) {
        TransferEngine.stop(this@MainActivity)
        return
      }
      val kind = TransferEngine.taskKind
      if ("completed" == TransferEngine.outcome && "import" == kind) {
        selectTab(1)
        return
      }
      if ("completed" == TransferEngine.outcome && "upload" == kind) {
        recyclePhone()
        return
      }
      if ("completed" == TransferEngine.outcome && "delete-source" == kind) {
        ejectCard()
        return
      }
      if ("delete-source" == kind) deleteCard()
      else if ("cleanup" == kind) recyclePhone()
      else if ("import" == kind) scan()
      else upload()
    }

    fun update(all: ArrayList<Store.Item>) {
      if (card.visibility != View.VISIBLE) return
      if (scanning || awaitingStart) {
        title.text = if (scanning) "正在检查文件与空间" else "正在启动任务"
        percent.text = "…"
        totalBar.isIndeterminate = true
        totalLabel.text = "进度会在这里显示"
        totalEta.text = "即将开始"
        speed.text = ""
        phaseLabel.text = "准备中"
        fileName.text = ""
        currentLabel.text = ""
        action.isEnabled = false
        action.text = "请稍候"
        return
      }
      totalBar.isIndeterminate = false
      val deleting = "delete-source" == TransferEngine.taskKind
      val cleaning = "cleanup" == TransferEngine.taskKind
      val importing = "import" == TransferEngine.taskKind
      var size = 0L
      var finished = 0.0
      var count = 0
      var complete = 0
      var name = "等待处理视频"
      for (item in all) {
        if (!TransferEngine.taskIds.contains(item.id)) continue
        size += item.size
        count++
        val ok = if (deleting) item.sourceDeleted
        else if (cleaning) "uploaded" == item.state
        else if (importing) item.sha != null
            && listOf("ready", "uploading", "upload_error", "cleanup_error", "uploaded")
                .contains(item.state)
        else listOf("uploaded", "cleanup_error").contains(item.state)
        if (ok) {
          finished += item.size
          complete++
        } else if (item.id == TransferEngine.activeId) {
          val fraction = if (deleting || cleaning)
            TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * .95
          else if (importing)
            TransferProgress.imported(TransferEngine.phase, TransferEngine.done, TransferEngine.total)
          else if (TransferEngine.phase == TransferProgress.Phase.UPLOAD)
            TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * .95
          else if (TransferEngine.phase == TransferProgress.Phase.VERIFY_CLOUD) .95 else 0.0
          finished += item.size * fraction
        }
        if (item.id == TransferEngine.activeId) name = item.name
      }
      val busy = TransferEngine.BUSY.get()
      val completed = "completed" == TransferEngine.outcome
      val percentage = if (size > 0) Math.min(100.0, finished * 100 / size).toInt() else 0
      title.text = (when {
        deleting -> "卡上删除"
        cleaning -> "手机清理"
        importing -> "整批拷贝"
        else -> "整批上传"
      }) + (if (busy) "进度" else if (completed) "已完成" else "已暂停 / 待继续")
      percent.text = "$percentage%"
      totalBar.progress = percentage * 10
      totalLabel.text = "已完成 $complete / $count 个视频 · " + TransferEngine.bytes(finished.toLong()) + " / " + TransferEngine.bytes(size) + (if (importing) "（含校验）" else "")
      val multiplier = if (importing) 3.0 else 1.0
      val remaining = batchEstimate.remaining(
          TransferEngine.generation, SystemClock.elapsedRealtime(),
          finished * multiplier, size * multiplier, busy)
      totalEta.text = when {
        percentage == 100 -> "已完成"
        !busy && completed -> "本次操作已结束"
        !busy -> "已暂停"
        remaining < 0 -> "正在测量整批速度…"
        else -> "约 " + TransferProgress.duration(remaining)
      }
      speed.text = if (batchEstimate.rate > 0 && busy)
        "处理速度 " + TransferEngine.bytes(batchEstimate.rate.toLong()) + "/s" else ""
      phaseLabel.text = TransferProgress.label(TransferEngine.phase)
      fileName.text = name
      val checking = TransferEngine.phase == TransferProgress.Phase.VERIFY_CLOUD
      currentBar.isIndeterminate = checking && busy
      currentBar.progress =
          (TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * 1000).toInt()
      currentLabel.text = if (checking) "当前视频：正在核验云端完整性"
      else "当前视频 " + (TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * 100).toInt() + "%"
      action.isEnabled = true
      action.text = when {
        busy -> "暂停"
        completed && importing -> "下一步：上传云端 →"
        completed && deleting -> "安全弹出 TF 卡"
        completed && !cleaning -> "删除手机副本"
        else -> "继续当前操作"
      }
      action.visibility = if (!busy && completed && cleaning) View.GONE else View.VISIBLE
    }
  }

  internal inner class FileRow(item: Store.Item, row: LinearLayout, private val state: TextView) {
    private val id = item.id
    private val detail: TextView
    private val error: TextView
    private val progress: ProgressBar

    init {
      detail = text("", 12, Ui.BLUE)
      ui.add(row, detail, 10)
      progress = ui.bar()
      row.addView(progress)
      error = text("", 12, Ui.AMBER)
      ui.add(row, error, 10)
      update(item)
    }

    fun update(item: Store.Item) {
      state.text = TransferEngine.bytes(item.size) + " · " + fileState(item.state)
      error.text = item.error
      error.visibility = if (!item.error.isNullOrEmpty()) View.VISIBLE else View.GONE
      val active = id == TransferEngine.activeId && TransferEngine.BUSY.get()
      detail.visibility = if (active) View.VISIBLE else View.GONE
      progress.visibility = if (active) View.VISIBLE else View.GONE
      if (active) {
        detail.text = TransferProgress.label(TransferEngine.phase) + " · " + TransferEngine.detail()
        progress.progress =
            (TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * 1000).toInt()
      }
    }
  }

  internal fun empty(
      parent: LinearLayout, icon: String, title: String, description: String,
      action: String?, run: (() -> Unit)?
  ) {
    val card = ui.card()
    card.gravity = Gravity.CENTER_HORIZONTAL
    card.setPadding(dp(24), dp(32), dp(24), dp(32))
    card.addView(ui.icon(icon, Ui.BLUE, 42))
    val t = ui.bold(title, 18, Ui.INK)
    t.gravity = Gravity.CENTER
    ui.add(card, t, 18)
    val body = text(description, 14, Ui.MUTED)
    body.gravity = Gravity.CENTER
    ui.add(card, body, 10)
    if (action != null) ui.add(card, ui.button(action, true) { run?.invoke() }, 22)
    ui.add(parent, card, 0)
  }

  internal fun fileState(state: String): String = when (state) {
    "waiting" -> "等待导入"
    "copying" -> "复制到手机"
    "verifying" -> "校验文件"
    "ready" -> "等待上传"
    "uploading" -> "上传中"
    "cleanup_error" -> "备份已完成 · 手机副本保留"
    "copy_error" -> "导入暂停 · 可继续"
    "upload_error" -> "上传暂停 · 可重试"
    else -> "待处理"
  }

  internal fun renderLocal(files: ArrayList<Store.Item>) {
    list.removeAllViews()
    fileRows.clear()
    if (files.isEmpty()) {
      empty(
          list, "upload", "还没有待上传视频",
          "先把 TF 卡视频导入到手机。\n导入完成后，会自动加入上传队列。", "去导入视频") { selectTab(0) }
      return
    }
    var date = ""
    for (f in files) {
      val group = if (f.importTime != null)
        f.importTime!!.replace('_', ' ')
            .replace(Regex(" (\\d{2})-(\\d{2})-(\\d{2})$"), " $1:$2:$3")
      else f.date
      if (group != date) {
        date = group
        ui.add(list, ui.bold(date, 13, Ui.MUTED), 14)
      }
      val card = ui.card()
      val top = ui.row()
      top.addView(ui.icon("video", Ui.BLUE, 24))
      val name = ui.bold(f.name, 14, Ui.INK)
      name.maxLines = 2
      val np = LinearLayout.LayoutParams(0, -2, 1f)
      np.leftMargin = dp(12)
      top.addView(name, np)
      card.addView(top)
      val state = text("", 12, Ui.MUTED)
      ui.add(card, state, 12)
      fileRows[f.id] = FileRow(f, card, state)
      if (f.dest != null) card.setOnClickListener { play(Uri.parse(f.dest), "video/*") }
      ui.add(list, card, 10)
    }
  }

  internal fun loadCloud(more: Boolean) {
    if (loading) return
    loading = true
    if (!more) {
      cloudList.removeAllViews()
      empty(cloudList, "cloud", "正在同步云端列表", "稍等片刻，正在读取已上传的视频。", null, null)
    }
    executor.execute {
      try {
        val result = Api.call(
            "GET",
            "/videos" + (if (more && next != null)
              "?before=" + URLEncoder.encode(next, "UTF-8") else ""),
            null)
        val fetched = result.getJSONArray("videos")
        runOnUiThread {
          if (!more) cloudItems = JSONArray()
          for (i in 0 until fetched.length()) cloudItems.put(fetched.optJSONObject(i))
          next = if (result.isNull("next")) null else result.optString("next", null)
          loading = false
          renderCloud()
        }
      } catch (e: Exception) {
        runOnUiThread {
          loading = false
          cloudList.removeAllViews()
          empty(
              cloudList, "cloud", "暂时无法读取云端", TransferEngine.readable(e), "重新连接")
          { loadCloud(false) }
        }
      }
    }
  }

  internal fun renderCloud() {
    cloudList.removeAllViews()
    cloudCount.text = "云端视频 · " + cloudItems.length()
    if (cloudItems.length() == 0) {
      empty(
          cloudList, "cloud", "云端还没有视频",
          "先完成一次导入和上传。\n上传成功后，所有手机都能在这里看到。", "去导入视频") { selectTab(0) }
      return
    }
    var date = ""
    for (i in 0 until cloudItems.length()) {
      val video = cloudItems.optJSONObject(i) ?: continue
      val group = video.optString("import_time", video.optString("date"))
          .replace('_', ' ')
          .replace(Regex(" (\\d{2})-(\\d{2})-(\\d{2})$"), " $1:$2:$3")
      if (date != group) {
        date = group
        ui.add(cloudList, ui.bold(date, 13, Ui.MUTED), 14)
      }
      val row = ui.card()
      val top = ui.row()
      top.addView(ui.icon("video", Ui.BLUE, 24))
      val name = ui.bold(video.optString("name"), 14, Ui.INK)
      name.maxLines = 2
      val p = LinearLayout.LayoutParams(0, -2, 1f)
      p.leftMargin = dp(12)
      top.addView(name, p)
      row.addView(top)
      val missing = "missing" == video.optString("state")
      ui.add(
          row,
          text(
              TransferEngine.bytes(video.optLong("size")) + " · " +
                  (if (missing) "云端文件已不存在" else "已完整校验 · 点击播放"),
              12, if (missing) Ui.AMBER else Ui.GREEN), 12)
      if (!missing) row.setOnClickListener {
        executor.execute {
          try {
            val result = Api.call("GET", "/videos/" + video.getString("_id") + "/url", null)
            runOnUiThread { play(Uri.parse(result.optString("url")), "video/*") }
          } catch (e: Exception) {
            runOnUiThread { toast(TransferEngine.readable(e)) }
          }
        }
      }
      ui.add(cloudList, row, 10)
    }
    if (next != null) ui.add(cloudList, button("加载更多") { loadCloud(true) }, 16)
  }

  internal fun choose() {
    if (TransferEngine.BUSY.get() || scanning) {
      toast("请等待检查完成，或先暂停当前传输")
      return
    }
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    val old = prefs.getString("source_tree", null)
    if (old != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(old))
    startActivityForResult(i, 7)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(request: Int, result: Int, data: Intent?) {
    super.onActivityResult(request, result, data)
    if (request == 7 && result == RESULT_OK && data != null && data.data != null) {
      val tree = data.data!!
      try {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (data.flags and rw != rw) throw SecurityException("目录未授予读写权限")
        contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("source_tree", tree.toString()).apply()
        clearInventory()
        checkStorage(true)
        refresh()
      } catch (_: Exception) {
        toast("目录授权失败，请重新选择可读写的视频目录")
      }
    }
    if (request == 9 && packageManager.canRequestPackageInstalls()) install()
  }

  fun scan() {
    if (scanning) return
    if (TransferEngine.BUSY.get()) {
      toast("当前任务正在运行，请先暂停")
      return
    }
    val tree = prefs.getString("source_tree", null) ?: run {
      choose()
      return
    }
    scanning = true
    requestedKind = "import"
    refresh()
    executor.execute {
      try {
        var pending: String? = null
        store.readableDatabase.rawQuery(
            "SELECT id FROM batches WHERE tree=? AND ready=0 ORDER BY rowid DESC LIMIT 1",
            arrayOf(tree)).use { c ->
          if (c.moveToFirst()) pending = c.getString(0)
        }
        if (pending != null) {
          val batch = pending
          runOnUiThread {
            scanning = false
            start(batch!!, false)
            refresh()
          }
          return@execute
        }
        val dir = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(tree))
        if (dir == null || !dir.exists() || !dir.canRead())
          throw IOException("读卡器未连接或目录授权失效，请重插或重新选择")
        val files = ArrayList<DocumentFile>()
        var size = 0L
        for (f in dir.listFiles()) {
          if (f.isFile) {
            if (f.length() <= 0) throw IOException("存在无法读取或空文件：" + f.getName())
            files.add(f)
            size += f.length()
          }
        }
        if (files.isEmpty()) throw IOException("目录内没有文件")
        if (TransferEngine.free(this@MainActivity) < size + TransferEngine.RESERVE)
          throw IOException(
              "空间不足，请清理至少 " + TransferEngine.bytes(size + TransferEngine.RESERVE - TransferEngine.free(this@MainActivity)) + "，保留 5 GB 后再复制")
        files.sortBy { it.getName() ?: "" }
        val batch = UUID.randomUUID().toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINA)
        timestamp.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val importTime = timestamp.format(Date())
        val date = importTime.substring(0, 10)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
          val bv = ContentValues()
          bv.put("id", batch)
          bv.put("tree", tree)
          bv.put("delete_source", 0)
          db.insertOrThrow("batches", null, bv)
          for (file in files) {
            val v = ContentValues()
            v.put("id", UUID.randomUUID().toString())
            v.put("batch", batch)
            v.put("source", file.uri.toString())
            v.put("tree", tree)
            v.put("name", file.getName())
            v.put("size", file.length())
            v.put("modified", file.lastModified())
            v.put("date", date)
            v.put("import_time", importTime)
            db.insertOrThrow("files", null, v)
          }
          db.setTransactionSuccessful()
        } finally {
          db.endTransaction()
        }
        runOnUiThread {
          scanning = false
          selectTab(0)
          start(batch, false)
        }
      } catch (e: Exception) {
        runOnUiThread {
          scanning = false
          TransferEngine.message = TransferEngine.readable(e)
          refresh()
          AlertDialog.Builder(this@MainActivity)
              .setTitle("暂时无法导入")
              .setMessage(TransferEngine.message)
              .setPositiveButton("知道了", null)
              .show()
        }
      }
    }
  }

  internal fun start(batch: String?, cellular: Boolean) {
    requestNotification()
    prefs.edit().putBoolean("upload_paused", false).apply()
    val i = Intent(this, TransferService::class.java)
    if (batch != null) i.putExtra("batch", batch)
    i.putExtra("cellular", cellular)
    requestedGeneration = TransferEngine.generation
    awaitingStart = true
    requestedKind = if (batch == null) "upload" else "import"
    startForegroundService(i)
    refresh()
    handler.postDelayed({
      awaitingStart = false
      refresh()
    }, 5000)
  }

  internal fun upload() {
    if (TransferEngine.BUSY.get()) {
      toast("任务正在运行")
      return
    }
    val ready = store.files("state IN ('ready','uploading','upload_error')")
    if (ready.isEmpty()) {
      toast("先完成视频导入，再开始上传")
      selectTab(0)
      return
    }
    val engine = connectivity
    if (!engine.connected()) {
      toast("请先连接网络")
      return
    }
    if (!engine.wifi()) AlertDialog.Builder(this)
        .setTitle("使用移动网络上传")
        .setMessage(
            "本次待上传约 " + TransferEngine.bytes(ready.map { f -> f.size }.sum()) + "，是否通过流量上传？中断后需再次手动确认。")
        .setNegativeButton("取消", null)
        .setPositiveButton("开始上传") { _, _ -> start(null, true) }
        .show()
    else start(null, false)
  }

  internal fun play(uri: Uri, mime: String) {
    try {
      startActivity(
          Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, mime)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
      toast("没有可用播放器；不影响复制和上传")
    }
  }

  internal fun updates() {
    checkUpdates(false)
  }

  internal fun checkUpdates(quiet: Boolean) {
    if (!quiet) toast("正在检查更新")
    executor.execute {
      try {
        val version = Api.call("GET", "/updates/android", null)
        if (version.optInt("version_code") <= BuildConfig.VERSION_CODE) {
          if (!quiet) runOnUiThread { toast("当前已是最新版本") }
          return@execute
        }
        runOnUiThread {
          if (isFinishing || isDestroyed) return@runOnUiThread
          AlertDialog.Builder(this)
              .setTitle("发现新版本 " + version.optString("version_name"))
              .setMessage(version.optString("notes", "改进传输体验"))
              .setNegativeButton("稍后", null)
              .setPositiveButton("下载并安装") { _, _ -> download(version) }
              .show()
        }
      } catch (e: Exception) {
        if (!quiet) runOnUiThread { toast(TransferEngine.readable(e)) }
      }
    }
  }

  internal class Cancelled : IOException()

  internal fun download(version: JSONObject) {
    updateCancelled = false
    runOnUiThread {
      if (isFinishing || isDestroyed) return@runOnUiThread
      val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
      val label = TextView(this)
      label.text = "准备下载…"
      val box = LinearLayout(this)
      box.orientation = LinearLayout.VERTICAL
      val pad = (20 * resources.displayMetrics.density).toInt()
      box.setPadding(pad, pad, pad, pad)
      box.addView(bar)
      box.addView(label)
      updateDialog = AlertDialog.Builder(this)
          .setTitle("下载更新 " + version.optString("version_name"))
          .setView(box)
          .setNegativeButton("取消") { _, _ ->
            updateCancelled = true
            if (updateDialog != null && updateDialog!!.isShowing) updateDialog!!.dismiss()
          }
          .setCancelable(false)
          .show()
      executor.execute { doDownload(version, bar, label) }
    }
  }

  internal fun dismissUpdateDialog() {
    runOnUiThread {
      if (updateDialog != null && updateDialog!!.isShowing) updateDialog!!.dismiss()
    }
  }

  internal fun doDownload(version: JSONObject, bar: ProgressBar, label: TextView) {
    val apk = File(cacheDir, "update.apk")
    try {
      val length = version.getLong("size")
      val c = URL(version.getString("url")).openConnection() as HttpURLConnection
      c.connectTimeout = 20000
      c.readTimeout = 60000
      try {
        if (c.responseCode != 200) throw IOException("下载链接失效，请重新检查更新")
        if (TransferEngine.free(this) < length + TransferEngine.RESERVE)
          throw IOException("请清理手机空间后更新")
        val lastPct = intArrayOf(-1)
        c.inputStream.use { input ->
          FileOutputStream(apk).use { out ->
            val b = ByteArray(128 * 1024)
            var received = 0L
            while (true) {
              val n = input.read(b)
              if (n == -1) break
              if (updateCancelled) throw Cancelled()
              out.write(b, 0, n)
              received += n
              if (received > length) throw IOException("安装包大小不匹配")
              val pct = (received * 100 / length).toInt()
              if (pct != lastPct[0]) {
                lastPct[0] = pct
                val done = received
                val total = length
                runOnUiThread {
                  bar.progress = pct
                  label.text = "$pct% · " + done / 1048576 + " / " + total / 1048576 + " MB"
                }
              }
            }
            if (received != length) throw IOException("安装包未下载完整")
          }
        }
      } finally {
        c.disconnect()
      }
      runOnUiThread { label.text = "校验安装包…" }
      val digest: Array<String>
      apk.inputStream().use { input ->
        digest = Digests.hash(input) { }
      }
      if (digest[0] != version.getString("sha256")) throw IOException("安装包校验失败")
      if (updateCancelled) throw Cancelled()
      dismissUpdateDialog()
      runOnUiThread { install() }
    } catch (_: Cancelled) {
      apk.delete()
      dismissUpdateDialog()
    } catch (e: Exception) {
      apk.delete()
      dismissUpdateDialog()
      runOnUiThread { toast(TransferEngine.readable(e)) }
    }
  }

  internal fun install() {
    if (!packageManager.canRequestPackageInstalls()) {
      AlertDialog.Builder(this)
          .setMessage("需要允许本应用安装更新，设置完成后返回即可继续。")
          .setPositiveButton("打开设置") { _, _ ->
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")), 9)
          }
          .setNegativeButton("取消", null)
          .show()
      return
    }
    try {
      startActivity(
          Intent(Intent.ACTION_VIEW)
              .setDataAndType(
                  Uri.parse("content://" + packageName + ".install/update.apk"),
                  "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
      toast("无法打开安装器")
    }
  }

  internal fun toast(s: String) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show()
  }
}
