# 0.4.0 两页手动流程

Android35隔离模拟器实际截图，无底部导航。

- [第一页面：拷贝、删除、下一步](home-status.png)
- [完整目录绝对路径](folder-path.png) / [未连接状态](home-disconnected.png)
- [第二页面：上传、手机回收站](queue-empty.png)
- [拷贝进度](import-progress.png) / [上传进度](upload-progress.png)
- [更多入口的历史批次](batch-history.png) / [历史明细](batch-detail.png)

路径与进度截图使用界面fixture；完整路径展示不代表该目录实际获得SAF读写授权。独立设备测试实际在debug专用隔离文档提供方完成小文件复制、内容校验、手动删除前再次核对，证明旧自动删除字段不再触发删除，手机内容变化时卡上原文件保留。真实MediaStore回收站测试证明上传操作不会清理手机，手动回收站操作后字节仍可读取。测试provider只包含在debug APK，生产APK不含此组件。

虚拟可移动存储卸载/挂载与SQLite迁移也有设备测试；模拟器不能替代红米K40 Pro/一加9真实OTG、文件系统、省电锁屏与回收站入口验收。
