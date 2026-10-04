# 0.2.0 界面验证

以下截图由Android35隔离模拟器实际运行App捕获，包含首次使用引导、检测到虚拟可移动存储、空列表、云端列表、设置、导入及上传阶段。

- [连接引导](onboarding-connect.png)、[导入引导](onboarding-import.png)、[上传引导](onboarding-upload.png)
- [检测读卡器后的首页](home-connect.png)
- [待上传空列表](queue-empty.png)、[云端列表](cloud-videos.png)、[设置](settings.png)
- [导入进度](import-progress.png)、[上传进度](upload-progress.png)

进度/云端列表使用设备测试fixture数据，用于验证布局与状态表达，不是真实上传测速。回收站测试实际写入MediaStore、设置IS_TRASHED并确认内容仍可读取；挂载检测使用模拟器虚拟SD卡实际卸载/挂载事件。

真机OTG兼容性、系统相册回收站入口与省电锁屏恢复仍需红米K40 Pro/OnePlus9验收。
