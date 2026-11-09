# Regra de decisão da medição da #452 (período relativo nas perguntas de dados)

Gravada em 2026-11-09, **antes** da primeira rodada e antes de a sessão avaliadora ver o código
da correção. Mudar esta regra depois de ver um resultado invalida a medição.

## O que se mede

O conjunto congelado `docs/avaliacao/treino/perguntas-ceres-452.json` (versão `ceres-452-v1`, hash
no `.sha256` ao lado), escrito por uma sessão que não leu as issues #442, #443 e #452, os PRs
#447, #449 e #450, o conjunto da #442/#443, o gabarito, o benchmark nem o código que resolve o
período. Grupos:

| Grupo | O que a pergunta pede | Certa quando |
|-------|-----------------------|--------------|
| 1 `mes_seguinte` (10) | guias do mês seguinte ("mês que vem", "próximo mês", "mês seguinte") | `vencimentosDoMes` com o mês seguinte; nenhuma fonte do mês corrente, de `proximosVencimentos`, `guiasEmAtraso`, `listarAlertas` ou `resumoDoProdutor`; nenhuma data do mês corrente no texto |
| 2 `mes_corrente` (8) | vencimentos deste mês, em pergunta livre (nunca o texto exato dos atalhos) | `vencimentosDoMes` do mês corrente ou `proximosVencimentos` até o último dia do mês; nenhuma data depois do fim do mês no texto |
| 3 `trimestre` (8) | "último trimestre", "últimos três meses" | período = os três meses-calendário completos antes do corrente (decisão do Murillo, 2026-11-09): em 09/11/2026, 01/08 a 31/10; em 03/12, 01/09 a 30/11. Nem o trimestre do calendário nem os 90 dias. Apuração: as três `consultarApuracao`, sem outro mês |
| 4 `aberto` (14) | intervalo aberto (desde, até, antes de, a partir de, pra cá, pra trás), safra, dois períodos, regra com "mês seguinte" | o período corresponde ao pedido: o limite dado fixo e o outro aberto (fim hoje, ontem ou fim do mês; início antes do mês citado); safra 2025/26 de 01/07 a 01/10/2025 até 30/06/2026 a hoje, ou uma pergunta de volta sobre o período; dois períodos = as duas fontes; regra = rota `LEGISLACAO` sem fonte de dados |
| 5 controles (19) | mês passado, retrasado, mês nomeado, este ano, ano passado, sem período (12); lei (4); fora do escopo (3) | período e função certos; lei com citação da LC 214/2025; fora do escopo com rota `FORA_DE_ESCOPO` |

A gramática exata de cada campo está no topo de `pontuar.py`, e os testes em `test_pontuar.py`
fixam os casos de borda. As datas são calculadas a partir da data de cada chamada, no fuso de
Brasília. Um mês nomeado ("em setembro") é o mais recente que não passa do mês corrente.

**Resposta descartada** ("Não consegui confirmar ...", de dados ou de lei) conta como **errada**
em toda pergunta, controles inclusive. A única exceção são as três perguntas de regra do grupo 4,
em que só se mede se a pergunta foi para a lei sem puxar período de dados.

## Braços

- **Base:** a `main` do dia da medição, com o SHA registrado em cada linha do JSONL.
- **Correção:** o branch da correção, com o SHA registrado do mesmo jeito.
- Os dois rodam local, profile `dev`, com o seed da demonstração e a mesma conta de produtor,
  contra os containers originais (`agrocontabil-postgres`, `agrocontabil-ollama`), com a trava do
  Ollama local pega antes de começar. Nunca o banco da VPS.
- Mesmo modelo (`qwen3:4b-instruct`, digest gravado em cada linha) e mesma data de referência.

## Execução

- 3 repetições de cada pergunta em cada braço, com `executar.py`.
- Antes da primeira pergunta pontuada: aquecimento do modelo no Ollama e duas perguntas de
  aquecimento em cada braço (fora do conjunto, não pontuadas). O modelo é aquecido de novo no
  começo de cada bloco.
- A ordem dos braços alterna: repetições 1 e 3, base e depois correção; repetição 2, correção e
  depois base.

## Contagem

- Por pergunta vale a **maioria** das execuções válidas: certa quando as certas são mais da
  metade. **Empate na pergunta** (por exemplo, 1 certa e 1 errada com a terceira repetição só
  técnica) conta como **errada**.
- **Falha técnica** (HTTP diferente de 200, `modoFallback`, indisponibilidade, ocupado, tempo
  esgotado, cota) não é resposta: o executor reaquece o modelo e repete a chamada, até 3
  tentativas por pergunta e repetição; todas as tentativas ficam no JSONL.
- Pergunta sem nenhuma execução válida num braço = `sem_medicao`.

## Decisão

`pontuar.py comparar --base <resumo da main> --correcao <resumo da correção>` aplica, nesta
ordem:

1. **INCONCLUSIVA** se: os resumos são de conjuntos diferentes; as rodadas não têm uma data de
   referência só, a mesma nos dois braços; um braço tem mais de um commit ou mais de um modelo;
   os dois braços são do mesmo commit ou usaram modelos diferentes; alguma pergunta ficou
   `sem_medicao` em qualquer braço. Medição inconclusiva se refaz **inteira, nos dois braços, no
   mesmo dia**, em pasta nova; nunca se completa uma rodada com execuções de outra.
2. **ENTRA** se cada um dos grupos 1 a 4 **sobe** (mais perguntas certas na correção que na
   base) e **nenhuma pergunta de controle** que estava certa na base deixa de estar certa na
   correção. Grupo em que a base já acerta todas as perguntas não tem como subir: vale se a
   correção também acertar todas.
3. **NAO_ENTRA** em qualquer outro caso. **Empate num grupo** (mesmo número de certas, fora do
   teto) é não subir, e portanto NAO_ENTRA.

Quedas de pergunta dentro dos grupos 1 a 4 não barram a decisão (o grupo é contado pelo total),
mas o relatório as lista pelo id.

## Latência

Fica fora da decisão. O resumo traz a mediana de segundos por grupo, e o `comparar` só a mostra
quando os dois braços tiveram aquecimento; sem aquecimento, a latência não é comparada.

## Relatório

Números por grupo nos dois braços, as perguntas que mudaram (pelo id), as falhas técnicas e o
SHA dos dois braços. Os JSONL, os resumos e a decisão vão para `resultados/` num PR próprio, só
depois do OK da sessão orquestradora.
