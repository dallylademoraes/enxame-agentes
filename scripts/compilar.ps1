# Compila todo o código de src/ para out/
$raiz = Split-Path $PSScriptRoot -Parent
$fontes = Get-ChildItem -Path "$raiz\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }

if (Test-Path "$raiz\out") { Remove-Item -Recurse -Force "$raiz\out" }
javac -encoding UTF-8 -d "$raiz\out" $fontes

if ($LASTEXITCODE -eq 0) { Write-Host "Compilado em out/" -ForegroundColor Green }
else { Write-Host "Erro na compilacao" -ForegroundColor Red; exit 1 }
