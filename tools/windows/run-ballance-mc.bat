@echo off
rem Start Ballance with the Minecraft frame passthrough switched on.
rem
rem   producer : Minecraft + the mod in mc\mods\ballance_passthrough-*.jar
rem              (writes %LOCALAPPDATA%\BallanceMcPassthrough\ballance_mc_frame.dat)
rem   consumer : RenderEngines\CKSdlGpuRasterizer.dll (reads that file)
rem
rem Without this variable the game behaves exactly like a plain build; that is the
rem default on purpose, so a broken mailbox can never stop the game from starting.
rem Diagnostics land in Bin\mc-passthrough.log.
setlocal
set "BALLANCE_MC_PASSTHROUGH=1"
cd /d "%~dp0Bin"
start "" "Player.exe"
endlocal
