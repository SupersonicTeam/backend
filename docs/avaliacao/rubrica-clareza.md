# Rubrica de clareza da Ceres (avaliador: orientador)

Instrumento da issue #264. A **nota** é dada na #275 pelo **orientador**, que é de fora da equipe
(decisão do Murillo, 2026-11-06, #387: sem contador): nota dada pelos três autores é enviesada.

## O que o avaliador recebe

Só o arquivo `<data>-<hora>-<modelo>-clareza-cego.csv`, gerado pelo comando de avaliação. Ele tem
`item`, `pergunta`, `resposta` e duas colunas para preencher (`nota_1_a_5`, `comentario`), em
ordem embaralhada, **sem** rota, variante, rodada ou resultado do gabarito.

**Não entregue** o `-clareza-chave.json`: é ele que liga cada item à pergunta e à rodada, e fica
com a equipe para cruzar as notas depois.

## A pergunta que a nota responde

> **Um produtor rural, sem formação em contabilidade, entenderia esta resposta e saberia o que
> fazer com ela?**

Julgue só a **clareza**. Não julgue se o número ou a lei estão certos (o gabarito já mede isso),
nem o assunto da pergunta.

| Nota | Quer dizer | Sinais |
|---|---|---|
| **5** | Entende de primeira e sabe o que fazer | Frases curtas; termo técnico, quando aparece, explicado; o número ou a resposta vêm logo no começo |
| **4** | Entende, com uma releitura | Uma frase longa ou uma sigla sem explicação, mas o essencial está claro |
| **3** | Entende parte | Precisa adivinhar o sentido de algum termo, ou a resposta está no meio de explicação que não pediu |
| **2** | Entende pouco | Linguagem de lei ou de sistema; várias siglas soltas; não fica claro o que fazer |
| **1** | Não entende | Texto confuso, contraditório ou que não responde à pergunta feita |

Recusas ("não encontrei...", "desculpe, não consigo ajudar com isso") também recebem
nota: uma recusa clara, que diz o que foi procurado, vale 4 ou 5.

## Como preencher

1. Leia a pergunta e a resposta de cada linha, na ordem do arquivo.
2. Preencha `nota_1_a_5` com um inteiro de 1 a 5. Em `comentario`, opcional, o que mais atrapalhou
   ("sigla CBS sem explicação", "resposta no fim").
3. Não volte para mudar notas antigas depois de ver respostas parecidas: a ordem embaralhada existe
   para isso.
4. Devolva o CSV preenchido. A equipe cruza com a chave e registra a média por rota na #275.

## Medidas automáticas (sem avaliador)

O relatório de toda rodada já traz, por rota, a média de **palavras por frase** e quantas respostas
usam **sigla do glossário sem explicação** (IBS, CBS, NF-e, NCM, CFOP, Funrural, DANFE, SEFAZ;
`ClarezaTexto`). São um indicador barato para toda etapa; a nota humana é o critério das etapas de
redação (4, 5 e 7 da Ceres 2.0) e da linha de base.

## Perguntas externas: os 5 critérios do Murillo (#358)

As 40 perguntas externas do benchmark (`benchmark/perguntas-benchmark.json`, origem
`inedita_externa_ia`: geradas por IA, ChatGPT, a pedido do Murillo; não são perguntas reais de
produtores) são avaliadas pelos 5 critérios do Murillo. Dois são **automáticos** e saem no
relatório do benchmark: **alíquotas** (classe de tratamento dita na resposta) e **fundamentação**
(dispositivo aceito citado, depois de conferido na base). Os outros três são **humanos**, no
arquivo `externas-avaliacao-cego.csv` que o gerador do relatório grava na pasta da rodada: as colunas
`contextualizacao_1a5`, `orientacao_pratica_1a5` e `precisao_final_1a5`, de 1 a 5, e `comentario`.
O `externas-avaliacao-chave.json` liga cada `codigo` à célula e à pergunta e **não vai ao
avaliador** (o `.gitignore` de `resultados/` já o ignora).

| Critério | Vale 1 | Vale 5 | Onde |
|---|---|---|---|
| **Precisão tributária** | Afirma regra errada (tratamento, incidência, crédito) ou contradiz a lei | Tudo o que afirma está certo e não promete o que a lei não dá | heurística no relatório (`afirmacoesProibidas`) + **nota humana final** (`precisao_final_1a5`) |
| **Alíquotas** | Diz a classe errada (zero, redução de 60%, padrão) ou nenhuma quando a pergunta pede | Diz a classe certa e, quando depende do produto ou da situação, diz do que depende | automático (`classeTratamento`, heurística) |
| **Contextualização** | Responde em abstrato, ignora o que o produtor contou (cultura, comprador, situação) | Aplica a regra ao caso descrito na pergunta, com as condições que mudam a resposta | **humano** (`contextualizacao_1a5`) |
| **Fundamentação** | Sem citação, ou citação que não sustenta o que diz | Cita o dispositivo que sustenta a resposta (artigo, anexo) | automático (`dispositivosEsperados`, depois de conferidos) |
| **Orientação prática** | Não diz o que fazer; deixa o produtor sem próximo passo | Diz o próximo passo concreto (o que conferir, onde, e quando procurar o contador) | **humano** (`orientacao_pratica_1a5`) |

Julgue a resposta como um contador julgaria a orientação dada a um cliente produtor rural. Uma
recusa ou um "procure o seu contador" pode valer 5 em orientação prática quando a pergunta depende
da situação do produtor (marcadas com `encaminharContador`), mas vale 1 quando a lei responde
direto.
