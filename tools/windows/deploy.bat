@echo off
rem Copy the freshly installed stage into the playable directory.
rem
rem   deploy.bat [stage] [play] [--reset-ini]
rem
rem The stage is D:\bmc-build\stage by default, the playable directory D:\bmc-play.
rem --reset-ini deletes Bin\Minecraft.ini so the build's own defaults apply again
rem (the in-game panel writes that file, so it survives redeploys on purpose).
setlocal enabledelayedexpansion
set "STAGE=%~1"
if "%STAGE%"=="" set "STAGE=D:\bmc-build\stage"
set "PLAY=%~2"
if not "%PLAY%"=="--reset-ini" goto :have_play
set "PLAY="
shift
:have_play
if "%PLAY%"=="" set "PLAY=D:\bmc-play"
set "RESET=0"
for %%A in (%*) do if /i "%%A"=="--reset-ini" set "RESET=1"

echo == stage %STAGE% -^> play %PLAY%
for %%D in (Bin BuildingBlocks Managers Plugins RenderEngines) do (
    robocopy "%STAGE%\%%D" "%PLAY%\%%D" /E /NFL /NDL /NJH /NJS /NP >nul
)

rem The stage ships Player.ini with SkipOpening = 0; the playable copy wants the
rem opening skipped or every launch waits a minute.
powershell -NoProfile -Command "$p = '%PLAY%\Bin\Player.ini'; if (Test-Path $p) { (Get-Content $p) -replace '^SkipOpening = .*', 'SkipOpening = 1' | Set-Content -Encoding ASCII $p }"

if "%RESET%"=="1" (
    if exist "%PLAY%\Bin\Minecraft.ini" del /q "%PLAY%\Bin\Minecraft.ini"
    echo == reset Bin\Minecraft.ini
)
echo deploy ok
endlocal
