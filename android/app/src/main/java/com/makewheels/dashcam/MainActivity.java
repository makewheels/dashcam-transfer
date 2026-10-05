package com.makewheels.dashcam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.hardware.usb.*;
import android.net.*;
import android.os.*;
import android.os.storage.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  Store store;
  TransferEngine connectivity;
  SharedPreferences prefs;
  Ui ui;
  LinearLayout root, list, cloudList, batchList;
  TextView networkState,
      networkHelp,
      otgState,
      otgHelp,
      copyButton,
      uploadButton,
      homePause,
      liveHint,
      batchStatus;
  TextView deleteCardButton, recycleButton, chooseButton;
  LinearLayout steps;
  TextView firstStep, secondStep;
  ProgressBar stepConnector;
  final BatchEstimate batchEstimate = new BatchEstimate();
  long focusedGeneration = Long.MIN_VALUE;
  boolean progressWasVisible = false, awaitingStart = false;
  String requestedKind = "";
  long requestedGeneration = Long.MIN_VALUE;
  long storageCheckedAt = 0, inventoryEpoch = 0;
  boolean inventoryLoading = false, inventoryWasBusy = false;
  long inventoryJob = Long.MIN_VALUE;
  String inventoryOutcome = "";
  String inventorySummary = "", inventorySignature = "";
  AlertDialog inventoryDialog;
  AlertDialog updateDialog;
  volatile boolean updateCancelled;
  JSONArray remoteBatches = new JSONArray();
  boolean batchesLoading = false, readerPresent = false;
  String batchNext = null;
  final ExecutorService executor = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  final View[] pages = new View[4];
  LinearLayout queueControls;
  TextView queueAction, queuePause;
  TextView heading,
      subtitle,
      network,
      guideStep,
      guideTitle,
      guideBody,
      guideAction,
      guideSecondary,
      source,
      space,
      queueTitle,
      queueHint,
      cloudCount;
  TaskCard homeTask, uploadTask;
  LinearLayout guideCard, sourceCard;
  boolean resumed = false, loading = false, scanning = false, cardPresent = false;
  String cardName = "", lastRows = "", next = null, sourceName = "";
  int tab = 0;
  JSONArray cloudItems = new JSONArray();
  final Map<String, FileRow> fileRows = new HashMap<>();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new Store(this);
    connectivity = new TransferEngine(this);
    prefs = getSharedPreferences("settings", 0);
    ui = new Ui(this);
    build();
    UploadWorker.schedule(this);
    registerMedia();
    checkStorage(false);
    prefs.edit().putBoolean("intro_seen", true).apply();
    if (System.currentTimeMillis() - prefs.getLong("update_checked", 0) > 24 * 60 * 60 * 1000L)
      checkUpdates(true);
  }

  int dp(int n) {
    return ui.dp(n);
  }

  TextView text(String value, int size, int color) {
    return ui.text(value, size, color);
  }

  TextView button(String name, Runnable run) {
    return ui.button(name, false, run);
  }

  void build() {
    root = ui.column();
    root.setBackgroundColor(Ui.BG);
    insets(root);
    setContentView(root);
    LinearLayout header = ui.column();
    header.setPadding(dp(24), dp(16), dp(24), dp(12));
    LinearLayout line = ui.row();
    heading = ui.bold("开始", 22, Ui.INK);
    line.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    TextView more = ui.bold("⋯", 24, Ui.INK);
    more.setPadding(dp(12), dp(4), 0, dp(4));
    more.setContentDescription("更多：历史与更新");
    more.setOnClickListener(v -> more());
    line.addView(more);
    header.addView(line);
    subtitle = text("把行车视频安全转存到云端", 13, Ui.MUTED);
    ui.add(header, subtitle, 8);
    root.addView(header);
    steps = ui.row();
    steps.setPadding(dp(24), 0, dp(24), dp(14));
    firstStep = ui.bold("① 拷贝到手机", 15, Ui.BLUE);
    firstStep.setPadding(dp(4), dp(10), dp(12), dp(10));
    firstStep.setOnClickListener(v -> selectTab(0));
    secondStep = ui.bold("② 上传云端", 15, Ui.MUTED);
    secondStep.setPadding(dp(12), dp(10), dp(4), dp(10));
    secondStep.setOnClickListener(v -> selectTab(1));
    steps.addView(firstStep);
    stepConnector = ui.bar();
    LinearLayout.LayoutParams connector = new LinearLayout.LayoutParams(0, dp(4), 1);
    connector.setMargins(dp(10), 0, dp(10), 0);
    steps.addView(stepConnector, connector);
    steps.addView(secondStep);
    root.addView(steps);
    FrameLayout container = new FrameLayout(this);
    root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));
    pages[0] = homePage();
    pages[1] = queuePage();
    pages[2] = batchPage();
    pages[3] = cloudPage();
    for (View page : pages) container.addView(page, new FrameLayout.LayoutParams(-1, -1));

    selectTab(0);
    refresh();
  }

  ScrollView scroll(LinearLayout content) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(true);
    s.setClipToPadding(false);
    s.setPadding(dp(24), 0, dp(24), dp(24));
    s.addView(content);
    return s;
  }

  View homePage() {
    LinearLayout content = ui.column();
    homeTask = new TaskCard("整批拷贝进度");
    ui.add(content, homeTask.card, 0);
    sourceCard = ui.card();
    otgState = ui.bold("检测 TF 卡…", 22, Ui.INK);
    sourceCard.addView(otgState);
    otgHelp = text("插入读卡器后，选择视频目录", 13, Ui.MUTED);
    ui.add(sourceCard, otgHelp, 8);
    source = text("", 14, Ui.INK);
    source.setTextIsSelectable(true);
    ui.add(sourceCard, source, 18);
    chooseButton = ui.button("选择视频文件夹", true, this::choose);
    ui.add(sourceCard, chooseButton, 14);
    space = text("", 12, Ui.MUTED);
    ui.add(sourceCard, space, 12);
    ui.add(content, sourceCard, 0);
    liveHint = text("", 15, Ui.INK);
    ui.add(content, liveHint, 22);
    copyButton = ui.button("拷贝到手机", true, this::scan);
    ui.add(content, copyButton, 18);
    deleteCardButton = ui.button("删除卡上已拷贝的视频", false, this::deleteCard);
    ui.add(content, deleteCardButton, 12);
    homePause = ui.button("暂停", false, () -> TransferEngine.stop(this));
    return scroll(content);
  }

  void more() {
    new AlertDialog.Builder(this)
        .setTitle("更多")
        .setItems(
            new String[] {
              "历史批次",
              "云端视频",
              "更换视频文件夹",
              "安全弹出 TF 卡",
              "检查更新 · " + BuildConfig.VERSION_NAME,
              "通知与后台运行设置"
            },
            (d, index) -> {
              if (index == 0) selectTab(2);
              else if (index == 1) selectTab(3);
              else if (index == 2) choose();
              else if (index == 3) ejectCard();
              else if (index == 4) updates();
              else backgroundSettings();
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  @Override
  public void onBackPressed() {
    if (tab != 0) selectTab(0);
    else super.onBackPressed();
  }

  void explicitAction(String action) {
    if (TransferEngine.BUSY.get()) {
      toast("请先暂停当前任务");
      return;
    }
    requestNotification();
    Intent intent = new Intent(this, TransferService.class).setAction(action);
    intent.putExtra("tree", prefs.getString("source_tree", ""));
    requestedGeneration = TransferEngine.generation;
    awaitingStart = true;
    requestedKind = action.equals("delete-source") ? "delete-source" : "cleanup";
    startForegroundService(intent);
    refresh();
    handler.postDelayed(
        () -> {
          awaitingStart = false;
          refresh();
        },
        5000);
  }

  void deleteCard() {
    new AlertDialog.Builder(this)
        .setTitle("删除卡上已拷贝的视频？")
        .setMessage("只删除已经整批复制校验、并再次核对手机副本与卡上内容的视频。未完成或内容变化的文件保留，不格式化 TF 卡。")
        .setNegativeButton("保留", null)
        .setPositiveButton("删除", (d, w) -> explicitAction("delete-source"))
        .show();
  }

  void ejectCard() {
    if (TransferEngine.BUSY.get() || scanning) {
      toast("请先暂停拷贝或删除，再卸载 TF 卡");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("安全弹出 TF 卡")
        .setMessage("接下来打开系统存储页。请选择 TF 卡并点「卸载 / 弹出」，系统确认完成后再拔出读卡器。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "打开系统存储页",
            (d, w) -> {
              Intent intent = new Intent(Settings.ACTION_MEMORY_CARD_SETTINGS);
              StorageManager manager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
              String selectedVolume = "";
              try {
                selectedVolume = DocumentsContract.getTreeDocumentId(
                    Uri.parse(prefs.getString("source_tree", ""))).split(":", 2)[0];
              } catch (RuntimeException ignored) { }
              for (StorageVolume volume : manager.getStorageVolumes())
                if (volume.isRemovable() && selectedVolume.equalsIgnoreCase(volume.getUuid())) {
                  intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, volume);
                  break;
                }
              try {
                startActivity(intent);
              } catch (ActivityNotFoundException unavailable) {
                try {
                  startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
                } catch (ActivityNotFoundException alsoUnavailable) {
                  toast("请在手机系统设置的存储页面卸载 TF 卡");
                }
              }
            })
        .show();
  }

  void recyclePhone() {
    new AlertDialog.Builder(this)
        .setTitle("移入手机回收站？")
        .setMessage("仅处理云端已核验完成的视频，手机副本移入系统回收站，不永久删除。回收站仍占空间，保留期限由系统管理。")
        .setNegativeButton("保留", null)
        .setPositiveButton("移入回收站", (d, w) -> explicitAction("recycle-phone"))
        .show();
  }

  View batchPage() {
    LinearLayout content = ui.column();
    LinearLayout intro = ui.card();
    LinearLayout row = ui.row();
    row.addView(ui.bold("之前的批次", 21, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(button("同步", () -> loadBatches(false)));
    intro.addView(row);
    batchStatus = text("本机导入记录一直保留；联网同步所有手机已上传的批次。", 13, Ui.MUTED);
    ui.add(intro, batchStatus, 10);
    ui.add(content, intro, 0);
    batchList = ui.column();
    ui.add(content, batchList, 18);
    return scroll(content);
  }

  String lastBatchRows = "";

  String timeLabel(String value) {
    return value == null
        ? "时间未知"
        : value.replace('_', ' ').replaceAll(" (\\d{2})-(\\d{2})-(\\d{2})$", " $1:$2:$3");
  }

  final class BatchView {
    String id, time;
    long size;
    int count, copied, uploaded, cloud, missing;
    List<Store.Item> local = new ArrayList<>();
  }

  void loadBatches(boolean more) {
    if (batchesLoading) return;
    batchesLoading = true;
    batchStatus.setText("正在同步云端批次，本机记录可以查看…");
    renderBatches();
    executor.execute(
        () -> {
          try {
            JSONObject result =
                Api.call(
                    "GET",
                    "/batches"
                        + (more && batchNext != null
                            ? "?before=" + URLEncoder.encode(batchNext, "UTF-8")
                            : ""),
                    null);
            runOnUiThread(
                () -> {
                  if (!more) remoteBatches = new JSONArray();
                  JSONArray fetched = result.optJSONArray("batches");
                  for (int i = 0; i < fetched.length(); i++)
                    remoteBatches.put(fetched.optJSONObject(i));
                  batchNext = result.isNull("next") ? null : result.optString("next", null);
                  batchesLoading = false;
                  batchStatus.setText("本机记录已保留 · 云端批次已同步");
                  lastBatchRows = "";
                  renderBatches();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  batchesLoading = false;
                  batchStatus.setText("本机记录可查看。云端未同步：" + TransferEngine.readable(e));
                  renderBatches();
                });
          }
        });
  }

  void renderBatches() {
    if (batchList == null) return;
    Map<String, BatchView> map = new LinkedHashMap<>();
    for (Store.Item item : store.files("1=1")) {
      if (item.batch == null) continue;
      BatchView batch =
          map.computeIfAbsent(
              item.batch,
              k -> {
                BatchView b = new BatchView();
                b.id = k;
                return b;
              });
      batch.local.add(item);
      batch.count++;
      batch.size += item.size;
      batch.time = item.importTime != null ? item.importTime : item.date;
      if (item.sha != null) batch.copied++;
      if (Arrays.asList("uploaded", "cleanup_error").contains(item.state)) batch.uploaded++;
    }
    for (int i = 0; i < remoteBatches.length(); i++) {
      JSONObject remote = remoteBatches.optJSONObject(i);
      String id = remote.optString("id");
      BatchView batch =
          map.computeIfAbsent(
              id,
              k -> {
                BatchView b = new BatchView();
                b.id = k;
                return b;
              });
      batch.cloud = remote.optInt("count");
      batch.missing = remote.optInt("missing");
      if (batch.local.isEmpty()) {
        batch.count = batch.cloud;
        batch.size = remote.optLong("size");
        batch.time = remote.optString("import_time", remote.optString("date"));
      }
    }
    List<BatchView> batches = new ArrayList<>(map.values());
    batches.sort((a, b) -> Objects.toString(b.time, "").compareTo(Objects.toString(a.time, "")));
    String signature = "";
    for (BatchView b : batches)
      signature +=
          b.id
              + ":"
              + b.count
              + ":"
              + b.copied
              + ":"
              + b.uploaded
              + ":"
              + b.cloud
              + ":"
              + b.missing
              + ";";
    signature += batchNext;
    if (signature.equals(lastBatchRows) && batchList.getChildCount() > 0) return;
    lastBatchRows = signature;
    batchList.removeAllViews();
    if (batches.isEmpty()) {
      empty(
          batchList,
          "history",
          "还没有导入批次",
          "每次拷贝会生成一个批次。\n完成上传后，记录仍然保留。",
          "去拷贝视频",
          () -> selectTab(0));
      return;
    }
    for (BatchView batch : batches) {
      LinearLayout card = ui.card();
      card.addView(ui.bold(timeLabel(batch.time), 17, Ui.INK));
      ui.add(
          card, text(batch.count + " 个视频 · " + TransferEngine.bytes(batch.size), 13, Ui.MUTED), 10);
      String detail =
          batch.local.isEmpty()
              ? "云端已校验 " + batch.cloud + " 个"
              : "本机已导入 "
                  + batch.copied
                  + " / "
                  + batch.count
                  + " · 云端已确认 "
                  + Math.max(batch.uploaded, batch.cloud)
                  + " 个";
      if (batch.missing > 0) detail += " · " + batch.missing + " 个云端文件不存在";
      ui.add(card, text(detail, 12, Ui.GREEN), 8);
      ui.add(card, text("点击查看这批视频", 12, Ui.BLUE), 10);
      card.setOnClickListener(v -> showBatch(batch));
      ui.add(batchList, card, 12);
    }
    if (batchNext != null) ui.add(batchList, button("加载更早的批次", () -> loadBatches(true)), 16);
  }

  void showBatch(BatchView batch) {
    LinearLayout body = ui.column();
    body.setPadding(dp(20), dp(8), dp(20), dp(20));
    TextView info = text("本机 " + batch.local.size() + " 个记录 · 正在读取云端视频…", 13, Ui.MUTED);
    body.addView(info);
    LinearLayout rows = ui.column();
    ui.add(body, rows, 12);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    new AlertDialog.Builder(this)
        .setTitle(timeLabel(batch.time))
        .setView(scroll)
        .setPositiveButton("关闭", null)
        .show();
    Set<String> seen = new HashSet<>();
    for (Store.Item item : batch.local) {
      if (item.sha != null) seen.add(item.sha);
      LinearLayout row = ui.card();
      row.addView(ui.bold(item.name, 14, Ui.INK));
      ui.add(
          row,
          text(
              TransferEngine.bytes(item.size)
                  + " · "
                  + ("uploaded".equals(item.state) ? "已上传 · 手机副本已回收" : fileState(item.state)),
              12,
              Ui.MUTED),
          10);
      ui.add(rows, row, 10);
      if (Arrays.asList("uploaded", "cleanup_error").contains(item.state) && item.sha != null)
        row.setOnClickListener(v -> playCloud(item.sha));
      else if (item.dest != null)
        row.setOnClickListener(v -> play(Uri.parse(item.dest), "video/*"));
    }
    loadBatchFiles(batch.id, null, rows, info, seen, batch.local.isEmpty());
  }

  void playCloud(String id) {
    executor.execute(
        () -> {
          try {
            JSONObject result = Api.call("GET", "/videos/" + id + "/url", null);
            runOnUiThread(() -> play(Uri.parse(result.optString("url")), "video/*"));
          } catch (Exception e) {
            runOnUiThread(() -> toast(TransferEngine.readable(e)));
          }
        });
  }

  void loadBatchFiles(
      String id,
      String before,
      LinearLayout rows,
      TextView info,
      Set<String> seen,
      boolean onlyCloud) {
    executor.execute(
        () -> {
          try {
            JSONObject result =
                Api.call(
                    "GET",
                    "/videos?batch="
                        + URLEncoder.encode(id, "UTF-8")
                        + (before != null ? "&before=" + URLEncoder.encode(before, "UTF-8") : ""),
                    null);
            runOnUiThread(
                () -> {
                  JSONArray files = result.optJSONArray("videos");
                  for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    String sha = f.optString("_id");
                    if (!seen.add(sha)) continue;
                    LinearLayout row = ui.card();
                    row.addView(ui.bold(f.optString("name"), 14, Ui.INK));
                    ui.add(
                        row,
                        text(
                            TransferEngine.bytes(f.optLong("size"))
                                + " · "
                                + ("missing".equals(f.optString("state"))
                                    ? "云端文件已不存在"
                                    : "已上传 · 云端已校验"),
                            12,
                            Ui.MUTED),
                        10);
                    ui.add(rows, row, 10);
                    if (!"missing".equals(f.optString("state")))
                      row.setOnClickListener(v -> playCloud(sha));
                  }
                  info.setText("已展示本机记录与此批云端视频");
                  String next = result.isNull("next") ? null : result.optString("next", null);
                  if (next != null) {
                    TextView more =
                        button(
                            "加载此批更多视频",
                            () -> loadBatchFiles(id, next, rows, info, seen, onlyCloud));
                    more.setOnClickListener(
                        v -> {
                          rows.removeView(more);
                          loadBatchFiles(id, next, rows, info, seen, onlyCloud);
                        });
                    ui.add(rows, more, 12);
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () ->
                    info.setText(
                        (onlyCloud ? "云端暂时无法读取" : "本机记录可查看，云端暂未同步")
                            + "："
                            + TransferEngine.readable(e)));
          }
        });
  }

  void insets(View view) {
    view.setOnApplyWindowInsetsListener(
        (v, w) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                w.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
          } else
            v.setPadding(
                w.getSystemWindowInsetLeft(),
                w.getSystemWindowInsetTop(),
                w.getSystemWindowInsetRight(),
                w.getSystemWindowInsetBottom());
          return w;
        });
    view.requestApplyInsets();
  }

  void addInstruction(LinearLayout parent, String number, String title, String description) {
    LinearLayout row = ui.row();
    TextView n = ui.chip(number, Ui.BLUE, Ui.TINT);
    n.setGravity(Gravity.CENTER);
    row.addView(n, new LinearLayout.LayoutParams(dp(30), dp(30)));
    LinearLayout copy = ui.column();
    copy.addView(ui.bold(title, 14, Ui.INK));
    ui.add(copy, text(description, 12, Ui.MUTED), 5);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(12);
    row.addView(copy, p);
    ui.add(parent, row, 18);
  }

  View queuePage() {
    LinearLayout content = ui.column();
    LinearLayout netCard = ui.card();
    network = ui.chip("", Ui.GREEN, 0xffe7f5ed);
    netCard.addView(network);
    ui.add(content, netCard, 0);
    uploadTask = new TaskCard("整批上传进度");
    ui.add(content, uploadTask.card, 0);
    LinearLayout intro = ui.card();
    queueTitle = ui.bold("", 23, Ui.INK);
    intro.addView(queueTitle);
    queueHint = text("", 13, Ui.MUTED);
    ui.add(intro, queueHint, 10);
    LinearLayout actions = ui.row();
    queueControls = actions;
    TextView start = ui.button("开始上传", true, this::upload);
    queueAction = start;
    uploadButton = start;
    actions.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
    TextView pause = button("暂停", () -> TransferEngine.stop(this));
    queuePause = pause;
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.leftMargin = dp(10);
    actions.addView(pause, p);
    ui.add(intro, actions, 18);
    ui.add(content, intro, 0);
    recycleButton = ui.button("移入手机回收站", false, this::recyclePhone);
    ui.add(content, recycleButton, 12);

    list = ui.column();
    ui.add(content, list, 20);
    return scroll(content);
  }

  View cloudPage() {
    LinearLayout content = ui.column();
    LinearLayout card = ui.card();
    LinearLayout top = ui.row();
    cloudCount = ui.bold("云端视频", 23, Ui.INK);
    top.addView(cloudCount, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(button("刷新", () -> loadCloud(false)));
    card.addView(top);
    ui.add(card, text("所有手机共享同一份列表。点视频可用手机播放器打开。", 13, Ui.MUTED), 10);
    ui.add(content, card, 0);
    cloudList = ui.column();
    ui.add(content, cloudList, 20);
    return scroll(content);
  }

  void backgroundSettings() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 8);
      return;
    }
    try {
      startActivity(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
    } catch (Exception e) {
      toast("请在系统设置中允许后台运行");
    }
  }

  void selectTab(int selected) {
    tab = selected;
    String[] titles = {"① 拷贝到手机", "② 上传云端", "历史批次", "云端"},
        sub =
            {"拷贝完成后，再决定是否删除卡上视频", "点上传开始备份，完成后再决定清理手机", "按每次导入时间查看记录", "已完成校验的云端视频"};
    for (int i = 0; i < 4; i++) {
      pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
    }
    steps.setVisibility(tab < 2 ? View.VISIBLE : View.GONE);
    firstStep.setTextColor(tab == 0 ? Ui.BLUE : Ui.GREEN);
    secondStep.setTextColor(tab == 1 ? Ui.BLUE : Ui.MUTED);
    stepConnector.setProgress(tab == 1 ? 1000 : 0);
    heading.setText(titles[tab]);
    subtitle.setText(sub[tab]);
    if (tab == 3 && !loading) loadCloud(false);
    if (tab == 2 && !batchesLoading) loadBatches(false);
    refresh();
  }

  void onboarding() {
    Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    LinearLayout page = ui.column();
    page.setBackgroundColor(Ui.BG);
    page.setPadding(dp(28), dp(36), dp(28), dp(30));
    TextView skip = ui.bold("稍后再看", 14, Ui.MUTED);
    skip.setGravity(Gravity.END);
    skip.setPadding(0, dp(12), 0, dp(12));
    page.addView(skip);
    View space = new View(this);
    page.addView(space, new LinearLayout.LayoutParams(-1, 0, 1));
    ImageView illustration = ui.icon("folder", Ui.BLUE, 88);
    page.addView(illustration);
    TextView step = ui.chip("", Ui.BLUE, Ui.TINT);
    ui.add(page, step, 30);
    TextView title = ui.bold("", 30, Ui.INK);
    ui.add(page, title, 20);
    TextView body = text("", 17, Ui.MUTED);
    ui.add(page, body, 16);
    View bottomSpace = new View(this);
    page.addView(bottomSpace, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView action = ui.button("下一步", true, () -> {});
    page.addView(action);
    TextView footer = text("进度会保存，可暂停后继续", 12, Ui.MUTED);
    footer.setGravity(Gravity.CENTER);
    ui.add(page, footer, 14);
    int[] current = {0};
    String[] titles = {"先把 TF 卡接上手机", "把视频完整导入手机", "回家连上 Wi-Fi 上传"},
        bodies =
            {
              "将 TF 卡插进读卡器，再连接手机。\n检测到读卡器后，选择存放视频的文件夹。",
              "点击「开始导入」，查看复制和校验进度。\n整批校验成功后，才清理 TF 卡原视频。",
              "点击上传后开始备份。\n云端核验完成后，可手动移入手机回收站。"
            },
        icons = {"folder", "check", "cloud"};
    Runnable render =
        () -> {
          int i = current[0];
          step.setText("第 " + (i + 1) + " 步 / 3");
          title.setText(titles[i]);
          body.setText(bodies[i]);
          illustration.setImageDrawable(new Ui.Icon(icons[i], Ui.BLUE));
          action.setText(i == 2 ? "开始使用" : "下一步");
        };
    action.setOnClickListener(
        v -> {
          if (current[0] < 2) {
            current[0]++;
            render.run();
          } else {
            prefs.edit().putBoolean("intro_seen", true).apply();
            dialog.dismiss();
            requestNotification();
          }
        });
    skip.setOnClickListener(
        v -> {
          prefs.edit().putBoolean("intro_seen", true).apply();
          dialog.dismiss();
        });
    render.run();
    LinearLayout wrapper = ui.column();
    wrapper.setBackgroundColor(Ui.BG);
    wrapper.addView(page, new LinearLayout.LayoutParams(-1, -1));
    insets(wrapper);
    dialog.setContentView(wrapper);
    dialog.show();
    if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
  }

  void requestNotification() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 8);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    handler.post(tick);
    storageCheckedAt = 0;
    checkStorage(true);
    refresh();
  }

  @Override
  protected void onPause() {
    resumed = false;
    handler.removeCallbacks(tick);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    unregisterReceiver(mediaReceiver);
    unregisterReceiver(usbReceiver);
    executor.shutdownNow();
    store.close();
    connectivity.store.close();
    super.onDestroy();
  }

  final Runnable tick =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          boolean busy = TransferEngine.BUSY.get();
          if (inventoryWasBusy && !busy) storageCheckedAt = 0;
          inventoryWasBusy = busy;
          if (inventoryJob != TransferEngine.generation || !inventoryOutcome.equals(TransferEngine.outcome)) {
            inventoryJob = TransferEngine.generation;
            inventoryOutcome = TransferEngine.outcome;
            if (!busy) storageCheckedAt = 0;
          }
          if (SystemClock.elapsedRealtime() - storageCheckedAt >= 5000) checkStorage(true);
          refresh();
          handler.postDelayed(this, 1000);
        }
      };
  final BroadcastReceiver mediaReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (!Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction()) && cardPresent) {
            cardPresent = false;
            clearInventory();
            toast("读卡器已断开，重新连接后可继续导入");
            if (TransferEngine.BUSY.get()
                && Arrays.asList("import", "delete-source").contains(TransferEngine.taskKind)) {
              TransferEngine.pauseReason = "读卡器已断开，重新连接后继续导入";
              TransferEngine.stopped = true;
            }
            refresh();
          }
          handler.postDelayed(() -> checkStorage(true), 500);
        }
      };

  final BroadcastReceiver usbReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          handler.postDelayed(() -> checkStorage(true), 350);
        }
      };

  void registerMedia() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_REMOVED);
    filter.addAction(Intent.ACTION_MEDIA_EJECT);
    filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
    filter.addDataScheme("file");
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(mediaReceiver, filter);
    IntentFilter usb = new IntentFilter();
    usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(usbReceiver, usb, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(usbReceiver, usb);
  }

  void checkStorage(boolean notify) {
    storageCheckedAt = SystemClock.elapsedRealtime();
    boolean before = cardPresent;
    cardPresent = false;
    cardName = "";
    StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
    if (manager != null)
      for (StorageVolume volume : manager.getStorageVolumes())
        if (volume.isRemovable() && Environment.MEDIA_MOUNTED.equals(volume.getState())) {
          cardPresent = true;
          cardName = volume.getDescription(this);
          break;
        }
    readerPresent = cardPresent;
    UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
    if (usb != null)
      for (UsbDevice device : usb.getDeviceList().values())
        for (int i = 0; i < device.getInterfaceCount(); i++)
          if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE)
            readerPresent = true;
    if (notify && cardPresent && !before) {
      toast("检测到读卡器，请选择 TF 卡的视频文件夹");
    } else if (notify && !cardPresent && before) {
      toast("读卡器已断开，重新连接后可继续导入");
    }
    if (!cardPresent) {
      if (before || !inventorySummary.isEmpty()) clearInventory();
      if (before && TransferEngine.BUSY.get()
          && Arrays.asList("import", "delete-source").contains(TransferEngine.taskKind)) {
        TransferEngine.pauseReason = "TF 卡已断开，重新连接后继续";
        TransferEngine.stopped = true;
      }
    } else if (resumed) inspectCard(notify);
    refresh();
  }

  void clearInventory() {
    inventoryEpoch++;
    inventorySummary = "";
    inventorySignature = "";
    if (inventoryDialog != null) inventoryDialog.dismiss();
  }

  void showInventory(String signature, String title, String detail, boolean canCopy) {
    if (!resumed || TransferEngine.BUSY.get() || scanning || awaitingStart
        || signature.equals(inventorySignature)) return;
    inventorySignature = signature;
    if (inventoryDialog != null) inventoryDialog.dismiss();
    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(detail)
        .setNegativeButton("稍后", null);
    if (canCopy) dialog.setPositiveButton("拷贝到手机", (d, w) -> scan());
    else if (title.equals("手机空间不足")) dialog.setPositiveButton("知道了", null);
    else dialog.setPositiveButton("选择视频文件夹", (d, w) -> choose());
    inventoryDialog = dialog.create();
    inventoryDialog.show();
  }

  void inspectCard(boolean notify) {
    if (!cardPresent || inventoryLoading || TransferEngine.BUSY.get() || scanning || awaitingStart)
      return;
    String tree = prefs.getString("source_tree", "");
    if (tree.isEmpty()) {
      inventorySummary = "请先选择视频文件夹，授权后自动统计数量和大小";
      if (notify) showInventory("choose", "已检测到 TF 卡", inventorySummary, false);
      return;
    }
    final long epoch = inventoryEpoch;
    inventoryLoading = true;
    if (inventorySummary.isEmpty()) inventorySummary = "正在检查视频数量和大小…";
    executor.execute(() -> {
      int count = 0;
      long size = 0;
      String signature, error = null;
      try {
        DocumentFile directory = DocumentFile.fromTreeUri(this, Uri.parse(tree));
        if (directory == null || !directory.exists() || !directory.canRead())
          throw new IOException("记住的目录暂时无法读取，请确认是这张 TF 卡，或重新选择视频文件夹。");
        ArrayList<String> entries = new ArrayList<>();
        for (DocumentFile file : directory.listFiles()) {
          if (!file.isFile()) continue;
          long length = file.length();
          count++;
          size += length;
          entries.add(file.getUri() + ":" + length + ":" + file.lastModified());
        }
        Collections.sort(entries);
        if (!directory.exists() || !directory.canRead()) throw new IOException("TF 卡已断开");
        signature = tree + "|" + entries.toString();
      } catch (Exception failure) {
        error = TransferEngine.readable(failure);
        signature = tree + "|unavailable";
      }
      final int videos = count;
      final long bytes = size;
      final String resultSignature = signature, resultError = error;
      runOnUiThread(() -> {
        inventoryLoading = false;
        if (isDestroyed() || epoch != inventoryEpoch || !cardPresent
            || !tree.equals(prefs.getString("source_tree", ""))) return;
        inventorySummary = resultError == null
            ? "所选目录有 " + videos + " 个视频 · " + TransferEngine.bytes(bytes)
            : resultError;
        refresh();
        if (!notify) return;
        if (resultError != null) {
          showInventory(resultSignature, "TF 卡目录需要确认", inventorySummary, false);
        } else if (videos == 0) {
          showInventory(resultSignature, "TF 卡视频已更新", "所选目录没有视频。可以更换文件夹，或继续上传手机里的视频。", false);
        } else {
          long free = TransferEngine.free(this);
          boolean enough = free >= bytes + TransferEngine.RESERVE;
          String detail = SourcePath.display(this, Uri.parse(tree)) + "\n\n" + inventorySummary
              + "\n手机可用 " + TransferEngine.bytes(free) + "，拷贝后需保留 5 GB。"
              + (enough ? "\n点「拷贝到手机」开始，卡上原视频会保留。" : "\n空间不足，请先清理手机空间。");
          showInventory(resultSignature + "|" + enough, enough ? "TF 卡视频已检测到" : "手机空间不足", detail, enough);
        }
      });
    });
  }

  List<Store.Item> local() {
    return store.files("state!='uploaded'");
  }

  void refresh() {
    if (source == null) return;
    String tree = prefs.getString("source_tree", null);
    if (tree != null) {
      sourceName = SourcePath.display(this, Uri.parse(tree));
    } else sourceName = "尚未选择文件夹";
    source.setText(sourceName);
    chooseButton.setVisibility(tree != null ? View.GONE : View.VISIBLE);
    space.setText("可用 " + TransferEngine.bytes(TransferEngine.free(this)) + " · 始终预留 5 GB");
    TransferEngine engine = connectivity;
    network.setText(engine.wifi() ? "Wi-Fi 已连接" : engine.connected() ? "移动网络" : "未联网");
    network.setTextColor(engine.wifi() ? Ui.GREEN : Ui.MUTED);
    List<Store.Item> files = local();
    long bytes = 0;
    int ready = 0;
    for (Store.Item f : files) {
      bytes += f.size;
      if (Arrays.asList("ready", "uploading", "upload_error").contains(f.state)) ready++;
    }
    queueTitle.setText(ready + " 个待上传 · " + store.files("state='cleanup_error'").size() + " 个已备份");
    queueHint.setText(
        files.isEmpty()
            ? "导入后，视频会出现在这里。"
            : TransferEngine.bytes(bytes)
                + (prefs.getBoolean("upload_paused", false)
                    ? " · 已暂停，点击继续"
                    : " · 手动点击上传，完成后手机副本仍保留"));
    updateGuide(files, ready, tree);
    sourceCard.setVisibility(View.VISIBLE);
    queueControls.setVisibility(View.VISIBLE);
    boolean busy = TransferEngine.BUSY.get();
    queueAction.setEnabled(!busy && ready > 0);
    queueAction.setAlpha(busy || ready == 0 ? .5f : 1);
    queueAction.setText(busy ? "任务进行中" : "上传云端" + (ready > 0 ? " · " + ready + " 个" : ""));
    queuePause.setVisibility(View.GONE);
    List<Store.Item> taskFiles =
        (homeTask.card.getVisibility() == View.VISIBLE
                || uploadTask.card.getVisibility() == View.VISIBLE)
            ? store.files("1=1")
            : Collections.emptyList();
    homeTask.update(taskFiles);
    uploadTask.update(taskFiles);
    StringBuilder signature = new StringBuilder();
    for (Store.Item f : files) signature.append(f.id);
    if (!signature.toString().equals(lastRows) || list.getChildCount() == 0) {
      lastRows = signature.toString();
      renderLocal(files);
    }
    for (Store.Item f : files) {
      FileRow row = fileRows.get(f.id);
      if (row != null) row.update(f);
    }
  }

  void updateGuide(List<Store.Item> files, int ready, String tree) {
    boolean busy = TransferEngine.BUSY.get();
    boolean wifi = connectivity.wifi(), connected = connectivity.connected();
    otgState.setText(cardPresent ? "TF 卡已检测到" : readerPresent ? "读卡器已连接" : "未检测到读卡器");
    otgState.setTextColor(cardPresent ? Ui.GREEN : Ui.INK);
    otgHelp.setText(cardPresent ? cardName + (inventorySummary.isEmpty() ? "" : "\n" + inventorySummary) : readerPresent ? "请插入 TF 卡或检查挂载" : "请插入 OTG 读卡器");
    copyButton.setEnabled(!busy && !scanning);
    copyButton.setAlpha(busy || scanning ? .5f : 1);
    copyButton.setText(scanning ? "正在检查视频…" : "拷贝到手机");
    uploadButton.setEnabled(!busy && !scanning && ready > 0);
    int deletable =
        store
            .files(
                "source_deleted=0 AND tree=? AND batch IN (SELECT id FROM batches WHERE ready=1)",
                tree == null ? "" : tree)
            .size();
    deleteCardButton.setEnabled(!busy && !scanning && deletable > 0);
    deleteCardButton.setAlpha(!busy && deletable > 0 ? 1f : .45f);
    boolean canRecycle = !busy && !store.files("state='cleanup_error'").isEmpty();
    recycleButton.setEnabled(canRecycle);
    recycleButton.setAlpha(canRecycle ? 1f : .45f);
    homePause.setVisibility(busy && tab == 0 ? View.VISIBLE : View.GONE);
    uploadButton.setAlpha(busy || scanning || ready == 0 ? .5f : 1);
    uploadButton.setText("上传云端" + (ready > 0 ? " · " + ready + " 个" : ""));
    homePause.setVisibility(busy ? View.VISIBLE : View.GONE);
    if (scanning) liveHint.setText("正在检查文件和空间，完成后开始拷贝。");
    else if (busy)
      liveHint.setText(
          Arrays.asList("import", "delete-source").contains(TransferEngine.taskKind)
              ? "正在拷贝、核对或删除，请保持 TF 卡连接。"
              : "第二页任务正在进行，请到上传页面查看。");
    else if (files.stream()
        .anyMatch(
            f ->
                !Arrays.asList("ready", "uploading", "upload_error", "cleanup_error")
                    .contains(f.state))) liveHint.setText("有未完成的拷贝。连接原 TF 卡后，点「拷贝到手机」继续。");
    else if (deletable > 0) liveHint.setText("拷贝已完成，有 " + deletable + " 个卡上视频可手动删除，也可直接进入下一步。");
    else if (ready > 0)
      liveHint.setText(
          prefs.getBoolean("upload_paused", false)
              ? "视频已在手机上，可删除卡上已拷贝的视频，再进入下一步。"
              : wifi ? "拷贝已完成。可点删除清理 TF 卡，或直接进入下一步上传。" : "拷贝已完成。卡上视频仍保留，可删除或进入下一步。");
    else
      liveHint.setText(
          tree == null
              ? "先选择 TF 卡的视频文件夹，再点「拷贝到手机」。"
              : cardPresent ? "TF 卡已连接。点「拷贝到手机」，完整校验后卡上原视频仍保留。" : "已记住视频文件夹。连接读卡器后，点「拷贝到手机」。");
    if (!busy
        && !scanning
        && Arrays.asList("import", "delete-source").contains(TransferEngine.taskKind)
        && !TransferEngine.taskIds.isEmpty()) liveHint.append("\n" + TransferEngine.message);
    if (busy || (TransferEngine.generation != requestedGeneration
        && !"running".equals(TransferEngine.outcome))) awaitingStart = false;
    boolean hasTask = !TransferEngine.taskIds.isEmpty();
    boolean show = busy || awaitingStart || scanning || hasTask;
    String visibleKind = scanning || awaitingStart ? requestedKind : TransferEngine.taskKind;
    homeTask.card.setVisibility(
        show && Arrays.asList("import", "delete-source").contains(visibleKind)
            ? View.VISIBLE
            : View.GONE);
    uploadTask.card.setVisibility(
        show && Arrays.asList("upload", "cleanup").contains(visibleKind)
            ? View.VISIBLE
            : View.GONE);
    boolean currentVisible =
        tab == 0
            ? homeTask.card.getVisibility() == View.VISIBLE
            : tab == 1 && uploadTask.card.getVisibility() == View.VISIBLE;
    if (currentVisible
        && (!progressWasVisible || (busy && focusedGeneration != TransferEngine.generation))) {
      focusedGeneration = TransferEngine.generation;
      View page = pages[tab];
      page.post(() -> ((ScrollView) page).smoothScrollTo(0, 0));
    }
    progressWasVisible = currentVisible;
    if (!busy
        && "completed".equals(TransferEngine.outcome)
        && "import".equals(TransferEngine.taskKind)) {
      liveHint.setText("第一步已完成：视频已拷贝并校验。下一步上传云端。卡上原视频可按需手动删除。");
    }
    if (tab == 2 && !batchesLoading) renderBatches();
  }

  boolean engineWifi() {
    return connectivity.wifi();
  }

  final class TaskCard {
    final LinearLayout card = ui.card();
    final TextView title,
        percent,
        totalLabel,
        phaseLabel,
        fileName,
        currentLabel,
        speed,
        totalEta,
        action;
    final ProgressBar totalBar, currentBar;

    TaskCard(String name) {
      LinearLayout top = ui.row();
      title = ui.bold(name, 16, Ui.INK);
      top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      percent = ui.bold("0%", 26, Ui.BLUE);
      top.addView(percent);
      card.addView(top);
      totalBar = ui.bar();
      card.addView(totalBar);
      totalLabel = text("", 13, Ui.MUTED);
      ui.add(card, totalLabel, 8);
      ui.add(card, text("总剩余时间", 12, Ui.MUTED), 14);
      totalEta = ui.bold("正在测量整批速度…", 23, Ui.BLUE);
      ui.add(card, totalEta, 5);
      speed = text("", 12, Ui.MUTED);
      ui.add(card, speed, 6);
      ui.line(card, 12);
      phaseLabel = ui.chip("", Ui.BLUE, Ui.TINT);
      card.addView(phaseLabel);
      fileName = text("", 12, Ui.INK);
      fileName.setMaxLines(2);
      ui.add(card, fileName, 8);
      currentBar = ui.bar();
      card.addView(currentBar, new LinearLayout.LayoutParams(-1, dp(4)));
      currentLabel = text("", 11, Ui.MUTED);
      ui.add(card, currentLabel, 6);
      action = ui.button("暂停", false, this::act);
      ui.add(card, action, 12);
    }

    void act() {
      if (TransferEngine.BUSY.get()) {
        TransferEngine.stop(MainActivity.this);
        return;
      }
      String kind = TransferEngine.taskKind;
      if ("completed".equals(TransferEngine.outcome) && "import".equals(kind)) {
        selectTab(1);
        return;
      }
      if ("completed".equals(TransferEngine.outcome) && "upload".equals(kind)) {
        recyclePhone();
        return;
      }
      if ("completed".equals(TransferEngine.outcome) && "delete-source".equals(kind)) {
        ejectCard();
        return;
      }
      if ("delete-source".equals(kind)) deleteCard();
      else if ("cleanup".equals(kind)) recyclePhone();
      else if ("import".equals(kind)) scan();
      else upload();
    }

    void update(List<Store.Item> all) {
      if (card.getVisibility() != View.VISIBLE) return;
      if (scanning || awaitingStart) {
        title.setText(scanning ? "正在检查文件与空间" : "正在启动任务");
        percent.setText("…");
        totalBar.setIndeterminate(true);
        totalLabel.setText("进度会在这里显示");
        totalEta.setText("即将开始");
        speed.setText("");
        phaseLabel.setText("准备中");
        fileName.setText("");
        currentLabel.setText("");
        action.setEnabled(false);
        action.setText("请稍候");
        return;
      }
      totalBar.setIndeterminate(false);
      boolean deleting = "delete-source".equals(TransferEngine.taskKind),
          cleaning = "cleanup".equals(TransferEngine.taskKind);
      boolean importing = "import".equals(TransferEngine.taskKind);
      long size = 0;
      double finished = 0;
      int count = 0, complete = 0;
      String name = "等待处理视频";
      for (Store.Item item : all) {
        if (!TransferEngine.taskIds.contains(item.id)) continue;
        size += item.size;
        count++;
        boolean ok =
            deleting
                ? item.sourceDeleted
                : cleaning
                    ? "uploaded".equals(item.state)
                    : importing
                        ? item.sha != null
                            && Arrays.asList(
                                    "ready",
                                    "uploading",
                                    "upload_error",
                                    "cleanup_error",
                                    "uploaded")
                                .contains(item.state)
                        : Arrays.asList("uploaded", "cleanup_error").contains(item.state);
        if (ok) {
          finished += item.size;
          complete++;
        } else if (item.id.equals(TransferEngine.activeId)) {
          double fraction =
              deleting || cleaning
                  ? TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * .95
                  : importing
                      ? TransferProgress.imported(
                          TransferEngine.phase, TransferEngine.done, TransferEngine.total)
                      : TransferEngine.phase == TransferProgress.Phase.UPLOAD
                          ? TransferProgress.fraction(TransferEngine.done, TransferEngine.total)
                              * .95
                          : TransferEngine.phase == TransferProgress.Phase.VERIFY_CLOUD ? .95 : 0;
          finished += item.size * fraction;
        }
        if (item.id.equals(TransferEngine.activeId)) name = item.name;
      }
      boolean busy = TransferEngine.BUSY.get(),
          completed = "completed".equals(TransferEngine.outcome);
      int percentage = size > 0 ? (int) Math.min(100, finished * 100 / size) : 0;
      title.setText(
          (deleting ? "卡上删除" : cleaning ? "手机清理" : importing ? "整批拷贝" : "整批上传")
              + (busy ? "进度" : completed ? "已完成" : "已暂停 / 待继续"));
      percent.setText(percentage + "%");
      totalBar.setProgress(percentage * 10);
      totalLabel.setText(
          "已完成 "
              + complete
              + " / "
              + count
              + " 个视频 · "
              + TransferEngine.bytes((long) finished)
              + " / "
              + TransferEngine.bytes(size)
              + (importing ? "（含校验）" : ""));
      double multiplier = importing ? 3 : 1;
      long remaining =
          batchEstimate.remaining(
              TransferEngine.generation,
              SystemClock.elapsedRealtime(),
              finished * multiplier,
              size * multiplier,
              busy);
      totalEta.setText(
          percentage == 100
              ? "已完成"
              : !busy && completed
                  ? "本次操作已结束"
                  : !busy
                      ? "已暂停"
                      : remaining < 0 ? "正在测量整批速度…" : "约 " + TransferProgress.duration(remaining));
      speed.setText(
          batchEstimate.rate > 0 && busy
              ? "处理速度 " + TransferEngine.bytes((long) batchEstimate.rate) + "/s"
              : "");
      phaseLabel.setText(TransferProgress.label(TransferEngine.phase));
      fileName.setText(name);
      boolean checking = TransferEngine.phase == TransferProgress.Phase.VERIFY_CLOUD;
      currentBar.setIndeterminate(checking && busy);
      currentBar.setProgress(
          (int) (TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * 1000));
      currentLabel.setText(
          checking
              ? "当前视频：正在核验云端完整性"
              : "当前视频 "
                  + (int)
                      (TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * 100)
                  + "%");
      action.setEnabled(true);
      action.setText(
          busy
              ? "暂停"
              : completed && importing
                  ? "下一步：上传云端 →"
                  : completed && deleting
                      ? "安全弹出 TF 卡"
                      : completed && !cleaning ? "移入手机回收站" : "继续当前操作");
      action.setVisibility(!busy && completed && cleaning ? View.GONE : View.VISIBLE);
    }
  }

  final class FileRow {
    final String id;
    final TextView detail, state, error;
    final ProgressBar progress;

    FileRow(Store.Item item, LinearLayout row, TextView state) {
      id = item.id;
      this.state = state;
      detail = text("", 12, Ui.BLUE);
      ui.add(row, detail, 10);
      progress = ui.bar();
      row.addView(progress);
      error = text("", 12, Ui.AMBER);
      ui.add(row, error, 10);
      update(item);
    }

    void update(Store.Item item) {
      state.setText(TransferEngine.bytes(item.size) + " · " + fileState(item.state));
      error.setText(item.error);
      error.setVisibility(item.error != null && !item.error.isEmpty() ? View.VISIBLE : View.GONE);
      boolean active = id.equals(TransferEngine.activeId) && TransferEngine.BUSY.get();
      detail.setVisibility(active ? View.VISIBLE : View.GONE);
      progress.setVisibility(active ? View.VISIBLE : View.GONE);
      if (active) {
        detail.setText(
            TransferProgress.label(TransferEngine.phase) + " · " + TransferEngine.detail());
        progress.setProgress(
            (int) (TransferProgress.fraction(TransferEngine.done, TransferEngine.total) * 1000));
      }
    }
  }

  void empty(
      LinearLayout parent,
      String icon,
      String title,
      String description,
      String action,
      Runnable run) {
    LinearLayout card = ui.card();
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setPadding(dp(24), dp(32), dp(24), dp(32));
    card.addView(ui.icon(icon, Ui.BLUE, 42));
    TextView t = ui.bold(title, 18, Ui.INK);
    t.setGravity(Gravity.CENTER);
    ui.add(card, t, 18);
    TextView body = text(description, 14, Ui.MUTED);
    body.setGravity(Gravity.CENTER);
    ui.add(card, body, 10);
    if (action != null) ui.add(card, ui.button(action, true, run), 22);
    ui.add(parent, card, 0);
  }

  String fileState(String state) {
    return switch (state) {
      case "waiting" -> "等待导入";
      case "copying" -> "复制到手机";
      case "verifying" -> "校验文件";
      case "ready" -> "等待上传";
      case "uploading" -> "上传中";
      case "cleanup_error" -> "备份已完成 · 手机副本保留";
      case "copy_error" -> "导入暂停 · 可继续";
      case "upload_error" -> "上传暂停 · 可重试";
      default -> "待处理";
    };
  }

  void renderLocal(List<Store.Item> files) {
    list.removeAllViews();
    fileRows.clear();
    if (files.isEmpty()) {
      empty(
          list,
          "upload",
          "还没有待上传视频",
          "先把 TF 卡视频导入到手机。\n导入完成后，会自动加入上传队列。",
          "去导入视频",
          () -> selectTab(0));
      return;
    }
    String date = "";
    for (Store.Item f : files) {
      String group =
          f.importTime != null
              ? f.importTime
                  .replace('_', ' ')
                  .replaceAll(" (\\d{2})-(\\d{2})-(\\d{2})$", " $1:$2:$3")
              : f.date;
      if (!Objects.equals(group, date)) {
        date = group;
        ui.add(list, ui.bold(date, 13, Ui.MUTED), 14);
      }
      LinearLayout card = ui.card();
      LinearLayout top = ui.row();
      top.addView(ui.icon("video", Ui.BLUE, 24));
      TextView name = ui.bold(f.name, 14, Ui.INK);
      name.setMaxLines(2);
      LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
      np.leftMargin = dp(12);
      top.addView(name, np);
      card.addView(top);
      TextView state = text("", 12, Ui.MUTED);
      ui.add(card, state, 12);
      fileRows.put(f.id, new FileRow(f, card, state));
      if (f.dest != null) card.setOnClickListener(v -> play(Uri.parse(f.dest), "video/*"));
      ui.add(list, card, 10);
    }
  }

  void loadCloud(boolean more) {
    if (loading) return;
    loading = true;
    if (!more) {
      cloudList.removeAllViews();
      empty(cloudList, "cloud", "正在同步云端列表", "稍等片刻，正在读取已上传的视频。", null, null);
    }
    executor.execute(
        () -> {
          try {
            JSONObject result =
                Api.call(
                    "GET",
                    "/videos"
                        + (more && next != null
                            ? "?before=" + URLEncoder.encode(next, "UTF-8")
                            : ""),
                    null);
            JSONArray fetched = result.getJSONArray("videos");
            runOnUiThread(
                () -> {
                  if (!more) cloudItems = new JSONArray();
                  for (int i = 0; i < fetched.length(); i++)
                    cloudItems.put(fetched.optJSONObject(i));
                  next = result.isNull("next") ? null : result.optString("next", null);
                  loading = false;
                  renderCloud();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  loading = false;
                  cloudList.removeAllViews();
                  empty(
                      cloudList,
                      "cloud",
                      "暂时无法读取云端",
                      TransferEngine.readable(e),
                      "重新连接",
                      () -> loadCloud(false));
                });
          }
        });
  }

  void renderCloud() {
    cloudList.removeAllViews();
    cloudCount.setText("云端视频 · " + cloudItems.length());
    if (cloudItems.length() == 0) {
      empty(
          cloudList,
          "cloud",
          "云端还没有视频",
          "先完成一次导入和上传。\n上传成功后，所有手机都能在这里看到。",
          "去导入视频",
          () -> selectTab(0));
      return;
    }
    String date = "";
    for (int i = 0; i < cloudItems.length(); i++) {
      JSONObject video = cloudItems.optJSONObject(i);
      String group =
          video
              .optString("import_time", video.optString("date"))
              .replace('_', ' ')
              .replaceAll(" (\\d{2})-(\\d{2})-(\\d{2})$", " $1:$2:$3");
      if (!date.equals(group)) {
        date = group;
        ui.add(cloudList, ui.bold(date, 13, Ui.MUTED), 14);
      }
      LinearLayout row = ui.card();
      LinearLayout top = ui.row();
      top.addView(ui.icon("video", Ui.BLUE, 24));
      TextView name = ui.bold(video.optString("name"), 14, Ui.INK);
      name.setMaxLines(2);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.leftMargin = dp(12);
      top.addView(name, p);
      row.addView(top);
      boolean missing = "missing".equals(video.optString("state"));
      ui.add(
          row,
          text(
              TransferEngine.bytes(video.optLong("size"))
                  + " · "
                  + (missing ? "云端文件已不存在" : "已完整校验 · 点击播放"),
              12,
              missing ? Ui.AMBER : Ui.GREEN),
          12);
      if (!missing)
        row.setOnClickListener(
            v ->
                executor.execute(
                    () -> {
                      try {
                        JSONObject result =
                            Api.call("GET", "/videos/" + video.getString("_id") + "/url", null);
                        runOnUiThread(() -> play(Uri.parse(result.optString("url")), "video/*"));
                      } catch (Exception e) {
                        runOnUiThread(() -> toast(TransferEngine.readable(e)));
                      }
                    }));
      ui.add(cloudList, row, 10);
    }
    if (next != null) ui.add(cloudList, button("加载更多", () -> loadCloud(true)), 16);
  }

  void choose() {
    if (TransferEngine.BUSY.get() || scanning) {
      toast("请等待检查完成，或先暂停当前传输");
      return;
    }
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    String old = prefs.getString("source_tree", null);
    if (old != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(old));
    startActivityForResult(i, 7);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 7 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri tree = data.getData();
      try {
        int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if ((data.getFlags() & rw) != rw) throw new SecurityException("目录未授予读写权限");
        getContentResolver()
            .takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs.edit().putString("source_tree", tree.toString()).apply();
        clearInventory();
        checkStorage(true);
        refresh();
      } catch (Exception e) {
        toast("目录授权失败，请重新选择可读写的视频目录");
      }
    }
    if (request == 9 && getPackageManager().canRequestPackageInstalls()) install();
  }

  void scan() {
    if (scanning) return;
    if (TransferEngine.BUSY.get()) {
      toast("当前任务正在运行，请先暂停");
      return;
    }
    String tree = prefs.getString("source_tree", null);
    if (tree == null) {
      choose();
      return;
    }
    scanning = true;
    requestedKind = "import";
    refresh();
    executor.execute(
        () -> {
          try {
            String pending = null;
            try (Cursor c =
                store
                    .getReadableDatabase()
                    .rawQuery(
                        "SELECT id FROM batches WHERE tree=? AND ready=0 ORDER BY rowid DESC LIMIT"
                            + " 1",
                        new String[] {tree})) {
              if (c.moveToFirst()) pending = c.getString(0);
            }
            if (pending != null) {
              String batch = pending;
              runOnUiThread(
                  () -> {
                    scanning = false;
                    start(batch, false);
                    refresh();
                  });
              return;
            }
            DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(tree));
            if (dir == null || !dir.exists() || !dir.canRead())
              throw new IOException("读卡器未连接或目录授权失效，请重插或重新选择");
            ArrayList<DocumentFile> files = new ArrayList<>();
            long size = 0;
            for (DocumentFile f : dir.listFiles())
              if (f.isFile()) {
                if (f.length() <= 0) throw new IOException("存在无法读取或空文件：" + f.getName());
                files.add(f);
                size += f.length();
              }
            if (files.isEmpty()) throw new IOException("目录内没有文件");
            if (TransferEngine.free(this) < size + TransferEngine.RESERVE)
              throw new IOException(
                  "空间不足，请清理至少 "
                      + TransferEngine.bytes(
                          size + TransferEngine.RESERVE - TransferEngine.free(this))
                      + "，保留 5 GB 后再复制");
            files.sort(Comparator.comparing(f -> f.getName() == null ? "" : f.getName()));
            String batch = UUID.randomUUID().toString();
            SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINA);
            timestamp.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            String importTime = timestamp.format(new Date());
            String date = importTime.substring(0, 10);
            var db = store.getWritableDatabase();
            db.beginTransaction();
            try {
              ContentValues bv = new ContentValues();
              bv.put("id", batch);
              bv.put("tree", tree);
              bv.put("delete_source", 0);
              db.insertOrThrow("batches", null, bv);
              for (DocumentFile file : files) {
                ContentValues v = new ContentValues();
                v.put("id", UUID.randomUUID().toString());
                v.put("batch", batch);
                v.put("source", file.getUri().toString());
                v.put("tree", tree);
                v.put("name", file.getName());
                v.put("size", file.length());
                v.put("modified", file.lastModified());
                v.put("date", date);
                v.put("import_time", importTime);
                db.insertOrThrow("files", null, v);
              }
              db.setTransactionSuccessful();
            } finally {
              db.endTransaction();
            }
            runOnUiThread(
                () -> {
                  scanning = false;
                  selectTab(0);
                  start(batch, false);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  scanning = false;
                  TransferEngine.message = TransferEngine.readable(e);
                  refresh();
                  new AlertDialog.Builder(this)
                      .setTitle("暂时无法导入")
                      .setMessage(TransferEngine.message)
                      .setPositiveButton("知道了", null)
                      .show();
                });
          }
        });
  }

  void start(String batch, boolean cellular) {
    requestNotification();
    prefs.edit().putBoolean("upload_paused", false).apply();
    Intent i = new Intent(this, TransferService.class);
    if (batch != null) i.putExtra("batch", batch);
    i.putExtra("cellular", cellular);
    requestedGeneration = TransferEngine.generation;
    awaitingStart = true;
    requestedKind = batch == null ? "upload" : "import";
    startForegroundService(i);
    refresh();
    handler.postDelayed(
        () -> {
          awaitingStart = false;
          refresh();
        },
        5000);
  }

  void upload() {
    if (TransferEngine.BUSY.get()) {
      toast("任务正在运行");
      return;
    }
    List<Store.Item> ready = store.files("state IN ('ready','uploading','upload_error')");
    if (ready.isEmpty()) {
      toast("先完成视频导入，再开始上传");
      selectTab(0);
      return;
    }
    TransferEngine engine = connectivity;
    if (!engine.connected()) {
      toast("请先连接网络");
      return;
    }
    if (!engine.wifi())
      new AlertDialog.Builder(this)
          .setTitle("使用移动网络上传")
          .setMessage(
              "本次待上传约 "
                  + TransferEngine.bytes(ready.stream().mapToLong(f -> f.size).sum())
                  + "，是否通过流量上传？中断后需再次手动确认。")
          .setNegativeButton("取消", null)
          .setPositiveButton("开始上传", (d, w) -> start(null, true))
          .show();
    else start(null, false);
  }

  void play(Uri uri, String mime) {
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, mime)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (ActivityNotFoundException e) {
      toast("没有可用播放器；不影响复制和上传");
    }
  }

  void updates() {
    checkUpdates(false);
  }

  void checkUpdates(boolean quiet) {
    if (!quiet) toast("正在检查更新");
    executor.execute(
        () -> {
          try {
            JSONObject version = Api.call("GET", "/updates/android", null);
            prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply();
            if (version.optInt("version_code") <= BuildConfig.VERSION_CODE) {
              if (!quiet) runOnUiThread(() -> toast("当前已是最新版本"));
              return;
            }
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  new AlertDialog.Builder(this)
                      .setTitle("发现新版本 " + version.optString("version_name"))
                      .setMessage(version.optString("notes", "改进传输体验"))
                      .setNegativeButton("稍后", null)
                      .setPositiveButton("下载并安装", (d, w) -> download(version))
                      .show();
                });
          } catch (Exception e) {
            if (!quiet) runOnUiThread(() -> toast(TransferEngine.readable(e)));
          }
        });
  }

  static final class Cancelled extends IOException {}

  void download(JSONObject version) {
    updateCancelled = false;
    runOnUiThread(
        () -> {
          if (isFinishing() || isDestroyed()) return;
          ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
          TextView label = new TextView(this);
          label.setText("准备下载…");
          LinearLayout box = new LinearLayout(this);
          box.setOrientation(LinearLayout.VERTICAL);
          int pad = (int) (20 * getResources().getDisplayMetrics().density);
          box.setPadding(pad, pad, pad, pad);
          box.addView(bar);
          box.addView(label);
          updateDialog =
              new AlertDialog.Builder(this)
                  .setTitle("下载更新 " + version.optString("version_name"))
                  .setView(box)
                  .setNegativeButton(
                      "取消",
                      (d, w) -> {
                        updateCancelled = true;
                        if (updateDialog != null && updateDialog.isShowing()) updateDialog.dismiss();
                      })
                  .setCancelable(false)
                  .show();
          executor.execute(() -> doDownload(version, bar, label));
        });
  }

  void dismissUpdateDialog() {
    runOnUiThread(
        () -> {
          if (updateDialog != null && updateDialog.isShowing()) updateDialog.dismiss();
        });
  }

  void doDownload(JSONObject version, ProgressBar bar, TextView label) {
    File apk = new File(getCacheDir(), "update.apk");
    try {
      long length = version.getLong("size");
      HttpURLConnection c =
          (HttpURLConnection) new URL(version.getString("url")).openConnection();
      c.setConnectTimeout(20000);
      c.setReadTimeout(60000);
      try {
        if (c.getResponseCode() != 200) throw new IOException("下载链接失效，请重新检查更新");
        if (TransferEngine.free(this) < length + TransferEngine.RESERVE)
          throw new IOException("请清理手机空间后更新");
        int[] lastPct = {-1};
        try (InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(apk)) {
          byte[] b = new byte[128 * 1024];
          int n;
          long received = 0;
          while ((n = in.read(b)) != -1) {
            if (updateCancelled) throw new Cancelled();
            out.write(b, 0, n);
            received += n;
            if (received > length) throw new IOException("安装包大小不匹配");
            int pct = (int) (received * 100 / length);
            if (pct != lastPct[0]) {
              lastPct[0] = pct;
              final long done = received, total = length;
              runOnUiThread(
                  () -> {
                    bar.setProgress(pct);
                    label.setText(pct + "% · " + done / 1048576 + " / " + total / 1048576 + " MB");
                  });
            }
          }
          if (received != length) throw new IOException("安装包未下载完整");
        }
      } finally {
        c.disconnect();
      }
      runOnUiThread(() -> label.setText("校验安装包…"));
      String[] digest;
      try (InputStream in = new FileInputStream(apk)) {
        digest = Digests.hash(in, n -> {});
      }
      if (!digest[0].equals(version.getString("sha256"))) throw new IOException("安装包校验失败");
      if (updateCancelled) throw new Cancelled();
      dismissUpdateDialog();
      runOnUiThread(this::install);
    } catch (Cancelled cancelled) {
      apk.delete();
      dismissUpdateDialog();
    } catch (Exception e) {
      apk.delete();
      dismissUpdateDialog();
      runOnUiThread(() -> toast(TransferEngine.readable(e)));
    }
  }

  void install() {
    if (!getPackageManager().canRequestPackageInstalls()) {
      new AlertDialog.Builder(this)
          .setMessage("需要允许本应用安装更新，设置完成后返回即可继续。")
          .setPositiveButton(
              "打开设置",
              (d, w) ->
                  startActivityForResult(
                      new Intent(
                          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                          Uri.parse("package:" + getPackageName())),
                      9))
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(
                  Uri.parse("content://" + getPackageName() + ".install/update.apk"),
                  "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (Exception e) {
      toast("无法打开安装器");
    }
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }
}
