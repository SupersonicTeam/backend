# Partição de caso especial na busca de lei: medição de 2026-11-07 (#436)

## Resultado

**Nenhuma variante passou na regra de decisão.** A partição fica no código com o padrão
`desligado`, que chama a busca exatamente como antes (o braço B confirma: lista entregue idêntica à
da `main` nas 40 perguntas).

| | C (`regimes-especificos`) | D (`ampliada`) |
|---|---|---|
| perguntas que viram `certa` | 0 | 0 |
| perguntas que deixam de ser `certa` | 1 (busca-17) | 2 (busca-17, busca-23) |
| **(a)** saldo ≥ 3 | −1: **não** | −2: **não** |
| **(b)** `outro_artigo` não sobe (entram ≤ saem) | 0 ≤ 0: sim | 0 ≤ 1 (busca-09): sim |
| **(c)** nenhuma `setor` deixa de ser `certa` | sim | **não** (busca-23) |
| **(d)** nenhuma `ausente` passa a responder | sim | sim |
| **(e)** saldo maior que o ruído (1) | **não** | **não** |
| **passa** | **não** | **não** |

"Não passou" quer dizer ficar como está, não que a partição piora: as perdas são de uma e duas
perguntas, do tamanho do ruído, e a de busca-17 aconteceu com a lista entregue idêntica à de B
(abaixo).

## Como foi medido

Regra de decisão gravada e enviada antes de qualquer rodada
([`regra-de-decisao.md`](regra-de-decisao.md), commit `428c3e0`) e aplicada pelo
[`decisao.py`](decisao.py) sem mudança ([saída](resultados/decisao.json)). Conjunto
[`perguntas-lei-busca-2026-11.json`](../treino/perguntas-lei-busca-2026-11.json), sha256
`0d6c08f2…edf5` (versão do commit `6a7058a`): 20 `geral`, 10 `setor`, 10 `ausente`. Antes de medir,
a [conferência das faixas](conferir_faixas-saida.txt) mostrou que nenhuma pergunta `geral` tem
`esperado` ou `aceito` numa faixa de variante (nenhum grupo mudou) e que, nas 7 `setor` com o
esperado numa faixa, a regex do setor casa a pergunta.

| Braço | Código | Partição | Início | Fim | `ERRO_MODELO` refeito |
|---|---|---|---|---|---|
| A | `ec96871` (`main` + ferramentas) | não existe | 12:57 | 13:55 | busca-15, resolvido |
| B | `1746e48` (mudança) | `desligado` | 13:55 | 14:33 | busca-17, resolvido |
| C | `1746e48` | `regimes-especificos` | 14:33 | 15:08 | busca-01, resolvido |
| D | `1746e48` | `ampliada` | 15:08 | 15:33 | nenhum |

Mesma máquina, mesmo dia, nessa ordem, com o `qwen3:4b-instruct` (`0edcdef34593`) e o `bge-m3`
(`790764642607`) do Ollama local, **os dois em CPU** (o contêiner não enxerga a GPU). Base de lei
igual nos quatro braços: EC 132/2023 538 trechos, LC 214/2025 5.004, LC 227/2026 2.211, Lei
8.212/1991 547, Lei 9.528/1997 4. Nenhuma pergunta saiu por erro persistente. Os logs de B, C e D
confirmam a partição de cada braço (`particao de caso especial DESLIGADO`/`REGIMES_ESPECIFICOS`/
`AMPLIADA`). Metadados em `resultados/<braço>-metadados.txt`.

**A × B:** os `entregues` são idênticos nas 40 perguntas. **Ruído** (rótulo que muda entre A e B):
1 pergunta, busca-27 (`descarte` → `outro_artigo`, com a mesma lista).

## Rótulo por pergunta

| grupo | rótulo | A | B | C | D |
|---|---|---|---|---|---|
| geral | certa | 3 | 3 | 2 | 2 |
| geral | outro_artigo | 4 | 4 | 4 | 3 |
| geral | descarte | 13 | 13 | 14 | 15 |
| setor | certa | 3 | 3 | 3 | 2 |
| setor | outro_artigo | 0 | 1 | 1 | 1 |
| setor | descarte | 7 | 6 | 6 | 7 |
| ausente | descarte | 10 | 10 | 10 | 10 |
| **total** | certa | 6 | 6 | 5 | 4 |
| **total** | outro_artigo | 4 | 5 | 5 | 4 |
| **total** | descarte | 30 | 29 | 30 | 32 |

Nenhuma pergunta ficou `sem_maioria`. As que mudam em algum braço (as outras 34 têm o mesmo rótulo
nos quatro):

| id | grupo | A | B | C | D |
|---|---|---|---|---|---|
| busca-09 | geral | outro_artigo | outro_artigo | outro_artigo | descarte |
| busca-17 | geral | certa | certa | descarte | descarte |
| busca-23 | setor | certa | certa | certa | descarte |
| busca-27 | setor | descarte | outro_artigo | outro_artigo | outro_artigo |

A tabela completa, por pergunta e por braço, está em [`resultados/decisao.json`](resultados/decisao.json)
(`rotulos`).

### O que mudou em cada pergunta

- **busca-17** (C e D): a lista entregue é **idêntica** à de B (mesmos trechos, mesma ordem). Em B o
  modelo respondeu e citou o artigo esperado nas 3 repetições; em C e em D, 2 das 3 saíram com marca
  inválida. É variação do modelo com a mesma entrada, não efeito da partição, e pesa contra as duas
  variantes na regra do mesmo jeito.
- **busca-23** (D): a partição tirou do top 5 o trecho do art. 313 (regime automotivo, faixa 309 a
  316 da ampliada; a pergunta não fala de veículo) e o artigo esperado subiu para a posição 1. Mesmo
  assim, nas 3 repetições a resposta saiu com marca inválida (`CITACAO_INVALIDA`) e foi descartada.
- **busca-09** (D): desceu o trecho do art. 393 (benefícios onerosos do ICMS, faixa 384 a 405), que
  em B o modelo citava (`outro_artigo`); em D a resposta saiu com marca inválida (`descarte`).
- **busca-27** (D, sem mudança de rótulo): desceram os trechos do art. 99 (ZPE); a lista passou a ter
  o art. 69 (base na importação) e o 110, que o modelo citou. Continua `outro_artigo` (o esperado é
  o art. 63).
- **busca-01** (C e D, sem mudança de rótulo): o art. 252 (bens imóveis) desceu e o art. 6º subiu
  para as posições 1 e 2, mas o modelo seguiu citando só o 252 e a guarda descartou a resposta nas
  3 repetições dos quatro braços.

## Medidas descritivas

**Por execução** (120 por braço; precisão = `certa` ÷ `RESPONDIDA`, cobertura = `RESPONDIDA` ÷
execuções válidas):

| braço | geral: precisão / cobertura | setor: precisão / cobertura | ausente: respondidas | total: precisão / cobertura |
|---|---|---|---|---|
| A | 0,400 / 0,333 | 0,900 / 0,333 | 0 de 30 | 0,567 / 0,250 |
| B | 0,429 / 0,350 | 0,750 / 0,400 | 0 de 30 | 0,545 / 0,275 |
| C | 0,368 / 0,317 | 0,750 / 0,400 | 0 de 30 | 0,516 / 0,258 |
| D | 0,438 / 0,267 | 0,667 / 0,300 | 0 de 30 | 0,520 / 0,208 |

**Recall da lista entregue** (`DiagnosticoRecallLeiManual`, 30 perguntas `geral` e `setor`):

| braço | recall@5 (esperado) | posição 1 (esperado) | recall@5 (aceitável) | posição 1 (aceitável) |
|---|---|---|---|---|
| A | 7 | 4 | 9 | 6 |
| B | 7 | 4 | 9 | 6 |
| C | 7 | 4 | 9 | 6 |
| D | 7 | 5 | 9 | 7 |

Nas 10 `ausente`, 1 tem algum trecho acima do limiar, nos quatro braços.

**Top 5 entregue que mudou em relação a B:** A em 0 perguntas; C em 1 (busca-01); D em 9 (busca-01,
02, 07, 09, 11, 21, 22, 23, 27).

**Perguntas `geral` em que alguma regex de setor casa** (nelas os trechos daquela faixa não descem):
5 em C (busca-02, 04, 12 e 19 pela regex de bens imóveis, que tem "fazenda" e "constru", e busca-10
pela de prognósticos, que tem "sorteio" e "premio") e 6 em D (mais a busca-13, pela regex do ICMS).

**Guarda (`REGIME_ESPECIFICO_SEM_SETOR`):** 6 execuções por braço, as mesmas nos quatro: busca-01
(`geral`) e busca-24 (`setor`), 3 cada.

**Tempo da 1ª repetição** (mediana / média): A 30,0 s / 33,8 s; B 5,5 s / 16,4 s; C 6,9 s / 11,5 s;
D 6,2 s / 16,0 s. A comparação com A não vale: o A rodou primeiro, com o cache de prompt do Ollama
frio, e os braços seguintes reaproveitam os mesmos prompts e trechos. Entre B, C e D a partição não
mostra custo de tempo visível.

## Por que a partição quase não age neste conjunto (nota para decisão)

Com o limiar de similaridade atual (0,55), a busca entrega em média **2,8 trechos** por pergunta, e
**6 das 30** perguntas `geral`/`setor` ficam **sem nenhum trecho** (busca-04, 05, 10, 14, 18 e 26),
o que vira "não encontrei fundamento legal" antes do modelo. Na lista sem limiar
(`top20` do recall), o artigo aceitável está entre os 5 primeiros em 14 perguntas; na lista entregue,
em 9. Com poucos candidatos acima do limiar, a partição tem pouco a reordenar (o top 5 de C mudou em
uma pergunta só). E quando a lista tem o artigo, o 4B ainda descarta muito: 87 das 120 execuções do
braço B (72,5%) não saem `RESPONDIDA`.

Isto **não** entra neste PR (mudaria a saída de `buscar` depois da medição): fica para decisão do
Murillo, e qualquer mudança no limiar ou no tamanho da lista precisa de medição nova, num conjunto
novo, escrito por outra sessão.

## Limitações

- A ferramenta chama a `RotaLegislacao` direto: não passa pelo classificador de intenção nem pela
  parte de lei do MISTO.
- O conjunto foi escrito por um modelo (uma sessão sem acesso às perguntas de avaliação), não pela
  equipe, e passou por duas rodadas de troca pedidas pela conferência contra as perguntas de
  avaliação.
- O critério é o artigo citado, não o sentido da resposta: uma resposta certa que cita outro artigo
  conta como `outro_artigo`, e uma errada que cita o artigo esperado conta como `certa`.
- Medido só com o `qwen3:4b-instruct`, em CPU. Vale também para os modelos do benchmark, que não
  foram medidos.
- A sessão que escreveu o conjunto viu, pelo `CLAUDE.md`, uma pergunta de dados da avaliação.
- O ruído foi medido com uma repetição de braço (A × B: 1 pergunta); a busca-17 mostra que uma
  pergunta pode mudar de rótulo com a mesma entrada.
- No começo do braço A a mesma máquina compilou a mudança em paralelo: afeta só o tempo, que é
  descritivo.

## Saídas

Em [`resultados/`](resultados/): `<braço>-citacoes.json` (entrada do
[`busca_contagem.py`](busca_contagem.py)), `<braço>-citacoes-bruto.json` e
`<braço>-citacoes-refeitas.json` (antes da troca dos `ERRO_MODELO`), `<braço>-recall.json`,
`<braço>-metadados.txt`, `decisao.json` e `relatorio.json` (as medidas acima, do
[`relatorio.py`](relatorio.py)).
