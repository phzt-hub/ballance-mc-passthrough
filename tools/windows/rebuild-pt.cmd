@echo off
rem Rebuild the passthrough Player.exe on this Windows box and deploy it into the
rem playable runtime (J:\bmc-project\runtime).
rem
rem   configure -> build -> copy stage into the runtime
rem
rem Configure is part of the sequence on purpose: adding a source file to a
rem CMakeLists without re-running configure leaves the old build.ninja behind and
rem the link then fails on the missing symbol (docs/windows-build.md, pitfall 2).
rem
rem Deploy never touches the runtime's own configuration: only .exe/.dll land in
rem Bin\, so Player.ini / Minecraft.ini / logs survive a rebuild.
rem
rem   schtasks /run /tn bmc-pt
setlocal
set "PATH=C:\tools\cmake\cmake-3.31.12-windows-x86_64\bin;%PATH%"
set "LOG=D:\bmc-build\rebuild-pt.log"
set "SRC=D:\bmc-build\Ballanced"
set "STAGE=%SRC%\build\windows-x64-runtime\stage"
set "PLAY=J:\bmc-project\runtime"

echo START %DATE% %TIME% > "%LOG%"
cd /d "%SRC%"

echo == configure >> "%LOG%"
cmake --preset windows-x64-runtime -DCMAKE_PREFIX_PATH=C:/tools/sdl3/SDL3-3.4.20 >> "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
echo CONFIGURE_EXIT=%RC% >> "%LOG%"
if not "%RC%"=="0" goto fail

echo == build >> "%LOG%"
cmake --build --preset windows-x64-runtime-stage-release --parallel 12 >> "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
echo BUILD_EXIT=%RC% >> "%LOG%"
if not "%RC%"=="0" goto fail

echo == deploy >> "%LOG%"
robocopy "%STAGE%\Bin" "%PLAY%\Bin" Player.exe SDL3.dll CK2.dll VxMath.dll /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
set "D1=%ERRORLEVEL%"
robocopy "%STAGE%\RenderEngines" "%PLAY%\RenderEngines" /E /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
set "D2=%ERRORLEVEL%"
robocopy "%STAGE%\BuildingBlocks" "%PLAY%\BuildingBlocks" /E /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
set "D3=%ERRORLEVEL%"
robocopy "%STAGE%\Managers" "%PLAY%\Managers" /E /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
set "D4=%ERRORLEVEL%"
robocopy "%STAGE%\Plugins" "%PLAY%\Plugins" /E /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
set "D5=%ERRORLEVEL%"
echo DEPLOY_EXITS=%D1%,%D2%,%D3%,%D4%,%D5% >> "%LOG%"
dir "%PLAY%\Bin\Player.exe" >> "%LOG%" 2>&1
echo DONE %DATE% %TIME% >> "%LOG%"
endlocal
exit /b 0

:fail
dir "%PLAY%\Bin\Player.exe" >> "%LOG%" 2>&1
echo FAILED %DATE% %TIME% >> "%LOG%"
endlocal
exit /b 1
