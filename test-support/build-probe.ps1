param([string]$Libraries='C:\Users\artyo\Documents\Codex\nordchat-test-20261004\paper\libraries')
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $MyInvocation.MyCommand.Path
$classes=Join-Path $project ('build\probe-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$deps=Get-ChildItem -LiteralPath $Libraries -Recurse -File -Filter '*.jar' |
 Where-Object {$_.FullName -match 'paper-api.*build.129|adventure|examination|annotations|jspecify|bungeecord-chat'} |
 Select-Object -ExpandProperty FullName
& 'C:\Program Files\Java\jdk-25\bin\javac.exe' --release 25 -encoding UTF-8 -classpath ($deps -join ';') -d $classes (Join-Path $project 'probe\FilterTestProbe.java')
if($LASTEXITCODE-ne0){throw 'Probe compile failed'}
Copy-Item -LiteralPath (Join-Path $project 'probe\plugin.yml') -Destination $classes
& 'C:\Program Files\Java\jdk-25\bin\jar.exe' --create --file (Join-Path $project 'build\FilterTestProbe.jar') -C $classes .
if($LASTEXITCODE-ne0){throw 'Probe packaging failed'}
