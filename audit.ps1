# Lists every comment block of 4+ lines, per java file, so long explanations can
# be spotted and trimmed. The path used to be hardcoded to one machine; derive it
# from this script's own location so it runs anywhere the project is checked out.
$ErrorActionPreference = 'Stop'
$d = Join-Path $PSScriptRoot 'java\com\BB465_stuff\Terminal'

foreach ($f in (Get-ChildItem $d -Filter *.java | Sort-Object Name)) {
    $l = [System.IO.File]::ReadAllLines($f.FullName)
    $blocks = @()
    $i = 0
    while ($i -lt $l.Length) {
        $t = $l[$i].Trim()
        if ($t.StartsWith('/*') -or $t.StartsWith('//')) {
            $start = $i
            if ($t.StartsWith('//')) {
                $run = 0
                while ($i -lt $l.Length -and $l[$i].Trim().StartsWith('//')) { $run++; $i++ }
                $blocks += [PSCustomObject]@{ L = $start + 1; N = $run; K = '//' }
                continue
            } else {
                while ($i -lt $l.Length) {
                    if ($l[$i].Trim().EndsWith('*/')) { $i++; break }
                    $i++
                }
                $blocks += [PSCustomObject]@{ L = $start + 1; N = $i - $start; K = '/*' }
                continue
            }
        }
        $i++
    }
    $tot = ($blocks | Measure-Object -Property N -Sum).Sum
    $big = $blocks | Where-Object { $_.N -ge 4 } | Sort-Object N -Descending
    if ($big) {
        "=== $($f.Name): $($l.Length) lines, $($blocks.Count) blocks, $tot comment lines ==="
        foreach ($b in $big) { "   L$($b.L)  $($b.N) lines  $($b.K)" }
        ""
    }
}
