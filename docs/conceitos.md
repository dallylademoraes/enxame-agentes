# Conceitos de Sistemas Distribuídos

Preenchido ao longo das etapas: o que é cada conceito e **onde ele aparece no nosso código**.

## Etapa 0 — Arquitetura

O sistema tem três papéis, cada um rodando como um **processo separado**:

| Papel | Função | Analogia (restaurante) |
|---|---|---|
| **Cliente** | Porta de entrada do usuário: envia a pergunta e mostra a resposta | Quem faz o pedido |
| **Coordenador** | Intermediário: recebe a pergunta, escolhe qual worker responde e faz o **failover** se um falhar | O garçom/gerente |
| **Worker** | Faz o trabalho de fato: responde usando um LLM | Os cozinheiros |

`comum/` não é um processo: é código compartilhado pelos três (ex.: formato das mensagens).

**Por que um coordenador, e não o cliente decidindo sozinho?** O cliente só precisa conhecer um endereço. Quem conhece os workers, as chaves de API e a regra de failover é o coordenador. Adicionar um worker novo não exige mudar nenhum cliente (**transparência de localização e de falha**).

**Por que workers separados, e não o coordenador chamando as APIs direto?** Assim cada worker pode rodar em uma máquina diferente e cair de forma independente das outras (**falha parcial**), e essa é a situação que um sistema distribuído precisa tratar.

## Etapa 1 — Sockets TCP

**Processos não compartilham memória.** A única forma de cliente e worker se comunicarem é **trocando mensagens pela rede**.

**Endereçamento: IP + porta.** O IP identifica a máquina (`localhost` = a própria) e a porta identifica o programa dentro dela.
- O servidor usa **porta fixa** (5001), para os clientes saberem onde encontrá-lo.
- O cliente recebe do sistema operacional uma **porta efêmera** (ex.: 56516), escolhida automaticamente.
- **Só um processo por vez pode ocupar uma porta** → `BindException: Address already in use`.

**Socket:** a ponta da conexão. Cada lado tem um tubo de **saída** (`getOutputStream`) e um de **entrada** (`getInputStream`).
- `PrintWriter(..., true)` escreve linhas de texto. O `true` liga o *autoflush*, que envia imediatamente.
- `BufferedReader(new InputStreamReader(...))` lê linhas de texto.

**TCP:** garante entrega, ordem e ausência de perda dos dados.

**Os dois papéis:**

| | Servidor (Worker) | Cliente |
|---|---|---|
| Classe | `ServerSocket` | `Socket` |
| Ação | **espera** conexões (`accept()`) | **toma a iniciativa** (`new Socket(host, porta)`) |
| Ciclo de vida | roda para sempre (`while (true)`) | conecta, conversa e termina |

**A conversa:**
```
CLIENTE                                    WORKER
new Socket(host, porta)  ──── conecta ───> accept()
saida.println(pergunta)  ──── envia ─────> entrada.readLine()
leitor.readLine()        <─── responde ─── saida.println("eco: ...")
s.close()                                  conexao.close()
```

**Comunicação bloqueante:** `accept()` e `readLine()` travam até algo chegar. Se o worker nunca responder, o cliente **fica esperando para sempre**. Pior: o cliente **não consegue distinguir** um worker lento, um worker que caiu e uma mensagem perdida na rede, porque nos três casos ele só vê silêncio. A solução é o **timeout** (Etapa 6), que é o que torna o failover possível.

**Onde está no código:** `src/enxame/worker/Worker.java` e `src/enxame/cliente/Cliente.java`.

## Etapa 2 — Protocolo de mensagens

**Protocolo:** o combinado entre os dois lados sobre *como* as mensagens são escritas. O TCP só entrega um fluxo de bytes; quem dá sentido a esses bytes é o protocolo. Especificação completa em `docs/protocolo.md`.

**Três ideias:**

| Ideia | O que é | No nosso protocolo |
|---|---|---|
| **Framing** | Saber onde uma mensagem termina e a próxima começa | Uma mensagem por linha (`\n`) |
| **Serialização** | Transformar um objeto em texto/bytes para enviar | `Mensagem.paraLinha()` → `TIPO\|ID\|CONTEUDO` |
| **Desserialização** | O caminho inverso, do texto de volta ao objeto | `Mensagem.deLinha(linha)` |

**Heterogeneidade:** como o formato é texto simples e bem definido, qualquer programa (em qualquer linguagem, em qualquer máquina) que siga o combinado consegue conversar com os nossos nós.

```
Objeto Mensagem ──paraLinha()──> "PERGUNTA|1|oi" ──rede──> "PERGUNTA|1|oi" ──deLinha()──> Objeto Mensagem
```

**Perguntas:**

1. *E se o conteúdo tivesse uma quebra de linha e não fosse trocada por `\n`?* O `readLine()` do outro lado leria só até a primeira quebra. A mensagem chegaria cortada, e o resto seria lido depois como se fosse **outra** mensagem (que estaria mal formada). O framing quebraria.
2. *Para que serve o ID, se hoje só há uma pergunta por conexão?* Para associar cada resposta à sua pergunta. Na Etapa 5, o Coordenador vai falar com vários workers ao mesmo tempo, e as respostas chegam em qualquer ordem; o ID diz a qual pergunta cada uma pertence.
3. *Por que um tipo `ERRO` em vez de responder "deu erro"?* Porque "deu erro" poderia ser uma resposta legítima de um LLM. Com um campo próprio, o programa decide olhando só o TIPO, sem tentar adivinhar pelo texto. Isso é essencial para o failover: o Coordenador precisa saber com certeza que um worker falhou.

**Onde está no código:** `src/enxame/comum/Mensagem.java` (formato), usado em `src/enxame/worker/Worker.java` e `src/enxame/cliente/Cliente.java`.

## Etapa 3 — Latência

**Abstração:** o Worker só conhece a interface `ProvedorLLM` (`responder(pergunta)`), não sabe qual LLM está por trás. Hoje é o `ProvedorFalso`; na Etapa 9 entra um LLM real **sem mudar o resto do Worker**.

**Provedor falso:** demora de 0,5 a 2 s (sorteado) e falha em 20% das vezes. Permite testar latência e falhas sem internet e sem custo. Quando falha, o Worker devolve `ERRO` com o mesmo ID (o tipo criado na Etapa 2).

**Latência** = tempo entre pedir e receber:
```
tempo total (Cliente) = tempo de rede/conexão + tempo de processamento (Worker)
```

**Medições** (Worker e Cliente na mesma máquina):

| Execução | Processamento (Worker) | Total (Cliente) | Diferença |
|---|---|---|---|
| 1 | 1391 ms | 1495 ms | 104 ms |
| 2 | 1876 ms | 1943 ms | 67 ms |
| 3 | 1889 ms | 1947 ms | 58 ms |
| 4 | 1998 ms | 2070 ms | 72 ms |
| 5 | 1072 ms | 1151 ms | 79 ms |
| 6 | 823 ms | 908 ms | 85 ms |

A diferença (~60–100 ms) é o custo de abrir a conexão, enviar, ler e montar as mensagens. O processamento domina.

**Perguntas:**

1. *Por que a variável é do tipo `ProvedorLLM` e não `ProvedorFalso`?* Porque o resto do Worker só usa o que a interface promete. Na Etapa 9, basta trocar o `new ProvedorFalso()` por `new ProvedorOllama()`, e nada mais muda.
2. *O que pesou mais: rede ou processamento?* Processamento (~95% do tempo). Com o Worker em outra cidade, a parte de rede cresceria (dezenas a centenas de ms por ida e volta), mas um LLM real leva segundos, então o processamento continuaria dominando. A 2ª falácia da computação distribuída é justamente achar que "a latência é zero".
3. *Por que latência sorteada, e não fixa?* Porque na Etapa 5 cada worker vai demorar um tempo diferente, como na vida real. Isso mostra que, em paralelo, o tempo total é o do worker **mais lento**, e não a soma.

**Onde está no código:** `src/enxame/worker/provedores/ProvedorLLM.java` (interface), `ProvedorFalso.java` (latência e falha), `src/enxame/worker/Worker.java` (usa a interface, mede o processamento e devolve `ERRO`), `src/enxame/cliente/Cliente.java` (mede o total).

## Etapa 4 — RPC

O **Coordenador** entra no meio, como um garçom entre o cliente e a cozinha:

```
Cliente ──PERGUNTA──> Coordenador ──PERGUNTA──> StubWorker ──liga──> Worker
Cliente <─RESPOSTA─── Coordenador <─RESPOSTA─── StubWorker <──────── Worker
```

Ele tem **dois papéis**: é **servidor** para o Cliente (`ServerSocket` na porta 5000) e **cliente** para o Worker (abre `Socket` para a 5001). A lista de workers vem de `config/workers.txt`, passado em `args[1]` na hora de rodar.

**RPC (chamada de procedimento remoto):** fazer uma chamada pela rede *parecer* uma função comum:
```java
Mensagem resposta = workers.get(0).perguntar(pergunta);
```

**Stub:** a classe que esconde a rede por trás dessa chamada (`StubWorker`), como um contato na agenda. Dentro do `perguntar`: liga (abre o socket), fala, escuta, desliga. Quem chama não vê nada disso.

**Cuidado:** parece local, mas não é. Uma função local nunca "some" nem demora para sempre; uma chamada remota pode (o worker caiu, a rede caiu, o worker está lento). Isso é tratado na Etapa 6.

**Perguntas:**

1. *O que é um stub e por que ajuda?* É um "representante" do worker dentro do Coordenador. O Coordenador só chama `perguntar(...)`; todo o código de socket fica num lugar só.
2. *Duas coisas que acontecem numa chamada remota e nunca numa local:* o outro lado pode estar fora do ar (`ConnectException`), e a resposta pode demorar indefinidamente ou se perder na rede.
3. *Que transparência o Cliente ganhou?* **Transparência de localização:** o Cliente só conhece o endereço do Coordenador (porta 5000). Ele não sabe quantos workers existem nem onde estão; dá para trocar, adicionar ou mover workers sem mudar uma linha do Cliente.

**Onde está no código:** `src/enxame/coordenador/Coordenador.java` (lê a lista, atende o Cliente, repassa) e `src/enxame/coordenador/StubWorker.java` (o stub). O `Cliente.java` não mudou: só passou a ser rodado com a porta 5000.

## Etapa 5 — Scatter-gather e speedup

**Scatter-gather (espalhar e juntar):** o Coordenador manda a mesma pergunta para **todos** os workers (*scatter*) e depois junta as respostas num texto só (*gather*).

**Por que threads?** O `readLine()` trava até a resposta chegar. Sem threads, o Coordenador só consegue esperar **um worker de cada vez**. Com threads, cada "ajudante" espera o seu worker, todos ao mesmo tempo.

```
Sequencial:  5001 ███  5002 ███  5003 ███     → a SOMA dos tempos
Paralelo:    5001 ███
             5002 █████                       → o tempo do MAIS LENTO
             5003 ██
```

Os workers sempre trabalharam ao mesmo tempo (são processos separados). O gargalo era o Coordenador, que só sabia esperar um por vez.

| Peça | O que é | No código |
|---|---|---|
| **Thread** | Uma "linha de execução" dentro do mesmo processo (um ajudante) | |
| **Pool** (`ExecutorService`) | Um grupo fixo de threads que executa as tarefas entregues | `Executors.newFixedThreadPool(workers.size())` |
| **Tarefa** (lambda) | O que o ajudante deve fazer | `() -> w.perguntar(pergunta)` |
| **`Future`** | Um "recibo" do resultado: `submit` devolve na hora, sem esperar | `ajudantes.submit(...)` |
| **`get()`** | Busca o resultado do recibo; espera se ainda não chegou | `recibos.get(i).get()` |

**Medições** (3 workers com provedor falso, 0,5–2 s cada):

| Execução | Sequencial | Paralelo |
|---|---|---|
| 1 | 3963 ms | 1246 ms |
| 2 | 4373 ms | 1603 ms |
| 3 | 4014 ms | 1612 ms |
| 4 | 3584 ms | 1456 ms |
| 5 | 3928 ms | 1746 ms |
| **Média** | **3972 ms** | **1533 ms** |

**Speedup** S = T_sequencial / T_paralelo = 3972 / 1533 ≈ **2,59**
**Eficiência** E = S / p = 2,59 / 3 ≈ **0,86** (86% do ideal)

**Perguntas:**

1. *Por que o tempo paralelo fica perto do worker mais lento?* Porque o Coordenador só devolve a resposta ao Cliente depois de juntar **todas**: o último `get()` espera o worker mais demorado. Os outros já terminaram antes, "de graça".
2. *Por que o speedup não deu exatamente 3?* Porque cada worker sorteia um tempo diferente, e o mais lento quase sempre demora mais que a média (no sequencial pagamos a média 3 vezes; no paralelo, o máximo 1 vez). Também há custos que não se dividem: abrir as conexões, criar as tarefas, juntar o texto (Lei de Amdahl).
3. *E se um worker demorasse 30 s?* A resposta inteira demoraria 30 s, mesmo com os outros dois prontos em 2 s, porque o `get()` daquele recibo fica esperando. Se o worker travasse de vez, o Coordenador esperaria para sempre. É isso que a Etapa 6 resolve com **timeout**.

**Onde está no código:** `src/enxame/coordenador/Coordenador.java` (pool `ajudantes`, o `for` que espalha com `submit` e o `for` que junta com `get`).

## Etapa 6 — Falhas parciais e timeouts

**Falha parcial:** num sistema distribuído, *uma parte* pode quebrar enquanto o resto continua funcionando. O worker 5002 pode morrer e o 5001 e o 5003 seguem vivos. O sistema tem que **continuar respondendo** com o que sobrou.

**Modelo de falhas** (os tipos de falha que o sistema tolera):

| Falha | O que acontece | Como aparece no Java | Quem trata |
|---|---|---|---|
| **Crash** | O processo morreu (janela fechada) | `ConnectException: Connection refused` | `connect(..., 1000)` no stub |
| **Omissão** | A resposta nunca vem | `readLine()` esperaria para sempre | `setSoTimeout(...)` no stub |
| **Temporização** | Responde, mas tarde demais | `SocketTimeoutException` | `setSoTimeout(...)` no stub |
| **Erro do provedor** | O worker responde `ERRO` | mensagem com tipo `ERRO` | `if (r.tipo.equals("ERRO"))` no Coordenador |

**Timeout:** lento, morto e mensagem perdida **parecem iguais** (silêncio). O timeout é a decisão prática: "se não respondeu em X segundos, trato como falho".
- 1 s para conseguir conectar;
- 5 s para receber a resposta (com o provedor falso). Na Etapa 9, com LLMs reais, subiu para **30 s**: o Ollama demora mais, principalmente na primeira pergunta, quando carrega o modelo na memória.

**Failover:** quando um worker falha, o sistema segue com os outros. No nosso caso (scatter-gather), cada worker tem o **seu próprio `try/catch`** dentro do `for` que junta as respostas: o erro de um só tira aquele worker da "bandeja". Se **todos** falharem, o Cliente recebe um `ERRO` e nunca fica travado (`nenhum worker disponivel`; a partir da Etapa 8, `sem quorum`).

> Outra estratégia possível seria o failover **sequencial**: perguntar a um worker e, só se ele falhar, tentar o próximo. Gasta menos (só um trabalha por vez), mas dá uma resposta só e espera o timeout do que caiu antes de tentar o seguinte. Escolhemos perguntar a todos porque já responde rápido mesmo com falhas e permite a **votação** (Etapa 8).

**Teste: derrubando workers** (o que o Cliente recebeu e o que o Coordenador imprimiu):

| Situação | Cliente | Coordenador |
|---|---|---|
| 3 workers ligados | respostas do 5001 e 5002 | `Worker 5003 falhou: falha simulada` (os 20% sorteados) |
| Fechei o 5002 | respostas do 5001 e 5003 | `Worker 5002 falhou: java.net.ConnectException: Connection refused` |
| Fechei todos | `[ERRO 1] nenhum worker disponivel` (101 ms) | os 3 com `Connection refused` |

**Perguntas:**

1. *O que é falha parcial e por que não existe num programa de um computador só?* É quando uma parte do sistema quebra e o resto continua. Num programa comum, tudo roda no mesmo processo: se ele cai, cai tudo junto (falha total). Num sistema distribuído, cada nó é um processo separado e pode cair sozinho.
2. *Como escolher o timeout? E se for curto ou longo demais?* Pelo tempo normal de resposta: o provedor falso leva até 2 s, então 5 s dá folga. **Curto demais:** workers saudáveis, só um pouco lentos, são tratados como mortos (falsos positivos), e respostas boas são jogadas fora. **Longo demais:** quando um worker trava de verdade, o Cliente espera muito até receber a resposta. Para LLMs reais (Etapa 9), que podem levar vários segundos, o valor provavelmente terá de aumentar.
3. *Um worker que levou 6 s (timeout de 5 s) estava morto?* Não necessariamente. O Coordenador **acha** que ele falhou, mas ele podia estar vivo e só lento. Pela rede é impossível distinguir "lento" de "morto"; o timeout é só uma **suspeita**, não uma certeza.

**Onde está no código:** `src/enxame/coordenador/StubWorker.java` (`connect` com 1 s e `setSoTimeout`) e `src/enxame/coordenador/Coordenador.java` (`try/catch` por worker no `for` que junta, e `ERRO` quando ninguém responde).

## Etapa 8 — Quórum e votação

Com várias respostas na mão, **qual delas o Cliente recebe?** Juntar todas (Etapa 5) não é uma decisão. Agora o Coordenador **vota**: cada resposta de worker conta como um "voto", e vence a resposta que mais workers deram.

**Analogia:** você pergunta a capital da França para 3 amigos. Dois dizem "Paris", um diz "Lyon". Você acredita em Paris, porque a maioria concorda. Ninguém "votou" de verdade: cada um só respondeu, e quem decidiu foi você.

**Quórum:** o número **mínimo** de votos iguais para aceitar uma resposta. Com N workers, `quorum = N / 2 + 1` (maioria absoluta). Com 3 workers, o quórum é **2**.
- O quórum é calculado sobre os workers **configurados**, e não só sobre os que responderam. Se 2 de 3 caírem, sobra 1 voto, que não é maioria → `sem quorum`.
- Se ninguém chega ao quórum, o Coordenador **prefere avisar a arriscar** uma resposta errada.

**Por que isso é tolerância a falhas?** Se 1 worker de 3 **cair** ou responder **errado**, os outros 2 ainda formam maioria. Para tolerar `k` respostas erradas são necessários `2k + 1` workers.

**Falha bizantina:** um nó que funciona, mas diz algo incorreto. Um LLM "alucinando" é exatamente isso. O provedor falso simula esse caso: responde "Paris" (70%) ou "Lyon" (30%) para **qualquer** pergunta.

**Problema prático: comparar texto.** Dois LLMs quase nunca escrevem a mesma frase. Duas soluções:
1. O Worker acrescenta à pergunta: *"Responda com uma unica palavra, sem explicacao."*
2. O Coordenador **normaliza** cada resposta antes de contar (`normalizar`): tira espaços, deixa minúsculo e remove ponto/exclamação do final. Assim `" Paris."` e `"paris"` viram o mesmo voto.

**Como funciona no código:**

| Passo | Código |
|---|---|
| Contar | `votos.merge(normalizar(r.conteudo), 1, Integer::sum)` — um `HashMap` resposta → nº de votos |
| Achar a mais votada | um `for` no `votos.entrySet()` guardando o maior |
| Decidir | `if (maior >= quorum)` → `RESPOSTA` com o placar; senão `ERRO` `sem quorum` |

O Cliente recebe a decisão **e** o voto de cada worker (inclusive quem falhou), para ficar claro como a decisão foi tomada.

**Resultados reais** (Ollama + Groq + Falso):

| Pergunta | Ollama | Groq | Falso | Decisão |
|---|---|---|---|---|
| capital do brasil | Brasília. | Brasília | Paris | **brasília (2 de 3 votos)**: o falso errou e foi vencido |
| capital da frança | Paris. | Paris | caiu (falha simulada) | **paris (2 de 3 votos)**: um worker caiu e o sistema respondeu mesmo assim |
| mulher que cortou o cabelo de sansão | São Paulo. | Delilah | caiu | **sem quorum**: o Ollama alucinou e não houve maioria |
| religião com mais fiéis do mundo | Cristãos | Cristianismo | caiu | **sem quorum**: concordam no sentido, não nas palavras |
| capital do brasil (com `llama3.2:1b`) | Rio | Brasília | Paris | **sem quorum**: 3 respostas diferentes |

**Perguntas:**

1. *Por que o quórum é `N / 2 + 1` e não `N / 2`?* Com 4 workers, `N / 2` seria 2, e um empate 2 a 2 teria **duas** respostas "vencedoras". Com `N / 2 + 1` = 3, só pode existir uma maioria.
2. *Quantos workers seriam necessários para tolerar 2 respostas erradas?* `2k + 1` = **5**. Com 3 workers e 2 errando (como no exemplo do `llama3.2:1b`), não há maioria.
3. *Por que comparar respostas de LLMs é difícil?* Porque o mesmo sentido pode ser escrito de muitas formas ("Cristãos" × "Cristianismo"). Contornamos pedindo uma palavra só e normalizando o texto, mas sinônimos continuam sendo votos diferentes. Uma solução mais completa compararia o **significado** (por exemplo, com *embeddings*), não as letras.

**Onde está no código:** `src/enxame/coordenador/Coordenador.java` (`normalizar`, o `HashMap votos`, o cálculo do `quorum` e a decisão), `src/enxame/worker/Worker.java` (pedido de resposta em uma palavra) e `src/enxame/worker/provedores/ProvedorFalso.java` (Paris/Lyon).

## Etapa 9 — Heterogeneidade (LLMs reais)

**Heterogeneidade:** os nós de um sistema distribuído não precisam ser iguais. Aqui cada worker usa um LLM **diferente**, em um lugar **diferente**:

| Worker | Provedor | Modelo | Onde roda | Comunicação |
|---|---|---|---|---|
| 5001 | Ollama | `llama3.2:3b` (3 bilhões de parâmetros) | no próprio PC | HTTP para `localhost:11434` |
| 5002 | Groq | `openai/gpt-oss-20b` (20 bilhões) | na nuvem | HTTPS para `api.groq.com`, com chave |
| 5003 | Falso | — | no próprio PC | nenhuma (sorteio) |

**A abstração da Etapa 3 valeu a pena:** o Worker só conhece a interface `ProvedorLLM`. Entraram duas classes novas (`ProvedorOllama`, `ProvedorGroq`) e o Worker só ganhou um `switch` que escolhe o provedor pelo nome passado na linha de comando (`java ... Worker 5001 ollama`). O Coordenador e o Cliente não mudaram nada por causa disso.

**Serviços externos:** o worker do Groq depende de outro sistema distribuído, fora do nosso controle (internet + servidores do Groq). Aprendemos isso na prática:
- O modelo usado no início (`llama-3.1-8b-instant`) foi **retirado** pelo Groq. O worker passou a responder `ERRO` (HTTP 404, "modelo não encontrado"). O sistema seguiu funcionando com os outros dois, e a troca do modelo foi uma linha.
- A chave da API fica no arquivo `.env` (fora do código e fora do repositório). O `iniciar.ps1` carrega o `.env` nas variáveis de ambiente antes de abrir os workers.

**Tamanho do modelo importa:** com o `llama3.2:1b` (1 bilhão de parâmetros), o Ollama respondeu "Rio" para "capital do Brasil". Com o `llama3.2:3b`, acertou ("Brasília"). Modelos maiores erram menos, mas são mais lentos no PC.

**Timeout:** LLMs reais demoram mais que o provedor falso, então o timeout de leitura do `StubWorker` subiu de 5 s para **30 s** (ver Etapa 6).

**Latência observada** (tempo total no Cliente, 3 workers em paralelo): entre ~0,7 s e ~2,1 s nas perguntas acima. Como o Coordenador espera todos os workers, o tempo é o do **mais lento**.

**Perguntas:**

1. *O que precisou mudar no Worker para usar um LLM real?* Só a linha que cria o provedor (virou um `switch`). Todo o resto (socket, protocolo, medição de tempo, `ERRO`) continuou igual, porque usa apenas a interface `ProvedorLLM`.
2. *O que muda quando um worker depende de um serviço na nuvem?* Surgem falhas que não são nossas: falta de internet, limite de uso, chave inválida, modelo retirado. Para o Coordenador, tudo isso aparece igual: o worker respondeu `ERRO` ou não respondeu a tempo, e é tratado pelo failover.
3. *Por que não chamar as APIs direto do Coordenador?* Porque cada worker é um processo separado que pode rodar em outra máquina e cair sozinho. Se tudo ficasse no Coordenador, um problema em uma API poderia travar todo o resto.

**Onde está no código:** `src/enxame/worker/provedores/ProvedorOllama.java`, `ProvedorGroq.java`, `Http.java` (envio HTTP e leitura do JSON, sem bibliotecas), `src/enxame/worker/Worker.java` (o `switch`), `config/workers.txt` (qual provedor cada worker usa) e `scripts/iniciar.ps1` (carrega o `.env`).

## Melhorias futuras (não implementadas)

**Etapa 7 — Detecção de falhas (heartbeat):** hoje o Coordenador só descobre que um worker caiu quando pergunta e ele não responde. Com **heartbeat**, cada worker mandaria um "estou vivo" a cada poucos segundos, e o Coordenador deixaria de perguntar a quem ficou em silêncio. Isso economizaria a espera do timeout.

**Etapa 10 — Balanceamento de carga:** hoje **todos** os workers respondem **todas** as perguntas (necessário para a votação). Com muitas perguntas, uma alternativa seria **distribuir** as perguntas entre os workers (cada pergunta vai para um só), o que aumenta a vazão, mas perde a votação.

## Limitações

- **O Coordenador é um ponto único de falha.** Se ele cair, o sistema inteiro para, mesmo com todos os workers vivos. A solução seria ter **vários coordenadores**, mas aí eles precisariam entrar em acordo entre si sobre o estado do sistema (**consenso**, por exemplo com o protocolo **Raft**). Isso é bem mais complexo e ficou fora do escopo.
- **A votação compara letras, não significado.** Sinônimos ("Cristãos" × "Cristianismo") viram votos diferentes.
- **Timeout fixo:** um worker apenas lento (mais de 30 s) é tratado como morto. Pela rede é impossível distinguir os dois casos.
- **Speedup com LLMs reais não foi remedido:** o valor da Etapa 5 (≈ 2,59) foi medido com o provedor falso.
