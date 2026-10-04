# 用 GitHub Actions 免费编译 APK（无需本地环境）

GitHub 提供的云端构建机器**可以正常访问 Google / GitHub 的下载源**，
所以不用自己装 Flutter 和 Android SDK，也不用配置镜像。

## 三步拿到 APK

### 1. 上传代码到 GitHub

在 GitHub 新建一个仓库（比如 `photobox`），把 `photobox` 目录下的所有文件传上去。
如果本地装了 git：

```bash
cd photobox
git init
git add .
git commit -m "光影相册 PhotoBox"
git remote add origin https://github.com/你的用户名/photobox.git
git push -u origin main
```

也可以直接在 GitHub 网页上用 **Add file → Upload files** 把文件拖进去。

> 注意：本仓库已包含 `.github/workflows/build-apk.yml`，上传时不要漏掉
> （网页上传不会自动包含以点开头的目录，建议用 git 命令行）。

### 2. 触发构建

进入仓库页面 → 点顶部 **Actions** 标签 → 左侧选「构建 Android APK」
→ 右侧点 **Run workflow**（绿色的按钮）→ 再点一次确认。

### 3. 下载 APK

构建开始后大约 **15~30 分钟**。跑完后在同一个页面点最新的那次运行，
在页面底部的 **Artifacts** 区域下载 `光影相册-APK`，解压即可得到 `.apk` 文件。

直接安装到手机即可（首次打开会提示授予照片权限）。

## 也可以推标签自动构建

```bash
git tag v1.0.0
git push origin v1.0.0
```

推送 `v` 开头的标签会自动触发构建，适合后续发版本。

## 常见问题

**Q：Actions 页面是空的？**
确认 `.github/workflows/build-apk.yml` 上传成功了。在仓库首页地址栏后面加
`/actions` 可以直接进入。

**Q：构建失败了？**
点进失败的运行，展开红色步骤看日志。最常见的是权限问题——
仓库 **Settings → Actions → General → Workflow permissions** 选
「Read and write permissions」。

**Q：免费额度够吗？**
GitHub 免费账户每月有 2000 分钟 Actions 额度，单次构建约 15~30 分钟，
完全够用。
