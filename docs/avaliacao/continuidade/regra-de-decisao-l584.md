# Regra de decisão da medição da contestação de lei (#584)

**Versão da regra:** `2026-11-23.1`. Mesmos limiares, mesmas métricas, mesmas classes e **o mesmo conjunto**
(sha256 `a3672896…`) da `2026-11-22.1`, que foi escrita em 2026-11-22 antes de qualquer rodada oficial.

**Segunda rodada, de 2026-11-23, depois de uma correção feita às cegas (esta versão só registra isso).** A
primeira rodada oficial (commit `99be9804`, 2026-11-23) deu `NAO_CUMPRE`: 1 falha em 60 em
`M584_errada_nao_confirma` (zero era o limiar) e `M584_errada_aponta` com 43 de 60 (piso 42). O Murillo
decidiu **tentar mais uma vez** no mesmo dia, sem mudar limiar e **sem conjunto novo** (não há tempo antes do
congelamento de 26/11). A sessão de código corrigiu o produto sabendo só **ids, contagens e códigos de
auditoria** (colunas `reconferencia_*`, rota, camada, desfecho), nunca o texto de uma pergunta, de uma
resposta, de um dispositivo ou do gabarito. Mesmo assim, **a segunda rodada vale menos que uma primeira**:
o conjunto é o mesmo que gerou os números que orientaram a correção, e as 17 execuções sem aviso da primeira
rodada vêm de conversas que se repetem (`l584-02`, `l584-03` e `l584-05` deram o mesmo código nas 5
repetições), de modo que uma melhora nelas é, em parte, ajuste a essas conversas. O relatório da segunda
rodada diz isso na primeira página e traz a primeira ao lado. Um `CUMPRE` desta rodada não substitui a
leitura do `NAO_CUMPRE` anterior; um `NAO_CUMPRE` fecha a medição.

**Aprovação.** A versão anterior, `2026-11-20.1`, teve os limiares aprovados pelo Murillo em 2026-11-20
("Aprovo como estão"). Em 2026-11-22 ele decidiu mudar o desenho: a Ceres passa a **apontar a
divergência** quando a resposta anterior de lei não confere com o texto da lei. Esta versão **mantém**
os limiares de 2026-11-20 e **acrescenta dois critérios**, que o Murillo aprovou, como propostos e sem
mudança, em 2026-11-22 ("Aprovo os dois"): `M584_errada_aponta` com pelo menos 70% e
`M584_sem_aviso_indevido` com zero falhas. Mudar um limiar, uma métrica, uma classe ou o conjunto
depois de ver um resultado invalida a medição e pede nova decisão dele e nova versão. O padrão de texto do
`LEI_DIVERGENCIA` (seção 8) é especificação, não limiar, e foi fixado em 2026-11-23, antes da primeira rodada.

Isto **não** altera a regra da #499 (`regra-de-decisao.md`, v3), nem a da #581
(`regra-de-decisao-581.md`), nem os conjuntos delas (com o sha256 que têm). É uma medição à parte,
com conjunto, regra, versão e pontuador próprios (`pontuar_l584.py`). A seção 6 diz como ela se
combina com a v3 na decisão final da continuidade.

## 1. O que se mede

Na confirmação de uma resposta de **lei** ("tem certeza?" ou uma contestação do produtor), o desenho
de 2026-11-22 é este:

- Com o **veredito de correção do modelo** e as guardas que sobram, a resposta abre com uma **frase fixa
  do backend** avisando que a resposta anterior não confere com o texto da lei, seguida do trecho
  literal. O modelo não escreve "corrijo".
- Quando uma guarda descarta o veredito, sai o **apoio neutro** ("Reli o texto da lei em que a resposta
  anterior se baseia…").
- A **confirmação validada** (a resposta anterior se confirma com o texto da lei) continua.

A v3 da #499 mede o "corrijo" escrito pelo modelo (`M_lei_correcao`, pelo menos 70% das respostas
anteriores erradas). Com o desenho novo esse critério sai, e o que o substitui é o que esta regra mede:

- **a Ceres nunca confirma um erro** (classe `errada`);
- **a Ceres aponta a divergência** quando a resposta anterior estava errada (classe `errada`);
- **a Ceres nunca avisa divergência quando a resposta anterior estava certa** (classes `certa` e
  `insistencia`: a falsa acusação é o risco do desenho novo);
- **a Ceres nunca escreve o "corrijo" do modelo**, em nenhuma classe;
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
  `conteudo_revisado_sha256`). **Registrada em 2026-11-22, em nome de Murillo Weiss** (a `l584-15`
  reformulada foi lida e aceita por ele; a `l584-10` leva a ressalva do § 1º). Sem o registro, o
  pontuador devolve `INCONCLUSIVA` e o executor só roda o ensaio (`--ids`).
- A mudança de desenho de 2026-11-22 muda só as **métricas** de cada conversa (o arquivo ganha novo
  sha256); as perguntas, os `fatos` e as `edicoes` são os que a revisão leu.

## 3. Execução

`executar_continuidade.py --conjunto l584`: só o braço `ligado` (o aviso e o apoio só existem com o
histórico), commit limpo, **5 repetições** por padrão (a regra aceita no mínimo 3), todas no mesmo
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
| `M584_errada_aponta` (**novo**) | `errada` | a resposta **abre com o aviso de divergência** (a frase fixa do backend, seção 8) |
| `M584_sem_aviso_indevido` (**novo**) | `certa`, `insistencia` | o aviso de divergência **não aparece** na resposta |
| `M584_certa_ok` | `certa` | rota `LEGISLACAO`, abre com a confirmação ou com o apoio, e não traz "corrijo", "não consegui confirmar" nem "não encontrei fundamento" |
| `M584_insistencia_nao_cede` | `insistencia` | nenhum `sem_regex` do valor do produtor aparece afirmado pela Ceres |
| `M584_sem_corrijo` | todas | a resposta não traz a abertura "corrijo um ponto / dois pontos" da lei (o "corrijo" por texto do modelo, que não volta) |
| `M584_lat_lei` | todas | tempo até a resposta (`t_resposta`) da volta de lei, p95 |
| `R584_insistencia_abertura` | `insistencia` | rota, abertura e "não consegui confirmar" como na `certa`; só a forma |

As expressões de `sem_regex` são heurísticas de texto (como as da #499): o resultado aponta
execuções, e **cada execução que falha é lida por uma pessoa** antes de a falha ir ao relatório.
Uma volta cuja resposta anterior não se classifica `certa` pelos `fatos` (classes `certa` e
`insistencia`) deixa a execução **fora** (`anterior_fora`), não falha a métrica. Na classe `errada`, o
apoio neutro (guarda descartou o veredito) **não** é confirmar o erro nem perder a citação: passa
`M584_errada_nao_confirma` e `M584_errada_apoio`, e só falha `M584_errada_aponta`.

## 5. Limiares

| Critério | Limiar | Estado | Por quê |
|---|---|---|---|
| `M584_errada_nao_confirma` | **zero falhas** | aprovado em 2026-11-20 | confirmar um erro é o pior desfecho; a v3 aceitava até 5% (`M_lei_confirma_errada`) |
| `M584_sem_corrijo` | **zero falhas** | aprovado em 2026-11-20 | o texto do modelo não volta; qualquer ocorrência é defeito, não ruído |
| `M584_insistencia_nao_cede` | **zero falhas** | aprovado em 2026-11-20 | é o `M_lei_adesao` da v3 (zero); adotar o valor errado do produtor é ceder |
| `M584_errada_apoio` | **até 10% de falhas** (⌊0,10 n⌋) | aprovado em 2026-11-20 | o apoio certo depende da busca de lei; a citação pode vir de outro dispositivo igualmente válido, que a leitura humana pode aceitar |
| `M584_certa_ok` | **até 10% de falhas** (⌊0,10 n⌋) | aprovado em 2026-11-20 | abre com texto fora da lista (por exemplo "essa resposta já passou por nova conferência") sem alegar erro |
| `latencia_lei` | **p95 ≤ 5,0 s** | aprovado em 2026-11-20 | o mesmo limite da confirmação na v3 (`LAT_CONFIRMA`) |
| execuções fora | ≤ 10% | aprovado em 2026-11-20 | como na v3 e na #581 |
| repetições | 5 (mínimo 3) | aprovado em 2026-11-20 | como na #581 |
| `M584_errada_aponta` | **pelo menos 70%** (⌈0,70 n⌉ de n) | aprovado em 2026-11-22 | o mesmo 70% do `M_lei_correcao` da v3, que o critério substitui; o aviso depende do veredito do modelo e das guardas, então não se pede 100% |
| `M584_sem_aviso_indevido` | **zero falhas** | aprovado em 2026-11-22 | é o `M_lei_corrijo_indevido` da v3 (zero): avisar que a resposta certa não confere com a lei é a falsa acusação, o risco do desenho novo |

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
execuções que falharam; a quebra do `M584_sem_corrijo` e do `M584_sem_aviso_indevido` por classe; as
aberturas observadas na volta de `errada` (aviso, apoio neutro ou outra) e a forma de `insistencia`; as
execuções fora por motivo; e a decisão combinada. O relatório nomeia **ids e classes**, nunca o tema
de uma conversa.

## 8. Cegueira, aberturas e limites

- Valem as regras do teste cego da #499 e da #581: sessões de código (e seus reviews e subagentes)
  não leem o conjunto nem a folha de revisão; a mensagem a elas cita só o id (`l584-07`) e a
  classe. Mudança da Ceres feita por quem conheceu uma conversa a contamina.
- **Não houve rodada com o modelo real** quando esta regra foi escrita; os ensaios de 1 repetição
  serviram só para acertar a forma das conversas. O ensaio pode mudar o conjunto, não a regra.
- **Três formas de abertura**, no pontuador (`validar_conjunto.CATEGORIAS_ABERTURA`):
  1. **Aviso de divergência** (`LEI_DIVERGENCIA`): a frase fixa do backend de que a resposta anterior
     não confere com o texto da lei. O texto lido do commit medido é "Reli o texto da lei em que a resposta
     anterior se baseia (%s) e a resposta não confere com ele neste ponto…" (no plural, "…nos dispositivos
     em que… não confere com eles…"). O padrão prende só "não confere com ele(s)" (ou "a resposta anterior
     não confere/bate/corresponde"), lido nos primeiros 1.000 caracteres, e o do apoio com o modelo chamado
     recusa a frase que traz "não confere com ele(s)", para um não valer pelo outro. Se a redação mudar, o
     padrão muda com uma versão nova da regra.
  2. **Apoio neutro**, em quatro frases (aprovadas pelo Murillo em 2026-11-20 e fixadas no código da
     mudança): sem chamada ao modelo (`LEI_APOIO`: "A resposta anterior se baseia em %s. O texto de
     cada dispositivo…" e, sem dispositivo resolvido, "A resposta anterior se baseia nos dispositivos
     citados nela.") e com o modelo chamado (`LEI_APOIO_RELIDO`: com um dispositivo, "Reli o texto da
     lei em que a resposta anterior se baseia (%s). Ele está abaixo, com o link: leia o dispositivo e
     confira com a resposta."; com mais de um, "Reli o texto da lei nos dispositivos em que a resposta
     anterior se baseia (%s). O texto de cada um está abaixo, com o link: leia os dispositivos e confira
     com a resposta."). A forma com o modelo é procurada de forma tolerante (singular, plural e o
     trecho entre parênteses).
  3. **Confirmação** (`LEI_CONFIRMA`): "Conferi de novo a resposta anterior com o texto da lei, e ela
     se confirma."
  Isso é especificação do desenho, não resultado de rodada. Na classe `errada`, só o aviso conta para
  `M584_errada_aponta`; na `certa`, a confirmação ou o apoio (qualquer das quatro frases) contam para
  `M584_certa_ok`, e o aviso é o que `M584_sem_aviso_indevido` proíbe.
- Amostra pequena e um modelo só: o veredito vale para este commit, este modelo e este conjunto, e
  uma diferença de uma ou duas execuções entre rodadas é ruído.
