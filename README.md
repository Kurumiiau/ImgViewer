# ImgViewer

一款使用 Kotlin 编写的本地 Android 相册，主要解决部分手机自带相册无法播放 **WEBP 动图** 的问题。

支持 WEBP / GIF 动图播放和暂停续播、照片与图集浏览、双指调整排列密度、多选删除与分享，以及 HSL 调整和裁剪等本地编辑。

从 [Releases](https://github.com/Kurumiiau/ImgViewer/releases/latest) 下载 APK，支持 Android 9 及以上。已有版本可直接覆盖安装。

作者：**Kurumiiau**。应用内「关于」页面可查看署名和[隐私协议](docs/PRIVACY.md)。

构建：用 Android Studio 打开项目，或使用 JDK 17+、Android SDK 36 执行 `./gradlew assembleRelease`。源码不包含签名密钥；自行构建的安装包需要自行签名。
