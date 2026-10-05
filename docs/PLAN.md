# 行车转存：实施计划与接手记录

更新日期：2026-10-05。此文件同时保存已确认需求、实施顺序、实际进度与验收门槛。接手前先读全文，再核对 Git、资源和运行状态；文档中的“待完成”不能当成已实现。每完成一个阶段或发现阻塞，都更新本文件的进度表及最后的接手记录。

## 目标与范围

优先交付 Android 可安装 APK，验收设备为红米 K40 Pro、一加 9。公开 GitHub 源码，目录为 `server/`、`android/`、`ios/`；iOS 当前仅预留接口与目录，不属于第一版 APK 完成条件。

实际流程：车上将 TF 卡插入 OTG 读卡器 → 选择视频目录并记住授权 → 扫描和检查空间 → 复制全部直接子文件 → 校验整批 → 手动点击删除已校验的 TF 卡原视频（也可保留）→ 进入第二页手动上传 → OSS 确认完整后手动移入手机回收站。移动网络可以通过上传按钮手动启动，暂停、开始、继续均有按钮；移动网络中断后恢复必须再次手动启动。

最新反馈要求线性两页：第一页面负责卡到手机的拷贝和单独删除，第二页面负责手机到云端的上传和单独回收站清理。取消底部导航和自动操作，历史保留在「更多」入口。多个手机使用同一存储空间，同步后看到同一份云端列表，无注册、登录、账号、扫码加入或选择空间的产品流程。用户最新要求可以查看之前的批次：保留每次导入记录，按北京时间展示文件数、容量与上传状态，支持本机历史和共享云端历史；不引入云端删除或复杂批次编辑。

## 已确认规则

1. 来源是用户通过系统文件选择器选定的一个目录，常见名字是 `video`，但不能写死路径。只处理直接包含的文件，不递归扫描子目录；用户确认该目录内都是视频。保存 URI 与持久读写授权，重插读卡器后尝试复用，失效才请求重选。
2. 不格式化 TF 卡，不删除目录，不动所选视频目录以外的计数器、配置等文件。复制中或校验失败不能删除来源。整次导入全部文件复制并校验成功后仍保留卡上原文件，只有明确点击删除并确认后才删除。旧自动删除偏好和批次字段不能触发自动删除。删除前再次核对手机与TF内容，副本丢失或变化时保留源文件。逐个删除结果需要持久记录，删除失败可重试。
3. 导入前检查：可用空间至少等于本次需要复制的容量加 **5 GiB**。不足直接拒绝并提示清理多少空间，不做容量不足的拆批导入。任务途中空间变少则暂停保留原文件。
4. 手机保存到共享目录，文件管理器可见，按本次导入的北京时间精确到时分秒组织；同名目标不能覆盖。使用 `Movies/行车视频/YYYY-MM-DD_HH-mm-ss_<导入ID前8位>/`。API 29+ 使用 MediaStore，复制未完成对象使用 `IS_PENDING`；复制校验完成后才公开。需要验证所选 MediaStore 集合与相对目录兼容。
5. 同时显示整体、当前文件、已完成/总文件数、已处理/总容量、速度、预计剩余时间；复制、校验、上传需要区分。预计时间根据当前阶段和近期吞吐计算，初期显示计算中。暂停不显示继续增长的速度。
6. 本地 SQLite 保存任务与断点。读卡器断开、任务进程消失、锁屏、切换 App 后可恢复。复制断点若提供方无法可靠 seek，可重复制当前文件并明确提示，不能伪造已完成。保留已完成文件的状态。
7. 所有网络都通过第二页上传按钮手动开始或继续，不自动衔接拷贝/上传/删除/回收站。升级时取消旧Wi-Fi worker，旧worker运行入口为空。移动网络必须点击上传；网络切换到移动网络、重启或进程恢复后不沿用之前的流量授权。手动流量许可只在本次正在运行的上传中有效。
8. 上传走 OSS 分片，进度持久化并以云端已存在分片为准，避免只靠本地断点。每个视频整文件核验后保留手机副本，手动点击回收站按钮并确认才将副本移入系统回收站，不能永久删除，不能用 HTTP 200 或分片 ETag 代替完整性核验。若用户通过文件管理器移动或删手机文件，需要明确报错。
9. 云端视频一直保留，无生命周期自动删除；App **不提供删除云端视频功能**。用户之后可以自行在 OSS 删除，访问时标记云端文件不存在，保留历史清单。
10. 不转码。点击视频交给手机播放器处理，不能播放不影响传输。
11. Android 检查新版本、提示后一键下载安装，调用系统确认安装；不要求静默安装，也不做业务代码热更新。签名必须持续一致。
12. 阿里云所有新资源默认北京 `cn-beijing`，除非用户明确特殊要求。开发、生产各建专用 OSS Bucket，**ACL private**，不允许公开读。上传签名与下载签名均短期有效；不把阿里云长期 AccessKey 或 MongoDB 凭据放手机安装包。
13. 服务端仅用云函数控制授权、上传会话、列表和校验，不转发大视频字节，不需常驻应用服务器。使用已有 MongoDB，多手机共享记录。敏感信息唯一来源 Infisical，不进入 Git、GitHub Secrets 或长期本机 `.env`。
14. 开发和生产隔离：北京 `dashcam-transfer-dev` / `dashcam-transfer-prod` 两个私有Bucket，分别配置runtime与release RAM用户（共四个）；云函数名带dev/prod；生产数据库`dashcam_transfer`，开发数据库`dashcam_transfer_dev`，各自用户仅本库readWrite。Infisical运行配置使用tools/dev与tools/prod的`/dashcam`；发行配置项目按环境隔离。测试必须指向开发，最终APK指向生产。
16. 打开直接进入「①卡→手机」，显示TF卡状态、完整所选目录绝对路径和拷贝按钮。拷贝后可手动删除卡上已核对的视频或进入「②手机→云端」。第二页显示上传与手动回收站按钮，均只执行一件事。没有底部Tab；历史与更新在更多菜单。系统SAF外部存储提供方用实际volume目录+完整相对路径；其他提供方若没有磁盘路径，展示完整content URI并说明，不伪造路径。
17. OSS视频目录同样使用导入北京时间 `videos/YYYY-MM-DD_HH-mm-ss_<导入ID前8位>/文件名`；断点恢复与跨天上传不能重新计算目录。保留旧客户端/旧记录兼容，不移动已上传对象。
15. 交付公开 GitHub 项目与可安装 APK，并通过已有飞书应用将 APK 发送给用户。用户已授权建项目、云资源配置、开源发布、CI 自动打包及最终飞书交付，无须再逐项询问相同事项。

## 当前实际进度

| 项目 | 状态 | 证据 / 未完成部分 |
|---|---|---|
| 需求确认 | 完成 | 上述规则源于本次对话 |
| 本地项目 | 已创建 | `/Users/mint/workspace/tools/dashcam-transfer`，GitHub公开仓库已创建，源码已commit/push |
| 现有 MongoDB 专用库和账号 | 完成并验证 | `dashcam_transfer` / `dashcam_transfer_app`；仅本库 `readWrite`；认证、写读删与其他库拒绝均通过 |
| 数据库初始化记录 | 完成 | `transfer_metadata` 中 `_id=schema, version=1`；尚无真实视频记录 |
| Infisical 数据库凭据 | 完成并回读 | 开发 `tools/dev /dashcam`、生产 `tools/prod /dashcam`；发行在独立项目 |
| infra 记录 | 已提交推送 | inventory、secrets、数据库/TLS期望配置与当日changelog；仓库检查通过，提交9ae1bb6 |
| OSS Bucket / RAM | 两环境完成 | 北京dev/prod两个private Bucket、四个独立最小权限RAM用户；旧media Bucket、旧函数、旧用户/Key/策略已删除 |
| 云函数 | 两环境部署与鉴权通过 | dev/prod health 200、有效凭据列表200、无凭据401；无预留实例；源码包来自对应私有OSS |
| 服务端代码 | 两环境0.3.0已部署 | uv.lock已生成；10项故障/目录/批次兼容测试通过，独立临时真实MongoDB也通过；开发环境真实私有OSS分片续传、冲突、最终CRC、错误CRC拒绝、签名下载SHA一致和匿名403均通过；测试对象和记录已清理 |
| Android 代码 | 0.4.0模拟器验证通过 | 两页手动流程、完整目录路径、历史更多入口；lint、4项单元及6项Android35设备测试通过；真机OTG/锁屏仍待验收 |
| iOS | 仅占位 | `ios/README.md` |
| GitHub Actions / 发布工具 | 0.4.1完成 | CI37282738302和发行37282863723成功，OIDC→构建/lint/单测/签名→privateOSS→latest完整运行成功 |
| APK / 飞书发送 | 已交付0.4.1 | artifacts/android/0.4.1/app.apk来自GitHub正式发行，版本4001、同签名与SHA/大小通过；飞书APK及简短升级说明发送成功 |
| 真机连接 | 无 | `adb devices` 未发现设备，不能声称 OTG/锁屏行为通过 |

当前Android首版已交付，开发/生产云端部署和自动发布已验证。下一步是用户真机验收及修复反馈；iOS仅占位，不能称iOS完成。以下五阶段作为复现与后续维护步骤，历史进度段落不代表当前未完成状态。

## 五个实施阶段

### 1. 项目、配置与私有云资源准备

- 在公开项目中补充 README、配置示例、MIT License、构建指南和贡献安全边界；源码及路径不能使用执行者名称。
- 使用开发 `dashcam-transfer-dev` 与生产 `dashcam-transfer-prod` 两个独立专用 Bucket；北京、标准存储、本地冗余、private。不设置自动删视频的生命周期；可设置清理超过 7 天的未完成 multipart。
- 建立仅本 Bucket / 指定前缀有效的 RAM 权限；服务端不需要删除已完成视频、修改 ACL、操作其他 Bucket 或管理 RAM。视频路径为 `videos/日期/内容标识/原文件名`，发布路径为 `releases/android/版本/app.apk`。
- 服务端优先角色/短期身份；若使用专用 RAM AccessKey，保存 Infisical，运行时受控注入，权限和轮换写 infra。发布身份只写 release 与函数代码前缀，不得复用全账号管理员凭据。
- 用受限 Machine Identity / GitHub OIDC 读取本项目凭据，绑定 `makewheels/dashcam-transfer` 与发布 ref/环境；不要复用其他仓库身份或使 PR 获得生产凭据。
- Android 同一套预配置空间无需账号。安装包需要有限用途服务访问凭据或设备密钥机制；只能由 Infisical 构建时注入，不进入公开源码或公开 APK 制品。源码开源不等于个人存储接口匿名开放。当前 Gradle 用 `DASHCAM_APP_TOKEN` 做受限服务凭据，须明确安装包可被提取凭据的边界并设计轮换；绝不能注入云厂商或数据库管理凭据。
- MongoDB 原连接是已有公网服务的连接，部署云函数前落实传输加密/受限入口；可在已有数据库主机加独立 TLS 代理，不新建常驻应用服务器。相关期望配置、证书来源、备份与防火墙需要写 infra，不影响其他应用。

验收：匿名 OSS GET 被拒绝；未签名下载拒绝，短期签名可读；Bucket ACL 回读为 private；凭据 Infisical 回读一致；RAM 无跨 Bucket / 改 ACL 权限；Git 内容扫描无真实密钥。数据库原有应用仍健康。新资源事实同步 infra 的 inventory、期望配置及日期 changelog。

### 2. 服务端与本地 MongoDB验证

- 在 `server/` 用 `uv` 锁定依赖；生产 ZIP 构建使用系统临时目录，安装 Linux 目标依赖后打包，成功失败均清理。
- 本地 MongoDB只用于测试，检测现有进程/端口后使用独立测试数据库，不覆盖已有本地数据；也可在唯一系统临时目录启动独立实例并在 finally 停止和清理。
- 初稿接口见下文。完善输入校验、错误码、分页、签名过期重试、同文件多手机竞争和完成回包丢失后的幂等重试。
- 云函数只做控制面。手机直传分片；服务端从 OSS ListParts 读取实际分片，合并后 HEAD 校验对象大小及 OSS `x-oss-hash-crc64ecma`。客户端 SHA-256 负责文件身份，CRC64负责与云端内容一致性。不能将 metadata 中客户端自报 SHA-256 当作云端独立计算 SHA-256。
- 为文件记录建立唯一内容标识与状态索引。当前初稿以内容 SHA-256 为 `_id`；内容相同而文件名不同的显示策略需在测试/文档固定，避免无意重复存视频。
- 用上传 owner 与有期限 lease 协调两台手机，冲突手机保留副本稍后重试；允许 lease 过期接管，但旧持有者不得继续完成。大分片传输期间确保 lease 持续或足够长。
- 补充真实 HTTP错误、OSS错误、服务端超时的重试与本地不删保证。当前通用异常仅输出异常类型，不输出 URI、签名 URL、token或密码。

验收：无 token 401；有效 token 通过；输入非法 400；缺分片/大小不符/CRC不符必须拒绝且未标记 uploaded；正确合并后 verified=true；重复完成幂等；两手机竞争不破坏对象；云端文件被手动删除后签名接口 404 且历史记录保留。必须同时有本地数据库真实读写证明和真实私有 OSS 分片上传、HEAD CRC64、签名下载证明，mock 不能代替实际云链路。

### 3. Android 导入、恢复与列表

- 采用当前 Java/原生界面骨架，最低 Android 10（API 29），compile/target 35；使用 SAF + MediaStore，避免为了读卡器申请全盘管理权限。
- 实现 `MainActivity`、`TransferService`、后台 Worker、安装 Provider。Manifest 已引用这些类，未写完不能构建。
- UI：只有拷贝与上传两个流程页面，没有底部Tab。第一页面显示卡状态、完整目录路径、空间、拷贝/手动删除/下一步；第二页面显示上传/暂停/手动回收站、待处理文件和进度。右上更多菜单访问历史和更新，不保留自动删除开关。
- 首次目录选择调用 `ACTION_OPEN_DOCUMENT_TREE`，保存 `takePersistableUriPermission` 成功的读写授权和 URI。再次使用验证权限与文件可读，不靠固定文件系统路径。
- 扫描构建稳定任务清单，保存原 URI、名字、大小、修改时间、当前断点、目标 URI。来源重新插入与内容变化要区分；同名目标用不同子目录/安全后缀，绝不覆盖。
- 空间预检与每次写入检查；只有全量容量+5 GiB 满足才开始新导入。恢复任务扣除已占空间，不能重复计入全部容量导致恢复被错误拒绝。
- 使用 seekable 文件描述符尽量续传当前文件，断点写入 SQLite。写入数据刷盘与 journal 顺序明确，断点不能领先已落盘字节；进程恢复先核对目标长度再使用断点。
- 每文件复制完成后，独立读取原文件与手机副本计算 SHA-256并比较大小；对手机副本计算 OSS兼容 CRC64。先校验全部并保留源文件；用户手动确认删除后，重新核对手机内容与来源 URI / 内容 / 大小 / 修改时间，发生变化则保留。复制失败、部分成功或校验失败不进行整批删除。目标 `IS_PENDING` 公开时机与删除顺序要保证副本可恢复。
- Android前台通知显示任务进度与暂停，持有必要 wake lock，处理 Android15 dataSync 6 小时超时并停止；用户系统强制停止无法承诺自动恢复，但再次打开可继续。
- 上传成功后删本地目标前再次核对本地大小/校验/归属；删除失败记录已上传、待清理，绝不重复上传。不要删除用户在同一路径创建的新文件。

验收：列表与按钮真实可用；断开读卡器、暂停、杀进程恢复不损坏文件；源/目标同名不覆盖；不足空间拒绝；校验故意损坏时来源保留；整批成功只删除清单文件，额外新增文件、子目录、其他目录和计数器均保留。真实 OTG、共享目录可见性与锁屏续传需要红米 K40 Pro / 一加 9验收，当前没有连接设备，必须明确待用户验收。

### 4. 分片上传、云函数部署及实际链路

- 分片尺寸默认 8 MiB，串行上传控制内存；每片获取只针对该对象/partNumber/uploadId的短期 PUT URL，设置正确 Content-Length，禁止携带 App Authorization 到 OSS URL。
- 服务器返回已有 parts 后按分片编号和长度核对，从手机副本正确偏移读取。网络失败重查 parts，签名过期重新签名，避免重复合并或误删。
- 最新版本不用自动上传：升级取消旧WorkManager任务，旧worker不会运行传输。Wi-Fi和移动网络均点击开始；Wi-Fi到移动网络立即暂停，流量许可不持久保存。
- 前台手动任务与 WorkManager任务需共享单实例锁，不能两个引擎同时处理同一 SQLite记录。各自观察持久状态，云端lease是最终多设备协调。
- 云函数北京按量、零预留实例，沿用现有 custom.debian10+Python3.10/Gunicorn方法或验证新的运行时；启动 `app:create_app()`，512 MiB 作为初始值。视频字节不经云函数。
- 云函数身份从 Infisical读取或使用登记的受控部署副本；不要留下旧环境文件或个人管理员会话作生产客户端。API鉴权、访问限额、必要日志脱敏要实现。
- 云端核验完成后标记上传成功、同步共享列表并保留手机副本；用户手动清理才移入手机回收站。App或网络在任意一步中断，都可在下次重新核验而不丢文件。

验收：实际函数 health、无鉴权请求拒绝、真实 multipart 上传及CRC匹配、错误CRC拒绝、签名下载读回内容一致。Wi-Fi手动开始/暂停/继续、移动网络确认开始、移动网络中断后不自动恢复、两手机共享列表均有对应证据或明确真机待验收。云函数无常驻预留实例。云端清理测试对象只能删除本次创建的测试前缀，不能扩展删除业务文件。

### 5. 自动更新、GitHub发布、APK与飞书交付

- 生成 Android发行签名，在系统临时目录生成 keystore，保存 base64 与密码到 Infisical，回读恢复后验证签名；永久不进 Git。后续版本保持同一证书，至少保存可恢复的 Infisical键。
- 加 Gradle 8.9 wrapper（AGP8.7.3所需）；使用 Java17/21。依赖按默认共享缓存，不复制 SDK或Gradle caches。
- GitHub公开仓库 `makewheels/dashcam-transfer`；先扫描源代码、暂存区及完整Git历史再推送。不要上传个人配置、本地properties、密钥、私人测试数据或带服务凭据的个人APK到公开GitHub附件。
- 普通CI跑服务端测试与无私有凭据的 Android debug构建；受保护发布任务在版本标签触发，GitHub OIDC从 Infisical受限路径读取配置、签名及发布身份，构建发行APK，上传私有OSS。
- 每版本目录 `releases/android/0.1.0/` 放 `app.apk` 与 `manifest.json`。manifest保存 `version_name`、单调递增 `version_code`、文件大小、SHA-256、更新说明。先上传APK、回读/HEAD验证，再写版本manifest，最后原子更新 `releases/android/latest.json`。不能先发布latest再传APK。
- App调用 `/updates/android` 得到临时下载签名；比较versionCode，显示更新说明，下载后核对大小/SHA-256，使用不可导出的ContentProvider授权系统安装。失效签名重取，未授权未知来源安装引导系统设置后继续。
- 飞书通过 Infisical已有bot凭据上传APK并发送文件消息给登记的用户。先验证recipient定位，发送权限仅用于用户明确授权的本次APK交付，不向其他群/人广播。记录API成功的message_id，不能把文件上传成功当消息发送成功。
- 最终报告给出GitHub链接、版本、APK校验值、飞书发送结果、已通过测试与真机待验收边界。

验收：`assembleRelease`成功、APK签名验证成功、package/version/minSdk确认；下载私有OSS APK字节SHA-256一致；新版本检查成功，旧版本不误报更新；公开Git无秘密；GitHub CI与受限发布workflow真实成功；飞书文件消息发送API成功。APK构建/静态检查不能代替真机安装、OTG权限或后台实际行为。iOS占位不能报告为已完成。

## 服务端接口与记录草案

所有业务接口使用服务访问鉴权；仅 `GET /healthz`公开。短期签名只能给精确对象和动作，不返回云厂商长期密钥。

| 接口 | 用途 |
|---|---|
| `GET /videos?limit=100&before=...` | 共享已上传/云端缺失列表，分页 |
| `POST /uploads/start` | 文件SHA-256、CRC64、大小、名字、日期、owner；已核验对象直接返回已上传，否则取得/恢复分片会话 |
| `POST /uploads/{id}/part` | owner + number，返回短期分片PUT签名 |
| `POST /uploads/{id}/complete` | 以OSS真实parts合并，校验最终大小与CRC，成功才verified |
| `GET /videos/{id}/url` | HEAD核对对象存在，返回短期GET签名；手动删除时标记missing |
| `GET /updates/android` | 版本manifest及短期APK签名 |

MongoDB `videos`：内容标识 `_id`、sha256、crc64（无符号十进制字符串）、size、name、date、object_key、state、created_at、verified_at、owner、lease_until、upload_id。共享列表只显示成功核验的文件，未上传副本只在持有它的手机待上传列表出现。

SQLite `files`：id、内部batch、source、tree、name、size、modified、dest、offset、state、sha、crc、date、error、source_deleted；`batches`仅用于整批校验后删除，保留错误恢复与删除状态。状态机需实现为：waiting → copying → verifying → ready → uploading → uploaded → local cleanup；错误/暂停保留之前可恢复状态，不仅用一条error覆盖事实。

## 凭据与运维定位（只记录名称，不记录真值）

- 私有infra项目：`/Users/mint/workspace/infra`，腾讯云子树 `tencent-cloud/`。先读两层AGENTS；接手先运行 `scripts/check-repo.sh`和 `scripts/status.sh`。当前已有未跟踪`aliyun/`资料，不得误删。
- Infisical `tools`项目ID可从私有infra `services/infisical/README.md`读取；本项目环境`dev`、路径`/dashcam`。已有键：`DASHCAM_MONGO_URI`、`DASHCAM_DB_PASSWORD`、`DASHCAM_DB_NAME`、`DASHCAM_DB_USER`。
- CLI必须显式指定自托管domain，位置见私有infra，不从记忆猜测；导出到内存，不直接把JSON输出到终端。新增密钥用唯一系统临时目录的0600文件 `infisical secrets set --file`，finally清理，再回读布尔比较。
- 本次恢复过本机Infisical会话；恢复材料在私有infra登记的gitignored文件中。该文件的示例`cli_login`有organization-id占位符，不能直接运行；真实组织定位应查现有组织，密码通过`INFISICAL_PASSWORD`进程环境注入，不打印、不放命令参数。组织名是Mint Infra。生产自动化不能依赖此管理员会话。
- 既有数据库主机定位从infra `inventory/hosts.yaml`读取。默认SSH别名`tencent-db`本机不可解析；可以按inventory使用TAT，或现有 `services/video-editing/remote_ops.py` 的临时SSH授权机制（必须finally清理授权）。导入模块时禁用写pycache。
- 云CLI `aliyun`已认证，北京；不得输出完整配置。已有函数`site-monitor-api`配置可作为只读参考，但不能修改其工作负载或复用其消费者权限。
- 飞书应用键在Infisical `tools/dev /site-monitor`：`FEISHU_APP_ID`、`FEISHU_APP_SECRET`、`FEISHU_USER_ID`、`FEISHU_CHAT_ID`。只输出键名，验证对象定位后发送给用户。需要新消费者密钥来源登记；不修改旧应用凭据。
- 已配置的键名（运行/发行按上述项目隔离）：`DASHCAM_APP_TOKEN`、`DASHCAM_API_URL`、`DASHCAM_KEYSTORE_BASE64`、`DASHCAM_KEYSTORE_PASSWORD`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET`、`OSS_ENDPOINT`、`OSS_REGION`；其他生产身份材料按具体选型登记，不随意扩大权限。
- 当前数据库命名已从`dashcam_archive` / `dashcam_archive_app`迁移到`dashcam_transfer` / `dashcam_transfer_app`，旧库和旧用户确认移除，不要再次使用archive名称。

## 本机工具与工作约束

- Python使用uv，Node如需使用则pnpm；现有Android SDK在`~/Library/Android/sdk`，platform/build-tools 35已安装。Gradle8.9已在共享wrapper cache中，系统Gradle9.7不是当前AGP匹配版本。
- `java`入口当前是21，系统gradle默认可能使用26；显式选Java17/21构建，不能因为系统Gradle可执行就忽略兼容性。
- `mongod`已安装，可用本地MongoDB试验。无已连接adb设备。
- 所有一次性脚本、下载检查文件、临时keystore、构建中转目录放系统临时目录；POSIX用TMPDIR/mktemp，任务独占并先注册finally/trap清理。递归删除前核对归属和临时目录边界。交付APK及项目源码可持久保存，检查用副本不能长期遗留。
- 不允许凭据进入公开Git，不允许日志输出完整连接串、token、签名URL或secret。公开发布前扫描内容和历史，CI日志用mask并避免shell trace。
- 用户授权可以持续执行，不要在每个步骤反复请求确认。真实权限失效或缺失信息阻塞时，明确实际原因与已完成部分。

## 最近接手记录

2026-10-04：刚创建项目并写入服务端初稿和Android骨架；尚未构建或测试。用户要求先记录细节，以便中断后接续，故当前优先写本文件。下一步从阶段1补齐资源与凭据，再并行推进本地服务端验证和Android缺失类（当前未获单独多执行者授权，不自行派生执行者）。阶段2验证前需认真检查服务端初稿的lease并发、分页、完成幂等、CRC规则及CA临时文件生命周期。

后续每次更新用简短事实记录：改过的文件/资源、实际执行过的验证、剩余问题、精确下一步。不要用“完成”概括尚无实际证据的构建、部署、发送或真机测试。

2026-10-04 继续实施：独立发行Infisical项目 `dashcam-transfer-release`（project ID `ba9fb256-59e7-4712-887d-20a9634407a7`，prod根路径），GitHub OIDC identity `4334737c-b159-41a2-a0cc-5bcbe64d3759`，audience `dashcam-transfer-release`，repository与tag ref绑定。原因：现有Infisical不支持Enterprise自定义角色，故隔离发行项目并使用viewer；该项目不保存MongoDB/服务端RAM凭据。配置已创建，真实OIDC认证待workflow验证。

数据库主机新增独立 `dashcam-mongo-tls.service`，公网27417转发本地27017；TLS证书和私钥base64先存 tools/dev /dashcam（`DASHCAM_MONGO_CA_BASE64`、`DASHCAM_MONGO_TLS_KEY_BASE64`），root0600运行副本在 `/etc/dashcam-mongo-tls/`。腾讯云实例防火墙已开放27417；实际公网TLS应用认证、写读删除通过，Infisical MongoURI已改TLS端口。原MongoDB工作负载未重启。此状态必须同步到infra期望配置/防火墙/changelog，当前尚未补完相关文档。

发行签名已生成并保存在runtime Infisical及独立发行项目；专用release RAM只允许本Bucket releases前缀GetObject/PutObject。服务访问token未进Git，个人配置APK只存私有OSS/飞书，公开CI只构建空配置debug包。下一步检查正在部署的FC，记录HTTP URL并同步两个Infisical项目的DASHCAM_API_URL，再实际分片上传核验；完成后构建签名APK、推开源仓库、跑GitHubCI与tag发行、发送飞书。

2026-10-04 用户补充开发/生产隔离要求：当前正在创建两环境的Bucket及四个最小权限RAM用户，并把tools/dev /dashcam改为开发配置、tools/prod /dashcam作为生产配置。生产原库保留，新增开发专用库。最初单Bucket和两RAM用户为过渡资源，等新环境验证后只清理本次创建的旧函数、旧代码包、旧RAM及空Bucket，不触碰既有其他Bucket。原HTTP函数启动返回502，正在使用脱敏尾日志定位；不能将函数创建成功当部署完成。

2026-10-04 云链路验证完成：dev/prod云函数health200、认证列表200、无凭据401；生产预留实例target/current均0，dev并发上限1、prod上限2。开发环境实际9MiB两分片上传，上传第一片后重取session确认续传，第二手机冲突409，合并后CRC匹配，重复complete幂等，共享列表可见，签名下载SHA匹配，匿名GET403；错误CRC用例拒绝且未uploaded；仅本次测试对象和记录已清理。冷启动依赖冲突修复：显式打包pyOpenSSL26.4和service-identity24.2，防止使用FC内置旧版本；crcmod打包纯Python文件，避免Mac本机构建扩展混入LinuxZIP。

Android增加开发applicationIdSuffix `.dev`、开发标签，生产app ID不变；构建改用 `deploy/build_android.py`，所有Gradle中转及临时keystore在系统临时目录，finally清理，仅请求的APK持久输出。当前正在本机构建production 0.1.0/versionCode1000并执行lint、单元测试和签名验证。下一步源码敏感信息扫描、首次commit/push、GitHubCI/tag发布，再从privateOSS下载发行APK并通过飞书交付。

2026-10-04 过渡资源清理完成：已删除dashcam-transfer-api函数及HTTP触发器；dashcam-transfer-media仅有两份旧函数ZIP、无视频、无分片上传，已清空并删除Bucket；旧dashcam-transfer-runtime / dashcam-transfer-release RAM用户、全部AccessKey及对应旧自定义策略已删除。复查仅保留dev/prod各runtime/release共四个项目RAM用户。本地生产APK签名/lint/单元测试通过；源码已公开推送。GitHub首次CI因SDK动作默认安装已下架tools包失败，已改为platform-tools并推送修复，等待验证；正式标签发布和飞书交付尚未完成。

2026-10-04 发行OIDC403已定位：GitHub新仓库use_immutable_subject=true，subject前缀包含owner/repository数字ID，与传统repo:name/name不一致。用gh api repos/makewheels/dashcam-transfer/actions/oidc/customization/sub实时读取sub_claim_prefix，再绑定前缀+:ref:refs/tags/v*；保留repository/ref/audience限制，未放宽PR或main读取。v0.1.0未发布APK，补充脱敏错误日志后以v0.1.1重试，运行37203291324。

## 0.1.1 首版交付结果（历史，当前以最新版本节和状态表为准）

2026-10-04：Android正式版0.1.1（versionCode1001）已通过GitHub发行并发送到用户飞书。安装包5287144字节，SHA-256 `f211618ef6ccc4934adb305170fe8a11ac52c638198a1bb1ed0e54e44adb1d9f`。云端安装包和latest均private，匿名APK403；手机更新接口返回0.1.1和短期签名。代码无真实凭据，公开Git不含配置APK。

- 发行证据：https://github.com/makewheels/dashcam-transfer/actions/runs/37203291324
- CI证据：https://github.com/makewheels/dashcam-transfer/actions/runs/37203289235
- infra已提交推送，两环境资源清单和密钥唯一来源已更新。旧单Bucket/函数/RAM/Key/策略已删除；仅保留两个private Bucket与四个专用RAM用户。发行材料只在独立发行项目；TLS服务私钥仅生产运行目录。
- 下一位维护者首先读取本节与当前状态表，按反馈修复；不要重复创建资源、轮换签名或重新发送旧版。后续发行推送更高语义版本标签，保留生产签名；通过GitHub不可变subject前缀限制OIDC。v0.1.0只有失败发行记录，未上架正式APK。
- 尚无真机安装/OTG/锁屏证据；iOS尚未实现。

### 用户真机验收顺序

1. 在红米K40 Pro和OnePlus9安装飞书APK，确认首页与通知权限正常。先在TF卡选择含少量测试视频的目录，关闭自动删除测试；退出重开确认目录记忆。两台手机均需验证系统SAF读卡器能读写该TF卡文件系统。
2. 断网导入，核对文件数/字节/进度/速度/剩余时间，以及手机文件管理器的日期目录。复制中暂停/恢复、拔卡/重新插卡、退出/重开，确认继续后最终文件SHA与原文件相同；空间不足时拒绝且保留5GiB。
3. 用另一份测试导入启用默认自动删除：只有整批复制校验完成后删除对应源视频，故障时未完成源文件保留，目录及计数器/编号文件不被格式化或清除。实际源文件删除是否支持由系统SAF供应者决定，失败必须显示并可重试。
4. Wi-Fi自动上传，上传中暂停/继续与断网重连，观察分片恢复；切换流量后自动停，手动点上传才继续，再中断需手动授权。核验完成后手机副本删除；另一台手机联网看到相同云端列表。云端播放交给外部播放器，不要求转码。
5. 锁屏运行与手机省电策略下观察通知/任务恢复；强制停止应用后需重开。手动检查更新验证当前版本提示。只接受真机实际结果，模拟或CI不能替代这些结论。发现问题记录手机型号、Android版本、动作、页面错误与对应视频大小，不记录凭据或签名URL。

## 0.2.0 用户反馈修正（2026-10-04，已发行并飞书交付）

- 手机副本清理已改成MediaStore系统回收站，云端校验成功先持久化cleanup_error，再核对内容并IS_TRASHED=1；失败保持可重试，Android10保留原副本，严禁降级ContentResolver.delete。TF卡原视频删除策略不受此手机回收站改动影响。
- 重做首次三步引导与首页唯一主动作、四个底部Tab（开始/待上传/云端/设置）、真实可移动存储挂载/拔出反馈；无视频时入口直接引导导入。总任务进度与当前文件阶段分开，速度/当前阶段ETA/估计总ETA分别标出；导入总进度包含复制和两次内容校验。列表状态局部更新，避免每个文件状态变动把列表和滚动位置重建。
- SQLite v1→v2仅新增import_time，保留旧队列、断点和URI。新导入固定北京时间时分秒+唯一ID，同批文件保持同目录。手机与OSS都使用该命名；服务端兼容0.1.1客户端，并保留已存在记录object_key。
- 本地8项服务端测试通过；开发真实OSS9MiB分片、续传/租约/完整CRC/签名下载/匿名403及坏CRC拒绝再次通过，测试数据已清理。两环境API已更新。
- Android35隔离模拟器4项设备测试通过：真实MediaStore回收站且内容仍可读取、旧SQLite迁移保留断点、虚拟可移动存储实际unmount/mount后引导反馈、首次引导与导航/进度页。另4项纯单元测试与lint通过。截图在docs/screenshots/0.2.0，fixture进度截图是布局验证，不是真实传输测速。
- 源码及截图敏感信息扫描无命中、未提交APK；GitHub CI37205318302与v0.2.0发行37205320284均成功。正式安装包5319896字节，SHA-256 `8ae57e49136d2e3cf502b14dfac3000e8c75c1b56dd1ae73d2a80c9813dc4104`；versionCode2000，包名com.makewheels.dashcam，新旧签名证书完全一致。更新接口指向0.2.0，匿名APK403。飞书上传/发送均返回code0和message_id，附升级说明。隔离模拟器及所有构建中转已退出清理。
- 下一步只处理用户真实使用反馈；红米/一加真实OTG、省电锁屏和系统相册回收站入口仍由用户验收，模拟器不替代这些结论。不要重复发布0.2.0不同APK，下一次修改必须增加版本并继续沿用签名。

## 0.3.0 实时首页与批次反馈（2026-10-04，已发行并飞书交付）

- 删除启动引导弹窗，首页常驻网络/OTG状态、目录记忆、独立拷贝/上传按钮与动态提示；USB读卡器和挂载TF卡分别检测。
- 新增批次页与明细；SQLite保留本机导入记录，云端新增import_batches关联集合，已上传内容重复导入仍在每次批次中可见，不重复存储OSS对象。兼容0.2主记录和早期只有日期的记录；不修改SQLite版本或移动云对象。
- 10项服务端模拟和独立真实MongoDB测试通过。Android35模拟器实际虚拟SD拔出/挂载、回收站、SQLite迁移和直接首页/五Tab/历史明细4项测试通过，lint和4项单元测试通过。截图保存于docs/screenshots/0.3.0。真实手机OTG/锁屏仍待用户验收。
- 两环境函数已部署；开发真实9MiB分片上传、续传、CRC、批次摘要/明细、签名读取与匿名403通过，坏CRC拒绝，全部本次测试OSS对象、videos和import_batches记录已清理。生产health200、鉴权401/200与批次接口200通过。
- 源码提交967ca6a已公开推送，CI37207452063与标签发行37207474227成功。APK5334208字节，SHA-256 `000b7516e14c762bfb9e71e5e24af3cc54cf1b8e2e45e0f49dcd44ed399558d9`，包名com.makewheels.dashcam，版本0.3.0/code3000；与0.2.0证书一致，更新接口指向0.3.0，匿名APK403。正式文件在artifacts/android/0.3.0/app.apk，飞书文件与说明均code0/message_id。隔离模拟器及中转目录已清理。
- 下一步：用户验收红米/一加真实OTG、锁屏与系统回收站入口，按反馈修复。不得重新发布0.3.0不同APK，不要改生产签名。恢复历史时同时保留videos/import_batches；无需增建账号、Bucket或数据库用户。iOS仍仅占位。

## 0.4.0 线性两页与手动操作（2026-10-05，已发行并飞书交付）

1. 目录展示SourcePath解析TF卷挂载绝对路径，保留全部层级，文字可选中复制。不能提供磁盘路径的第三方SAF显示完整授权URI。
2. 取消五Tab：直接卡→手机页，选择目录、拷贝、删除卡上已校验的视频、进入手机→云端页；后者上传、暂停与单独回收站。历史只从更多菜单访问，保留旧记录。
3. 取消所有自动删除及拷贝后上传衔接、Wi-Fi自动worker（升级取消旧任务，旧入口不会运行）。复制校验持久ready；手动删除重新核对手机和TF内容，TF断开或手机副本损坏时保留源文件；上传校验持久cleanup_error，手机副本保留，只有手动清理移入系统回收站。
4. 6项Android设备测试已通过（包含debug专用隔离DocumentsProvider的真实复制/手动删除边界），4项单元与lint通过。测试provider仅debug构建包含，禁止出现在生产APK。
5. 源码3d23680已公开推送，v0.4.0发行37282027226、CI37282004499均成功。正式APK5336520字节，SHA-256 `f9b1b856a616d707b447bde02ddb5792b1ac06a5a5961fe2a9b313a54407c89d`，包名com.makewheels.dashcam，code4000；与0.3.0签名一致。生产Manifest确认无debug文档测试provider。私有APK匿名403，更新接口版本/SHA一致，飞书APK和说明code0/message_id。安装包artifacts/android/0.4.0/app.apk；模拟器与临时构建目录已退出清理。服务端/数据库/OSS资源本次没有改动。
6. 下一步：用户验收真实TF目录授权、完整路径、拷贝后保留/手动删除、第二页手动上传与回收站、锁屏/暂停恢复。发现问题修复后增加版本，不覆盖0.4.0不同内容，不改变签名。iOS仍仅占位。

## 0.4.1 图标与名称（2026-10-05，已发行并飞书交付）

- 用户选择桌面名称「行车记录仪转存」（开发版后缀仍保留），更新安装显示名同步。
- AdaptiveIcon背景整层#167D6E，中央白色摄像机；适配桌面圆形/圆角方形裁切，不留透明白边。保留完整绿色legacy资源，通知另用透明白色单色图标，避免通知显示实心方块。
- 源码d685980已推送，CI37282738302与发行37282863723成功；正式APK5338544字节，SHA-256 `a0dc275f0e189778415ba3a72a9985d5c6395443b34ebbb6af84a43e9438482c`，版本0.4.1/code4001，名称行车记录仪转存，与0.4.0同签名；private匿名403，更新接口/SHA一致，飞书APK与说明均code0/message_id。
- aapt验证APK使用adaptive-icon、背景颜色#167D6E、白色前景；资源压缩会改名，按badging提供的图标资源路径检查，不能假定ZIP中仍有原文件名。矢量裁切预览在docs/screenshots/0.4.1。桌面实测未完成：Android35镜像最小用户分区使本机剩余空间无法启动模拟器，临时目录均退出清理，不能把预览当设备截图。
- 版本顺序0.3.0→0.4.0（两页手动流程）→0.4.1（名称/图标修正）；下一步用户验收桌面主题裁切与名字显示，沿0.4.0流程继续真实OTG/锁屏验收。

## 0.4.2 整批进度与两步指引（2026-10-05，已发行并飞书交付）

- 用户最新要求：任务开始后进度立即可见，突出整批百分比/完成文件数/唯一总剩余时间；当前视频仅文件名与小进度条，不显示每视频/每阶段剩余时间。
- 总剩余时间改成连续整批工作采样，跨文件/校验阶段保留估计值；暂停不计入耗时，新任务重置。顶部固定①拷贝到手机→②上传云端，复制完成明确提示下一步，不自动上传。
- 增加安全弹出TF卡入口，普通应用打开系统存储页由用户确认卸载，不能伪造已卸载成功；忙碌时禁用。流量先确认本次容量，Wi-Fi直接开始。
- 本地编译、lint与7项单元通过；GitHub CI37288169129与隔离Android35设备验证37288175096成功，7项设备测试通过（真实64KiB文档复制、整批进度/估时、自动滚到顶部、完成引导及原有删除/回收站边界）。9张实际模拟器截图在docs/screenshots/0.4.2；进度演示使用固定测试状态，不代表真实TF卡吞吐性能。debug空配置，不导出生产凭据。
- 源码8705f76、设备证据aa8daca已公开；CI37288799762与标签发行37288804265成功。正式APK5344796字节、SHA-256 `e8713e4d1a1a2d2ac45235c9bb5170f0425644f478d44d303e03b4e0cacdb5cd`，0.4.2/code4002，与0.4.1同签名；生产Manifest无测试provider，private匿名403、更新接口版本/SHA一致。飞书APK和说明均code0/message_id成功，安装包artifacts/android/0.4.2/app.apk。
- 下一步：用户验收真实红米/一加OTG、系统卸载入口、实际传输估时与锁屏；虚拟卡与固定进度截图不能代替这些验收。修复时递增版本，不覆盖已发布APK。云函数/数据库/OSS/RAM未变更；iOS仍预留。
