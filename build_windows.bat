@echo off
chcp 65001 >nul
REM ===== 一键构建 Windows EXE =====
REM 前置：Windows 10/11、Python 3.10+、Visual Studio 2022（含“使用 C++ 的桌面开发”）
REM 在本文件所在目录打开命令行，直接双击运行即可

cd /d "%~dp0"

echo ==^> 安装依赖
python -m pip install --upgrade pip
python -m pip install -r requirements.txt

echo ==^> 构建 Windows 可执行文件（首次会自动下载 Flutter SDK，约 1.5GB）
flet build windows --yes ^
  --project photobox ^
  --product "光影相册" ^
  --org "cn.photobox" ^
  --bundle-id "cn.photobox.app" ^
  --description "本地照片管理与清理工具" ^
  --build-version "1.0.0" ^
  -o build

echo ==^> 完成，EXE 位于 build\windows\ 目录
dir /s /b build\windows\*.exe 2>nul
pause
