# Contrato frontend ↔ backend — Ceres, a assistente fiscal (Sprint 6)

Contrato para o Angular consumir a **Ceres**, a assistente fiscal do AgroContabil entregue na Sprint 6
do backend (issues #141–#144, #156 e #157). Cobre os dois endpoints do chat, os eventos do
streaming, os erros e as regras de exibição que fazem parte da garantia do produto (citação legal
visível e origem dos números).

- **Backend:** `SupersonicTeam/backend`, `main` a partir de `fd2a23e`.
- **Issue de frontend correspondente:** SupersonicTeam/Agrocontabil#40 (interface de chat).
- **Desenho interno da Ceres:** [`docs/ceres-assistente.md`](ceres-assistente.md).

---

## 1. O que a Ceres responde

A cada pergunta o backend classifica a intenção e escolhe uma **rota**:

| `rota` | Quando | O que vem na resposta |
|---|---|---|
| `DADOS` | Pergunta sobre os dados do produtor no sistema (compras, notas, apuração de IBS/CBS, créditos, guias e vencimentos) | Texto + `fontesDados` (as consultas feitas) |
| `LEGISLACAO` | Pergunta sobre a lei (EC 132/2023, LC 214/2025, LC 227/2026) | Texto + `citacoes` (dispositivo, link oficial e trecho) |
| `MISTO` | Precisa dos dados **e** da regra ("tenho direito à redução nessas compras?") | As duas coisas |
| `FORA_DE_ESCOPO` | Qualquer outro assunto, ou intenção incerta | Texto fixo de apresentação da Ceres |

Garantias do backend que a tela deve **mostrar**, não esconder:

- **Todo número vem de uma consulta aos dados do produtor.** Resposta com número sem fonte é
  descartada e trocada por uma mensagem fixa. As consultas usadas vêm em `fontesDados`.
- **Toda afirmação legal cita o dispositivo**, com link para o texto oficial no Planalto.
- A Ceres **só enxerga os dados do produtor autenticado**. O produtor não é parâmetro da requisição.

---

## 2. Identidade e acesso

- Mesma identidade do resto da API: header **`X-Usuario-Id`** com o UUID do produtor (em
  `dev` — em `homolog` e `prod`, só o JWT, desde a #221). Sem o header, o backend usa o produtor semente.
- **Só produtor.** Contador recebe `403 PAPEL_NAO_AUTORIZADO`: a Ceres ainda não recebe produtor-alvo.
- CORS: origem `http://localhost:4200` liberada; `Retry-After` exposto (seção 5).

---

## 3. `POST /api/assistente/chat` — resposta síncrona

### Requisição

```http
POST /api/assistente/chat
Content-Type: application/json
X-Usuario-Id: <uuid do produtor>

{ "pergunta": "Quando vence a minha guia de FUNRURAL e qual o valor?" }
```

| Campo | Regra |
|---|---|
| `pergunta` | obrigatória, não vazia, **até 1000 caracteres** |
| corpo | até **16 KB** (acima disso, 413 antes de ler o JSON) |

### Resposta `200`

```json
{
  "resposta": "A guia de FUNRURAL vence em 20/10/2026 e tem valor de R$ 1.840,55.",
  "rota": "DADOS",
  "citacoes": [],
  "fontesDados": [
    { "funcao": "proximosVencimentos", "parametros": { "dias": "30" } }
  ],
  "modoFallback": false,
  "atalhos": []
}
```

| Campo | Tipo | Significado |
|---|---|---|
| `resposta` | `string` | Texto **já validado**. Texto puro com quebras de linha (`\n`), **nunca HTML** |
| `rota` | `"DADOS" \| "LEGISLACAO" \| "MISTO" \| "FORA_DE_ESCOPO"` | Camada que respondeu |
| `citacoes` | `Citacao[]` | Trechos de lei citados, na ordem da resposta. Vazio fora de `LEGISLACAO`/`MISTO` |
| `fontesDados` | `FonteDado[]` | Consultas aos dados do produtor usadas. **Vazio quando a resposta foi descartada**: sem selo "calculado a partir das suas notas" |
| `modoFallback` | `boolean` | `true` quando o modelo estava fora e a resposta foi montada **sem ele** (#146). Exiba um aviso ("assistente em modo limitado") |
| `atalhos` | `("NOTAS" \| "APURACAO" \| "CREDITOS" \| "CALENDARIO")[]` | Só no modo fallback, para perguntas de dados: telas onde o dado está. Mapeie cada valor para a rota do Angular e mostre como botões. Vazio fora do fallback |

#### Modo fallback (#146)

Com o modelo indisponível (Ollama fora, timeout, provedor sem resposta), a Ceres **não responde
503**: responde `200` com `modoFallback: true` e nenhum texto gerado.

- **Pergunta de legislação:** `resposta` traz os trechos mais próximos da pergunta, **verbatim**
  (busca textual no PostgreSQL), um por linha `- LC 214/2025, art. 138: ...` (trecho longo
  cortado com `...`); o texto inteiro de cada um vem em `citacoes`.
- **Pergunta de dados:** sem modelo não há como interpretar a pergunta. `resposta` é um texto fixo
  e `atalhos` lista as telas. Nenhum valor do produtor aparece.
- **Mista:** as duas partes; se a parte de dados já tinha sido respondida pelo modelo antes de ele
  cair, ela fica (com `fontesDados`) e só a lei vem da busca textual.
- Sem o classificador, a `rota` sai de palavras-chave e pode errar (pior caso: trechos de lei para
  uma pergunta de dados). Depois de uma falha, as perguntas seguintes pulam o modelo por 30 s.
- `503 ASSISTENTE_INDISPONIVEL` continua existindo quando **o banco também** está fora.

Exemplo real de `LEGISLACAO` (resumido):

```json
{
  "resposta": "A redução das alíquotas do IBS e da CBS para insumos agropecuários é de 60%. (LC 214/2025, art. 138, § 1º)",
  "rota": "LEGISLACAO",
  "citacoes": [
    {
      "norma": "LC 214/2025",
      "dispositivo": "art. 138, § 1º",
      "url": "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm",
      "trecho": "Art. 138. Ficam reduzidas em 60% (sessenta por cento) as alíquotas do IBS e da CBS incidentes sobre o fornecimento dos insumos agropecuários e aquícolas relacionados no Anexo IX [...] § 1º A redução de alíquotas prevista no caput deste artigo somente se aplica [...]"
    }
  ],
  "fontesDados": [],
  "modoFallback": false
}
```

Citações de anexo vêm com o item: `"dispositivo": "Anexo IX, item 1"` (biofertilizantes, NCM 3101.00.00).

### Tempo de resposta

Modelo local em CPU: de **alguns segundos a ~1,5 min** (medido: fora de escopo 4 s, dados 24–86 s,
legislação 45 s, misto 41 s). O backend interrompe a pergunta em **5 min**
(`ASSISTENTE_TEMPO_ESGOTADO`). Configure o timeout do `HttpClient` **acima de 5 min** e mostre um
indicador de progresso. Para dar retorno antes, use o streaming (seção 4).

---

## 4. `POST /api/assistente/chat/stream` — streaming (SSE)

Mesma requisição e mesmo contrato de resposta, entregue em eventos
[Server-Sent Events](https://developer.mozilla.org/docs/Web/API/Server-sent_events). O path é
próprio: não troque `/chat` por `Accept: text/event-stream`.

> **Não use `EventSource`:** ele só faz `GET` e não envia headers. Use `fetch` com `POST` e leia o
> corpo como stream (exemplo abaixo).

### Eventos, nesta ordem

| Evento | `data` | Quando |
|---|---|---|
| `rota` | `{"rota":"DADOS"}` | Assim que a intenção é decidida (em segundos): troque "pensando…" por "consultando suas notas…" / "consultando a legislação…" |
| `resposta` | o mesmo JSON do `POST /chat` | Resposta final, já validada |
| `fim` | `{}` | O stream termina |
| `erro` | envelope `ApiError` (seção 5) | Falha depois de o stream abrir (modelo fora, tempo esgotado). Encerra o stream |

Sequência real:

```text
event:rota
data:{"rota":"DADOS"}

event:resposta
data:{"resposta":"A guia de FUNRURAL vence em 20/10/2026 e tem valor de R$ 1.840,55.","rota":"DADOS","citacoes":[],"fontesDados":[{"funcao":"proximosVencimentos","parametros":{"dias":"30"}}],"modoFallback":false}

event:fim
data:{}
```

**Não há tokens parciais**, e é de propósito: números e citações só saem depois de conferidos. O
texto chega inteiro no evento `resposta`.

**Erros antes de o stream abrir** (400, 401, 403, 413 e 503 `ASSISTENTE_OCUPADO`) vêm como resposta
HTTP comum, **em JSON**, mesmo com `Accept: text/event-stream`. Confira `response.ok` antes de ler o
stream.

**Cancelar:** abortar o `fetch` (`AbortController`) fecha a conexão. O backend percebe a saída na
próxima escrita do stream (ou no limite de 5 min), interrompe a pergunta e libera a vaga (desfecho
`CANCELADA` na auditoria). Até lá, uma nova pergunta do mesmo usuário pode receber
`503 ASSISTENTE_OCUPADO`: trate com o `Retry-After`.

### Exemplo de consumo (Angular / TypeScript)

```ts
export async function perguntarEmStream(
  pergunta: string,
  usuarioId: string,
  aoEvento: (evento: 'rota' | 'resposta' | 'fim' | 'erro', dado: any) => void,
  cancelamento?: AbortSignal,
): Promise<void> {
  const resposta = await fetch(`${environment.apiUrl}/api/assistente/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Usuario-Id': usuarioId,
    },
    body: JSON.stringify({ pergunta }),
    signal: cancelamento,
  });

  if (!resposta.ok) {
    // 400/401/403/413/503 antes de o stream abrir: envelope ApiError em JSON.
    const erro: ApiError = await resposta.json();
    const retryAfter = resposta.headers.get('Retry-After'); // só no 503 ASSISTENTE_OCUPADO
    throw new CeresErro(erro, retryAfter ? Number(retryAfter) : undefined);
  }

  const leitor = resposta.body!.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const { value, done } = await leitor.read();
    if (done) return;
    buffer += value;
    // Eventos separados por linha em branco.
    let fimDoBloco: number;
    while ((fimDoBloco = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const bloco = buffer.slice(0, fimDoBloco);
      buffer = buffer.slice(fimDoBloco).replace(/^\r?\n\r?\n/, '');
      let nome = '';
      const dados: string[] = [];
      for (const linha of bloco.split(/\r?\n/)) {
        if (linha.startsWith('event:')) nome = linha.slice(6).trim();
        else if (linha.startsWith('data:')) dados.push(linha.slice(5));
      }
      if (nome) aoEvento(nome as any, JSON.parse(dados.join('\n') || 'null'));
    }
  }
}
```

---

## 5. Erros

Todo erro usa o envelope padrão da API:

```json
{
  "timestamp": "2026-09-30T14:00:05.804-03:00",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A assistente Ceres esta indisponivel no momento. Tente novamente em instantes.",
  "path": "/api/assistente/chat",
  "errors": [
    { "code": "ASSISTENTE_INDISPONIVEL", "message": "A assistente Ceres esta indisponivel no momento. Tente novamente em instantes." }
  ]
}
```

Decida pelo **`errors[0].code`**, não pelo texto. As mensagens já são seguras para exibir.

| HTTP | `errors[0].code` | Quando | Tratamento sugerido na tela |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` (`field: "pergunta"`) | Pergunta vazia ou acima de 1000 caracteres | Validar no formulário antes (contador de caracteres); mostrar a mensagem no campo |
| 401 | `NAO_AUTENTICADO` | `X-Usuario-Id` inválido ou sem identidade | Sessão inválida (o mesmo 401 do JWT na Sprint 8) |
| 403 | `PAPEL_NAO_AUTORIZADO` | Usuário contador | Esconder o chat para contador |
| 413 | `PAYLOAD_TOO_LARGE` | Corpo acima de 16 KB | Não acontece com o limite de 1000 caracteres respeitado |
| 429 | `LIMITE_USO_ASSISTENTE` | Créditos da semana acabaram (#166). Vem **antes** de o stream abrir, como JSON comum | Ler `Retry-After` (segundos até segunda 00:00, Brasília); mostrar "créditos da semana esgotados, renovam na segunda" e desabilitar o envio. A barra de uso (`GET /api/assistente/uso`) já avisa antes |
| 503 | `ASSISTENTE_OCUPADO` | Sem vaga: a Ceres atende no máximo N perguntas ao mesmo tempo, **ou o usuário já tem uma pergunta em andamento** | Ler `Retry-After` (segundos, hoje `5`), mostrar "Ceres ocupada, tentando de novo em 5 s" e reenviar uma vez. Desabilitar o envio enquanto houver pergunta em andamento evita o segundo caso |
| 503 | `ASSISTENTE_TEMPO_ESGOTADO` | Pergunta passou de 5 min | "A Ceres demorou demais. Tente uma pergunta mais específica." |
| 503 | `ASSISTENTE_INDISPONIVEL` | Modelo fora do ar | "Ceres indisponível no momento"; o resto do sistema segue normal |
| 500 | `INTERNAL_ERROR` | Falha inesperada | Mensagem genérica |

No streaming, os erros que acontecem depois de o stream abrir (`503 ASSISTENTE_TEMPO_ESGOTADO`,
`503 ASSISTENTE_INDISPONIVEL` e `500 INTERNAL_ERROR`) chegam como **evento `erro`**, com o mesmo
envelope.

### Barra de uso: `GET /api/assistente/uso` (#166)

```json
{ "plano": "padrao", "habilitada": true, "creditosSemana": 1000, "consumidos": 120,
  "restantes": 880, "renovaEm": "2026-10-19T00:00:00-03:00" }
```

- A semana vai de **segunda 00:00 a domingo 23:59** (Brasília) e a sobra **não acumula**.
- Mostre **créditos**, nunca tokens. Uma pergunta simples custa poucos créditos; uma que consulta
  várias funções e trechos de lei custa mais.
- `restantes` já desconta as perguntas em andamento. Uma pergunta só começa se houver saldo para
  o teto dela (hoje cerca de 73 créditos); o que sobra do teto volta no fim. Uma pergunta que
  falha não custa nada, e no modo fallback (sem modelo) também não.
- `habilitada: false`: a cota está desligada (demonstração) e nada é bloqueado.

---

## 6. Regras de exibição

1. **`resposta` é texto puro.** Renderize com interpolação (`{{ }}`) e `white-space: pre-line`.
   Nunca `[innerHTML]`: o texto vem de um modelo de linguagem.
2. **Citações sempre visíveis** quando `citacoes` não estiver vazio: `norma` + `dispositivo` como
   link para `url` (`target="_blank" rel="noopener noreferrer"`) e o `trecho` expansível ("ver
   trecho da lei"). A referência no texto ("LC 214/2025, art. 138, § 1º") é a mesma de
   `norma` + `dispositivo`.
3. **Origem dos números:** quando `fontesDados` não estiver vazio, mostrar "Calculado a partir das
   suas notas" com as consultas, usando rótulos em português:

   | `funcao` | Rótulo sugerido | `parametros` possíveis |
   |---|---|---|
   | `totalComprasPorInsumo` | Compras de insumo | `descricaoOuNcm`, `dataInicio`, `dataFim` (AAAA-MM-DD) |
   | `consultarApuracao` | Apuração de IBS/CBS | `mes` (AAAA-MM) |
   | `consultarCreditos` | Créditos de IBS/CBS | `dataInicio`, `dataFim` |
   | `listarNotas` | Notas fiscais | `tipo`, `situacao`, `dataInicio`, `dataFim`, `limite` |
   | `proximosVencimentos` | Guias a vencer | `dias` |
   | `guiasEmAtraso` | Guias em atraso | nenhum |
   | `vencimentosDoMes` | Guias para pagar no mês | `mes` (AAAA-MM) |
   | `consultarNota` | Nota fiscal | `numero` |
   | `totalVendasPorProduto` | Vendas de produto | `descricaoOuNcm`, `dataInicio`, `dataFim` |

   Os valores de `parametros` já vêm mascarados (CPF, CNPJ etc. viram `[CPF]`...). Podem ser
   exibidos como estão.
4. **Uma pergunta por vez:** desabilitar o botão de envio até `fim`/`erro` (streaming) ou até a
   resposta (síncrono).
5. **`rota`:** opcional mostrar um selo discreto ("dados", "legislação", "dados + legislação").
   `FORA_DE_ESCOPO` já traz o texto de apresentação da Ceres.
6. **Aviso fixo** abaixo do chat: "A Ceres é uma assistente informativa e não substitui a análise
   do seu contador."

---

## 7. Tipos TypeScript

```ts
export type RotaCeres = 'DADOS' | 'LEGISLACAO' | 'MISTO' | 'FORA_DE_ESCOPO';

export interface CeresPergunta {
  pergunta: string; // 1..1000 caracteres
}

export interface CeresCitacao {
  norma: string;       // "LC 214/2025"
  dispositivo: string; // "art. 138, § 1º" | "Anexo IX, item 1"
  url: string;         // link oficial do Planalto
  trecho: string;      // texto do dispositivo
}

export interface CeresFonteDado {
  funcao: string;                     // ver tabela da seção 6
  parametros: Record<string, string>;
}

export interface CeresResposta {
  resposta: string;
  rota: RotaCeres;
  citacoes: CeresCitacao[];
  fontesDados: CeresFonteDado[];
  modoFallback: boolean;
  atalhos: ('NOTAS' | 'APURACAO' | 'CREDITOS' | 'CALENDARIO')[];  // só no modo fallback
}

export interface ApiErrorDetail {
  code: string;
  field?: string;
  message: string;
  suggestion?: string;
  rejectedValue?: unknown;
  expectedValue?: unknown;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  errors: ApiErrorDetail[];
}

export type CeresEvento =
  | { evento: 'rota'; dado: { rota: RotaCeres } }
  | { evento: 'resposta'; dado: CeresResposta }
  | { evento: 'fim'; dado: Record<string, never> }
  | { evento: 'erro'; dado: ApiError };
```

---

## 8. Checklist de integração

- [ ] Serviço `CeresApi` com `perguntar()` (síncrono) e `perguntarEmStream()` (fetch + SSE)
- [ ] Header `X-Usuario-Id` pelo mesmo interceptor do resto da API (no `fetch` do streaming, enviar à mão: o interceptor do `HttpClient` não o alcança)
- [ ] Timeout do cliente acima de 5 min no modo síncrono
- [ ] Contador de caracteres e validação de 1 a 1000
- [ ] Envio desabilitado enquanto há pergunta em andamento
- [ ] `Retry-After` lido no 503 `ASSISTENTE_OCUPADO`, com uma nova tentativa
- [ ] Citações com link oficial e trecho expansível
- [ ] "Calculado a partir das suas notas" com os rótulos de `fontesDados`
- [ ] `resposta` renderizada como texto (sem `innerHTML`)
- [ ] Chat escondido para contador (403)
- [ ] Mensagens para `ASSISTENTE_INDISPONIVEL` e `ASSISTENTE_TEMPO_ESGOTADO`
- [ ] Cancelar a pergunta ao sair da tela (`AbortController`)
