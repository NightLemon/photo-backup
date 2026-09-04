@echo off
setlocal
set "GRADLE_VERSION=8.9"
set "GRADLE_SHA256=D725D707BFABD4DFDC958C624003B3C80ACCC03F7037B5122C4B1D0EF15CECAB"
set "BOOTSTRAP=%~dp0.gradle-bootstrap"
set "ARCHIVE=%BOOTSTRAP%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_HOME=%BOOTSTRAP%\gradle-%GRADLE_VERSION%"
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%BOOTSTRAP%" mkdir "%BOOTSTRAP%"
  if not exist "%ARCHIVE%" curl.exe -L --fail --output "%ARCHIVE%" "https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
  if errorlevel 1 exit /b 1
  powershell.exe -NoProfile -Command "if ((Get-FileHash -LiteralPath '%ARCHIVE%' -Algorithm SHA256).Hash -ne '%GRADLE_SHA256%') { throw 'Gradle distribution checksum mismatch.' }"
  if errorlevel 1 exit /b 1
  powershell.exe -NoProfile -Command "Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%BOOTSTRAP%' -Force"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_HOME%\bin\gradle.bat" %*

