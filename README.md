# Trabalho 02 — Enxame de Agentes de IA Distribuído

Um **coordenador** recebe uma pergunta de um **cliente**, distribui para vários **workers** ao mesmo tempo (cada um usando um LLM diferente), decide a resposta por **votação/quórum** e devolve o resultado. Se um worker cair, o sistema continua funcionando com os demais.

## O problema

Depender de **um único LLM** é arriscado:

| Risco | Exemplo | Como o sistema resolve |
|---|---|---|
| **Ponto único de falha** | a API sai do ar, o PC com o modelo desliga | vários workers independentes; quem cai é ignorado (**tolerância a falhas**) |
| **Processamento pesado** | rodar um LLM leva segundos | os workers trabalham **em paralelo** (threads): o tempo total é o do mais lento, não a soma |
| **Resposta errada** | o LLM "alucina" (ex.: "capital do Brasil: Rio") | a resposta só vale se a **maioria** concordar (**quórum**) |

Tecnologias exigidas pelo enunciado: **Sockets** (toda a comunicação entre os processos) e **Threads** (o coordenador consulta os workers em paralelo).

## Arquitetura

```
                         ┌──────────────┐
                         │   Cliente    │
                         └──────┬───────┘
                                │ pergunta (TCP)
                         ┌──────▼───────┐
                         │ Coordenador  │  scatter-gather, timeout, quórum
                         └──┬────┬────┬─┘
              ┌─────────────┘    │    └─────────────┐
       ┌──────▼─────┐     ┌──────▼─────┐     ┌──────▼─────┐
       │ Worker 1   │     │ Worker 2   │     │ Worker 3   │
       │ (Ollama)   │     │ (Groq)     │     │ (Falso)    │
       └────────────┘     └────────────┘     └────────────┘
```

- Cada componente é um **processo separado**, com comunicação por **sockets TCP**.
- Os workers podem rodar no mesmo PC (portas diferentes) ou em máquinas diferentes da rede: basta trocar `localhost` pelo IP em `config/workers.txt`.

| Worker | Provedor | Onde roda |
|---|---|---|
| 1 (porta 5001) | Ollama, modelo `llama3.2:3b` | no próprio PC |
| 2 (porta 5002) | Groq, modelo `openai/gpt-oss-20b` | na nuvem |
| 3 (porta 5003) | Falso: responde "Paris" (70%) ou "Lyon" (30%) e falha em 20% das vezes | no próprio PC, simula um worker com defeito |

## Exemplo de execução

```
Pergunta: capital do brasil
[RESPOSTA 1] brasília (2 de 3 votos)
[ollama] Brasília.
[groq] Brasília
[falso] Paris
 (1781 ms)
Pergunta: capital da frança
[RESPOSTA 2] paris (2 de 3 votos)
[ollama] Paris.
[groq] Paris
[falso] falhou: falha simulada
 (702 ms)
Pergunta: mulher que cortou o cabelo de sansão
[ERRO 3] sem quorum {são paulo=1, delilah=1}
[ollama] São Paulo.
[groq] Delilah
[falso] falhou: falha simulada
 (1402 ms)
```

- Na 1ª, o falso "errou", mas foi vencido por 2 a 1.
- Na 2ª, o falso caiu, e o sistema respondeu do mesmo jeito.
- Na 3ª, o Ollama "alucinou" e o falso caiu; sem maioria, o sistema preferiu avisar a arriscar uma resposta errada.

## Estrutura

```
02-enxame-agentes/
├── README.md                 ← este arquivo
├── .env                      ← chave do Groq (não vai para o repositório)
├── docs/
│   ├── conceitos.md          ← relatório: conceitos de SD e onde aparecem no código
│   └── protocolo.md          ← formato das mensagens entre os nós
├── config/
│   └── workers.txt           ← lista de workers (host:porta provedor)
├── scripts/
│   ├── compilar.ps1          ← compila tudo para out/
│   └── iniciar.ps1           ← sobe workers, coordenador e cliente em janelas separadas
└── src/enxame/
    ├── comum/                ← Mensagem (protocolo compartilhado)
    ├── worker/               ← nó que responde perguntas usando um LLM
    │   └── provedores/       ← Falso, Ollama, Groq (+ Http, ajudante de requisições)
    ├── coordenador/          ← distribui, espera, vota (+ StubWorker, o "contato" de cada worker)
    └── cliente/              ← linha de comando: pergunta em loop, "sair" encerra
```

## Como rodar

**Requisitos:** Java 17+, [Ollama](https://ollama.com) e uma chave gratuita do [Groq](https://console.groq.com/keys).

1. Baixe o modelo local:
   ```
   ollama pull llama3.2:3b
   ```
2. Crie um arquivo `.env` na raiz do projeto com a chave do Groq:
   ```
   GROQ_API_KEY=gsk_...
   ```
3. Compile e inicie (no PowerShell):
   ```powershell
   .\scripts\compilar.ps1
   .\scripts\iniciar.ps1
   ```
   No Prompt de Comando (cmd), use `powershell -ExecutionPolicy Bypass -File scripts\compilar.ps1` (e o mesmo para `iniciar.ps1`).
4. Abrem 5 janelas: 3 workers, o coordenador e o cliente. Pergunte no cliente; `sair` encerra.

**Dica:** prefira perguntas com resposta de uma palavra (capitais, contas). Os workers pedem ao LLM uma resposta de uma palavra para que as respostas possam ser comparadas na votação.

**Demonstração de falha:** feche a janela de um worker e pergunte de novo. O sistema continua respondendo. Feche dois e aparece "sem quorum".

**Sem internet / sem Ollama:** troque os provedores em `config/workers.txt` para `falso`.

Java 17 puro, sem frameworks: cada conceito aparece explícito no código.

## Etapas

Os conceitos de cada etapa e onde estão no código: [`docs/conceitos.md`](docs/conceitos.md).

| # | Etapa | Conceitos de SD | Status |
|---|---|---|---|
| 0 | Arquitetura e papéis de cada nó | modelo cliente-servidor, coordenador/worker, transparência | ✅ |
| 1 | Worker "eco" via socket TCP | sockets, portas, endereçamento, comunicação bloqueante | ✅ |
| 2 | Protocolo de mensagens | serialização, *framing*, heterogeneidade | ✅ |
| 3 | Worker com provedor falso (latência simulada) | abstração, latência de rede vs. processamento | ✅ |
| 4 | Coordenador chama 1 worker | RPC (chamada remota), stubs | ✅ |
| 5 | Coordenador chama N workers em paralelo | scatter-gather, threads, **speedup** | ✅ |
| 6 | Timeouts e **failover** (worker falhou → segue com os outros) | falhas parciais, modelo de falhas (crash, omissão), tolerância a falhas | ✅ |
| 7 | Heartbeat e detecção de falhas | detector de falhas, *liveness* | melhoria futura |
| 8 | Votação / quórum | consenso, tolerância a falhas, maioria | ✅ |
| 9 | LLMs reais (Ollama local + Groq na nuvem) | heterogeneidade, serviços externos | ✅ |
| 10 | Balanceamento de carga + experimentos | balanceamento, escalabilidade, medição | melhoria futura |

## Limitações

- **O coordenador é um ponto único de falha:** se ele cair, o sistema para. A solução seria replicá-lo, mas aí os coordenadores precisariam entrar em acordo entre si (consenso, ex.: Raft).
- **Comparar texto livre é impreciso:** dois LLMs podem concordar no sentido e discordar nas palavras ("Cristãos" × "Cristianismo") e, nesse caso, não há quórum. Por isso as respostas são pedidas em uma palavra.
- **Timeout fixo (30 s):** um worker só lento é tratado igual a um que caiu. Um detector de falhas com heartbeat (etapa 7) melhoraria isso.
