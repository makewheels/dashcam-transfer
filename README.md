# 行车转存

将OTG读卡器中选定目录的视频复制到Android手机，完整校验后清理TF卡，再通过Wi-Fi自动或移动网络手动上传到私有OSS。服务端使用云函数与MongoDB，同一空间的手机共享已上传列表。

Android0.3.0改为直接进入实时状态首页，提供独立拷贝和上传按钮、历史批次以及系统回收站。完整需求、实施步骤、当前进度和验收方法见 [实施计划](docs/PLAN.md)。

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

打开即进入首页，常驻显示网络、OTG/TF卡、所选文件夹和空间。「拷贝到手机」「上传云端」分别操作；旁边的提示随连接和任务状态变化，不要求先完成开场引导。底部「首页」「待上传」「批次」「云端」「设置」分开显示。批次按导入时间查看本机与共享云端历史，手机副本进入回收站后记录仍保留。

上传完整核验后，手机副本移入系统回收站，保留期由系统管理，仍占用空间。Android10不支持此接口时保留手机副本。TF卡的视频仅在整批复制校验完成后按设置删除。

新导入的手机与OSS目录使用北京时间 `YYYY-MM-DD_HH-mm-ss` 加8位导入ID，一天多次导入和同秒冲突均隔离，恢复时沿用原目录。

[新版界面截图与验证边界](docs/screenshots/0.3.0/README.md)

隔离模拟器设备验收（先准备Android35虚拟可移动存储，不使用有个人数据的设备）：

```sh
python3 deploy/check_android_device.py emulator-5580 docs/screenshots/0.3.0
```
