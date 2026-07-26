# .env 파일을 읽어 환경변수로 로드한 뒤 bootRun을 실행한다.
# 키 값은 .env(gitignore 처리됨)에만 두고, 이 스크립트나 커밋에는 절대 남기지 않는다.
$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            if ($value) { [System.Environment]::SetEnvironmentVariable($name, $value) }
        }
    }
} else {
    Write-Host ".env 파일이 없습니다. .env를 만들고 JWT_SECRET/OPENAI_API_KEY/ANTHROPIC_API_KEY를 채워주세요."
    exit 1
}

& "$PSScriptRoot\gradlew.bat" bootRun
