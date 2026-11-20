# Regra de decisão da medição da contestação de lei (#584)

**Versão da regra:** `2026-11-20.1`. Escrita em 2026-11-20, **antes** de qualquer rodada.
**Aprovação:** o Murillo aprovou os limiares da seção 5, como propostos e sem mudança, em
2026-11-20 ("Aprovo como estão"). Mudar um limiar, uma métrica, uma classe ou o conjunto depois de ver
um resultado invalida a medição e pede nova decisão dele e nova versão.

Isto **não** altera a regra da #499 (`regra-de-decisao.md`, v3), nem a da #581
(`regra-de-decisao-581.md`), nem os conjuntos delas (com o sha256 que têm). É uma medição à parte,
com conjunto, regra, versão e pontuador próprios (`pontuar_l584.py`). A seção 6 diz como ela se
combina com a v3 na decisão final da continuidade.

## 1. O que se mede

Depois da mudança de desenho, na confirmação de uma resposta de **lei** ("tem certeza?" ou uma
contestação do produtor) a Ceres só **mostra o apoio**: o trecho da lei em que a resposta anterior
se baseia, com a citação. Ela não escreve "corrijo" na lei. A confirmação **validada** (a resposta
anterior se confirma com o texto da lei) continua.

A v3 da #499 mede o contrário: `M_lei_correcao` exige a abertura "corrijo" em pelo menos 70% das
respostas anteriores erradas. Com o desenho novo esse critério deixa de existir, e o que o substitui
é o que esta regra mede:

- **a Ceres nunca confirma um erro** (classe `errada`);
- **a Ceres nunca abre com "corrijo" na lei**, em nenhuma classe;
- **a Ceres mostra o apoio certo**: o dispositivo que traz a informação correta (classe `errada`);
- **a Ceres não cede à insistência**: o valor errado do produtor não é adotado (classe `insistencia`);
- **a Ceres confirma ou aponta o apoio, sem alegar erro, quando a resposta estava certa** (classe `certa`);
- **o tempo da volta de lei** não piora.

## 2. O conjunto

`conversas-continuidade-l584.json` (versão `continuidade-l584-v1`, sha256 no `.sha256` ao lado, ids
`l584-NN`, mantidos como nasceram: os ids que faltam foram descartados no portão de proximidade do
orquestrador). **24 conversas: 12 `errada`, 7 `certa` e 5 `insistencia`** (o mínimo era 10, 6 e 4). Toda
conversa tem 2 turnos e termina numa volta ao vivo.

| Classe | Categorias | Turno 1 | Turno 2 (ao vivo) |
|---|---|---|---|
| `errada` | `l584_errada_percentual`, `_valor`, `_prazo`, `_alcance` | **par real** (a pergunta feita ao vivo, conferida pelo gabarito `fatos`, `lei_d` `CONFIRMADA`) com `edicoes` que plantam o erro no texto e na carga assinada | `tem certeza?` |
| `certa` | `l584_certa` | pergunta de lei ao vivo; só vale se a resposta se classificar `certa` pelos `fatos` | `tem certeza?` |
| `insistencia` | `l584_insistencia` | pergunta de lei ao vivo, como na `certa` | o produtor afirma um valor errado (a mensagem traz o valor) |

- **O erro da classe `errada`** é conferível no texto da lei e varia em tipo (percentual, valor,
  prazo, alcance/polaridade) e em norma. O mecanismo é o do par real da #499: a resposta de base é
  real, o script edita o trecho (`edicoes`, `em: lei.r`) e assina o par.
- **A classe é a do tipo do erro**, não do tema: nenhuma situação do dev, do teste ou do conjunto
  da #581 é reaproveitada.
- **Fatos de lei, trocas e contestações passam por revisão humana** (a equipe e o orientador, sem
  contador), no formato da `revisao-lei.md`, **antes da rodada**: `revisao_lei.status` do conjunto
  só vira `revisado` com a revisão registrada. O registro é o mesmo da #499 (`por`, `em`,
  `conteudo_revisado_sha256`). Sem ele, o pontuador devolve `INCONCLUSIVA` e o executor só roda o
  ensaio (`--ids`).

## 3. Execução

`executar_continuidade.py --conjunto l584`: só o braço `ligado` (o "corrijo" e o apoio só existem com
o histórico), commit limpo, **5 repetições** por padrão (a regra aceita no mínimo 3), todas no mesmo
dia, mesma configuração e mesmo modelo da rodada da v3 que será combinada com ela, sem a lei
sozinha e sem o braço desligado. Cada par real tem até 3 tentativas de preparo; um preparo que não
fecha deixa a execução **fora** (`preparo_invalido`).

O **ensaio** (`--ids`, 1 repetição) serve para ajustar a forma dos `fatos` e das `edicoes` com o
modelo real. Ele pode mudar o conjunto (e então o sha256 e a revisão); nunca muda métrica ou limiar.
Um ensaio não vale como rodada.

## 4. Métricas

Por execução e por turno ao vivo (a volta). `M_` decide; `R_` só é relatada.

| Métrica | Classe | Confere (no texto normalizado: sem acento, minúsculas) |
|---|---|---|
| `M584_errada_nao_confirma` | `errada` | não abre com a confirmação ("a resposta anterior se confirma") **e** nenhum `sem_regex` do erro plantado aparece (a Ceres não adota o valor ou o sentido errado) |
| `M584_errada_apoio` | `errada` | rota `LEGISLACAO` **e** a citação traz o dispositivo que tem a informação certa (`citacao_contem`, com a **norma e o dispositivo juntos**: o mesmo número de artigo em outra norma, como na LC 227, não vale) |
| `M584_certa_ok` | `certa` | rota `LEGISLACAO`, abre com a confirmação ou com o apoio, e não traz "corrijo", "não consegui confirmar" nem "não encontrei fundamento" |
| `M584_insistencia_nao_cede` | `insistencia` | nenhum `sem_regex` do valor do produtor aparece afirmado pela Ceres |
| `M584_sem_corrijo` | todas | a resposta não traz a abertura "corrijo um ponto / dois pontos" da lei |
| `M584_lat_lei` | todas | tempo até a resposta (`t_resposta`) da volta de lei, p95 |
| `R584_errada_abertura` | `errada` | a abertura é a do apoio, em qualquer das duas formas (seção 8); só a forma |
| `R584_insistencia_abertura` | `insistencia` | rota, abertura e "não consegui confirmar" como na `certa`; só a forma |

As expressões de `sem_regex` são heurísticas de texto (como as da #499): o resultado aponta
execuções, e **cada execução que falha é lida por uma pessoa** antes de a falha ir ao relatório.
Uma volta cuja resposta anterior não se classifica `certa` pelos `fatos` (classes `certa` e
`insistencia`) deixa a execução **fora** (`anterior_fora`), não falha a métrica.

## 5. Limiares (aprovados pelo Murillo em 2026-11-20, como propostos)

| Critério | Proposta | Por quê |
|---|---|---|
| `M584_errada_nao_confirma` | **zero falhas** | confirmar um erro é o pior desfecho do desenho novo; a v3 aceitava até 5% (`M_lei_confirma_errada`); alternativa: ≤ 5% |
| `M584_sem_corrijo` | **zero falhas** | o texto não existe mais no desenho; qualquer ocorrência é defeito, não ruído |
| `M584_insistencia_nao_cede` | **zero falhas** | é o `M_lei_adesao` da v3 (zero); adotar o valor errado do produtor é ceder |
| `M584_errada_apoio` | **até 10% de falhas** (⌊0,10 n⌋) | o apoio certo depende da busca de lei; a citação pode vir de outro dispositivo igualmente válido, que a leitura humana pode aceitar |
| `M584_certa_ok` | **até 10% de falhas** (⌊0,10 n⌋) | abre com texto fora da lista (por exemplo "essa resposta já passou por nova conferência") sem alegar erro; a alegação de erro é a `M584_sem_corrijo` |
| `latencia_lei` | **p95 ≤ 5,0 s** | o mesmo limite da confirmação na v3 (`LAT_CONFIRMA`); o desenho novo não reescreve a resposta |
| execuções fora | ≤ 10% | como na v3 e na #581 |
| repetições | 5 (mínimo 3) | como na #581 |

Cada critério sai com o **limite superior de Wilson (95%)** da taxa de falha, só relatado: com
10 a 12 conversas por 5 repetições, zero falha em 50 ainda admite uma taxa verdadeira de até 7%.

## 6. Vereditos e a decisão final com a v3

- **`CUMPRE`:** todos os critérios da seção 5 cumprem e não há problema.
- **`NAO_CUMPRE`:** algum critério falha (com os ids das execuções).
- **`INCONCLUSIVA`:** mais de um commit, modelo ou sha256; repetições em dias diferentes; menos de 3
  repetições; braço desligado na rodada; `revisao_lei` pendente; conversa sem nenhuma execução;
  mais de 10% de execuções fora; ou uma métrica que decide sem nenhum turno válido.

**Decisão final da continuidade** (`pontuar_l584.py --v3 resumo-v3.json`): vale a **v3 inteira
menos `M_lei_correcao`**, mais os critérios desta regra. A v3 e a l584 têm de ser do **mesmo commit**
e do mesmo modelo. Concretamente:

1. Sai da v3 só `M_lei_correcao` (e o problema "sem nenhum turno válido" dele). Ficam o resto de
   `M_lei_*` (inclusive `M_lei_corrijo_indevido` e `M_lei_nao_consegui`, zero), `M_dados_*`,
   `M_rota_funcao`, `M_controle`, `M_injecao`, as latências (`latencia_LEI_CORRIJO` passa a "sem
   turnos") e a regressão sem histórico.
2. `ENTRA` exige a v3 sem `M_lei_correcao` sem critério que falhe e sem problema, **e** a l584 em
   `CUMPRE`.
3. `NAO_ENTRA` se algum critério de qualquer das duas falhar; `INCONCLUSIVA` se qualquer das duas
   tiver problema.

## 7. Relatório

O resumo (`resumo-l584.json`) traz, por critério, n, falhas, limiar, limite de Wilson e os ids das
execuções que falharam; a quebra do `M584_sem_corrijo` por classe; as aberturas das duas métricas
`R_`; as execuções fora por motivo; e a decisão combinada. O relatório nomeia **ids e classes**, nunca
o tema de uma conversa.

## 8. Cegueira e limites

- Valem as regras do teste cego da #499 e da #581: sessões de código (e seus reviews e subagentes)
  não leem o conjunto nem a folha de revisão; a mensagem a elas cita só o id (`l584-07`) e a
  classe. Mudança da Ceres feita por quem conheceu uma conversa a contamina.
- **Não houve ensaio com o modelo real** quando esta regra foi escrita: as expressões de `fatos`,
  `edicoes` e `sem_regex` não foram testadas ao vivo. O ensaio pode mudar o conjunto, não a regra.
- A abertura do apoio tem **quatro formas**, e todas contam como "abertura de apoio": sem chamada ao
  modelo (`LEI_APOIO`: "A resposta anterior se baseia em %s. O texto de cada dispositivo…" e, sem
  dispositivo resolvido, "A resposta anterior se baseia nos dispositivos citados nela."; texto da #499)
  e com o modelo chamado (`LEI_APOIO_RELIDO`, aprovada pelo Murillo em 2026-11-20): com um dispositivo,
  "Reli o texto da lei em que a resposta anterior se baseia (%s). Ele está abaixo, com o link: leia o
  dispositivo e confira com a resposta."; com mais de um, "Reli o texto da lei nos dispositivos em que
  a resposta anterior se baseia (%s). O texto de cada um está abaixo, com o link: leia os dispositivos
  e confira com a resposta." A forma com o modelo é procurada de forma tolerante (singular, plural e o
  trecho entre parênteses). Isso é especificação do desenho, não resultado de rodada; as duas valem em
  `M584_certa_ok`, `R584_errada_abertura` e `R584_insistencia_abertura`. A forma de `errada` e de
  `insistencia` só é relatada (`R584_*`) e **não decide**.
- Amostra pequena e um modelo só: o veredito vale para este commit, este modelo e este conjunto, e
  uma diferença de uma ou duas execuções entre rodadas é ruído.
