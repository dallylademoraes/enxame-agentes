# Sobe cada worker listado em config/workers.txt, o coordenador e o cliente,
# cada um em uma janela própria (são processos separados, como em máquinas distintas).
$raiz = Split-Path $PSScriptRoot -Parent
$out = "$raiz\out"
$portaCoordenador = 5000

# Carrega o .env (ex.: GROQ_API_KEY=...) nas variaveis de ambiente; as janelas abertas abaixo herdam
if (Test-Path "$raiz\.env") {
    Get-Content "$raiz\.env" | Where-Object { $_ -match '^\s*([^#=]+)=(.+)$' } | ForEach-Object {
        Set-Item "env:$($Matches[1].Trim())" $Matches[2].Trim()
    }
}

Get-Content "$raiz\config\workers.txt" | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
    $endereco, $provedor = $_ -split '\s+'
    $porta = ($endereco -split ':')[1]
    Start-Process powershell -ArgumentList '-NoExit', '-Command', "java -cp '$out' enxame.worker.Worker $porta $provedor"
}

Start-Sleep -Seconds 1
Start-Process powershell -ArgumentList '-NoExit', '-Command', "java -cp '$out' enxame.coordenador.Coordenador $portaCoordenador '$raiz\config\workers.txt'"

Start-Sleep -Seconds 1
Start-Process powershell -ArgumentList '-NoExit', '-Command', "java -cp '$out' enxame.cliente.Cliente localhost $portaCoordenador"
