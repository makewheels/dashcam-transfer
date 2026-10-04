# 行车转存：实施计划与接手记录

更新日期：2026-10-04。此文件同时保存已确认需求、实施顺序、实际进度与验收门槛。接手前先读全文，再核对 Git、资源和运行状态；文档中的“待完成”不能当成已实现。每完成一个阶段或发现阻塞，都更新本文件的进度表及最后的接手记录。

## 目标与范围

优先交付 Android 可安装 APK，验收设备为红米 K40 Pro、一加 9。公开 GitHub 源码，目录为 `server/`、`android/`、`ios/`；iOS 当前仅预留接口与目录，不属于第一版 APK 完成条件。

实际流程：车上将 TF 卡插入 OTG 读卡器 → 选择视频目录并记住授权 → 扫描和检查空间 → 复制全部直接子文件 → 校验整批 → 默认删除本次已校验的 TF 卡原视频 → 回家连任意 Wi-Fi 自动上传 → OSS 确认完整后删除手机副本。移动网络可以通过上传按钮手动启动，暂停、开始、继续均有按钮；移动网络中断后恢复必须再次手动启动。

首页聚焦未上传列表；已上传视频在单独列表查看。多个手机使用同一存储空间，同步后看到同一份云端列表，无注册、登录、账号、扫码加入或选择空间的产品流程。初期讨论的复杂批次管理不做；内部保留一次导入的操作记录，只用于整批删除和异常恢复，界面按导入日期组织即可。

## 已确认规则

1. 来源是用户通过系统文件选择器选定的一个目录，常见名字是 `video`，但不能写死路径。只处理直接包含的文件，不递归扫描子目录；用户确认该目录内都是视频。保存 URI 与持久读写授权，重插读卡器后尝试复用，失效才请求重选。
2. 不格式化 TF 卡，不删除目录，不动所选视频目录以外的计数器、配置等文件。复制中或校验失败不能删除来源。整次导入全部文件复制并校验成功后，默认自动删除对应原文件，设置允许关闭自动删除。逐个删除结果需要持久记录，删除失败可重试。
3. 导入前检查：可用空间至少等于本次需要复制的容量加 **5 GiB**。不足直接拒绝并提示清理多少空间，不做容量不足的拆批导入。任务途中空间变少则暂停保留原文件。
4. 手机保存到共享目录，文件管理器可见，按当天日期组织；同名目标不能覆盖。默认拟用 `Movies/行车视频/YYYY-MM-DD/`。API 29+ 使用 MediaStore，复制未完成对象使用 `IS_PENDING`；复制校验完成后才公开。需要验证所选 MediaStore 集合与相对目录兼容。
5. 同时显示整体、当前文件、已完成/总文件数、已处理/总容量、速度、预计剩余时间；复制、校验、上传需要区分。预计时间根据当前阶段和近期吞吐计算，初期显示计算中。暂停不显示继续增长的速度。
6. 本地 SQLite 保存任务与断点。读卡器断开、任务进程消失、锁屏、切换 App 后可恢复。复制断点若提供方无法可靠 seek，可重复制当前文件并明确提示，不能伪造已完成。保留已完成文件的状态。
7. Wi-Fi 自动开始或恢复上传；用户明确暂停则不自动覆盖其意愿。移动网络必须点击上传；网络切换到移动网络、重启或进程恢复后不沿用之前的流量授权。手动流量许可只在本次正在运行的上传中有效。
8. 上传走 OSS 分片，进度持久化并以云端已存在分片为准，避免只靠本地断点。每个视频整文件核验后才删手机副本，不能用 HTTP 200 或分片 ETag 代替完整性核验。若用户通过文件管理器移动或删手机文件，需要明确报错。
9. 云端视频一直保留，无生命周期自动删除；App **不提供删除云端视频功能**。用户之后可以自行在 OSS 删除，访问时标记云端文件不存在，保留历史清单。
10. 不转码。点击视频交给手机播放器处理，不能播放不影响传输。
11. Android 检查新版本、提示后一键下载安装，调用系统确认安装；不要求静默安装，也不做业务代码热更新。签名必须持续一致。
12. 阿里云所有新资源默认北京 `cn-beijing`，除非用户明确特殊要求。开发、生产各建专用 OSS Bucket，**ACL private**，不允许公开读。上传签名与下载签名均短期有效；不把阿里云长期 AccessKey 或 MongoDB 凭据放手机安装包。
13. 服务端仅用云函数控制授权、上传会话、列表和校验，不转发大视频字节，不需常驻应用服务器。使用已有 MongoDB，多手机共享记录。敏感信息唯一来源 Infisical，不进入 Git、GitHub Secrets 或长期本机 `.env`。
14. 开发和生产隔离：北京 `dashcam-transfer-dev` / `dashcam-transfer-prod` 两个私有Bucket，分别配置runtime与release RAM用户（共四个）；云函数名带dev/prod；生产数据库`dashcam_transfer`，开发数据库`dashcam_transfer_dev`，各自用户仅本库readWrite。Infisical运行配置使用tools/dev与tools/prod的`/dashcam`；发行配置项目按环境隔离。测试必须指向开发，最终APK指向生产。
15. 交付公开 GitHub 项目与可安装 APK，并通过已有飞书应用将 APK 发送给用户。用户已授权建项目、云资源配置、开源发布、CI 自动打包及最终飞书交付，无须再逐项询问相同事项。

## 当前实际进度

| 项目 | 状态 | 证据 / 未完成部分 |
|---|---|---|
| 需求确认 | 完成 | 上述规则源于本次对话 |
| 本地项目 | 已创建 | `/Users/mint/workspace/tools/dashcam-transfer`，已 `git init -b main`；GitHub公开仓库已创建并设置origin；尚无首个commit/push |
| 现有 MongoDB 专用库和账号 | 完成并验证 | `dashcam_transfer` / `dashcam_transfer_app`；仅本库 `readWrite`；认证、写读删与其他库拒绝均通过 |
| 数据库初始化记录 | 完成 | `transfer_metadata` 中 `_id=schema, version=1`；尚无真实视频记录 |
| Infisical 数据库凭据 | 完成并回读 | `tools / dev /dashcam`，四个键名见下文 |
| infra 记录 | 已修改并暂存 | inventory、secrets、数据库说明、`services/dashcam` 初始化脚本及当日 changelog；仓库检查通过；尚未 commit/push |
| OSS Bucket / RAM | 已创建 | 北京 `dashcam-transfer-media`，回读 ACL private；runtime用户只读写视频及读releases，release用户只读写releases |
| 云函数 | 两环境部署与鉴权通过 | dev/prod health 200、有效凭据列表200、无凭据401；无预留实例；源码包来自对应私有OSS |
| 服务端代码 | 本地测试通过 | uv.lock已生成；6项故障测试通过，独立临时真实MongoDB也通过；开发环境真实私有OSS分片续传、冲突、最终CRC、错误CRC拒绝、签名下载SHA一致和匿名403均通过；测试对象和记录已清理 |
| Android 代码 | 调试构建/静态检查通过 | Activity、Service、Engine、Worker、InstallProvider与Wi-Fi监听已实现；assembleDebug、lintDebug、2项CRC/SHA单元测试通过；尚无真机运行证据 |
| iOS | 仅占位 | `ios/README.md` |
| GitHub Actions / 发布工具 | 已编写、未运行 | CI、本地FC部署、私有OSS发布、OIDC获取凭据均有源码；GitHub公开仓库已创建；尚无首个push或真实workflow运行 |
| APK / 飞书发送 | 未完成 | 没有任何 APK 或发送记录 |
| 真机连接 | 无 | `adb devices` 未发现设备，不能声称 OTG/锁屏行为通过 |

当前工作区部分代码已通过测试，但云端部署和APK交付未完成，恢复任务不要直接发布。以磁盘实际内容为准；只有通过下列阶段门槛才能逐项改成完成。

## 五个实施阶段

### 1. 项目、配置与私有云资源准备

- 在公开项目中补充 README、配置示例、MIT License、构建指南和贡献安全边界；源码及路径不能使用执行者名称。
- 新建名称长期稳定的专用 Bucket，优先尝试 `dashcam-transfer-media`，重名再添加所有者区分；北京、标准存储、本地冗余、private。不设置自动删视频的生命周期；可设置清理超过 7 天的未完成 multipart。
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
- UI：顶部来源目录与选择/更换按钮、空间提示；首页待上传列表与总进度；导入、上传/继续、暂停；独立已上传页与刷新；设置项仅来源记忆、TF 卡复制后删除开关、更新检查等必要操作。不是复杂批次管理界面。
- 首次目录选择调用 `ACTION_OPEN_DOCUMENT_TREE`，保存 `takePersistableUriPermission` 成功的读写授权和 URI。再次使用验证权限与文件可读，不靠固定文件系统路径。
- 扫描构建稳定任务清单，保存原 URI、名字、大小、修改时间、当前断点、目标 URI。来源重新插入与内容变化要区分；同名目标用不同子目录/安全后缀，绝不覆盖。
- 空间预检与每次写入检查；只有全量容量+5 GiB 满足才开始新导入。恢复任务扣除已占空间，不能重复计入全部容量导致恢复被错误拒绝。
- 使用 seekable 文件描述符尽量续传当前文件，断点写入 SQLite。写入数据刷盘与 journal 顺序明确，断点不能领先已落盘字节；进程恢复先核对目标长度再使用断点。
- 每文件复制完成后，独立读取原文件与手机副本计算 SHA-256并比较大小；对手机副本计算 OSS兼容 CRC64。先校验全部，再执行来源删除；删除前再次核对来源 URI / 大小 / 修改时间，发生变化则保留。复制失败、部分成功或校验失败不进行整批删除。目标 `IS_PENDING` 公开时机与删除顺序要保证副本可恢复。
- Android前台通知显示任务进度与暂停，持有必要 wake lock，处理 Android15 dataSync 6 小时超时并停止；用户系统强制停止无法承诺自动恢复，但再次打开可继续。
- 上传成功后删本地目标前再次核对本地大小/校验/归属；删除失败记录已上传、待清理，绝不重复上传。不要删除用户在同一路径创建的新文件。

验收：列表与按钮真实可用；断开读卡器、暂停、杀进程恢复不损坏文件；源/目标同名不覆盖；不足空间拒绝；校验故意损坏时来源保留；整批成功只删除清单文件，额外新增文件、子目录、其他目录和计数器均保留。真实 OTG、共享目录可见性与锁屏续传需要红米 K40 Pro / 一加 9验收，当前没有连接设备，必须明确待用户验收。

### 4. 分片上传、云函数部署及实际链路

- 分片尺寸默认 8 MiB，串行上传控制内存；每片获取只针对该对象/partNumber/uploadId的短期 PUT URL，设置正确 Content-Length，禁止携带 App Authorization 到 OSS URL。
- 服务器返回已有 parts 后按分片编号和长度核对，从手机副本正确偏移读取。网络失败重查 parts，签名过期重新签名，避免重复合并或误删。
- Wi-Fi自动上传使用 WorkManager网络约束与运行时 Wi-Fi再检查；非计费网络不一定是 Wi-Fi，不能只用 UNMETERED 替代 TRANSPORT_WIFI 判断。Wi-Fi到移动网络立即暂停，手动流量许可不持久保存。用户暂停设置与系统中断区分。
- 前台手动任务与 WorkManager任务需共享单实例锁，不能两个引擎同时处理同一 SQLite记录。各自观察持久状态，云端lease是最终多设备协调。
- 云函数北京按量、零预留实例，沿用现有 custom.debian10+Python3.10/Gunicorn方法或验证新的运行时；启动 `app:create_app()`，512 MiB 作为初始值。视频字节不经云函数。
- 云函数身份从 Infisical读取或使用登记的受控部署副本；不要留下旧环境文件或个人管理员会话作生产客户端。API鉴权、访问限额、必要日志脱敏要实现。
- 云端核验完成后标记上传成功、同步共享列表，再删除手机副本；App或网络在任意一步中断，都可在下次重新核验而不丢文件。

验收：实际函数 health、无鉴权请求拒绝、真实 multipart 上传及CRC匹配、错误CRC拒绝、签名下载读回内容一致。Wi-Fi开始/暂停/继续、移动网络手动开始、移动网络中断后不自动恢复、两手机共享列表均有对应证据或明确真机待验收。云函数无常驻预留实例。云端清理测试对象只能删除本次创建的测试前缀，不能扩展删除业务文件。

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

SQLite `files` 初稿：id、内部batch、source、tree、name、size、modified、dest、offset、state、sha、crc、date、error、source_deleted；`batches`仅用于整批校验后删除，尚须补足错误恢复与删除状态。状态机需实现为：waiting → copying → verifying → ready → uploading → uploaded → local cleanup；错误/暂停保留之前可恢复状态，不仅用一条error覆盖事实。

## 凭据与运维定位（只记录名称，不记录真值）

- 私有infra项目：`/Users/mint/workspace/infra`，腾讯云子树 `tencent-cloud/`。先读两层AGENTS；接手先运行 `scripts/check-repo.sh`和 `scripts/status.sh`。当前已有未跟踪`aliyun/`资料，不得误删。
- Infisical `tools`项目ID可从私有infra `services/infisical/README.md`读取；本项目环境`dev`、路径`/dashcam`。已有键：`DASHCAM_MONGO_URI`、`DASHCAM_DB_PASSWORD`、`DASHCAM_DB_NAME`、`DASHCAM_DB_USER`。
- CLI必须显式指定自托管domain，位置见私有infra，不从记忆猜测；导出到内存，不直接把JSON输出到终端。新增密钥用唯一系统临时目录的0600文件 `infisical secrets set --file`，finally清理，再回读布尔比较。
- 本次恢复过本机Infisical会话；恢复材料在私有infra登记的gitignored文件中。该文件的示例`cli_login`有organization-id占位符，不能直接运行；真实组织定位应查现有组织，密码通过`INFISICAL_PASSWORD`进程环境注入，不打印、不放命令参数。组织名是Mint Infra。生产自动化不能依赖此管理员会话。
- 既有数据库主机定位从infra `inventory/hosts.yaml`读取。默认SSH别名`tencent-db`本机不可解析；可以按inventory使用TAT，或现有 `services/video-editing/remote_ops.py` 的临时SSH授权机制（必须finally清理授权）。导入模块时禁用写pycache。
- 云CLI `aliyun`已认证，北京；不得输出完整配置。已有函数`site-monitor-api`配置可作为只读参考，但不能修改其工作负载或复用其消费者权限。
- 飞书应用键在Infisical `tools/dev /site-monitor`：`FEISHU_APP_ID`、`FEISHU_APP_SECRET`、`FEISHU_USER_ID`、`FEISHU_CHAT_ID`。只输出键名，验证对象定位后发送给用户。需要新消费者密钥来源登记；不修改旧应用凭据。
- 后续拟新增：`DASHCAM_APP_TOKEN`、`DASHCAM_API_URL`、`DASHCAM_KEYSTORE_BASE64`、`DASHCAM_KEYSTORE_PASSWORD`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET`、`OSS_ENDPOINT`、`OSS_REGION`；其他生产身份材料按具体选型登记，不随意扩大权限。
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
