# Delete source files the Windows copy has but the Linux tree does not.
#
# tar extraction only ever writes files, so a .cpp deleted on Linux survives on
# Windows and - if a CMakeLists still globbed it, or an old build.ninja still
# listed it - the next build fails on a file nobody can find. The keep list is
# generated next to this script on every sync; anything under Source\ that is a
# C/C++ source and is NOT in the list gets removed and printed.
#
# Windows never owns source (see windows-sync.sh), so this cannot delete anybody's
# work in progress.
$ErrorActionPreference = 'Stop'
$src  = 'D:\bmc-build\Ballanced\Source'
$list = 'D:\bmc-build\keep-source.txt'

$keep = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($line in [System.IO.File]::ReadAllLines($list)) {
    $t = $line.Trim()
    if ($t) { [void]$keep.Add($t) }
}

$exts = @('.cpp', '.h', '.hpp', '.c', '.cc', '.inl')
$n = 0
Get-ChildItem -LiteralPath $src -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
    if ($exts -notcontains $_.Extension.ToLowerInvariant()) { return }
    $rel = $_.FullName.Substring($src.Length + 1)
    if (-not $keep.Contains($rel)) {
        Write-Output ('stale: ' + $rel)
        Remove-Item -LiteralPath $_.FullName -Force
        $n++
    }
}
Write-Output ('pruned ' + $n + ' stale source files')
