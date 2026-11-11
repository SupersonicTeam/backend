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
| `FORA_DE_ESCOPO` | Qualquer outro assunto, ou intenção incerta; e, desde a #493, a conversa leve (saudação, agradecimento, despedida, "ok", "quem é você?", "tem certeza?"), respondida pelo backend sem modelo e sem crédito | Texto fixo de recusa (desde a #494: a gentil, que diz o que a Ceres faz e dá exemplos, ou a firme, sem exemplo, para tentativa de injeção e pedido sobre outra pessoa), ou a resposta da conversa leve (com `atalhos`, `citacoes` e `fontesDados` vazios e `modoFallback: false`) |

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
  "resposta": "De 02/11/2026 a 02/12/2026 vence 1 guia nao paga, somando R$ 1.840,55:\n- FUNRURAL (contribuicao previdenciaria do produtor rural), vence em 20/11/2026: R$ 1.840,55.",
  "rota": "DADOS",
  "citacoes": [],
  "fontesDados": [
    { "funcao": "proximosVencimentos", "parametros": { "dias": "30" }, "origem": "PRODUTOR" }
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
| `fontesDados` | `FonteDado[]` | Consultas usadas. **Vazio quando a resposta foi descartada**: sem selo. Cada item traz `origem` (abaixo), que decide o selo |
| `modoFallback` | `boolean` | `true` quando o modelo estava fora e a resposta foi montada **sem ele** (#146). Exiba um aviso ("assistente em modo limitado") |
| `atalhos` | `("NOTAS" \| "APURACAO" \| "CREDITOS" \| "CALENDARIO")[]` | Telas onde o dado está, para perguntas de dados: no modo fallback e, desde a #266, quando a Ceres não identificou qual dado consultar (`modoFallback: false`). Mapeie cada valor para a rota do Angular e mostre como botões. Vazio nos outros casos |

#### Origem do dado: `fontesDados[].origem` (#342)

Cada `FonteDado` diz de onde veio o dado, para o frontend escolher o selo **sem depender do nome
da função** (função nova do backend aparece com o selo certo, sem deploy do Angular):

| `origem` | Significa | Selo sugerido |
|---|---|---|
| `PRODUTOR` | Calculado a partir dos dados do produtor (notas, guias, apuração, créditos, alertas) | "Calculado a partir das suas notas" |
| `TABELA_OFICIAL` | Consultado em tabela pública oficial (hoje: `consultarTratamentoProduto`, classificação do IBS/CBS) | "Consultado na tabela oficial do IBS/CBS" |

O backend não manda rótulo em português: quem traduz é o frontend. É mudança **aditiva**; o campo
sempre vem preenchido. Valor desconhecido (enum novo no futuro) deve cair num selo neutro ("Consultado pelo AgroConIA"), nunca no de `PRODUTOR`: ele afirmaria que o dado é das notas do produtor.

#### Resposta de dados por template (#266)

Na rota `DADOS` (e na parte de dados do `MISTO`), o texto é montado pelo backend, não pelo modelo:

- **Formato:** parágrafos separados por linha em branco (`\n\n`), um por assunto, e listas com
  itens começando por `- ` (no máximo 5; o resto vira "E mais N, na tela de X."). Renderize as
  quebras de linha e, se quiser, as linhas `- ` como lista. Continua texto puro, nunca HTML.
- **Ordem fixa:** guias em atraso → prazos dos próximos dias → notas paradas → pedidos do
  escritório → notas a conferir → informativos. Com mais de um assunto de atenção, a primeira
  linha é um resumo ("Encontrei 3 pontos de atenção: ...").
- **Pergunta geral** ("o que eu preciso saber?"): nova função `resumoDoProdutor` em
  `fontesDados[].funcao`. Rótulo sugerido para o selo: "Resumo dos seus pontos de atenção".
- **Sem dado identificado:** "Não consegui identificar qual dado consultar..." com `atalhos`
  preenchidos e `modoFallback: false`. Mostre os botões das telas também nesse caso.
- Siglas (IBS, CBS, Funrural, SEFAZ, NCM, CFOP, NF-e) vêm explicadas entre parênteses na primeira
  vez.

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

Sequência real (a primeira linha é um comentário SSE, `:ceres`, que **não é evento**: ignore
toda linha que começa com `:`; o exemplo de consumo abaixo já faz isso, porque só trata blocos com
`event:`). Desde a #499 o mesmo comentário pode vir **também antes de cada chamada ao modelo**,
entre os eventos, quantas vezes a pergunta chamar o modelo:

```text
:ceres

event:rota
data:{"rota":"DADOS"}

event:resposta
data:{"resposta":"A guia de FUNRURAL vence em 20/10/2026 e tem valor de R$ 1.840,55.","rota":"DADOS","citacoes":[],"fontesDados":[{"funcao":"proximosVencimentos","parametros":{"dias":"30"}}],"modoFallback":false}

event:fim
data:{}
```

**Não há tokens parciais**, e é de propósito: números e citações só saem depois de conferidos. O
texto chega inteiro no evento `resposta`.

**Nota de integração (#335):** o stream agora abre com o comentário `:ceres` e os cabeçalhos da
resposta chegam na hora, antes da classificação, então o `fetch` resolve logo. Um cliente que trate
qualquer bloco como evento (sem conferir `event:`) quebra nessa linha. Corrigido junto um defeito
que, de vez em quando, fechava o stream depois da `rota` sem `resposta`, `fim` nem `erro`. Se ainda
acontecer de o stream terminar sem `fim` nem `erro`, trate como falha de conexão e mostre o botão
de tentar de novo.

**Erros antes de o stream abrir** (400, 401, 403, 413 e 503 `ASSISTENTE_OCUPADO`) vêm como resposta
HTTP comum, **em JSON**, mesmo com `Accept: text/event-stream`. Confira `response.ok` antes de ler o
stream.

**Cancelar:** abortar o `fetch` (`AbortController`) fecha a conexão. O backend percebe a saída na
próxima escrita do stream (desde a #499, também no comentário antes de cada chamada ao modelo; ou
no limite de 5 min), interrompe a pergunta e libera a vaga (desfecho `CANCELADA` na auditoria). Até
lá, uma nova pergunta do mesmo usuário pode receber `503 ASSISTENTE_OCUPADO`: trate com o
`Retry-After`.

**Crédito de uma pergunta cancelada (#499):** ela paga o que já gastou (as chamadas ao modelo
concluídas e a entrada da que estava em curso), nunca mais que o reservado; sem nenhuma chamada,
não paga nada. Falha do servidor (tempo esgotado, modelo fora, erro) não cobra. O acerto acontece
quando a Ceres para, depois de o `fetch` ser abortado: **recarregue o `GET /api/assistente/uso` uns
5 s depois de cancelar**, e não logo em seguida.

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

## 4a. Continuidade da conversa (#499)

Atrás da flag `AI_CONTINUIDADE_HABILITADA` do backend, **desligada por padrão**. Desligada, nada
muda: o `historico` é ignorado e a resposta não traz os campos novos. Ligada, vale para todo
produtor.

**Resposta** (`POST /chat` e evento `resposta` do SSE), dois campos novos, **fora do JSON quando
nulos**:

- `perguntaInterpretada`: a pergunta que a Ceres considerou, quando a continuidade a mudou ("e
  algum crédito?" → "tenho algum crédito?"; "tem certeza?" → a pergunta anterior).
- `turno`: `{pergunta, resposta, assinatura}`. Guarde **exatamente como veio** e devolva no
  `historico` da próxima pergunta. Não há `turno` em conversa leve, recusa, fora de escopo,
  fallback, erro, pergunta cancelada e falha da reexecução. Nos textos fixos de lei o `turno` é o
  da resposta de origem (os mesmos bytes): o frontend junta os repetidos.

**Requisição:**

```json
{
  "pergunta": "tem certeza?",
  "historico": [ { "pergunta": "...", "resposta": "...", "assinatura": "v1...." } ],
  "ultimaRespostaSemTurno": false
}
```

- `historico`: opcional, **até 3 itens**, do mais antigo para o mais recente, cada um igual ao
  `turno` recebido. Mais de 3 → 400 de corpo ilegível; item nulo ou vazio → 400
  `VALIDATION_ERROR` com `field` `historico[i]` (ou `.pergunta`, `.resposta`, `.assinatura`), sem
  `rejectedValue`. Tamanhos: pergunta até 1000, resposta até 6000, assinatura até 2048.
- `ultimaRespostaSemTurno` (padrão `false`): mande `true` quando a **última mensagem da Ceres na
  tela não trouxe `turno`**. Aí "tem certeza?" e "por quê?" recebem o texto fixo do seguimento, em
  vez de reconferir uma resposta antiga.
- Par adulterado, de outra conta ou vencido (a validade é de **30 minutos** depois de cada
  pergunta) é ignorado em silêncio: nunca 400. Limpe o histórico ao sair da tela ou trocar de conta.
- O limite de corpo da Ceres passou a **80 KB** (o maior corpo legítimo tem 75 544 bytes).

**Aviso na tela:** `GET /api/assistente/uso` ganhou `conversaVaiAoProvedor` (boolean). Com `true`,
mostre uma linha fixa de aviso: as trocas anteriores vão ao provedor do modelo, fora do Brasil.

**SSE:** nenhum evento novo. Os comentários `:ceres` (linhas que começam com `:`) seguem
ignorados.

## 5. Erros

Todo erro usa o envelope padrão da API:

```json
{
  "timestamp": "2026-09-30T14:00:05.804-03:00",
  "status": 503,
  "error": "Service Unavailable",
  "message": "A assistente Ceres está indisponível no momento. Tente novamente em instantes.",
  "path": "/api/assistente/chat",
  "errors": [
    { "code": "ASSISTENTE_INDISPONIVEL", "message": "A assistente Ceres está indisponível no momento. Tente novamente em instantes." }
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
  o teto dela (hoje 32 créditos com os padrões; eram 73 até a revisão da #166 depois da #270); o
  que sobra do teto volta no fim. Uma pergunta que
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
   | `consultarTratamentoProduto` | Tabela oficial de classificação tributária (#283) | `produtoOuNcm` |
   | `resumoDoProdutor` | Resumo dos seus pontos de atenção (#266) | nenhum |

   O selo vem de `origem` (#342), não do nome da função: `PRODUTOR` = "Calculado a partir das suas
   notas"; `TABELA_OFICIAL` = "Consultado na tabela oficial do IBS/CBS". O rótulo da tabela acima
   continua sendo só do nome da função. `consultarTratamentoProduto` **não** é dado do produtor: é
   a tabela oficial (IT 2025.002), igual para todos, e chega com `origem: "TABELA_OFICIAL"`. A
   resposta vem com `citacoes` (o artigo da LC 214/2025 do
   tratamento). Sugestão de atalho do chat: "O [produto] tem redução de IBS e CBS?".

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
  origem: 'PRODUTOR' | 'TABELA_OFICIAL'; // #342: decide o selo; não usar o nome da função
}

export interface CeresResposta {
  resposta: string;
  rota: RotaCeres;
  citacoes: CeresCitacao[];
  fontesDados: CeresFonteDado[];
  modoFallback: boolean;
  atalhos: ('NOTAS' | 'APURACAO' | 'CREDITOS' | 'CALENDARIO')[];  // modo fallback e pergunta de dados sem dado identificado (#266)
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
