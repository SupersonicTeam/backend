# Diagnóstico das citações da rota de lei (#269)

Data: 2026-11-06. Modelo: `qwen3:4b-instruct` (Ollama local, CPU), prompt da `main` (`587c037`, sem a diretriz do PR #425). Base: a local, a mesma da VPS (8.304 trechos). **Nenhum código de produção foi alterado**; o diagnóstico usa duas classes de teste manuais (`DiagnosticoCitacoesLeiManual` e `DiagnosticoRecallLeiManual`, que não rodam no `mvn test`).

## Pergunta e método

Por que a citação sai errada? Para cada pergunta, a causa é uma de:

- **(a) Busca:** o artigo certo não chegou ao modelo.
- **(b) Escolha:** chegou, e o modelo citou outro.
- **(c) Sentido:** a citação está certa e o sentido, errado.
- **(d) Lei ausente:** o que a pergunta precisa não está em nenhuma norma da base.
- **(e) Validador:** a resposta estava certa e o validador a derrubou (achado novo, abaixo).

22 perguntas de treino novas (`docs/avaliacao/treino/perguntas-lei-diagnostico.json`): 18 de temas de lei do produtor com artigo esperado, 1 controle (D21, Senar) e 3 que podem cair fora da base (D19, D20, D22). O artigo esperado foi definido **lendo o texto da lei** na base, antes de ver qualquer resposta do modelo (raciocínio de cada um no JSON). Nenhuma pergunta é do gabarito, de paráfrase dele ou do conjunto cego; o gabarito não foi usado para escolher perguntas nem para julgar respostas.

Caminho real: `BuscaLegislacaoService` → `RotaLegislacao.responder` (modelo, `ValidadorCitacao`, `ValidadorNumeros`), 3 repetições por pergunta. O recall por `k` repete a requisição da busca (mesma pergunta mascarada, mesmo filtro de vigência) com `topK = 20` e sem limiar; o limite de produção (k=5, limiar 0,55) não foi mexido. A comparação é no nível do artigo, porque a base guarda um trecho por parágrafo/inciso.

## Resultado com o prompt da `main` (k=5)

Sobre as 18 perguntas com artigo esperado na base, em 54 execuções (18 × 3), as repetições deram quase sempre o mesmo resultado:

| Resultado | Execuções | Perguntas |
|---|---|---|
| Resposta aceita, citou o artigo esperado | 30 | 10 (D01, D02, D07, D08, D09, D11, D12, D13, D17, D18) |
| Resposta aceita, **citou outro artigo** | 12 | 4 (D03, D14, D15, D16) |
| Descartada (`TRECHOS_SEM_CONFIRMACAO`, só trechos) | 12 | 4 (D04, D05, D06, D10) |

### Contagem por causa (as 8 perguntas que não deram resposta certa)

| Causa | Perguntas | Casos |
|---|---|---|
| **(a) Busca** | **6 de 18 (33%)** | D03, D14, D15, D16 (resposta errada servida); D05, D06 (descartadas) |
| (b) Escolha, com o esperado entregue | 0 no k=5 | aparece no k=10 (abaixo) |
| **Falsa recusa**: o artigo certo foi entregue e o modelo respondeu `NAO_ENCONTREI` (não é marca inválida, ver abaixo) | 2 | D04, D10 |
| (c) Sentido errado com citação certa | 1 (D07) | lê "pagos até a entrega" (art. 76) como "quando surge a obrigação" (art. 65/67) |
| (e) Validador derrubou resposta certa | 1 (D21, fora das 18) | Senar |

O caso do ICMS/ISS (D03) é (a) **e** (c): o artigo esperado é o art. 2º da EC 132 (que dá ao ADCT o art. 129: extintos a partir de 2033), que não aparece nem nos 20 primeiros; o modelo cita o art. 408 da LC 214 e inverte o sentido ("continuam") de um dispositivo que diz que eles não incidem. Corrigir a citação não resolve: o texto certo nem chegou.

**Dominante: (a) busca.** Dos 6, o recall mostra que há dois tipos.

## Recall por `k` (só a busca, sem o modelo)

Sobre as 19 perguntas com artigo na base (D01–D18 e D21):

| k | Artigo esperado ou aceito entre os trechos | Faltam |
|---|---|---|
| 5 (produção) | 13 de 19 (68%) | D03, D05, D06, D14, D15, D16 |
| 10 | 15 de 19 (79%) | D03, D05, D14, D16 |
| 20 | 16 de 19 (84%) | D03, D14, D16 |

- **D06 (art. 47, posição 9) e D15 (art. 10, posição 6)** entram em k=10; **D05 (art. 79, posição 12)** só em k=20.
- **D03, D14 e D16 não aparecem nos 20 primeiros.** O texto da lei não usa as palavras da pergunta: o ADCT art. 129 não nomeia ICMS nem ISS; o art. 54 diz "o direito de utilização dos créditos extinguir-se-á após 5 anos" para uma pergunta sobre "por quanto tempo posso usar"; o art. 12 diz "base de cálculo ... é o valor da operação" para "sobre qual valor se calcula". Uma busca textual simples com as palavras da pergunta também não os traz entre os 6 primeiros: o ganho de busca híbrida **não está evidenciado** para estes três.
- **Fragmentação:** os trechos são um por parágrafo/inciso, então os 5 primeiros costumam ser pedaços do mesmo artigo (D02: 5 de 5 do art. 409; D12: 5 de 5 do art. 39; D15: 5 de 5 do art. 254). Os 5 primeiros cobrem de 4 a 13 artigos distintos. Tomar os 5 primeiros **artigos distintos** (sem mudar a quantidade de trechos por artigo) dá o mesmo recall do k=10, 15 de 19 (D06 e D15 entram).

### O limiar de similaridade (0,55)

Nas 19 perguntas com artigo na base, os 20 primeiros trechos ficam todos acima do limiar (similaridade do 1º: mínimo 0,595; mediana 0,671). Nas 3 que podem estar fora da base: D19 tem 0 acima (1º trecho em 0,548), D22 tem 0 (0,484), D20 tem 1 (0,565). O limiar separa bem a lei ausente da presente, mas por pouco (0,548 contra 0,595). **A similaridade não separa a busca que falhou da que acertou**: as 6 perguntas com falha na busca têm 1º trecho entre 0,621 e 0,719, tão alto quanto as certas. Aumentar o limiar não evita a resposta errada.

## (d) Lei ausente

| Pergunta | O que faltaria | Do escopo do produtor? | O que a Ceres fez (3 reps) |
|---|---|---|---|
| D19 ICMS da soja em MT | RICMS-MT e convênios do Confaz | Sim, é pergunta de produtor, mas de ICMS, fora da reforma | "Não encontrei fundamento legal" (0 trecho acima do limiar) |
| D20 ITR | Lei 9.393/1996 | Sim, é tributo do produtor rural, outra lei | 1 trecho passa (LC 214, art. 164), modelo responde, descartada: trechos mais próximos |
| D22 LCDPR | Instrução normativa da RFB | Sim, obrigação do produtor, fora da reforma | "Não encontrei fundamento legal" |
| D21 Senar (controle) | nada: Lei 9.528, art. 6º, está na base | Sim | trecho certo em 1º, **mas descartada** por `NUMERO_SEM_FONTE` (ver abaixo) |

As 3 perguntas fora da base **foram tratadas de forma honesta** (nenhuma recebeu resposta inventada). Os erros que vimos nas 18 perguntas **não vêm de lei faltando**: os artigos esperados estão na base (conferidos um a um).

### (e) Achado: número por extenso derruba resposta certa

A Lei 9.528 diz "é de **zero vírgula dois por cento**". O modelo respondeu 0,2%, o `ValidadorNumeros` não casa "zero vírgula dois por cento" com "0,2" e descartou a resposta (3 de 3). É um falso descarte determinístico, e vale para qualquer lei que escreva o percentual por extenso.

## E se aumentarmos o `k`? (modelo real, `AI_RAG_TOP_K=10`, 3 repetições, mesmas 18 perguntas)

| 54 execuções | k=5 | k=10 |
|---|---|---|
| Aceita, citou o artigo esperado | 30 | 29 |
| Aceita, **citou outro artigo** | **12** | **19** |
| Descartada | 12 | 6 |
| Tempo por chamada ao modelo (CPU) | cerca de 12,5 s | cerca de 18,7 s (+50%) |

- O k=10 **troca descartes por respostas aceitas, e boa parte delas erradas**: as aceitas com artigo errado sobem de 12 para 19 (22% → 35% das execuções). O `ValidadorCitacao` aceita qualquer trecho entregue, então mais trechos também dão mais citação errada.
- D04 (cashback) e D10 (crédito de carro de uso pessoal) passam de descartadas para **certas** (efeito de ordem/conjunto, não de recall: o esperado já estava entre os 5).
- **D06 e D15 entram no recall, mas o modelo não os usa**: com o art. 47 e o art. 10 entre os 10 trechos, ele responde pelo art. 171 (bem usado) e pelo art. 254 (imóvel). É a causa (a) virando (b).
- D09 piora (de certa para errada: cita o art. 49, que trata de quem compra, em vez do art. 51), D18 passa a ser descartada, D05 às vezes responde "não paga IBS e CBS" apoiado em suspensão (sentido errado).
- Para a lei ausente nada muda (D19 e D22 seguem barradas pelo limiar).

## Conclusão

1. **Ampliar a base: não.** Os 3 casos de lei ausente já terminam bem (resposta honesta); os 6 erros de busca ocorrem com o artigo certo na base.
2. **Aumentar o `k` para 10: não.** Recall de 13 para 15 em 19, mas respostas aceitas com artigo errado de 12 para 19 em 54, e 50% mais tempo por chamada.
3. **Causa dominante: (a) busca, 6 de 18 (33%).** Metade (D06, D15, e D05 só com k=20) é fragmentação e ordem; a outra metade (D03, D14, D16) é a pergunta não parecida com o texto da lei e não aparece nem em 20.
4. **Sentido e escolha** pesam menos (1 e 0 no k=5), mas o k maior os faz crescer.

## Menor correção recomendada (não implementada)

Para a causa dominante, a menor mudança que o dado sustenta é **escolher os 5 primeiros artigos distintos** na busca (buscar uns 20 trechos, agrupar por artigo e entregar os melhores trechos de 5 artigos). Efeito esperado pelo recall: 13 → 15 de 19, sem aumentar o número de artigos que o modelo lê. Riscos: (1) o k=10 mostrou que entregar o artigo certo não faz o modelo usá-lo (D06 e D15), então o ganho de resposta certa pode ser 0 a 2 de 18; (2) é mudança em `BuscaLegislacaoService`, que alimenta todas as respostas de lei; (3) não resolve D03, D14, D16. **Precisa de teste com 3 repetições antes de qualquer merge.**

Para D03, D14 e D16, a alavanca é a pergunta não casar com o texto da lei (vocabulário). A busca híbrida da #200, pelo teste simples feito aqui, não aparece como solução; reescrever a pergunta com vocabulário jurídico antes da busca, ou indexar cada trecho com o título do assunto, são as hipóteses, e as duas são maiores que uma correção pequena. **Não é recomendação de antecipar a #200** com esta amostra.

Correção separada, pequena e certa: fazer o `ValidadorNumeros` entender o percentual por extenso ("zero vírgula dois por cento" = 0,2%). Efeito: D21 passa a responder. Risco baixo e testável: a conversão é determinística e só amplia o que o validador reconhece.

## Limites do diagnóstico

- 22 perguntas, uma rodada de 3 repetições por configuração; as repetições deram resultados quase idênticos (o modelo é quase determinístico), então as 3 não dão intervalo de confiança. A amostra mostra o tipo de falha, não a taxa de produção.
- O artigo esperado é meu, lido na lei; um revisor jurídico pode discordar de um caso (mantive um `aceito` onde há dois artigos defensáveis).
- A causa (c) foi julgada por mim, lendo a resposta contra o trecho citado.
- O motivo dos 12 descartes `CITACAO_INVALIDA` do k=5 foi investigado (seção "Marca inválida"): não é formato de marca.
- Não usei o gabarito nem o conjunto cego.

## Como repetir

```bash
# Postgres e Ollama locais (docker start agrocontabil-postgres agrocontabil-ollama), DB_PASSWORD no ambiente
./mvnw test -Dtest=DiagnosticoRecallLeiManual -Ddiagnostico.saida=recall.json
./mvnw test -Dtest=DiagnosticoCitacoesLeiManual -Ddiagnostico.saida=diag.json -Ddiagnostico.repeticoes=3
AI_RAG_TOP_K=10 ./mvnw test -Dtest=DiagnosticoCitacoesLeiManual -Ddiagnostico.saida=diag_k10.json
```

## Por pergunta

| id | pergunta | esperado | posição do esperado | k=5 (3 reps) | k=10 (3 reps) | causa |
|---|---|---|---|---|---|---|
| D01 | O que é o Comitê Gestor do IBS e para que ele serve? | LC 227/2026 art. 1º | 1 | 3×certa | 3×certa | ok |
| D02 | O que é o Imposto Seletivo? | LC 214/2025 art. 409 | 1 | 3×certa | 3×certa | ok |
| D03 | Até quando existem o ICMS e o ISS durante a transição para o IBS? | EC 132/2023 art. 2º | >20 | 3×errada | 3×errada | (a) busca + (c) sentido invertido |
| D04 | Como funciona o cashback do IBS e da CBS para famílias de baixa renda? | LC 214/2025 art. 112 | 1 | 3×descartada | 3×certa | falsa recusa (NAO_ENCONTREI) com o artigo entregue; no k=10 responde |
| D05 | A venda de grãos para o exterior paga IBS e CBS? | LC 214/2025 art. 79 | 12 | 3×descartada | 2×descartada/1×errada | (a) busca |
| D06 | Como funciona o crédito do IBS e da CBS nas compras de quem é contribuinte? | LC 214/2025 art. 47 | 9 | 3×descartada | 3×errada | (a) busca; no k=10 vira (b) |
| D07 | Quando surge a obrigação de pagar o IBS e a CBS na importação de bens? | LC 214/2025 art. 67 | 1 | 3×certa | 3×certa | citação ok; (c) sentido (prazo de pagamento lido como "quando surge") |
| D08 | Quem é contribuinte do IBS e da CBS? | LC 214/2025 art. 21 | 1 | 3×certa | 1×descartada/2×certa | ok (cita também o art. 176, de combustíveis) |
| D09 | Se a minha venda for isenta, eu perco os créditos das minhas compras? | LC 214/2025 art. 51 | 1 | 3×certa | 3×errada | ok no k=5; no k=10 vira (b) |
| D10 | Posso descontar o crédito de um carro que comprei para uso pessoal? | LC 214/2025 art. 57 | 2 | 3×descartada | 3×certa | falsa recusa: o modelo respondeu NAO_ENCONTREI com o artigo entregue |
| D11 | Sou obrigado a emitir nota fiscal eletrônica nas minhas vendas? | LC 214/2025 art. 60 | 1 | 3×certa | 3×certa | ok |
| D12 | Posso pedir de volta o saldo de crédito que sobrou no fim do mês? | LC 214/2025 art. 39 | 1 | 3×certa | 3×certa | ok |
| D13 | Uma cooperativa de produtores pode ter alíquota zero de IBS e CBS? | LC 214/2025 art. 271 | 1 | 3×certa | 3×certa | ok |
| D14 | Por quanto tempo posso usar o crédito do IBS e da CBS? | LC 214/2025 art. 54 | >20 | 3×errada | 3×errada | (a) busca (acerta "5 anos" por coincidência) |
| D15 | Quando se considera que aconteceu o fato gerador do IBS e da CBS numa venda? | LC 214/2025 art. 10 | 6 | 3×errada | 3×errada | (a) busca; no k=10 vira (b) |
| D16 | Sobre qual valor se calcula o IBS e a CBS? | LC 214/2025 art. 12 | >20 | 3×errada | 3×errada | (a) busca |
| D17 | Em quais operações o IBS e a CBS não incidem? | LC 214/2025 art. 6º | 1 | 3×certa | 3×certa | ok |
| D18 | Quais operações são imunes ao IBS e à CBS? | LC 214/2025 art. 9º | 1 | 3×certa | 3×descartada | ok no k=5; descartada no k=10 |
| D19 | Qual a alíquota de ICMS na venda de soja dentro de Mato Grosso? | (fora da base) | n/a | 3×descartada | 3×descartada | (d) ausente; barrada pelo limiar |
| D20 | Como funciona o ITR, o imposto territorial rural? | (fora da base) | n/a | 3×descartada | 3×descartada | (d) ausente; 1 trecho passa o limiar, descartada |
| D21 | Quanto o produtor rural pessoa física paga de contribuição ao Senar? | Lei 9.528/1997 art. 6º | 1 | 3×descartada | 3×descartada | (e) número por extenso: descartada por NUMERO_SEM_FONTE |
| D22 | Como funciona o livro caixa digital do produtor rural, o LCDPR? | (fora da base) | n/a | 3×descartada | 3×descartada | (d) ausente; barrada pelo limiar |


Dados brutos das rodadas (desfecho, citações e texto de cada repetição): `diagnostico-lei-2026-11-06/`.


---

# Segunda rodada (2026-11-06): marca inválida, validador, candidatas de busca e controle

Pedido do Murillo: seguir corrigindo as citações. Três frentes.

## 1. Percentual por extenso (D21) — PR #427

`ValidadorNumeros` agora lê "zero vírgula dois por cento" como 0,2, nas fontes e na resposta. PR próprio, CI verde, `code-review high` feito (achados de reuso e `codebase-map` corrigidos). O merge foi negado pelo modo automático ("Merge Without Review"): fica para o orquestrador.

## 2. "Marca inválida" (D04, D10): a hipótese não se confirmou

Registrei o texto cru do modelo antes do `ValidadorCitacao` (espião de `ModeloLinguagem.chamar` no harness, 3 repetições em D04, D05, D06, D08, D10, D18; dados em `k5-texto-cru-descartes.json`).

- Os 12 descartes `CITACAO_INVALIDA` do k=5 (D04, D05, D06, D10 × 3) são o texto literal **`NAO_ENCONTREI`**. Não há marca inventada nem formato fora do padrão.
- A resposta com marcas (D08, D18) vem no formato correto: `... [T3]. ... [T5].`
- Portanto: 6 dos 12 são **recusas corretas** (D05 e D06: o artigo certo não foi entregue; recusar é o que protege o produtor) e 6 são **falsas recusas** (D04 e D10: o artigo certo estava entre os 5 e o modelo disse que não achou).
- Não há ajuste de formato a aceitar. Afrouxar a regra do `NAO_ENCONTREI` no prompt traria de volta as respostas de D05 e D06 com artigo errado (é o que o k=10 fez): ganho nos 6 e perda nos outros 6. **Não proponho ajuste e não medi um**, porque a causa é outra.

## 3. Candidatas de seleção na busca

Controle: 9 perguntas novas (D23 a D31, `holdout: true` no JSON), escritas depois de D01 a D22, com o artigo esperado lido na lei; **nenhuma regra foi ajustada olhando para elas**.

### Só o recall (sem o modelo; 20 primeiros trechos, mesmos dados do k=5)

| | treino (19 com artigo) | controle (9) |
|---|---|---|
| k=5 hoje | 13 | 7 |
| (a) 5 artigos distintos | **15** (D06, D15) | **8** (D30) |
| (b) rebaixar regime específico, 5 trechos | 12 (perde D02 e D13) | 8 |
| (b) rebaixar regime específico, 5 artigos distintos | 14 | 9 |

(b) usa palavras do caput ("combustível", "imóvel", "saúde", "crédito presumido", "regime específico"...) e só rebaixa se a pergunta não cita o setor. Falso positivo: "prejudiciais à **saúde**" põe o Imposto Seletivo (D02) como regime de saúde, e "regime específico" no caput do art. 271 rebaixa a cooperativa (D13). **Descartada**: no treino fica abaixo da linha de base e a regra é frágil por construção (palavra-chave de caput). Só a (a) foi para o modelo.

### Modelo real nas candidatas (3 repetições, `qwen3:4b-instruct`, k=5 artigos)

| Execuções | Aceita, **certa** | Aceita, **outro artigo** | Descartada |
|---|---|---|---|
| **Treino D01–D18** (54) | | | |
| k=5 hoje | 30 | 12 | 12 |
| (a1) 5 artigos, 1 trecho por artigo | 29 | 12 | 13 |
| (a2) 5 artigos, até 2 trechos por artigo | 21 | 18 | 15 |
| **Controle D23–D31** (27) | | | |
| k=5 hoje | 12 | 7 | 7 (+1 falha do modelo) |
| (a1) | 15 | 6 | 6 |
| (a2) | 14 | 6 | 7 |
| **Total (81)** | | | |
| k=5 hoje | 42 | 19 | 19 |
| (a1) | **44** | **18** | 19 |
| (a2) | 35 | 24 | 22 |

Por pergunta (a1): ganha D06 (3 de 3 viram certas), D25, D28, D30; perde D09 e D27 (certa → outro artigo) e D17 (certa → descartada). D09: o trecho mais próximo do art. 51 passou a ser o § 1º (proporcionalidade) e saiu o caput, que dizia que a isenção anula os créditos; o modelo cita o art. 49. Ou seja, **um trecho por artigo perde a informação do parágrafo certo**. O a2 tenta recuperar isso e piora (mais contexto, mais respostas por outro artigo).

Regra combinada (a "certa" sobe e "outro artigo" não sobe): **(a1) não passa no treino** (certa 30 → 29) e passa no controle (12 → 15; 7 → 6); no total, +2 certas e −1 "outro artigo" em 81 execuções, dentro do que as trocas por pergunta explicam (4 ganhos, 3 perdas). **(a2) não passa em nenhum dos dois**. Absolutamente nada mudou para a lei ausente (D19 e D22 seguem recusadas).

## Conclusão desta rodada
1. **Nenhuma candidata de busca se sustenta.** (b) perde recall no treino; (a1) melhora o recall (13 → 15 e 7 → 8) mas isso não vira ganho de resposta de forma consistente: +2 certas e −1 "outro artigo" em 81 execuções, com 3 perguntas piorando.
2. **O limite está na escolha do trecho, não só na busca.** O recall de artigos distintos sobe, mas o modelo de 4B cita o artigo errado mesmo com o certo na mão (D06 e D15 no k=10; D09 e D27 no a1).
3. **Falsa recusa** (D04, D10 e D26 do controle: `NAO_ENCONTREI` com o artigo certo entregue) é o que sobra de "descarte com o artigo na mão" e não tem correção segura: afrouxá-la traz de volta as respostas erradas das recusas corretas.
4. **Correção que ficou de pé:** percentual por extenso no validador (PR #427).
5. O ganho de qualidade que o dado ainda sustenta é de **modelo ou de reescrita da pergunta**, não de busca ou de base: D03, D14 e D16 continuam fora do alcance (nem nos 20 primeiros).

Dados das rodadas: `candidata-a1-1-trecho-por-artigo.json`, `candidata-a2-2-trechos-por-artigo.json`, `k5-controle-D23-D27.json`, `k5-controle-D28-D31.json`, `recall-candidatas.json` e `simula_candidatas.py` (a simulação de (a) e (b) sobre os 20 primeiros trechos). Código da candidata: branch `feat/269-busca-artigos-distintos` (sem PR; `BuscaLegislacaoService.melhoresPorArtigo`).


---

# Terceira rodada (2026-11-06): expansão de consulta com o vocabulário da lei

Hipótese (orquestrador): os casos de D03, D14 e D16 são de vocabulário (a pergunta não usa as palavras da lei), então uma tabela **versionada e determinística** tema → frase da própria lei, acrescentada **só à consulta da busca** (o modelo recebe a pergunta original; sem tema casado a consulta é a de hoje) poderia pôr o artigo certo em 1º lugar. Só o recall foi medido, sem o modelo.

## Conferência da posição 1 nos dados brutos (k=5, hoje)

Contado de `recall-por-k.json` e das rodadas (3 repetições por pergunta), pelo 1º trecho do artigo esperado ou aceito:

| Posição do artigo | Perguntas (D01–D18) | Execuções | Certa | Outro artigo | Descartada |
|---|---|---|---|---|---|
| 1º | 11 (D01, D02, D04, D07, D08, D09, D11, D12, D13, D17, D18) | 33 | **30** | 0 | 3 (D04, falsa recusa) |
| 2º a 5º | 1 (D10) | 3 | 0 | 0 | 3 (falsa recusa) |
| Fora do 5º | 6 (D03, D05, D06, D14, D15, D16) | 18 | **0** | 12 | 6 |

Com o artigo em 1º lugar, 10 das 11 perguntas saíram certas nas 3 repetições (a exceção é a falsa recusa de D04); com o artigo fora do 5º lugar, **nenhuma** das 6 saiu certa. No controle 1 (D23–D31), com o artigo em 1º: 12 certas, 1 de outro artigo, 4 descartadas e 1 falha do modelo em 18 execuções; em 2º a 5º: 3 de outro artigo; fora do 5º: 3 descartadas e 3 de outro artigo. A contagem do orquestrador (10 de 12 com D21) bate; o que muda é que a base de comparação sem D21 é 10 de 11. A posição importa mais que o recall.

## O que foi testado

- **Controle 2** (D32–D43, 12 perguntas) escrito **antes** da tabela, de temas gerais do Título I da LC 214 (apuração, pagamento, restituição, devolução, importação, cadastro, exportação de serviço etc.); sem ajuste olhando para elas. Linha de base: posição 1 em 10 de 12; só D33 e D39 falham, então o controle 2 deixa pouco espaço para ganho e serve mais para detectar dano.
- **Tabela** `ceres/vocabulario-lei.yml`: 6 temas pedidos (fato gerador, base de cálculo, prazo para usar o crédito, apropriação do crédito, imunidade da exportação, fim do ICMS e do ISS), cada um com frases tiradas do texto do artigo; gatilhos genéricos sobre a pergunta sem acento. `ExpansaoConsultaLei` (branch `feat/269-expansao-consulta`, sem PR).

## Resultado (só recall; mesma busca, k=5, 20 primeiros candidatos)

| Conjunto | n | Posição 1 | Top 5 | Top 20 |
|---|---|---|---|---|
| Treino D01–D18 + D21 | 19 | 12 → **16** | 13 → 17 | 16 → 18 |
| Controle 1 D23–D31 | 9 | 6 → 6 | 7 → 7 | 9 → 9 |
| Controle 2 D32–D43 | 12 | 10 → **8** | 11 → 9 | 11 → 9 |
| Lei ausente D19, D20, D22 | 3 | sem tema casado: 0, 1 e 0 trechos acima do limiar, como antes | | |

Por pergunta, no treino: D05 (12 → 1), D06 (9 → 1), D14 (fora → 1), D15 (6 → 1) e D10 (2 → 1) sobem para o 1º; D03 sai de fora do top 20 para o 10º e D16 para o 2º; **D09 piora** (1 → fora do top 20: o tema "apropriação do crédito" enche os 5 primeiros com pedaços do art. 47, que não é o art. 51). No controle 2, **D36** (ordem de uso dos créditos) e **D41** (exportação de **serviço**, art. 80) caem de 1º para fora do top 5: o gatilho de "crédito" enche os 5 primeiros de pedaços do art. 47 (D36 esperava o art. 53), e o de "exportação" põe o art. 79 (imunidade) na frente do art. 80 (exportação de serviço, D41).

## Conclusão: a frente se encerra aqui

- **No treino a posição 1 sobe (12 → 16), mas é em amostra por construção**: os 6 temas vieram das 6 falhas de busca do treino.
- **Fora da amostra não sobe nada**: controle 1 igual (6 → 6) e controle 2 pior (10 → 8). O dano vem dos gatilhos largos; estreitá-los olhando para D36 e D41 seria ajustar nos controles, o que não pode.
- A regra do orquestrador (a posição 1 subir de forma clara) **não se confirma**. Não rodei o modelo real. A lei ausente não foi empurrada para cima do limiar (nenhum tema casou nelas).
- O mecanismo é válido para o vocabulário que a tabela cobre, mas **tabela escrita à mão para temas gerais não generaliza com segurança** nesta amostra: cada tema novo vira um risco de piorar perguntas vizinhas, e a conferência exigiria um conjunto de controle por tema.
- Dados: `recall-43-sem-expansao.json` e `recall-43-com-expansao.json`. Código: `feat/269-expansao-consulta` (`ExpansaoConsultaLei`, `vocabulario-lei.yml`, ligação não feita na `BuscaLegislacaoService`).


---

# Quarta rodada (2026-11-06): Qwen3 30B-A3B contra o 4B, na VPS (item 4 da #270)

Autorizado pelo Murillo ("Autorizo na VPS"); o orquestrador baixou `qwen3:30b-instruct` (18 GB, Q4_K_M) e abriu o túnel `127.0.0.1:11435` para o Ollama da VPS. **Sem SSH.** Mesmo código da `main` em `2dba24f` (com o #427), nenhuma mudança de padrão (`.env`, `application.yml`, `compose`).

**Condições, iguais nos dois modelos:** `AI_OLLAMA_BASE_URL=http://127.0.0.1:11435`, `AI_CHAT_MODEL` = `qwen3:30b-instruct` ou `qwen3:4b-instruct`, `top-k` 5, `AI_OLLAMA_NUM_CTX=8192`, `AI_OLLAMA_THINK=false`. D01 a D43, 3 repetições, uma rodada por vez (a VPS tem `OLLAMA_NUM_PARALLEL=1`). O embedding é o `bge-m3` da VPS, o mesmo modelo. A chamada de aquecimento de cada rodada fica fora da conta. **O 4B da VPS é a linha de base**, não o 4B local de antes: o #427 entrou e o tempo era da CPU local. Os dois 4B concordam, com 4 perguntas a oscilar (D05, D06, D25, D28), porque a máquina é outra.

**Raciocínio no texto:** nenhum. Nos 129 textos crus de cada modelo não há `<think>` (30B: mediana 196 caracteres, máximo 1.646; 4B: 118 e 3.347).

**Falha de coleta:** na primeira rodada do 30B, D01 deu 3 `AssistenteIndisponivelException` seguidas (efeito de primeira chamada/disjuntor logo depois do aquecimento). Rodei D01 sozinho e as 3 repetições deram certo; o arquivo `…-bruto-com-D01-com-erro.json` guarda o original.

## Tabela por modelo e por conjunto (execuções)

| Conjunto | Modelo | Certa | Outro artigo | Descartada | Tempo p50 (todas as repetições) | p95 |
|---|---|---|---|---|---|---|
| Treino D01–D18 + D21 (57) | 4B | 33 | 15 | 9 | 3,8 s | 18,3 s |
| | **30B** | **39** | **18** | **0** | 4,4 s | 19,4 s |
| Controle 1 D23–D31 (27) | 4B | 12 | 8 | 7 | 7,0 s | 49,2 s |
| | **30B** | **18** | **9** | **0** | 4,1 s | 32,0 s |
| Controle 2 D32–D43 (36) | 4B | 33 | 3 | 0 | 5,7 s | 13,8 s |
| | **30B** | **33** | **3** | **0** | 8,1 s | 17,3 s |
| Lei ausente D19, D20, D22 (9) | 4B | 0 | 0 | 9 | 0,1 s | 3,5 s |
| | 30B | 0 | 0 | 9 | 0,2 s | 4,1 s |
| **Todas as chamadas (129)** | 4B | 78 | 26 | 25 | **3,7 s** | **20,3 s** |
| | 30B | 90 | 30 | 9 | **4,1 s** | **19,6 s** |

- **Tempo: ver a correção abaixo.** Os p50 de 4 s da tabela misturam as 3 repetições seguidas da mesma pergunta, com o prompt em cache; não valem como tempo de pergunta nova.
- **O 30B não recusa:** zero `NAO_ENCONTREI` nos conjuntos com artigo (o 4B tinha 25 recusas). Na lei ausente a recusa continua, e vem do limiar de similaridade (antes do modelo): D19 e D22 sem trecho, D20 descartada pelos validadores, nos dois modelos. D21 (Senar) sai certa nos dois, graças ao #427.

## Regra do orquestrador (certa sobe e "outro artigo" não sobe, nos três conjuntos com artigo)

| Conjunto | certa | outro artigo | passa? |
|---|---|---|---|
| Treino | 33 → 39 (sobe) | 15 → 18 (**sobe**) | não |
| Controle 1 | 12 → 18 (sobe) | 8 → 9 (**sobe**) | não |
| Controle 2 | 33 → 33 (igual) | 3 → 3 | não (certa não sobe) |

**O 30B não é candidato pela regra.** O resultado é **misto**, sem arredondar: 12 respostas certas a mais e 4 de outro artigo a mais em 129 execuções, porque o 30B troca as recusas do 4B por respostas, e parte delas está errada.

## O que mudou por pergunta

Ganhos (4B recusa ou erra, 30B acerta, sentido conferido contra o texto da lei):
- **D04** (cashback), **D10** (crédito de carro de uso pessoal) e **D26** (vender créditos): as 3 falsas recusas do 4B viram **certas nas 3 repetições**, com o sentido correto (D04: a CBS é devolvida pela União e o IBS pelos entes, art. 112; D10: não pode, art. 57; D26: não pode, art. 55 e, numa repetição, a exceção da sucessão em fusão, cisão ou incorporação, que está no parágrafo único do art. 55).
- **D25** (importação de serviços): oscila no 4B (1 certa, 2 de outro artigo), 3 certas no 30B. **D28**: 4B 2 certas e 1 recusa, 30B 3 certas.

Perdas (a recusa era a resposta certa):
- **D29** (compensar crédito de IBS com CBS): o 4B recusava nas 3 (o art. 47 não chegava ao modelo); o 30B responde nas 3, **com outro artigo**.
- **D05** (grãos para o exterior): o 4B respondia errado 1 vez e recusava 2; o 30B responde errado 3 vezes.
- **D06** (crédito): o 4B respondia errado 2 e recusava 1; o 30B, errado 3.

Sentido, nas perguntas pedidas (citação certa com sentido errado não conta como acerto):

| Pergunta | 4B | 30B |
|---|---|---|
| D03 ICMS e ISS | outro artigo (art. 408); vago ("continuam aplicáveis, com regras específicas") | outro artigo (art. 408); diz "vigentes até 31/12/2032", o que **na substância é próximo do certo** (o ICMS e o ISS são extintos a partir de 2033, ADCT art. 129), mas a citação não sustenta |
| D05 exportação de grãos | outro artigo; **sentido errado** ("não paga" por causa do art. 82, § 11, que é suspensão para a indústria exportadora); 1 de 3, nas outras recusa | outro artigo 3 de 3; **sentido errado**, e acrescenta a condição de "exportação acima de 30% da receita" como se fosse do produtor (é do adquirente industrial) |
| D06 crédito | outro artigo; **sentido errado** (diz que o crédito "não se aplica" às compras de contribuinte) | outro artigo 3 de 3; **sentido errado** (descreve só o crédito presumido de bem móvel usado, art. 171) |
| D07 importação | certa, **(c) sentido**: lê o prazo de pagamento (art. 76) como "quando surge a obrigação" | certa, **(c)** igual ("surge até a entrega"); marcado à parte, não como acerto |
| D14 prazo do crédito | outro artigo (créditos presumidos, arts. 452 e 468); "5 anos" certo por coincidência | idem |
| D15 fato gerador | outro artigo (art. 254, imóveis) | outro artigo (art. 254) |
| D16 base de cálculo | outro artigo (art. 173, combustíveis) | outro artigo (art. 255, imóveis) |

O 30B **não corrige** nenhuma das 7 falhas de busca/escolha (D03, D05, D06, D14, D15, D16 continuam outro artigo; D07 segue com o (c)): como o artigo certo não chega, modelo maior não o inventa. O que ele muda é o comportamento diante do trecho errado: responde em vez de recusar.

## Guarda: descartar a resposta quando todas as citações são da LC 214 em regime específico e a pergunta não cita o setor

Limites conferidos no texto da lei: o título "Dos regimes específicos do IBS e da CBS" começa depois do art. 171 (o título aparece colado ao fim do art. 171) e vai até o **art. 307** (o art. 308 abre "Dos regimes diferenciados da CBS" – ProUni); os arts. 172 a 307 batem. Capítulos: combustíveis 172–180, serviços financeiros 181–233, planos de saúde 234–243, prognósticos 244–250, bens imóveis 251–270, cooperativas 271–272, bares/hotelaria/transporte/turismo 273–291, SAF 292–296, missões diplomáticas 297–299, disposições comuns 300–307. O "setor" da pergunta é detectado por palavras do capítulo (`analisa_vps.py`).

| | Outro artigo (total) | Barradas pela guarda | Certas barradas |
|---|---|---|---|
| 4B (129) | 26 | **8** (D15 ×3, D16 ×3, D25 ×2) | **0** |
| 30B (129) | 30 | **6** (D15 ×3, D16 ×3) | **0** |

A guarda não barra nenhuma resposta certa nos dois modelos, e pega só os erros que citam regime de combustível ou de imóveis para pergunta geral (31% das respostas de outro artigo no 4B, 20% no 30B). Fica de fora tudo que cita um artigo geral errado (D03, D05, D06, D14). Com o 30B, a guarda também não resolve a troca de recusa por erro de D29, D05 e D06.

## Conclusão desta rodada
1. **Tempo (pela 1ª repetição, ver a correção abaixo)**: p50 de 11,6 s no 4B e 14,0 s no 30B, os dois abaixo da meta da #270 para lei (p50 ≤ 25 s); o p95 do 30B (35,9 s) fica acima dela.
2. **Qualidade: misto.** O 30B acerta o que o 4B recusava por medo (D04, D10, D26) e perde a proteção das recusas (D29, D05, D06). Em 129 execuções: +12 certas, +4 de outro artigo, −16 recusas. Pela regra combinada, **não é candidato**.
3. **O sentido não melhora** nas perguntas em que o artigo certo não chega; só melhora onde o artigo chegou e o 4B recusava.
4. **A guarda do regime específico** é segura nos dados (0 certa barrada) e cobre D15 e D16 em qualquer um dos dois modelos; com o 30B ela é ainda mais útil, porque o 30B deixa de recusar.
5. **Fora do teste:** a rota de dados e o classificador de rota (só a rota de lei foi medida); um único modelo por vez; as mesmas 43 perguntas que orientaram as outras decisões (D23 a D43 só como controle).

## Correção do tempo (apontada pelo orquestrador): só a 1ª repetição de cada pergunta

As repetições 2 e 3 da mesma pergunta vêm com o prefixo do prompt em cache e a busca repetida, e por isso são bem mais rápidas (p50 de 2,3 s no 4B e 3,3 s no 30B). A 1ª repetição é o tempo de uma pergunta nova. Conferido nos dados brutos (`ms` de cada rodada, fora a falha de coleta):

| Conjunto | 4B p50 / p95 (s) | 30B p50 / p95 (s) |
|---|---|---|
| treino (D01-D18, D21) | 12.8 / 22.4 (n=19) | 14.1 / 35.9 (n=19) |
| controle 1 (D23-D31) | 11.4 / 103.2 (n=9) | 15.2 / 46.4 (n=9) |
| controle 2 (D32-D43) | 11.6 / 14.3 (n=12) | 14.1 / 18.2 (n=12) |
| lei ausente (D19, D20, D22) | 0.1 / 3.5 (n=3) | 0.2 / 4.1 (n=3) |
| **Todas as perguntas** | **11.6 / 22.4 (n=43)** | **14.0 / 35.9 (n=43)** |

Leitura: o p50 do 30B é cerca de 2,4 s maior que o do 4B (14,0 contra 11,6 s) e o p95 é 13 s maior (35,9 contra 22,4 s), com a meta de p50 ≤ 25 s cumprida pelos dois. O p95 de 103 s do 4B no controle 1 é uma única chamada longa (n=9), não uma tendência. A lei ausente (sem trecho acima do limiar) não chama o modelo, daí 0,1 s.

Dados: `vps-4b-43-perguntas.json`, `vps-30b-43-perguntas.json` (D01 refeito), `vps-30b-43-perguntas-bruto-com-D01-com-erro.json`, `analisa_vps.py` (tabelas e guarda).
