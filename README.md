# 行车转存

将OTG读卡器中选定目录的视频复制到Android手机，完整校验后清理TF卡，再通过Wi-Fi自动或移动网络手动上传到私有OSS。服务端使用云函数与MongoDB，同一空间的手机共享已上传列表。

当前处于开发阶段，服务端两环境与实际私有OSS链路已验证，发行APK正在准备。完整需求、实施步骤、当前进度和验收方法见 [实施计划](docs/PLAN.md)。

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
