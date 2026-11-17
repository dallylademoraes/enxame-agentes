# Protocolo de mensagens

Transporte: TCP. Codificação: texto UTF-8.

## Framing

Uma mensagem por linha, terminada em `\n`. O lado que recebe usa `readLine()`: tudo até a quebra de linha é **uma** mensagem.

## Formato

```
TIPO|ID|CONTEUDO
```

| Campo | Valores | Descrição |
|---|---|---|
| TIPO | `PERGUNTA`, `RESPOSTA`, `ERRO` | Diz o que a mensagem é. `PERGUNTA` vai do cliente/coordenador para o worker; `RESPOSTA` e `ERRO` voltam. |
| ID | texto (ex.: `1`, `7`) | Identifica a pergunta. A resposta **repete o ID da pergunta**, para quem recebeu saber a qual pergunta ela pertence. |
| CONTEUDO | texto | A pergunta, a resposta ou a descrição do erro. Quebras de linha reais são escritas como `\n` literal (barra + n). |

- O separador é `|`. Na leitura, a linha é cortada em **no máximo 3 pedaços** (`split("\\|", 3)`), então um `|` dentro do conteúdo não quebra a mensagem.
- Se o conteúdo tiver várias linhas, ele é "achatado" em uma (`\n` real → `\n` literal) antes de enviar e restaurado depois de receber. Sem isso, o `readLine()` cortaria a mensagem no meio.

## Exemplos

Troca real entre Cliente e Worker (porta 5001):

```
Cliente → Worker:   PERGUNTA|1|tudo bem querido?
Worker  → Cliente:  RESPOSTA|1|eco: tudo bem querido?
```

Conteúdo com duas linhas, como vai pela rede:

```
RESPOSTA|2|primeira linha\nsegunda linha
```

Erro (a partir da Etapa 3, quando o provedor falhar):

```
ERRO|3|provedor indisponível
```

## Erros

- **Linha mal formada** (menos de 3 pedaços, ex.: `oi tudo bem`): `Mensagem.deLinha` lança `IllegalArgumentException("linha inválida: ...")`.
  - O Worker captura a exceção, imprime `Conexao com erro: ...`, fecha aquela conexão e continua atendendo as próximas.
- **Falha ao responder** (o provedor deu erro): o Worker devolve `ERRO|<mesmo id>|<descrição>` em vez de `RESPOSTA`. Assim quem recebe distingue um erro de uma resposta normal olhando só o campo TIPO.

## Limitação conhecida

Se o conteúdo original já tiver o texto `\n` literal (barra + n, como num trecho de código), ele vira uma quebra de linha real do outro lado. Para as perguntas deste trabalho isso não acontece; um protocolo completo escaparia também a própria barra (`\\`).
