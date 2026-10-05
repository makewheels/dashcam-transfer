# 行车转存

将OTG读卡器中选定目录的视频复制到Android手机，完整校验后清理TF卡，再通过Wi-Fi自动或移动网络手动上传到私有OSS。服务端使用云函数与MongoDB，同一空间的手机共享已上传列表。

Android0.3.0已发布并飞书交付，改为直接进入实时状态首页，提供独立拷贝和上传按钮、历史批次以及系统回收站。完整需求、实施步骤、当前进度和验收方法见 [实施计划](docs/PLAN.md)。

| 目录 | 用途 |
|---|---|
| `android/` | Android客户端，第一版交付目标 |
| `server/` | 云函数控制接口，不转发视频字节 |
| `ios/` | 后续客户端预留 |
| `deploy/` | 临时目录构建、云函数部署、私有OSS发布与真实链路验证 |
| `.github/workflows/` | 自动验证与GitHub OIDC发行 |

所有敏感配置从Infisical读取，不进入Git。自建部署使用自己的私有Bucket、数据库与访问身份；源码不包含个人生产凭据。

开发与生产分别使用独立的private Bucket、RAM用户、云函数和MongoDB数据库。开发Android应用ID带 `.dev`，可与生产版同时安装。

开发者构建（SDK35、Java21、uv）：

```sh
cd server
uv sync --locked
uv run pytest -q
# 本机已安装mongod时可验证独立真实数据库
uv run python tests/run_local_mongo.py
cd ..
python deploy/build_android.py debug
```

发行任务使用版本标签 `vMAJOR.MINOR.PATCH`。Infisical domain/project/identity通过仓库Variables配置；真实敏感值由OIDC读取，不使用GitHub Secrets，不上传个人配置APK到公开GitHub附件。

## 操作流程

0.4.0简化为两页，无底部选项卡：

1. **卡→手机**：插入TF卡，选择视频目录（展示完整绝对路径），点拷贝。完整校验后仍保留源视频，用户可手动点删除；再点下一步。
2. **手机→云端**：点上传，完成云端核验后仍保留手机视频；用户可单独点移入回收站。

没有自动删除、自动上传或拷贝后衔接任务。移动网络上传需确认流量；历史批次和更新在更多菜单。每个按钮只做一件事。

上传完整核验后，手动点击清理才将手机副本移入系统回收站，保留期由系统管理，仍占用空间。Android10不支持此接口时保留手机副本。TF卡的视频仅在整批复制校验完成后手动确认删除，且删除前重新核对手机副本和卡上内容。

新导入的手机与OSS目录使用北京时间 `YYYY-MM-DD_HH-mm-ss` 加8位导入ID，一天多次导入和同秒冲突均隔离，恢复时沿用原目录。

[新版界面截图与验证边界](docs/screenshots/0.4.0/README.md)

隔离模拟器设备验收（先准备Android35虚拟可移动存储，不使用有个人数据的设备）：

```sh
python3 deploy/check_android_device.py emulator-5580 docs/screenshots/0.4.0
```
