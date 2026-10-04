# 0.3.0 界面检查

Android35隔离模拟器实际截图。打开直接到首页，无启动引导弹窗。

- [首页实时状态与两个按钮](home-status.png)
- [读卡器未连接状态](home-disconnected.png)
- [待上传空列表](queue-empty.png)
- [拷贝进度](import-progress.png) / [上传进度](upload-progress.png)
- [历史批次](batch-history.png) / [批次明细](batch-detail.png)
- [云端列表](cloud-videos.png) / [设置](settings.png)

进度、历史与云端截图使用测试fixture，仅验证排版和操作入口，不是真实视频测速。设备测试另外验证真实MediaStore回收站、SQLite迁移及虚拟可移动存储实际卸载/挂载。模拟器不能替代红米K40 Pro/一加9的真实OTG、锁屏与省电策略验收。
