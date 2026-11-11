# Conversas da continuidade da Ceres (#499)

Conjunto de conversas de 2 e 3 turnos para medir a continuidade da Ceres antes do merge do PR da
#499. Ele mede três coisas: a reescrita ("e em setembro?", "e a segunda?"), a autocorreção dos
dados e a reconferência da lei ("tem certeza?"). A regra que decide está em `regra-de-decisao.md`.

Foi escrito por uma sessão que não leu o gabarito (#158), o conjunto do benchmark nem o código ou
os testes do PR da continuidade. A sessão só seguiu o contrato da issue. Fica fora de `treino/`.

| Arquivo | O que é |
|---|---|
| `conversas-continuidade-teste.json` (+ `.sha256`) | 42 conversas que decidem (`continuidade-v2-teste`) |
| `conversas-continuidade-dev.json` (+ `.sha256`) | 8 conversas de desenvolvimento (`continuidade-v2-dev`), sem pergunta em comum com o teste |
| `regra-de-decisao.md` | métricas, limiares e decisão, gravados antes da rodada |
| `revisao-lei.md` | material da revisão dos fatos de lei e das trocas, com as mudanças da v2 marcadas |
| `validar_conjunto.py` | confere a forma, os tokens, os marcadores e o sha256; guarda os catálogos |
| `test_validar_conjunto.py` | testes do validador, rodados na CI |

- **Quem lê o quê:** a sessão que implementa a #499 lê o dev e nunca o teste.
- **Mudança no conjunto:** pergunta mudou, mude a versão e o `.sha256`; o validador e o teste
  conferem os dois.
- **Revisão da lei:** os fatos de lei (`fatos`) e as trocas (`edicoes`) passam pela revisão da
  equipe e do orientador, sem contador. Quem revisa registra em `revisao_lei` (`status`
  `revisado`, `por`, `em`), e a rodada oficial exige isso.

## Versões

### v2 (2026-11-11)

A v2 saiu de um **ensaio rotulado como não oficial**, feito em 2026-11-11 contra o head `1ed747c`
do PR da continuidade: 3 repetições, revisão de lei pendente e nenhum valor de medição. O ensaio
achou dois desvios do PR, que a sessão orquestradora repassou à sessão da implementação, e estas
falhas do conjunto e da pontuação:

- **Pergunta de lei respondida pela rota de dados:** a pergunta sobre a redução de um produto
  nomeado (soja, milho, sementes) é respondida pela função de tratamento do produto (#283), na
  rota de dados, e não chega à reconferência de lei. Mudaram de pergunta o t-lei-01, o t-lei-04,
  o t-lei-08, o t-lei-10 e do d-lei-01 ao d-lei-03.
- **Pergunta de base que nunca confirma:** o t-lei-07 e o t-lei-09 recebiam "Não consegui
  confirmar" em todas as vezes e mudaram de pergunta. A pergunta nova do t-lei-07 fala só em "a
  contribuição do produtor", e a Ceres responde pelo SENAR (Lei 9.528/1997, art. 6º, parágrafo
  único, I); a da v1 era a do Funrural (Lei 8.212/1991, art. 30, III). Nas duas, quem recolhe é o
  adquirente, e a troca continua a mesma.
- **Edição que nunca casaria:** as regex do t-lei-07 da v1 tinham `\b` sem escapar no JSON, que
  vira o caractere backspace. Desde a v2, o validador recusa regex com caractere de controle.
- **t-lei-03, 3º turno:** aceita também `LEI_SO_TRECHOS`. A resposta do 2º turno, a
  reconferência, só traz os trechos de lei, e o "tem certeza?" sobre ela pode cair nesse texto
  fixo, também sem crédito.
- **t-dados-17, 2º turno:** aceita também a rota de dados. Com a reescrita certa ("a venda de
  soja tem redução?"), a resposta vem da função de tratamento do produto. O art. 137 nas
  citações continua exigido.
- **Pontuação:** a execução que perde só os turnos finais (`anterior_fora` parcial) passa a
  contar no limite dos 10% de execuções fora, como a regra já dizia.

**Como as perguntas novas foram escolhidas:**

- por uma sonda, também não oficial, só do primeiro turno, sozinho, 4 vezes cada, no head
  `1ed747c` com a flag ligada;
- o critério foi fixado antes da sonda: rota `LEGISLACAO`, `lei.d` `CONFIRMADA` nas 4 vezes e a
  resposta certa pela lei;
- o turno de continuidade não entrou na escolha.

Das 14 candidatas, 9 cumpriram o critério. Das outras, 4 não confirmaram nas 4 vezes (3 nunca
confirmaram e 1 confirmou uma vez) e 1 foi à rota mista.

**Mini-ensaio antes de congelar:** só as 11 conversas trocadas, 1 repetição, contra o head
`082dfa2` do PR, também não oficial, das 11h36 às 11h39 de 2026-11-11, com o conjunto já no sha
desta v2. No braço ligado:

- as 10 conversas de lei trocadas foram à rota de lei e confirmaram no 1º turno;
- os 3 pares reais foram montados e nenhuma execução ficou fora;
- o 3º turno do t-lei-03 caiu em `LEI_SO_TRECHOS`, sem crédito;
- a única falha foi o 2º turno do t-dados-17, que não foi reescrito e foi à lei sem o produto,
  como nas 3 repetições do ensaio. A falha é da continuidade, e a métrica a conta.

Nenhuma conversa mudou depois do mini-ensaio.

**O que não mudou:** as outras 34 conversas do teste e 5 do dev, as métricas e os limiares.

### v1 (2026-11-11)

Primeira versão, congelada no PR #504 antes de qualquer rodada.

## Cobertura do teste

| Grupo | Categorias (conversas) |
|---|---|
| Dados | confirmação com a anterior certa (2), anterior errada por produto (2), período (1), situação (1), número (1) e função (1, só relatada), contestação que reafirma (1), contestação com conteúdo novo (1), referência à resposta (2), elipse de assunto (2), elipse de período (2, uma com 3 turnos), troca para lei (1), controle (2) |
| Lei | anterior certa (1), dois percentuais no mesmo dispositivo (1), “tem certeza?” duas vezes (1), anterior errada por percentual, sim/não, dispositivo e quem paga (1 cada, as duas últimas esperando o apoio), premissa falsa por número e por sim/não (1 cada), contestação com número e com polaridade errados (1 cada) |
| Caminhos fixos | “tem certeza?” depois do atalho de lei, do atalho de dados, de `SEM_FUNDAMENTO`, de recusa e de conversa leve (1 cada) |
| Adversários | injeção na mensagem, no título de um pedido e no motivo de uma devolução; número forjado num par assinado; par adulterado, alheio e vencido (1 cada) |

O dev tem uma conversa de cada uma destas categorias:

- confirmação de dados;
- produto errado;
- elipse de período;
- referência à resposta;
- lei com a anterior certa;
- lei com o percentual errado;
- contestação de lei;
- “tem certeza?” depois de `SEM_CONFIRMACAO`.

## Formato

```json
{
  "versao": "continuidade-v2-teste", "parte": "teste", "produtor": "carlos.rossi@example.com",
  "revisao_lei": {"status": "pendente", "por": null, "em": null},
  "preparo": [ ... ],
  "conversas": [ {"id": "t-...", "categoria": "...", "turnos": [ ... ]} ]
}
```

### Turnos

- **`{"tipo": "pergunta", "texto": "...", "esperado": {...}}`:** mensagem ao vivo do produtor.
- **`{"tipo": "par", "origem": "real", ...}`:** troca anterior fabricada a partir de uma resposta
  verdadeira. O script:
  1. faz a `pergunta_real` ao vivo, no braço ligado e sem histórico;
  2. confere o `exigir` (`rotas`, `alternativas`, `fatos`, `lei_d`), decodificando a carga da
     assinatura para o `lei_d`;
  3. aplica as `edicoes` em ordem (`regex` → `para`, só dentro de `lei.r` ou na `resposta`
     inteira, a primeira ocorrência ou `todas`). Elas correm no texto **original**, como a Ceres o
     entregou, com acento e maiúscula, e a regex distingue maiúscula de minúscula; o texto
     normalizado é só das conferências. Sem `opcional`, a edição que não acha nada torna o preparo
     inválido; e, se nenhuma edição do par mudar o texto, o preparo também é inválido;
  4. ajusta as posições da carga ao novo tamanho do texto (`lei.r` e os `t` depois da edição;
     edição dentro de um `t` torna o preparo inválido);
  5. assina de novo, com `pergunta` como pergunta do par (padrão: a real).

  Opções do par real:

  | Opção | Efeito |
  |---|---|
  | `exp: "vencido"` | a carga vence 1 minuto antes do envio |
  | `assinar_para: "outro_produtor"` | assina com o id de outro produtor do seed (`luiz`) |
  | `adulterar: "resposta"` | troca um dígito da resposta depois de assinar |
  | `adulterar: "carga"` | troca um caractere da carga depois de assinar |
- **`{"tipo": "par", "origem": "fixa", "pergunta", "resposta", "carga"}`:** troca anterior escrita
  no arquivo, para os caminhos que uma pergunta ao vivo não garante (`SEM_FUNDAMENTO`,
  `SEM_CONFIRMACAO`). O script completa o `exp` no formato de um turno real e assina.
- **Assinatura dos pares fabricados:** sempre a da #499, seção "Assinatura": `v1.<carga>.<mac>`,
  com MAC sobre o produtor, a carga, a pergunta e a resposta, e chave derivada da `CRIPTO_CHAVE`.
  Só com a chave pública de desenvolvimento e só localmente.

### Marcadores

| Marcador | Valor |
|---|---|
| `{nota:devolvida_seed}` | número da nota DEVOLVIDA do seed (motivo “Peso divergente do romaneio...”) |
| `{nota:cancelada_seed}` | número da nota CANCELADA do seed |
| `{nota:aguardando_seed}` | número da nota AGUARDANDO_APROVACAO do seed, antes do preparo |
| `{resposta:N:nota:K}` | o K-ésimo número distinto depois de “nota” na resposta do turno N |

Os marcadores de nota saem do banco do braço, resolvidos antes do preparo.

### Preparo

O preparo roda uma vez por braço, antes da primeira repetição, com a conta da administradora do
escritório do seed.

| Ação | Efeito |
|---|---|
| `pedido_do_escritorio` | `POST /api/contador/produtores/{id}/pendencias` com o título, a descrição e o prazo |
| `devolver_nota` | `POST /api/contador/produtores/{id}/notas/{nota}/devolver` com o motivo |

### `esperado`

As conferências e as métricas estão em `regra-de-decisao.md`.

| Chave | O que confere |
|---|---|
| `rotas` | lista de rotas aceitas |
| `alternativas` / `proibidas` | condições sobre `fontesDados`. A gramática é a do `pontuar.py` da #452 (`funcoes`, `produto`, `mes`, `inicio`, `fim`, `dias_ate`, `tipo` e os tokens de data), mais `situacao` e `numero` (dígitos ou marcador) |
| `consulta_igual_ao_turno` | mesmas funções e parâmetros do turno citado |
| `abertura` | categoria nos primeiros 300 caracteres |
| `contem` | categorias em algum ponto do texto |
| `proibidas_texto` | categorias que não podem aparecer |
| `sem_regex` | regex que não podem casar, no texto normalizado (sem acento, minúsculas, espaços reduzidos) |
| `correcao_contem` | regex que casam depois da abertura `LEI_CORRIJO` |
| `citacao_contem` | dispositivo em alguma citação |
| `interpretada` | `ausente_ou_igual` (controle) |
| `credito_zero` | o turno não gasta crédito |
| `sem_turno` | a resposta não traz `turno` (recusa, conversa leve) |
| `fatos` | `certo_todos` e `errado_algum`, regex que classificam uma resposta de lei como certa, errada ou indefinida |
| `se_anterior` | `certa` / `errada`: o critério de cada caso, pela classificação do turno mais recente com `fatos` |
| `relatar` | o que só vai ao relatório |
| `metricas` | as métricas que o turno alimenta |

As categorias de texto vêm dos textos fixos da #499 (seções "Autocorreção", "Reconferência de lei"
e "Textos") e estão em `CATEGORIAS_ABERTURA`, no `validar_conjunto.py`. Se o PR da continuidade
escrever algum desses textos com outras palavras, a regex muda numa versão nova, antes da rodada.

## Conferir

```bash
python3 docs/avaliacao/continuidade/validar_conjunto.py
python3 -m unittest discover -s docs/avaliacao/continuidade -p "test_*.py"
```

O validador importa os tokens de data do `docs/avaliacao/ceres-452/pontuar.py`, sem gravar
bytecode.
