@echo off
setlocal DisableDelayedExpansion
rem Launch the Java runtime bundled with this directory, from any working directory.
set "IGV_DIRECTORY=%~dp0"
set "JAVA_HOME=%IGV_DIRECTORY%runtime"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Bundled Java was not found. Extract the entire IGV distribution before launching. 1>&2
    exit /b 1
)

rem Preserve existing BLAST tools on PATH; IGV locates the bundled tools if needed.
if exist "%USERPROFILE%\.igv\java_arguments" (
    "%JAVA_HOME%\bin\java.exe" -showversion -Xmx64g ^
        "@%IGV_DIRECTORY%igv.args" ^
        -Dsamjdk.snappy.disable=true ^
        -Djava.net.preferIPv4Stack=true ^
        -Djava.net.useSystemProxies=true ^
        "@%USERPROFILE%\.igv\java_arguments" ^
        -cp "%IGV_DIRECTORY%lib\*" org.broad.igv.ui.Main %*
) else (
    "%JAVA_HOME%\bin\java.exe" -showversion -Xmx64g ^
        "@%IGV_DIRECTORY%igv.args" ^
        -Dsamjdk.snappy.disable=true ^
        -Djava.net.preferIPv4Stack=true ^
        -Djava.net.useSystemProxies=true ^
        -cp "%IGV_DIRECTORY%lib\*" org.broad.igv.ui.Main %*
)
exit /b %ERRORLEVEL%
