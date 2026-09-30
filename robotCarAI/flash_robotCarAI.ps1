param(
    [string]$Port = "COM11",
    [int]$Baud = 460800,
    [string]$PythonPath = "C:\Users\xiaoying\.espressif\python_env\idf6.1_py3.14_env\Scripts\python.exe"
)

$ErrorActionPreference = "Stop"
$FirmwarePath = Join-Path $PSScriptRoot "build\merged-binary.bin"

if (-not (Test-Path -LiteralPath $PythonPath)) {
    throw "Không tìm thấy Python của ESP-IDF: $PythonPath"
}
if (-not (Test-Path -LiteralPath $FirmwarePath)) {
    throw "Không tìm thấy firmware: $FirmwarePath. Hãy build firmware trước."
}

Write-Host "Đang nạp Robot Car AI vào $Port ở $Baud baud..."
& $PythonPath -m esptool `
    --chip esp32s3 `
    --port $Port `
    --baud $Baud `
    --before default-reset `
    --after hard-reset `
    write-flash 0x0 $FirmwarePath

if ($LASTEXITCODE -ne 0) {
    throw "Nạp firmware thất bại (mã lỗi $LASTEXITCODE)."
}

Write-Host "Nạp xong. Mạch đã được reset và sẽ khởi động lại."
