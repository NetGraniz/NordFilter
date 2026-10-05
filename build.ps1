param([string]$ServerPath = 'C:\Users\artyo\Documents\Codex\nordchat-test-20261004\paper')
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $projectPath 'src\main\java'
$resourcePath = Join-Path $projectPath 'src\main\resources'
$buildPath = Join-Path $projectPath 'build'
$workPath = Join-Path $buildPath ('work-' + [Guid]::NewGuid().ToString('N'))
$classesPath = Join-Path $workPath 'classes'
$testClasses = Join-Path $workPath 'test-classes'
$outputPath = Join-Path $buildPath 'NordFilter-1.1.0.jar'
$javaPath = 'C:\Program Files\Java\jdk-25\bin'
if (-not (Test-Path -LiteralPath $ServerPath)) { throw 'Local Paper fixture not found' }
$localRoot = 'C:\Users\artyo\Documents\Codex\'
$resolvedServer = (Resolve-Path -LiteralPath $ServerPath).Path
if (-not $resolvedServer.StartsWith($localRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build requires isolated LOCAL server libraries' }
New-Item -ItemType Directory -Force -Path $classesPath,$testClasses | Out-Null
$paperApi = Get-ChildItem -LiteralPath (Join-Path $ServerPath 'libraries\io\papermc\paper\paper-api') -Recurse -Filter '*.jar' |
    Where-Object Name -Like 'paper-api-26.2.build.*-stable.jar' |
    Sort-Object { [int]([regex]::Match($_.Name,'build\.(\d+)').Groups[1].Value) } -Descending |
    Select-Object -First 1
if (-not $paperApi) { throw 'Local Paper API 26.2 not found' }
$dependencies = @($paperApi.FullName)
$dependencies += Get-ChildItem -LiteralPath (Join-Path $ServerPath 'libraries') -Recurse -File -Filter '*.jar' |
    Where-Object { $_.FullName -match 'adventure|examination|annotations|snakeyaml\\2.6\\|bungeecord-chat|guava|jspecify' } |
    Select-Object -ExpandProperty FullName
$classpath = ($dependencies | Sort-Object -Unique) -join ';'
$sources = Get-ChildItem -LiteralPath $sourcePath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath $classpath -d $classesPath $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item -Path (Join-Path $resourcePath '*') -Destination $classesPath -Recurse -Force
$tests = @(Get-ChildItem -LiteralPath (Join-Path $projectPath 'src\test\java') -Recurse -Filter '*.java' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
if ($tests.Count -gt 0) {
    & (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath ($classesPath + ';' + $classpath) -d $testClasses $tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & (Join-Path $javaPath 'java.exe') -ea -classpath ($testClasses + ';' + $classesPath + ';' + $classpath) com.nordfjell.nordfilter.FilterRegressionTest
    if ($LASTEXITCODE -ne 0) { throw 'Moderation regression tests failed' }
}
& (Join-Path $javaPath 'jar.exe') --create --file $outputPath -C $classesPath .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
Write-Output ('API=' + $paperApi.Name)
Write-Output ('SHA256=' + (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash)
Write-Output $outputPath
