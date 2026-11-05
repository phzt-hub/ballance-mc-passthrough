@echo off
rem Start Ballance with the passthrough off (plain game, control run for A/B).
setlocal
set "BALLANCE_MC_PASSTHROUGH=0"
cd /d "%~dp0Bin"
start "" "Player.exe"
endlocal
