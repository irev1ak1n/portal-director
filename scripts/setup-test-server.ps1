<#
Creates an isolated, localhost-only Paper 26.3 test server next to the repository (..\portal-director-server)
and installs the built plugin jar. Offline mode is on ON PURPOSE so test bots can join, and the server only
listens on 127.0.0.1. Never copy these settings to a server other people can reach.

  powershell -File scripts\setup-test-server.ps1 -AcceptEula
  (build the plugin first:  gradlew build)
#>
param(
    [string]$Dir = (Join-Path $PSScriptRoot '..\..\portal-director-server'),
    [int]$Port = 25590,
    [switch]$AcceptEula
)
$ErrorActionPreference = 'Stop'
$jarName = 'paper-26.3-231.jar'
$jarUrl = 'https://fill-data.papermc.io/v1/objects/a836a016be7ac52352eb3a1efb6857bb42ee73a6c78d46e292f7e87742edb0e6/paper-26.3-231.jar'
$jarSha256 = 'a836a016be7ac52352eb3a1efb6857bb42ee73a6c78d46e292f7e87742edb0e6'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$plugin = Get-ChildItem (Join-Path $repo 'build\libs') -Filter 'PortalDirector-*.jar' | Select-Object -First 1

if (-not $plugin) { throw 'Build the plugin first (gradlew build). No jar in build\libs.' }
if (-not $AcceptEula) { throw 'Pass -AcceptEula to confirm you accept the Minecraft EULA (https://aka.ms/MinecraftEULA).' }

New-Item -ItemType Directory -Force "$Dir\plugins" | Out-Null
$Dir = Resolve-Path $Dir

$jar = Join-Path $Dir $jarName
if (-not (Test-Path $jar)) {
    Write-Host "Downloading $jarName from papermc.io"
    Invoke-WebRequest $jarUrl -OutFile $jar
}
if ((Get-FileHash $jar -Algorithm SHA256).Hash.ToLower() -ne $jarSha256) { Remove-Item $jar; throw 'Paper jar checksum mismatch' }

Get-ChildItem "$Dir\plugins" -Filter 'PortalDirector-*.jar' | Remove-Item -Force
Copy-Item $plugin.FullName "$Dir\plugins\" -Force
'eula=true' | Set-Content "$Dir\eula.txt"
"settings:`n  connection-throttle: -1" | Set-Content "$Dir\bukkit.yml"
"advancements:`n  disable-saving: true`n  disabled:`n  - '*'" | Set-Content "$Dir\spigot.yml"

@"
# ISOLATED TEST SERVER. Offline mode, localhost only. Never expose this publicly.
server-ip=127.0.0.1
server-port=$Port
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
motd=PortalDirector test (localhost only)
max-players=40
gamemode=survival
difficulty=peaceful
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
spawn-protection=0
view-distance=2
simulation-distance=2
level-seed=portaltest
generate-structures=false
enable-rcon=false
enable-query=false
pause-when-empty-seconds=-1
"@ | Set-Content "$Dir\server.properties"

@'
@echo off
cd /d "%~dp0"
rem Starts the test server on Java 25 and keeps the console open. Type "stop" to shut down cleanly.
set JAVA=java
for /d %%d in ("%USERPROFILE%\.gradle\jdks\eclipse_adoptium-25*") do set JAVA=%%d\bin\java.exe
if defined JAVA25_HOME set JAVA=%JAVA25_HOME%\bin\java.exe
"%JAVA%" -Xms512M -Xmx2G -XX:+UseG1GC -jar paper-26.3-231.jar --nogui
'@ | Set-Content "$Dir\start.bat"

Write-Host "Test server ready in $Dir"
Write-Host "Start it with: $Dir\start.bat   (connect to 127.0.0.1:$Port, then run /op yourself in the console)"
