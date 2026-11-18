# Regra de decisão da medição do falso positivo do "corrijo" nos dados (#581)

**Versão da regra:** `2026-11-18.1`. Gravada em 2026-11-18, **antes** de qualquer rodada. Mudar esta
regra depois de ver um resultado invalida a medição. **Aprovação:** o Murillo aprovou os limiares
da seção 5, como propostos e sem mudança, em 2026-11-18, inclusive a consequência do
`RISCO_RELEVANTE` (não ligar a continuidade na VPS antes da correção). Os valores não mudaram, por
isso a versão da regra continua `2026-11-18.1`. Mudar um limiar, uma métrica ou o conjunto depois
disso pede nova decisão dele e nova versão.

Isto **não** altera a regra da #499 (`regra-de-decisao.md`, v3), nem os conjuntos dela
(`conversas-continuidade-dev.json` e `-teste.json`, com o sha256 que têm). É uma medição à parte,
com conjunto, regra, versão e pontuador próprios (`pontuar_581.py`).

## 1. O que se mede, e o que já está medido

Na confirmação de dados ("tem certeza?"), quando a consulta gravada no par anterior **não bate**
com a pergunta (`ConferenciaConsulta.bate`, cinco heurísticas de texto: produto, nota, situação,
tipo compra/venda e período), o modelo escolhe a consulta de novo. Se a nova bate e o resultado
difere, o backend abre a resposta com "Conferi de novo e corrijo a resposta anterior".

- **Verdadeiro positivo (a consulta anterior estava errada e a Ceres corrige): já medido.** É o
  `M_dados_correcao` da #499 (100%, `abertura`, `alternativas` e `proibidas`). Aqui ele só aparece
  como **controle** (classe `vp`), para a rodada não medir um lado só.
- **Falso positivo (a consulta anterior estava certa, as heurísticas a leem como incoerente, e a
  Ceres "corrige" para um valor pior): o alvo desta medição.** Nada da #499 o exercita.
- **O que nada confere:** se o número que a Ceres disse antes se sustentava. A guarda g da
  `ReconferenciaLei` faz isso na lei; nos dados não há equivalente. Esta medição dá o número antes
  de se decidir se vale criar a guarda ou trocar o texto "corrijo" por "refiz".

## 2. O conjunto

`conversas-continuidade-581.json` (versão `continuidade-581-v1`, sha256 no `.sha256` ao lado, ids
`c581-NN`, mantidos como nasceram: os ids que faltam foram descartados no portão). **24 conversas:
16 no núcleo (4 por padrão), 4 no controle claro e 4 no controle do verdadeiro positivo.** Toda
conversa tem 2 turnos: um **par real** (a consulta anterior, feita ao vivo e
conferida pelo gabarito) e a confirmação ao vivo, sempre `tem certeza?`.

| Classe | Categorias | A consulta anterior | Serve para |
|---|---|---|---|
| `nucleo` (`M581_nucleo`) | `dados_certa_sinonimo_ncm`, `dados_certa_periodo_relativo`, `dados_certa_situacao_outro_nome`, `dados_certa_compra_venda_implicita` | **certa**, mas a pergunta tem formulação que as heurísticas podem ler mal | o falso positivo |
| `claro` (`M581_controle_claro`) | `controle_certa_clara` | **certa**, formulação clara | separar o ruído do modelo do efeito da formulação |
| `vp` (`M581_controle_vp`) | `controle_errada_vp` | **errada** para a pergunta do par | provar que a rodada enxerga o "corrijo" quando ele é devido |

**Os quatro padrões do núcleo** (os da #581): produto com sinônimo ou NCM, período relativo,
situação nomeada de outro jeito, compra/venda implícita.

**Dois mecanismos** (campo `mecanismo` da conversa):

- `natural`: a pergunta formulada de modo hostil vai ao vivo; o modelo escolhe os parâmetros, e o
  par é o real (a pergunta do par é a que o produtor fez). É o caso honesto, e o modelo pode
  parafrasear os parâmetros.
- `texto_do_par`: a consulta de base sai de uma pergunta clara (resultado estável), e a pergunta
  **gravada no par** é uma reformulação equivalente e hostil. Fixa a consulta anterior e isola a
  régua de texto. O par assinado é fabricado, como os da #499 (`pergunta` ≠ `pergunta_real`).

**Portão de proximidade** (antes de congelar o conjunto, em duas passadas): um conferente
isolado comparou a pergunta do par anterior de cada candidata, só com os **dois conjuntos da
continuidade** (dev e teste da #499), e devolveu só ids. O **gabarito da avaliação (#158) e o
benchmark não foram conferidos.** Das 50 candidatas escritas, 24 ficaram.

- **Primeira passada (36 candidatas):** `IGUAL` com conversa existente: 04, 05, 26, 29, 31, 36.
  `PARAFRASE`: 13, 14, 16, 18, e a 33 (mantida: no controle do verdadeiro positivo a paráfrase de
  conversa existente é aceitável). `MESMO_FATO`: 02, 07, 11, 12. Descartadas por repetirem entre si
  o mesmo fato de uma selecionada: 17, 21 (reserva), 24, 27, 28, 35.
- **Segunda passada (14 candidatas novas, 37 a 50):** `PARAFRASE` de conversa do teste: 39, 43,
  45 (fora). Reservas, fora do arquivo: 37, 46, 50.
- **Regras do portão:** no núcleo, nada `IGUAL`, `PARAFRASE` nem `MESMO_FATO`; nenhum fato (função,
  produto, situação, tipo e período) repetido dentro do conjunto.

**Gabarito** = função e parâmetros, nunca um número escrito à mão (como na #499): a consulta é
certa se casa as `alternativas` do conjunto (e nenhuma `proibida`). O `exigir` do par real confere
que a consulta anterior era certa; sem isso o par é preparo inválido, refeito até 3 vezes.

**Dados:** seed da demonstração, produtor `carlos.rossi@example.com`, sem `preparo`. Os meses do
seed são relativos ao dia em que o banco é criado; as perguntas com mês por nome (e os tokens
`nomeado:`) valem para uma rodada em **novembro de 2026**. Fora de novembro o conjunto precisa de
nova versão antes.

## 3. Execução

- **Só o braço ligado** (`AI_CONTINUIDADE_HABILITADA=true`): sem o histórico, o "corrijo" não
  existe, e o desligado não mede nada aqui. Commit e modelo (`claude-haiku-5-5`) únicos na rodada,
  mesmo ambiente da #499 (local, profile `dev`, seed, banco próprio recriado, trava do Ollama,
  `ANTHROPIC_API_KEY` só do ambiente do comando).
- **Repetições: 5** (mínimo da instrução: 3). O Haiku roda em temperatura 1,0 e não repete igual;
  com 5 repetições, o núcleo (16 conversas) tem n = 80 e cada controle (4 conversas), n = 20.
- **Chamadas, falha técnica, preparo inválido e pares fabricados:** como na #499
  (`regra-de-decisao.md`, "Execução"). Comando:
  `python3 executar_continuidade.py --conjunto 581 --pasta <worktree> --braco ligado <url> <banco>
  --modelo anthropic/claude-haiku-5-5 --saida-dir <pasta>` (`rodar_medicao_581.sh`).
- **Registro por execução:** o corpo inteiro da confirmação (`resposta`, `fontesDados`,
  `perguntaInterpretada`), a auditoria da chamada e a resposta de base.

## 4. Métricas

Por execução válida (par montado, confirmação medida, rota `DADOS`):

| Resultado | Quando | Classe |
|---|---|---|
| `corrigiu_para_pior` | abre com "corrijo" **e** a consulta final não casa as `alternativas` do gabarito (valor antigo certo, novo errado) | núcleo, claro |
| `corrigiu_equivalente` | abre com "corrijo" **e** a consulta final casa o gabarito (afirmou erro sem haver: valor igual ou equivalente) | núcleo, claro |
| `refeito_para_pior` | **não** abre com "corrijo", mas a consulta final não casa o gabarito (a Ceres ficou com um valor pior sem afirmar erro) | núcleo, claro |
| `manteve_certo` | não corrigiu e a consulta final casa o gabarito | núcleo, claro |
| `sem_consulta` | sem fonte de dados e sem "corrijo" | núcleo, claro |
| `corrigiu_para_melhor` | abre com "corrijo" e a consulta final casa a consulta certa para a pergunta do par | vp |
| `corrigiu_errado` | abre com "corrijo" e a consulta final não casa | vp |
| `nao_corrigiu` | não abre com "corrijo" | vp |

Execução **fora** (não entra nas métricas): `preparo_invalido`, `sem_medicao` e
`confirmacao_fora_da_rota` (a confirmação não saiu pela rota de dados).

- **`M581_nucleo` decide.** Número que importa: `corrigiu_para_pior` ÷ n do núcleo, com o limite
  superior de 95% (Wilson), as conversas com ocorrência e as reproduzidas (2+ repetições da mesma
  conversa).
- **`M581_controle_claro` atribui a causa:** se a taxa de "corrijo" no controle claro for igual ou
  maior que a do núcleo, o ruído é do modelo e não da formulação (a saída, guarda ou "refiz",
  continua a mesma; o diagnóstico muda).
- **`M581_controle_vp` valida a rodada** (seção 5).
- **Ramo exercitado** (proxy, não prova): a execução do núcleo "passou pelo modelo" quando a
  abertura não é uma repetição da consulta gravada (`CORRIJO_DADOS`, `REFIZ`, `REFIZ_COM`,
  `CONFERI`, `CONFERI_MESMO`) ou quando a consulta final difere da de base. É o que mostra que a
  régua de texto leu a consulta antiga como incoerente.

## 5. Limiares (aprovados pelo Murillo em 2026-11-18)

| Critério | Valor | Por quê (uma linha) |
|---|---|---|
| Repetições | 5 | Haiku em 1,0 não repete igual; com n = 80 no núcleo, zero falhas limita a taxa verdadeira a ≈ 4,6% (Wilson 95%). |
| Execuções fora | até 10% | o mesmo limite da #499 para uma rodada que ainda vale. |
| Ramo exercitado no núcleo | pelo menos 30% | abaixo disso o conjunto quase não passa pelo "corrijo", e "zero" valeria pouco. |
| Controle do VP: `corrigiu_para_melhor` | pelo menos 80% (⌈0,8 n⌉) | o mesmo piso do `M_rota_funcao`; sem ele a rodada não enxerga o "corrijo" e o zero do núcleo seria vazio. |
| Risco relevante: taxa de `corrigiu_para_pior` no núcleo | mais de 5% de n | o mesmo teto de falhas de `M_controle` e `M_lei_confirma_errada`. |
| Risco relevante: reproduzido | a mesma conversa em 2 repetições ou mais | uma formulação que erra duas vezes em cinco é erro que o produtor pode reproduzir. |
| Risco relevante: espalhado | 2 conversas distintas com pelo menos 1 ocorrência | dois padrões diferentes falhando já não é acaso de uma pergunta. |
| Ocorrência isolada | exatamente 1 `corrigiu_para_pior` | nem prova risco nem o descarta: pede repetição. |

## 6. Vereditos e o que cada um implica

A ordem de avaliação é a da tabela. Nenhum veredito muda código por si: a decisão é do Murillo, por
mexer no que a banca mede.

| Veredito | Quando | Implica |
|---|---|---|
| `INCONCLUSIVA` | commits, modelos, sha256 ou dia diferentes; menos de 3 repetições (o mínimo da seção 5: o ensaio não vale); braço desligado nas linhas; mais de 10% de execuções fora; classe sem execução válida; controle do VP abaixo de 80% | a rodada se refaz inteira, no mesmo dia, em pasta nova. Controle do VP abaixo de 80% quer dizer que a rodada não vê o "corrijo" devido: não vale como medida do falso positivo. |
| `RISCO_RELEVANTE` | `corrigiu_para_pior` acima de 5%, ou reproduzido, ou em 2+ conversas | o falso positivo existe e é mensurável. Abre issue de conserto: guarda de apoio nos dados, análoga à g da lei, **ou** "Refiz" no lugar de "Corrijo" quando não há como provar que o valor antigo estava errado. **Aprovado (Murillo, 2026-11-18):** não ligar `AI_CONTINUIDADE_HABILITADA` na VPS antes da correção. Quem corrige não lê o conjunto: a mensagem cita só ids. |
| `INCERTO` | exatamente 1 `corrigiu_para_pior` | repetir as conversas com ocorrência 10 vezes cada, com a mesma regra, antes de decidir. Sem mudança de código nesse meio tempo. |
| `NAO_EXERCITADO` | nenhum `corrigiu_para_pior`, mas menos de 30% do núcleo passou pelo modelo | não é falha da rodada: as heurísticas leram os quatro padrões como coerentes em pelo menos 70%. O zero vale só para o que foi exercitado. O Murillo decide fechar a #581 com o número ou pedir formulações novas (por outro autor, sem ler estes resultados). |
| `SEM_RISCO_MEDIDO` | nenhum `corrigiu_para_pior` e pelo menos 30% exercitado | fecha a #581 com o número (e o limite superior de 95%); a decisão da #499 e a flag não mudam. |

## 7. Relatório

- **Por classe:** n, contagem por resultado, ids das execuções de cada resultado.
- **Por conversa, por categoria (padrão) e por mecanismo:** execuções e resultados.
- **Registrados** (item 4 da #581): quantos "corrijo" saem **por dia** e **por reescrita**
  (`perguntaInterpretada` presente e diferente da mensagem), e o detalhe (abertura, consulta
  anterior e consulta nova) de cada `corrigiu_para_pior`, `corrigiu_equivalente`,
  `refeito_para_pior` e `corrigiu_errado`.
- **Arquivos:** os JSONL, o resumo e a decisão vão para `resultados/` num PR próprio, só com o OK
  da sessão orquestradora. Os logs das aplicações ficam fora (trazem o id do usuário).

## 8. Cegueira e limites

- **Autoria:** o conjunto foi escrito por uma sessão que **não leu** o código da continuidade
  (`assistente/continuidade/`, `OrquestradorService`) nem os testes dela, o conjunto de teste da
  #499, o gabarito, o benchmark, os conjuntos de fidelidade e de ressalva. Sabe das cinco
  heurísticas só pela descrição da #581, e dos dados só pelo seed da demonstração
  (`CenarioDemonstracao`) e pela documentação das funções (`docs/ceres-assistente.md`).
- **Quem lê o quê:** sessões de código não leem `conversas-continuidade-581.json` nem o resultado
  antes da decisão; mensagem a elas cita só o id (`c581-07`). Conserto feito por quem leu uma
  conversa a contamina.
- **Limites:** o conjunto **não sabe** se uma formulação engana as heurísticas: o proxy "ramo
  exercitado" mede isso depois. Sinônimos e períodos relativos dependem do que o modelo põe nos
  parâmetros, e um par `natural` pode virar preparo inválido (conta nos 10%). A taxa de "corrijo
  para pior" é medida contra o gabarito de função e parâmetros, não contra o número final: duas
  consultas diferentes que dão o mesmo valor contam como equivalentes só se casarem o gabarito.
