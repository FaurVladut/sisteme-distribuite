@echo off
rem Porneste 3 noduri locale (n1 coordonator, n2, n3), cate o fereastra pentru fiecare.
rem Cu --no-build sare peste compilare.
setlocal
cd /d "%~dp0.."

if not "%~1"=="--no-build" (
    call mvn -q package || exit /b 1
)

start "n1" java -cp target\classes ro.ubm.sd.node.StorageNode --id n1 --port 7001 --dir data\n1 --coordinator
start "n2" java -cp target\classes ro.ubm.sd.node.StorageNode --id n2 --port 7002 --dir data\n2
start "n3" java -cp target\classes ro.ubm.sd.node.StorageNode --id n3 --port 7003 --dir data\n3

echo Noduri pornite. Clientul: java -cp target\classes ro.ubm.sd.client.Client
