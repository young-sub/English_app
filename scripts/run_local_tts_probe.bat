@echo off
setlocal

if "%~1"=="" (
  set TEXT=This is a standalone local TTS probe.
) else (
  set TEXT=%~1
)

echo [1/2] Connected devices
adb devices

echo [2/2] Running standalone local TTS probe
call gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.bookhelper.tts.LocalTtsStandaloneProbeInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.localTtsText="%TEXT%" -Pandroid.testInstrumentationRunnerArguments.localTtsSpeed=1.0 -Pandroid.testInstrumentationRunnerArguments.localTtsTimeoutMs=120000

endlocal
