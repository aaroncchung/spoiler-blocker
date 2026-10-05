<#
.SYNOPSIS
    Drives the Phase 0 probe app (SB Probe) from a PC. See docs/PROBE.md.

.DESCRIPTION
    Run it from the repository folder, in Windows PowerShell, with the phone
    connected by USB and USB debugging switched on.

    Every command prints the adb command it runs, so the plain adb form can be
    learned from it.

    Anything this script copies off the phone goes into the "captures" folder,
    which git ignores. Dumps and screenshots contain what was on the screen.
    Never move them anywhere that git tracks.

.EXAMPLE
    .\tools\probe.ps1 status
.EXAMPLE
    .\tools\probe.ps1 dump youtube-home
.EXAMPLE
    .\tools\probe.ps1 mechanism sc-display
.EXAMPLE
    .\tools\probe.ps1 log 100
#>
param(
    [Parameter(Position = 0)]
    [string]$Command = 'help',

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @(),

    # Only needed when more than one phone or emulator is connected.
    # "adb devices" lists the serial numbers.
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
# Screen text can be in any language. Without this, Windows PowerShell garbles it.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Package = 'io.github.aaroncchung.spoilerblocker.probe'
$Receiver = "$Package/.TriggerReceiver"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Captures = Join-Path $RepoRoot 'captures'

function Find-Adb {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($sdk) {
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    throw 'adb.exe was not found. Install Android Studio (or the SDK Platform-Tools), then try again. See docs/PROBE.md.'
}

$Adb = Find-Adb
$Target = @()
if ($Serial) { $Target = @('-s', $Serial) }

# Runs adb and returns its output as text lines.
function Invoke-Adb {
    param([string[]]$Arguments, [switch]$Show)
    if ($Show) { Write-Host ("> adb " + ($Arguments -join ' ')) -ForegroundColor DarkGray }
    $output = & $Adb @Target @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed (exit code $LASTEXITCODE). Is the phone connected and USB debugging allowed? Try: adb devices"
    }
    return $output
}

# Saves adb's raw output to a file. PowerShell's own ">" would re-encode the
# bytes and ruin images, so the bytes are copied straight from adb.
function Save-AdbOutput {
    param([string[]]$Arguments, [string]$Path)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Adb
    $start.Arguments = (($Target + $Arguments) -join ' ')
    $start.RedirectStandardOutput = $true
    $start.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($start)
    $file = [System.IO.File]::Create($Path)
    try {
        $process.StandardOutput.BaseStream.CopyTo($file)
    } finally {
        $file.Close()
    }
    $process.WaitForExit()
}

# Sends one trigger to the probe. $Extras are "am broadcast" extras such as "--es", "name", "wm-move".
function Send-Probe {
    param([string]$Action, [string[]]$Extras = @())
    $arguments = @('shell', 'am', 'broadcast', '-n', $Receiver, '-a', "sbprobe.$Action") + $Extras
    Invoke-Adb -Arguments $arguments -Show | Out-Null
}

function Convert-OnOff {
    param([string]$Value)
    switch ($Value) {
        'on' { return 'true' }
        'off' { return 'false' }
        default { throw "Say 'on' or 'off', not '$Value'." }
    }
}

# The files in one folder of the probe's private storage. Empty if the folder does not exist yet.
function Get-PrivateFiles {
    param([string]$Folder)
    # "2>/dev/null" runs on the phone: a missing folder then gives no output instead of an error.
    $lines = & $Adb @Target shell "run-as $Package sh -c 'ls files/$Folder 2>/dev/null'"
    if (-not $lines) { return @() }
    return @($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Format-OutlineLine {
    param([int]$Depth, [string]$Class, [string]$Id, [string]$Text, [string]$Desc, [string]$Bounds)
    # Keep only the last part of long names: "android.widget.TextView" -> "TextView".
    $shortClass = ($Class -split '\.')[-1]
    $shortId = ''
    if ($Id) { $shortId = ' #' + ($Id -split '/')[-1] }
    $line = ('{0,2} {1}{2}{3}' -f $Depth, (' ' * $Depth), $shortClass, $shortId)
    if ($Text) { $line += ' text="' + $Text + '"' }
    if ($Desc) { $line += ' desc="' + $Desc + '"' }
    return "$line $Bounds"
}

# Prints the nodes that carry text from a "uiautomator dump" XML file.
function Show-XmlOutline {
    param([string]$Path)
    $xml = New-Object System.Xml.XmlDocument
    $xml.Load($Path)
    $script:total = 0
    $script:shown = 0
    function Walk {
        param($Node, [int]$Depth)
        foreach ($child in $Node.ChildNodes) {
            if ($child.Name -ne 'node') { continue }
            $script:total++
            $text = $child.GetAttribute('text')
            $desc = $child.GetAttribute('content-desc')
            if ($text -or $desc) {
                $script:shown++
                Format-OutlineLine -Depth $Depth -Class $child.GetAttribute('class') -Id $child.GetAttribute('resource-id') `
                    -Text $text -Desc $desc -Bounds $child.GetAttribute('bounds')
            }
            Walk -Node $child -Depth ($Depth + 1)
        }
    }
    Walk -Node $xml.DocumentElement -Depth 0
    Write-Host "($script:shown of $script:total nodes carry text. Columns: depth, class, #view id, text, description, bounds.)"
}

# Prints the nodes that carry text from one of the probe's own tree dumps.
function Show-JsonOutline {
    param([string]$Path)
    $total = 0
    $shown = 0
    # The file has one node per line. Reading it line by line avoids the size
    # limit of ConvertFrom-Json in Windows PowerShell.
    foreach ($line in [System.IO.File]::ReadLines($Path, [System.Text.Encoding]::UTF8)) {
        if (-not $line.StartsWith('{"i":')) { continue }
        $total++
        if ($line -notmatch '"(text|desc)":') { continue }
        $node = $line.TrimEnd(',') | ConvertFrom-Json
        $shown++
        $b = $node.bounds
        Format-OutlineLine -Depth $node.depth -Class $node.class -Id $node.id -Text $node.text -Desc $node.desc `
            -Bounds ("[{0},{1}][{2},{3}]" -f $b[0], $b[1], $b[2], $b[3])
    }
    Write-Host "($shown of $total nodes carry text. Columns: depth, class, #view id, text, description, bounds.)"
}

function Show-Outline {
    param([string]$Path)
    if ($Path -like '*.xml') { Show-XmlOutline -Path $Path } else { Show-JsonOutline -Path $Path }
}

# E1: captures the screen in front twice, once as the probe's service sees it
# and once with Android's own "uiautomator dump".
function Invoke-Dump {
    param([string]$Label)
    if (-not $Label) { throw 'Give the screen a name, for example: .\tools\probe.ps1 dump youtube-home' }
    $Label = $Label -replace '[^A-Za-z0-9_-]', '-'
    New-Item -ItemType Directory -Force -Path $Captures | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

    # The service's view first: uiautomator switches every accessibility
    # service off for the moment it runs, the probe's included.
    Write-Host "`n== What the probe's accessibility service sees ==" -ForegroundColor Cyan
    $before = Get-PrivateFiles -Folder 'dumps'
    Send-Probe -Action 'DUMP_TREE' -Extras @('--es', 'label', $Label)
    $newFile = $null
    foreach ($attempt in 1..10) {
        Start-Sleep -Milliseconds 500
        $newFile = Get-PrivateFiles -Folder 'dumps' | Where-Object { $before -notcontains $_ } | Select-Object -First 1
        if ($newFile) { break }
    }
    if ($newFile) {
        $local = Join-Path $Captures "$stamp-$Label.service.capture.json"
        Save-AdbOutput -Arguments @('exec-out', 'run-as', $Package, 'cat', "files/dumps/$newFile") -Path $local
        Write-Host "Saved $local"
        Show-JsonOutline -Path $local
    } else {
        Write-Warning 'No dump appeared. Is the SB Probe accessibility service switched on, and is an app (not the notification shade) in front?'
    }

    Write-Host "`n== What 'uiautomator dump' sees ==" -ForegroundColor Cyan
    $remote = '/sdcard/sbprobe-window.xml'
    $result = Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', $remote) -Show
    if (($result -join ' ') -match 'dumped to') {
        $local = Join-Path $Captures "$stamp-$Label.uiautomator.xml"
        Save-AdbOutput -Arguments @('exec-out', 'cat', $remote) -Path $local
        Write-Host "Saved $local"
        Show-XmlOutline -Path $local
    } else {
        Write-Warning "uiautomator could not dump this screen (it gives up on screens that never stop moving). Its answer: $result"
    }
    # Do not leave screen text on the phone's shared storage.
    & $Adb @Target shell rm -f $remote | Out-Null
    Write-Host "`nNote: uiautomator switched the probe's accessibility service off and on again. The probe log shows it as a disconnect."
}

# Copies the log, the tree dumps and the screenshots into captures\probe-<time>\.
function Invoke-Pull {
    $folder = Join-Path $Captures ('probe-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Save-AdbOutput -Arguments @('exec-out', 'run-as', $Package, 'cat', 'files/probe.log') -Path (Join-Path $folder 'probe.log')
    $count = 1
    foreach ($sub in @('dumps', 'screenshots')) {
        foreach ($name in (Get-PrivateFiles -Folder $sub)) {
            Save-AdbOutput -Arguments @('exec-out', 'run-as', $Package, 'cat', "files/$sub/$name") -Path (Join-Path $folder $name)
            $count++
        }
    }
    Write-Host "Copied $count file(s) to $folder"
    Write-Host 'This folder is ignored by git. Keep it that way: it holds what was on your screen.'
}

function Show-Status {
    Write-Host 'Devices adb can see:'
    & $Adb devices
    $accessibility = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
    $listeners = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')
    $installed = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', $Package)
    Write-Host ('Probe installed:        ' + [bool]($installed -match $Package))
    Write-Host ('Accessibility service:  ' + $(if ("$accessibility" -match $Package) { 'ON in Settings' } else { 'off' }))
    Write-Host ('Notification access:    ' + $(if ("$listeners" -match $Package) { 'ON in Settings' } else { 'off' }))
    Write-Host 'The last lines of the probe log:'
    & $Adb @Target shell run-as $Package tail -n 5 files/probe.log
}

function Show-Help {
    Write-Host @'
Usage: .\tools\probe.ps1 <command> [arguments] [-Serial <serial>]

  status                     is the phone connected, are the services on
  log [lines]                the last lines of the probe log (default 60)
  pull                       copy the log, dumps and screenshots into captures\

E1  dump <label>             save both tree dumps of the screen in front, print outlines
    outline <file>           print the outline of a saved dump again
E2  repick                   move the box to the next item of the list
    nextlist                 move the box to another scrollable part of the screen
    mechanism [name]         wm-move | wm-canvas | sc-display | sc-window (no name: the next one)
    tracking events|poll     what makes the box move
    box on|off               show or hide the box
    strip on|off             the film strip (clock, touch and scroll markers)
    summary [reset]          log the timing summary; "reset" also starts a fresh count
E3  touchwatch on|off        the outside-touch watcher
    motionlisten             take the touch screen's events for 10 seconds
E4  notify [delay-ms]        post the test notification (default: after 3000 ms)
    cancel on|off [package]  dismiss notifications from <package>, or stop doing so
E5  screenshot               per-window screenshot, checked for the box
    ratetest                 find the screenshot rate limit (takes 15 seconds)
E6  runstart [label]         mark the start of a run in the log
'@
}

switch ($Command) {
    'help' { Show-Help }
    'status' { Show-Status }
    'log' {
        $lines = 60
        if ($Rest.Count -ge 1) { $lines = [int]$Rest[0] }
        Invoke-Adb -Arguments @('shell', 'run-as', $Package, 'tail', '-n', "$lines", 'files/probe.log') -Show
    }
    'pull' { Invoke-Pull }
    'dump' { Invoke-Dump -Label ($Rest | Select-Object -First 1) }
    'outline' {
        if ($Rest.Count -lt 1) { throw 'Which file? For example: .\tools\probe.ps1 outline captures\x.uiautomator.xml' }
        Show-Outline -Path (Resolve-Path $Rest[0]).Path
    }
    'repick' { Send-Probe -Action 'REPICK' }
    'nextlist' { Send-Probe -Action 'NEXT_LIST' }
    'mechanism' {
        if ($Rest.Count -ge 1) { Send-Probe -Action 'MECHANISM' -Extras @('--es', 'name', $Rest[0]) } else { Send-Probe -Action 'MECHANISM' }
    }
    'tracking' {
        if ($Rest.Count -lt 1) { throw "Say 'events' or 'poll'." }
        Send-Probe -Action 'TRACKING' -Extras @('--es', 'mode', $Rest[0])
    }
    'box' { Send-Probe -Action 'BOX' -Extras @('--ez', 'on', (Convert-OnOff ($Rest | Select-Object -First 1))) }
    'strip' { Send-Probe -Action 'FILM_STRIP' -Extras @('--ez', 'on', (Convert-OnOff ($Rest | Select-Object -First 1))) }
    'summary' {
        if ($Rest -contains 'reset') { Send-Probe -Action 'E2_SUMMARY' -Extras @('--ez', 'reset', 'true') } else { Send-Probe -Action 'E2_SUMMARY' }
        Start-Sleep -Milliseconds 500
        Invoke-Adb -Arguments @('shell', 'run-as', $Package, 'tail', '-n', '12', 'files/probe.log') | Where-Object { $_ -match ' E2 summary ' }
    }
    'touchwatch' { Send-Probe -Action 'TOUCH_WATCH' -Extras @('--ez', 'on', (Convert-OnOff ($Rest | Select-Object -First 1))) }
    'motionlisten' { Send-Probe -Action 'MOTION_LISTEN' }
    'notify' {
        $delay = '3000'
        if ($Rest.Count -ge 1) { $delay = $Rest[0] }
        Send-Probe -Action 'POST_TEST' -Extras @('--ei', 'delay_ms', $delay)
    }
    'cancel' {
        $extras = @('--ez', 'on', (Convert-OnOff ($Rest | Select-Object -First 1)))
        if ($Rest.Count -ge 2) { $extras += @('--es', 'package', $Rest[1]) }
        Send-Probe -Action 'CANCEL' -Extras $extras
    }
    'screenshot' {
        Send-Probe -Action 'SCREENSHOT'
        Start-Sleep -Seconds 2
        Invoke-Adb -Arguments @('shell', 'run-as', $Package, 'tail', '-n', '8', 'files/probe.log') | Where-Object { $_ -match ' E5 ' }
    }
    'ratetest' {
        Send-Probe -Action 'RATE_TEST'
        Write-Host 'Running for about 15 seconds...'
        Start-Sleep -Seconds 18
        Invoke-Adb -Arguments @('shell', 'run-as', $Package, 'tail', '-n', '14', 'files/probe.log') | Where-Object { $_ -match ' E5 rate-test ' }
    }
    'runstart' {
        if ($Rest.Count -ge 1) { Send-Probe -Action 'RUN_START' -Extras @('--es', 'label', $Rest[0]) } else { Send-Probe -Action 'RUN_START' }
    }
    default {
        Write-Warning "Unknown command '$Command'."
        Show-Help
    }
}
