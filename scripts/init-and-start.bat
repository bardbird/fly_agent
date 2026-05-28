@echo off
REM Fly Agent 平台初始化和启动脚本 (Windows)

echo =========================================
echo Fly Agent 平台 - 初始化和启动
echo =========================================
echo.

REM 设置JAVA_HOME (根据实际路径修改)
set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%

REM 1. 初始化MySQL数据库
echo 步骤 1/3: 初始化MySQL数据库...
echo 请输入MySQL密码 (root/123456789):
mysql -u root -p123456789 < scripts\init-mysql.sql

if %errorlevel% equ 0 (
    echo √ MySQL数据库初始化成功
) else (
    echo × MySQL数据库初始化失败
    pause
    exit /b 1
)

echo.

REM 2. 编译项目
echo 步骤 2/3: 编译项目...
call mvn clean package -DskipTests

if %errorlevel% equ 0 (
    echo √ 项目编译成功
) else (
    echo × 项目编译失败
    pause
    exit /b 1
)

echo.

REM 3. 启动应用
echo 步骤 3/3: 启动应用...
echo 正在启动 Fly Agent 平台...
cd fly-agent-server
java -jar target\fly-agent-server-1.0.0-SNAPSHOT.jar

pause
