# Rubrica de clareza da Ceres (avaliador externo)

Instrumento da issue #264. A **nota** é dada na #275 por alguém de fora da equipe (orientador,
contador parceiro ou produtor rural): nota dada pelos três autores é enviesada.

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

Recusas ("não encontrei...", "essa pergunta está fora do que consigo responder") também recebem
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
