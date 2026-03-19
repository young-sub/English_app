@echo off
setlocal

set TEST_CLASS=com.example.bookhelper.tts.LocalTtsLatencyInstrumentedTest

echo [1/2] Connected devices
adb devices

echo [2/2] Running instrumented TTS latency test
call gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=%TEST_CLASS%

endlocal
