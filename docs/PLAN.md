# 行车转存：实施计划与接手记录

更新日期：2026-10-05。此文件同时保存已确认需求、实施顺序、实际进度与验收门槛。接手前先读全文，再核对 Git、资源和运行状态；文档中的“待完成”不能当成已实现。每完成一个阶段或发现阻塞，都更新本文件的进度表及最后的接手记录。

## 目标与范围

优先交付 Android 可安装 APK，验收设备为红米 K40 Pro、一加 9。公开 GitHub 源码，目录为 `server/`、`android/`、`ios/`；iOS 当前仅预留接口与目录，不属于第一版 APK 完成条件。

实际流程：车上将 TF 卡插入 OTG 读卡器 → 选择视频目录并记住授权 → 扫描和检查空间 → 复制全部直接子文件 → 校验整批 → 手动点击删除已校验的 TF 卡原视频（也可保留）→ 进入第二页手动上传 → OSS 确认完整后手动移入手机回收站。移动网络可以通过上传按钮手动启动，暂停、开始、继续均有按钮；移动网络中断后恢复必须再次手动启动。

最新反馈要求线性两页：第一页面负责卡到手机的拷贝和单独删除，第二页面负责手机到云端的上传和单独回收站清理。取消底部导航和自动操作，历史保留在「更多」入口。多个手机使用同一存储空间，同步后看到同一份云端列表，无注册、登录、账号、扫码加入或选择空间的产品流程。用户最新要求可以查看之前的批次：保留每次导入记录，按北京时间展示文件数、容量与上传状态，支持本机历史和共享云端历史；不引入云端删除或复杂批次编辑。

## 已确认规则

1. 来源是用户通过系统文件选择器选定的一个目录，常见名字是 `video`，但不能写死路径。只处理直接包含的文件，不递归扫描子目录；用户确认该目录内都是视频。保存 URI 与持久读写授权，重插读卡器后尝试复用，失效才请求重选。
2. 不格式化 TF 卡，不删除目录，不动所选视频目录以外的计数器、配置等文件。复制中或校验失败不能删除来源。整次导入全部文件复制并校验成功后仍保留卡上原文件，只有明确点击删除并确认后才删除。旧自动删除偏好和批次字段不能触发自动删除。0.6.4按用户最新要求，删除前检查手机副本存在及大小、TF文件大小及修改时间，不再整文件重读；检查异常时保留源文件。逐个删除结果需要持久记录，删除失败可重试。
3. 导入前检查：可用空间至少等于本次需要复制的容量加 **5 GiB**。不足直接拒绝并提示清理多少空间，不做容量不足的拆批导入。任务途中空间变少则暂停保留原文件。
4. 手机保存到共享目录，文件管理器可见，按本次导入的北京时间精确到时分秒组织；同名目标不能覆盖。使用 `Movies/行车视频/YYYY-MM-DD_HH-mm-ss_<导入ID前8位>/`。API 29+ 使用 MediaStore，复制未完成对象使用 `IS_PENDING`；复制校验完成后才公开。需要验证所选 MediaStore 集合与相对目录兼容。
5. 整批百分比、已完成/总文件数、容量与唯一总剩余时间优先显示；当前视频仅文件名和小进度条，不显示每文件/每阶段剩余时间。总估时连续采样整批累计工作量，跨文件/阶段保留，不计暂停时间；首次采样前显示正在测量整批速度。任务开始自动滚到顶部，完成第一步明确引导第二步。
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
| Android 代码 | 0.4.3模拟器验证通过 | 两页手动流程、整批进度/估时、插卡统计与变化刷新；lint、7项单元与8项Android35设备测试通过；真机OTG/卸载/锁屏待验收 |
| iOS | 仅占位 | `ios/README.md` |
| GitHub Actions / 发布工具 | 0.4.3完成 | CI37296497080、设备验证37295901652、发行37296502173成功；OIDC→构建/lint/单测/签名→privateOSS→latest完整运行成功 |
| APK / 飞书发送 | 已交付0.4.3 | artifacts/android/0.4.3/app.apk来自GitHub正式发行，code4003、同签名/SHA/大小/private403/更新接口通过；飞书APK和说明均发送成功 |
| 真机连接 | 无 | `adb devices` 未发现设备，不能声称 OTG/锁屏行为通过 |

当前Android首版已交付，开发/生产云端部署和自动发布已验证。下一步是用户真机验收及修复反馈；iOS仅占位，不能称iOS完成。以下五阶段作为复现与后续维护步骤，历史进度段落不代表当前未完成状态。

## 五个实施阶段

### 1. 项目、配置与私有云资源准备

- 在公开项目中补充 README、配置示例、MIT License、构建指南和贡献安全边界；源码及路径不能使用执行者名称。
- 使用开发 `dashcam-transfer-dev` 与生产 `dashcam-transfer-prod` 两个独立专用 Bucket；北京、标准存储、本地冗余、private。不设置自动删视频的生命周期；可设置清理超过 7 天的未完成 multipart。
- 建立仅本 Bucket / 指定前缀有效的 RAM 权限；服务端不需要删除已完成视频、修改 ACL、操作其他 Bucket 或管理 RAM。视频路径为 `videos/北京时间含时分秒_导入ID前8位/原文件名`，发布路径为 `releases/android/版本/app.apk`。
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

## 0.4.3 插卡自动统计与变化刷新（2026-10-05，已发行并飞书交付）

- 插卡或进入前台时自动检查已授权目录，弹窗显示视频数量、总大小、完整路径和手机空间；未授权先引导选择文件夹，不自动拷贝或删除。
- 前台每5秒检查挂载与目录变化，插拔广播触发即时检查，任务结束重新检查；卡断开清除旧数量与弹窗，并暂停卡上操作。变化后更新数量，未变化不反复弹窗。
- 本地编译/lint与7项单元通过；CI37295868922与隔离设备验证37295901652成功，8项Android35设备测试通过（新增实际文档数量/大小、删除后更新、不重复弹窗、断开状态清空测试）。11张空配置模拟器截图在docs/screenshots/0.4.3，虚拟卡不代替真实OTG验收。
- 源码a3add3e、截图证据b1a8d4e；CI37296497080与发行37296502173成功。APK5349772字节、SHA-256 `87938b5009f31c21ebeecbad1ca63b1836ae54e7232578321ca36c674cdbce90`，0.4.3/code4003、与0.4.2同签名、生产Manifest无测试provider；private匿名403、更新接口版本/SHA一致。飞书APK及说明code0/message_id成功，安装包artifacts/android/0.4.3/app.apk。
- 下一步：用户验收真实手机拔插、所选目录权限与统计、删除后刷新和锁屏；模拟器逻辑验证不代替真实OTG/系统存储事件。修改时递增版本，不覆盖已有APK；云函数/数据库/OSS/RAM未改动，iOS仍占位。

- 设备验证37295296372发现首次启动扫描早于onResume，统计先完成但弹窗等待下一轮；已修正为界面进入前台后启动扫描，37295901652复验通过。

## 下一项：App更新下载修复（2026-10-05，用户要求20:51再开始）

- 0.4.3 APK/飞书交付已完成，infra同步提交48f5d5b。用户后续反馈自动更新无法下载，授权必要时在a4.fit新增子域名；以后在App内查看新版本并下载/安装，不再依赖飞书逐版发送。
- 已实际请求生产更新接口返回的签名APK URL：HTTP400，XML Code=ApkDownloadForbidden。之前验证仅包括更新接口、SDK凭据回读与匿名403，没有验证App使用的签名下载，不能把之前验证称为完整自动更新通过。
- 阿里云官方0048-00000200/升级通知明确默认公网域名对新Bucket的APK签名下载限制，正规分发需CNAME。当前server/app.py的/updates/android直接用默认bucket.sign_url；MainActivity.checkUpdates每天最多自动检查一次，download无可见进度，installer仍需系统确认，不能承诺普通App静默安装。
- a4.fit当前DNS托管AliDNS（dns19/dns20.hichina.com）；本机aliyun CLI可用。infra已有记录：cloud-credentials/prod/aliyun凭据具备AliDNS权限，未读取真值，未更改DNS/Bucket/证书/云函数/客户端更新代码。部署工具deploy/function.py当前只注入RUNTIME_KEYS，增加下载域名配置时需同步Infisical与部署脚本，不能暴露密钥。
- 20:51后继续：先确认OSS CNAME与HTTPS配置可行，尽量不新增常驻服务；必要时创建独立a4.fit子域名并绑定private生产Bucket（开发生产分离），签名下载依然有效、匿名依然拒绝。修复服务端返回地址和App检查/下载进度及安装引导。实际签名GET完整读取并核对大小/SHA；模拟器验证下载→系统安装器，不能只用接口200。发布递增版本，不覆盖0.4.3，记录正式更新链路证据并同步infra。
- 用户明确暂停至北京时间20:51；此记录只保存接手状态，未开展域名创建或更新实现。

- 已按用户要求注册一次性系统定时续跑：2026-10-05 20:51 Asia/Shanghai，任务local.dashcam-update.once。launchctl已验证注册；本地CLI登录状态和会话存在已验证，尚未执行不代表工作完成。状态/日志/result位置：~/Library/Application Support/TransferTasks/update-20261005；任务执行后删除自身临时runner与LaunchAgent。Mac需保持开机联网，恢复后以started/finished记录确认实际运行。用户无需再次发继续。

## 0.4.4 更新下载域名修复（2026-10-05，已发行并飞书交付）

用户接手继续执行（未等20:51定时任务，任务已提前卸载）。根因确认：阿里云禁止通过Bucket默认公网域名分发APK，签名GET实测HTTP 400 ApkDownloadForbidden，官方唯一正规解是绑定自定义域名。

云资源（均已实测）：
- DNS（a4.fit，阿里云解析）：`dashcam-transfer-{prod,dev}.oss.aliyun` CNAME到对应bucket默认域名；所有权验证用`_dnsauth.*` TXT记录，验证通过后已删除。
- OSS绑定：ossutil不支持，用oss2 `put_bucket_cname(PutBucketCnameRequest)` 提交成功；`list_bucket_cname`回读确认。
- HTTPS证书：Let's Encrypt ECC SAN证书覆盖两个下载域名（acme.sh dns_ali DNS-01），首次签发即由reloadcmd上传；重绑脚本`~/.acme.sh/rebind-dashcam-oss-cert.sh`在续期后自动重新绑定两个bucket（XML需含Domain元素，首次漏写导致MalformedXML已修复）。证书上传到OSS边缘有分钟级延迟，立即访问会短暂看到默认证书。

服务端与客户端：
- `server/app.py`：新增`DASHCAM_DOWNLOAD_DOMAIN`（可选）。设置后用该域名的cname bucket（oss2 is_cname=True，V1签名CanonicalizedResource不含bucket名）签下载类GET URL（APK与视频下载）；上传分片签名保持默认域名。未配置时行为不变，测试注入mock不受影响。
- `deploy/function.py`：RUNTIME_KEYS增加该键（可缺失，默认空串）。Infisical tools dev/prod /dashcam 已写入并回读验证。
- `MainActivity`：更新下载改为进度对话框（百分比、已下载/总量、可取消，取消即中断并删除临时文件），完成后照常SHA-256校验再进安装引导。签名过期/链接失效仍提示重新检查更新。
- 云函数dev/prod均已重新部署；dev的runtime RAM无releases写权限（最小权限正确），dev验证为接口正常响应available=false。

链路验证证据：匿名GET自定义域名403；默认域名签名GET仍400 ApkDownloadForbidden（限制仍在）；签名GET自定义域名200且SHA-256与manifest一致；篡改签名参数403。生产`/updates/android`返回0.4.4/code 4004/域名为自定义域名，完整下载5352928字节SHA `8e7bc2ba8ab56e630a4cf279e523b60a66868369bd1026a15706b37789413c00`与manifest一致。

发行与交付：
- 源码494f976推送，CI 37303112746与发行37303116740成功；v0.4.4/code 4004，安装包5352928字节，同0.4.3签名。飞书APK+说明发送成功，message_id `om_x100b630ce276eca0c4b620087cec40e`，群内可下载。
- 应用户要求补齐GitHub Release：v0.1.0至v0.4.4共9个版本均带版本说明（安装包仍只在私有OSS，不放公开附件，原因：APK内嵌受限服务凭据，公开附件会扩大可访问私有存储的客户端分发面）。Latest标记为v0.4.4。
- 以后版本release.yml自动创建带描述的GitHub Release：发布tag用annotated tag，CI读取tag说明作为`DASHCAM_RELEASE_NOTES`（OSS manifest notes）与Release正文；workflow权限改为contents:write仅用于创建Release文本。
- 验收边界：模拟器未再验证App内下载→安装（本机磁盘与时间限制）；真实链路以HTTP层完整下载核对为准。用户在0.4.3上「检查更新→下载安装0.4.4」即真实验证新域名链路；装上0.4.4后可见新进度对话框。真机OTG/锁屏/实际吞吐仍待用户验收。iOS仍占位。
- 本次20:51一次性LaunchAgent `local.dashcam-update.once` 已提前完成工作并卸载，未触发。

## 0.4.5 首页极简两步结构（2026-10-05，已发行并飞书交付）

用户反馈0.4.4首页仍堆砌（下一步/安全弹出/常驻换目录按钮等），要求拷贝页只有检测卡、拷贝、删除三件事，上传页只有上传的事。

- 顶部①②步骤条可点击直接切换两页；拷贝页仅保留：卡状态卡（含完整路径与空间）、引导文案、拷贝按钮、删除按钮、任务运行时进度卡与暂停；「选择视频文件夹」按钮只在未授权目录时显示在卡内，已授权后隐藏，换目录收进更多菜单。
- 「下一步：上传云端」「安全弹出 TF 卡」常驻按钮从首页移除；网络状态chip从头部移到上传页顶部；上传页与批次页删除「返回拷贝」按钮（顶部步骤与返回键均可回）；设置页（tab 4）删除，其唯一功能「检查更新」已在更多菜单。
- 更多菜单扩为：历史批次、云端视频、更换视频文件夹、安全弹出 TF 卡、检查更新、通知与后台运行设置。历史遗留死元素deviceBanner删除。
- TaskCard完成后的动态引导（下一步上传/安全弹出/移入回收站）保留——它属于任务流程反馈，不是常驻杂物。
- 模拟器验证：0.4.3→0.4.4 App内更新全链路实测通过（自动弹窗→下载→未知来源授权→系统安装器→versionName=0.4.4），下载走新域名约3秒完成。新UI截图验证首页/上传页/更多菜单符合预期。过程中发现并修复queuePage网络chip空引用崩溃（network创建语句随header移动时丢失）；本地模拟器受12GB userdata限制，用-partition-size 4096启动成功。
- uiautomator dump/screencap验证时曾被com.makewheels.dashcam正式版前台窗口误导（.dev首启崩溃退回），以topResumedActivity确认前台包名是排查关键；dexdump/strings对中文MUTF-8不可用，验证APK内容用python字节搜索。
- debug构建/lint/单测本地通过；设备测试交由GitHub Actions隔离验证。版本0.4.5/code 4005。
- 源码e51309f推送，CI 37307432191成功；发行37308871146成功。正式APK 5351260字节、SHA-256 `08632e957f60dc7b1347f79e9ee6241dbd37fd781dffc34dbcb97211c98ae24d`，0.4.5/code 4005、与0.4.4同签名；生产更新接口返回0.4.5且完整下载SHA一致，host为自定义下载域名。飞书APK+说明发送成功，message_id `om_x100b630d214040a0c32a4435b30db39`。
- GitHub Release v0.4.5带SHA256SUMS.txt校验文件（不挂APK公开附件）；发现CI浅克隆读不到annotated tag说明，标题/描述曾取到commit信息，已手动修正Release并用checkout fetch-depth: 0修复workflow（b436e1c），下个版本生效。期间遇到两次github.com 443间歇超时与Mac磁盘占满（模拟器userdata+构建产物，清理AVD与/tmp后释放41GB），tag曾指向旧commit，已取消发行、重打tag到0abefd6重跑。
- 用户验收：0.4.5真机安装后确认首页三件事结构与步骤切换；App内更新链路已在模拟器实测（0.4.3→0.4.4）。真机OTG/锁屏/实际吞吐仍待日常验收；iOS仍占位。

## 0.4.6 首页提示动态化（2026-10-05，已发行并飞书交付）

用户反馈一打开App就显示「拷贝已完成，29个视频要删除」：该文案按SQLite记录（已拷贝未删源的数量）计算，不判断卡是否连接，每次启动都显示同一句，像缓存残留。

- 修复：首页引导文案按当前状态动态生成。删除提示（含数量）只在TF卡实际连接时显示；卡不在时改提示「连接原读卡器才能清理卡上副本，或直接进入②上传云端」；ready分支文案去掉固定删除暗示，改为按暂停/Wi-Fi/流量状态引导上传；「删除卡上视频」按钮在卡未连接时禁用（点了必失败）；任务完成提示同样按卡连接状态裁剪删除语句。
- debug构建/lint/单测通过。源码af28a1e，发行37310881174成功；正式APK 5351732字节、SHA-256 `ed074f06fa68a4578591d9175fbf5f21206b05881dd1bb43c4370fba03253323`，0.4.6/code 4006、同签名；生产更新接口核对一致并完整下载验证。飞书APK+说明发送成功，message_id `om_x100b630de38fa0b0c360327d9fd53b0`。

## 0.4.7 遗留任务放弃与状态条（2026-10-05，已发行并飞书交付）

用户反馈0.4.6提示文案后明白是上次任务未完成的遗留，提出两点：可以放弃上次任务重新来；当前状态要突出展示。

- 新增「放弃上次任务（N 个视频），重新开始」按钮：存在 ready/copy_error/uploading/upload_error 遗留时显示；确认框写明手机副本移入系统回收站（不永久删除）、TF 卡原视频与云端已上传均不受影响。TransferEngine.runAbandon 副本回收站优先（API<30 直接删除未完成副本）、记录与空批次行删除、失败副本计数提示。上传会话留在云端自然过期（7天multipart清理），重新拷贝上传按SHA去重不重复占用。
- 首页引导文案升级为彩色状态条（加粗16sp浅底圆角）：蓝色=进行中、琥珀=有遗留待处理、绿色=拷贝完成待上传、灰色=初始引导。删除提示仍按卡连接状态动态显示。
- 模拟器实测全流程：注入2条ready+1条copy_error记录→琥珀状态条正确显示数量与两个选项、放弃按钮出现→确认框→执行后files/batches清零、状态条回灰色引导、按钮消失；放弃完成后不再显示空的进度卡（修正为仅运行中显示）。0.4.6的删除按钮卡未连接禁用、App内更新链路不受影响。
- debug构建/lint/单测通过。源码1b20356，发行37313281552成功；正式APK 5355308字节、SHA-256 `5fe9d200459296bac3e71db1f1a9bbbeb8f816f7e6dec894a49c504a769da17d`，0.4.7/code 4007、同签名；生产更新接口核对一致并完整下载验证；Release无附件。飞书APK+说明发送成功，message_id `om_x100b630db05e88a0c4a2e3fda83dd9c`。
- checkout fetch-depth:0仍取不到annotated tag对象（Release标题两次落到commit信息），已在workflow加`git fetch --tags --force`（723935d），v0.4.7标题手动修正，下版自动生效。
- todo（用户提出，下一版处理）：上传页网络状态区分Wi-Fi/移动网络/5G显示不同图标与颜色，字体加大。

## 0.4.8 网络状态可视化（2026-10-05，已发行并飞书交付）

用户要求处理上面的todo：上传页网络状态区分类型、图标、颜色，字体加大。

- 上传页顶部改为网络状态条：图标+16sp加粗文字+浅色圆角底。Wi-Fi=绿色wifi图标；蜂窝=蓝色cell信号柱图标（Ui.Icon新增cell）；未联网=灰色。蜂窝文字显示「5G 网络」/「4G 网络」/「移动网络」并附「流量上传需手动确认」。
- 5G识别：Android 11+用PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED（TelephonyDisplayInfo，无需权限），NETWORK_TYPE_NR判定5G；Android 10显示「移动网络」。onDestroy反注册。isNrAdvanced在当前编译环境不可用，仅用NETWORK_TYPE_NR（5G-Advanced细分不区分）。
- debug构建/lint/单测通过；模拟器验证Wi-Fi与未联网两态渲染（飞行模式实测灰色未联网态），蜂窝与5G文案待真机（模拟器无SIM）。
- 源码18bf5cf，发行37315482608成功；正式APK 5357152字节、SHA-256 `c81877037446a0ef7aab687b5b5dec96b4d027d93c2286ce2a6b9b2ffb9bbfe1`，0.4.8/code 4008、同签名；生产更新接口核对一致并完整下载验证；Release标题「0.4.8 网络状态一目了然」——workflow的`git fetch --tags --force`修复生效，annotated tag说明首次被CI正确读取，无附件。飞书APK+说明发送成功，message_id `om_x100b630e7585c4a0c45626003c12fa1`。期间github.com两次间歇443超时，标签重推后成功。

## 0.4.9 去重标题与无用文案（2026-10-05，已发行并飞书交付）

用户继续反馈不够极简：副标题「拷贝完成后，再决定是否删除卡上视频」无用；「① 拷贝到手机」标题与步骤条重复。

- 删除header副标题与selectTab的sub文案数组；拷贝/上传页隐藏大标题，步骤条（字号15→18）直接作为页面标题与切换器，一个信息只出现一次；历史/云端页保留标题。
- 未完成任务reset能力0.4.7已具备（放弃按钮+确认框+回收站+记录清理），本版不改。
- 纯布局删减，编译/lint/单测通过；本地模拟器因磁盘余量不足未再起，UI流程回归交由CI设备测试。
- 源码019f4ad，发行37316995801成功；正式APK 5356820字节、SHA-256 `ae0cd28dc42cab5d82749bff87703993c5a8fca49999da3dd0691704a49a70a3`，0.4.9/code 4009、同签名；生产更新接口核对一致并完整下载验证；Release标题「0.4.9 更极简」正确读取tag说明。飞书APK+说明发送成功，message_id `om_x100b630e2208f0acc3d8a328ff5430a`。

## 0.5.0 手机副本改为应用私有目录（2026-10-05，已发行并飞书交付）

用户改需求：副本不再放共享媒体目录（相册可见、Movies/行车视频），改存应用私有位置，相册不可见、卸载/清理即消失。

- TransferEngine新增openDestFd/openDestInput/removeDest兼容层：新副本写入`getExternalFilesDir/videos/北京时间_批次8位/原文件名`（App私有，MediaStore不再介入，无IS_PENDING）；旧版本生成的MediaStore副本仍按content URI继续可用（上传/清理/回收站不变），直到用户主动清理。
- copy目标改文件路径；校验/分片读取/上传前核对/删除前核对统一走新helper；cleanup从「移入系统回收站」改为「核对后直接删除」（旧MediaStore副本仍走回收站）；abandon同样直接删除。
- 文案：「移入手机回收站」→「删除手机副本」，确认框说明副本存于应用私有目录、云端与TF卡不受影响。
- CopyFlowTest的finally清理适配文件路径dest。设备测试发现本机模拟器缺sdcard镜像导致CardInventoryTest/UiFlowTest失败（0.4.3在本机同样失败，非代码回归，CI环境有sdcard而本地AVD未生成）；修复后以CI设备测试为准（workflow_dispatch手动触发）。
- debug构建/lint/单测通过。CI设备测试37326596860手动触发，8项全绿（真实复制走新私有路径）。源码a4c5fbf，发行37327351999成功；正式APK 5356680字节、SHA-256 `5136403fdcf88de1b5162019f61331c78c5e3cbda96f8012dcf6a0c80f62c699`，0.5.0/code 5000、同签名；生产更新接口核对一致并完整下载验证；Release标题正确、无附件。飞书APK+说明发送成功，message_id `om_x100b630fe601f0a4c3b51e0253e3bc2`。期间本机到阿里云FC链路多次SSL握手超时（baidu/github正常），用curl完成验证；本机模拟器sdcard.img缺失导致设备测试失败已定位（CI环境正常），AVD需mksdcard手动创建。

## 0.5.1 启动必检更新（2026-10-06）

用户要求启动即检测新版本并弹窗。原有每日一次限流（update_checked）移除：每次启动静默检查（checkUpdates(true)），发现新版本即弹「下载并安装」，已是最新不提示。checkUpdates的弹窗/下载/安装逻辑不变。debug构建/lint/单测通过。版本0.5.1/code 5001。

- 0.5.1交付：设备测试37385229663全绿；发行37385772597成功；APK SHA-256与manifest一致（curl验证，github/FC网络多次间歇超时）；Release标题正确；飞书message_id `om_x100b63768d7628a8c38f50bb12b08bb`。期间github.com多次443超时，重推后成功。

## 0.6.0 客户端全量迁移 Kotlin（2026-10-06，已发行并飞书交付）

用户要求使用主流语言：23个Java文件（4393行）全部迁移Kotlin（Kotlin 2.0.21 + AGP 8.7.3，jvmTarget 17），Java源码删除，Manifest与类名不变（同包）。

- main层13个类、debug的FixtureDocumentsProvider、3个单元测试与6个设备测试全部重写为Kotlin；kotlin目录作为标准sourceSet。迁移中顺手修正：TransferProgress阶段检测补「清理手机副本」→RECYCLE（0.5.0清理文案改版后错位为IDLE/REMOVE_SOURCE）；删除8个死字段（networkState等）。
- Kotlin/Java语义差异修正：0x8位十六进制字面量需.toInt()、0xC96C5795D7870F42需UL字面量、LayoutParams权重需1f、行首+拼接、TextView.setTextSize(float)、requireContext()为API 30（minSdk 29用getContext()!!）、ContentResolver.openInputStream可空、TaskCard/主界面字段internal供androidTest访问（AGP friend module）。
- 本地assembleDebug/lintDebug/testDebugUnitTest全过。构建脚本改就地构建加清理（Kotlin 2.0.20+忽略自定义buildDirectory，check_android_device.py同步适配）；测试Provider对齐旧版（补isChildDocument）；Store.Item恢复数据库可空语义。CI设备测试37392445342八项全绿。
- 源码c50ce07，发行37393048270成功；正式APK 0.6.0/code 6000、同签名、SHA与manifest一致（curl验证）；Release标题「0.6.0 全量迁移 Kotlin」无附件。飞书APK+说明发送成功，message_id `om_x100b6377a6977ca4c36002982a882e1`。真机验收Kotlin版行为与Java版一致（UI截图模拟器已核对）。

## 0.6.1 放弃任务清理修复（2026-10-06，已发布）

- 复现依据：放弃查询遗漏 waiting/copying，删除失败仍移除记录；进度保存在本地 SQLite 和运行时状态。
- 统一待处理状态查询，加入 waiting/copying/abandon_error；手机副本成功删除后才删除记录，失败保留以便重试。旧 MediaStore 副本永久删除，缺失私有文件视为已清理。清理完成归零运行时进度，删除空批次并暂停自动上传。TF 卡原文件保留。
- assembleDebug、lintDebug、testDebugUnitTest、assembleDebugAndroidTest 通过；隔离模拟器 AbandonFlowTest 实测七种状态的手机文件、记录及旧进度清空，重复执行不恢复旧进度（1项通过）。未验证真实 OTG 设备。
- 用户确认只删除手机副本、本地任务与进度，不删除云端；云端无需按批次分文件夹亦可。本版不迁移已有云端对象，保持上传和去重兼容。新增失败清理后重试的设备测试；CI设备回归37423197721全套10项通过。

- 源码 bdda455，发行37423812820成功；生产更新接口为0.6.1/code 6001。正式APK完整下载5451690字节，SHA-256 `a1c9d7716b322dd3217b9050eaae3976c61f8de5048b509b8d16e920fcddd5c8` 与manifest一致，包名、版本、APK签名校验通过。可通过App内检查更新安装；没有操作用户手机任务或云端视频。

## 0.6.2 页面检测、启动顺序与内容去重（2026-10-06，已发布）

- 用户要求检测结果仅显示在页面，先检查版本更新再检测TF卡；重复拷贝按内容SHA-256跳过，手机/云端无需批次目录。
- 卡检测取消AlertDialog和插卡toast，数量/大小/空间仍在页面显示。启动检测被更新检查门控，更新弹窗关闭或检查完成/失败后再检测，更新请求超时10秒。
- 拷贝扫描读取SHA-256，校验现有手机副本后跳过同内容，当前扫描同内容不同名字也只复制一次；空间估算只计新增内容，损坏/丢失副本不会被误跳过。新手机副本放单层videos目录并使用SHA命名，恢复兼容旧路径。
- 云端原有SHA主键、CRC/大小/HEAD验证去重继续保留；新对象统一videos/SHA_原文件名，旧对象沿用现有路径，不迁移或删除已上传的视频。
- 放弃待处理状态补verifying；复制修复同路径副本后移除多余所有者记录。

- 旧版本同SHA/长度的应用私有副本会在再次扫描时逐份读取核对，只保留一份内容正确的副本并清理多余记录和空批次。内容已变化的副本不当作重复数据删除；旧MediaStore不同URI不自动整理。
- 本地构建/lint/单元和服务端10项测试已通过；首轮设备37453413763十项通过（含改名同内容跳过及损坏副本识别），CI37453413428通过；开发实际OSS分片/续传/CRC/去重/签名下载验证通过，测试数据已清理；两环境函数已更新，生产只读视频/批次接口200。旧副本整理新增回归，待最终设备检查。

- 最终设备37454259051共11项通过，源码2b160b3；常规CI37454198855通过。设备截图核对无卡检测对话框。v0.6.2发行37454889175成功。

- 生产更新接口返回0.6.2/code6002；正式APK完整下载5456102字节，SHA-256 `8d0b2917ac45646dd9c1f4761748433530385a1037656b89676ecff1304e4601` 与manifest一致，包名/版本/签名校验通过。可App内检查更新安装；未操作真实手机或删除云端用户视频。

## 0.6.3 修复真实卡拷贝长期准备（2026-10-06，已发布）

- 用户实卡29个MOV视频、约5.8GB，最大236390128字节；0.6.2在扫描阶段整卡计算SHA+CRC而没有进度，拷贝引擎又重复读取源文件。旧64KB测试不足以覆盖该规模。
- 扫描改为仅枚举元数据，立即启动前台任务；SHA检查、已有副本校验、复制与手机校验逐文件执行，显示字节进度并可暂停。存储枚举使用独立执行器，避免网络请求阻塞拷贝启动。SHA预检不计算逐字节CRC，源文件无需在复制后再次全量读取，保留SHA/长度核对和手机CRC供云端核验。空间按去重后的单文件需要判断，始终保留5GB。
- 新增仅debug的只读HostCard文档提供方和check_host_card.py：从Mac卡上通过只读HTTP流直接读取，无需整卡预复制；正式APK不包含该提供方或HTTP例外，不配置云端凭据。临时AVD与构建均在唯一系统临时目录，finally关闭进程/桥接并清理。
- 第一次真实桥接测试：旧版读卡10秒仍准备、无前台进度；修正版首次字节进度/暂停/真实文件拷贝+独立SHA核对/重复跳过通过。文件数量/大小/修改时间未变。后续最大文件验证时Mac卷断开，读取返回Device not configured，待重插后完成；不能宣称最大文件通过。

- 重插后真实卡对照验证完成：旧版10秒仍准备且无前台进度；修正版480毫秒首次进度、50毫秒暂停响应。29视频共5810110087字节，最大236390128字节拷贝后独立SHA匹配，再次导入跳过且保留已有副本。源码0f1b0e3；只读桥接共提供2183464523字节，来源文件数/长度/修改时间不变。已核对前后页面截图，临时AVD/桥接/截图/构建全部清理。此证据是实卡文件经ADB只读桥接的Android模拟器测试，不代表手机真实OTG、锁屏或整卡全部复制完成。
- 首轮设备回归37460830682发现进度权重变化后旧UI断言仍为6%，已更新为22%；其他用例通过。最终设备37461537172通过（11项普通用例，2项实卡专用用例在CI跳过）；常规CI37461537890通过。v0.6.3发行37462120607成功。

- 生产更新接口0.6.3/code6003，正式APK完整下载5457622字节，SHA-256 `3342a2e904a96db9a428162482fef4cf9564a1ad21d06531d1461fb621cbfd49` 与manifest一致，包名/版本/签名验证通过。可从App更多菜单检查更新；未操作用户手机任务或删除云端视频。

## 0.6.4 卡上删除按文件计数（2026-10-06，已发布）

- 用户指出删除显示十几MB/s且耗时；原因是删除前再次读取手机和TF卡全文件SHA/CRC。
- 按用户要求取消删除阶段整文件重读，沿用拷贝完成的SHA校验结果；每个文件仅检查已完成状态、手机副本可打开且长度匹配、卡上长度与修改时间匹配后删除。异常保留，删除失败记录可重试，暂停在文件之间响应。
- 页面及通知按文件数显示，无MB/s、容量进度或剩余时间估算；显示当前文件名及已删除/总数，完成时显示保留数。
- 边界：不再重新检测拷贝后发生的同长度内容更改；来源有可用mtime时仍检查修改时间。不能将元数据检查称为再次完整性校验。新设备测试检查删除不重新打开源字节及UI按计数、无速度。

- 源码c06f1c3，常规CI37464029691及设备37464030547通过（11项常规用例，2项实卡专用用例在CI跳过），删除测试确认未重新打开源字节，UI按文件计数且不显示速度/剩余时间。发行37464831919成功，生产更新接口0.6.4/code6004，正式APK完整下载5457454字节、SHA-256 `6fde8f9da894f6ae6cb0f0a15bc7a1e909e893ae677931f9c65642820dcb4257` 与manifest一致，版本/包名/签名通过。未删除真实TF卡或云端视频。

## 0.6.5 云端批次路径迁移与上传保持亮屏（2026-10-06，已发布）

- 用户重申OSS不要批次并要求上传时不息屏。0.6.2起新对象已平铺videos/{sha}_{name}，但此前注册的103个生产文档仍存旧批次路径key，续传沿旧key落盘（29个行车MOV在videos/2026-10-05_16-26-41_e8d4531d/）；另74个早期测试文档的OSS对象已被清理、记录悬空。dev库为空无需迁移。
- 新增deploy/migrate_flat_keys.py（默认dry-run，--apply执行）：HEAD核实→同bucket服务端copy→size/crc64/meta sha256校验→条件更新Mongo→删旧对象；HEAD 404的文档核验后删除。运行时RAM用户按设计无OSS删除权限，删除分两步：脚本完成copy与Mongo后，29个旧key由主账号aliyun oss rm逐个精确删除，删除前逐key核对同名平铺副本存在。
- 结果：生产Mongo 103→29个文档全部平铺；OSS videos/仅剩29个平铺对象、批次目录消失；经生产API列表与签名URL Range GET（206、Content-Range总长一致）验证下载链路正常。
- 客户端：新增MainActivity.applyKeepScreenOn，任务运行期间（拷贝/上传/删除）保持亮屏，结束或暂停恢复自动息屏；离开App前台时不阻止息屏。新增KeepScreenOnTest设备用例。本地构建/lint/单测通过，隔离模拟器设备14项全过；首跑CardInventoryTest失败为裸模拟器缺sm set-virtual-disk setup，补齐后通过，与改动无关。
- 版本0.6.5/code 6005。
