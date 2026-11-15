# Regra de decisão da medição da #443

Versão da regra: `2026-11-15.1`. Conjunto: `perguntas-ressalva-443.json`, versão `2026-11-15.v1`.
**Escrita e congelada antes de qualquer rodada, sem ver nenhum resultado.** As margens estão em
`ressalva_comum.py`: mudou uma, mude esta regra, a versão da regra e rode de novo. Mudar uma pergunta
muda o sha256: suba a versão do conjunto.

## 1. O que se mede

A #443: a pergunta sobre redução ou tratamento de um produto, dependendo de como é escrita, cai na rota
de lei, que cita o artigo certo e afirma o percentual **sem a ressalva** de que o enquadramento do
produto se confirma com o contador. A rota de dados (`consultarTratamentoProduto`,
`TemplateTratamentoProduto`) traz a ressalva quando o produto tem condição (o template só a escreve
nesse caso).

**Defeito corrigido** = a resposta traz **o tratamento do produto e a ressalva do enquadramento com o
contador, venha pela rota que vier**. A rota não entra na conta (é só diagnóstico).

Dois braços, ambos o Haiku 5.5 pela API (temperatura 1,0, raciocínio desligado), a mesma conta
semeada, o mesmo seed e a mesma base de lei:

- **`base`:** a `main` (o SHA vai no manifesto da rodada);
- **`candidato`:** o head do PR da correção.

3 repetições por pergunta e braço (60 perguntas × 3 × 2 = 360 execuções), intercaladas pelo executor.
Só a diferença de código separa os braços. O executor **exige** o SHA de cada braço (`--commit`) e grava
no manifesto; a decisão recusa rodada sem eles, com outro número de repetições ou só com parte das
perguntas.

## 2. O que conta como acerto em cada execução

Detectores em `ressalva_comum.py` (texto sem acento e sem caixa):

- **Ressalva:** alguma frase cita `contador`, `contadora`, `contabilista` ou `escritório de
  contabilidade` **e** manda confirmar, conferir, consultar, validar, perguntar ou procurar (ou fala
  de enquadramento ou de condição). As recusas fixas da rota de lei (`Não consegui localizar a base
  legal…`, `Não consegui confirmar uma resposta…`, `não encontrei fundamento legal`) **nunca** contam,
  mesmo citando o contador.
- **Tratamento:** a resposta traz percentual (`60%`, `por cento`), `alíquota zero`, `alíquota cheia` ou
  `sem redução`, e não é recusa.

Por grupo:

| Grupo | Acerto da execução |
|-------|--------------------|
| **Defeito** (36) | HTTP 200 **e** tratamento **e** ressalva. Sem ressalva: `DEFEITO`. Sem tratamento (recusa, sem número, fora de escopo): `SEM_TRATAMENTO`. |
| **Controle de lei** (12) | HTTP 200, rota `LEGISLACAO`, pelo menos uma citação de uma norma aceita para a pergunta, não é recusa e **não** traz texto da tabela de tratamento (`cClassTrib`, `tabela oficial de classificação`). |
| **Controle de dados, tratamento** (9) | HTTP 200, rota `DADOS`, a função `consultarTratamentoProduto` nas fontes, tratamento e ressalva. |
| **Controle de dados, dado puro** (3) | HTTP 200, rota `DADOS`, alguma função de dados nas fontes que **não** seja `consultarTratamentoProduto`, sem ressalva e sem texto da tabela. |

O detector é lexical e mede a **presença** da ressalva, não se o percentual está certo (isso é da tabela).
Por isso há leitura humana (seção 6).

### Estratos do grupo de defeito

- **Estrato A (30 perguntas, decide):** produtos para os quais, **pela leitura do código** do
  `TemplateTratamentoProduto` e do `TratamentoProdutoService` (nada foi executado), o template da rota
  de dados escreve a ressalva hoje: in natura e Anexos I e VII, que têm condição de destino.
- **Estrato B (6 perguntas, informa):** produtos do Anexo IX (insumos) e do Anexo XV (hortícolas, frutas,
  ovos), em que a condição do dado pode não existir e o template, **mesmo na rota de dados, pode não
  trazer a ressalva**. Se for assim, a ressalva ali não vem de um conserto da rota de lei, e sim de uma
  decisão sobre o template. Por isso o estrato B é reportado e gera alerta, mas **não decide**. O
  conjunto não afirma qual é o caso: a rodada da base mostra.

## 3. A régua de ruído (a do projeto, não uma nova)

A Ceres roda com temperatura 1,0 e não repete a si mesma: a diferença entre repetições da **mesma**
pergunta é ruído. O projeto já fixou como ler isso, em
`docs/avaliacao/resultados/2026-11-15-comparacao-haiku-2bfb1ef3-main-fc58fa83.md`, **linha 50**
("Compare por soma de acertos nas 3 repetições e por id, não pela média") e **linha 51**:

> Como regra de bolso (minha, não é teste estatístico): um efeito real de uma correção é um id que
> muda nas 3 repetições (…) ou uma mudança de uns 5 acertos ou mais na soma das repetições; abaixo
> disso, trate como ruído até repetir.

Aplicação nesta regra (margens em `ressalva_comum.py`):

- **Efeito real** = um id que muda nas 3 repetições (0 de 3 na base e 3 de 3 no candidato, ou o
  inverso) **ou** uma mudança de **5** acertos na soma das repetições do grupo (`EFEITO_SOMA`).
- Diferença menor que isso, sem id que mude nas 3, é ruído e **não** conta nem como melhora nem como piora.
- Um id que perde 2 repetições (3 → 1, por exemplo) não é efeito real: gera **alerta** na leitura, não
  bloqueio (nos controles e no estrato A).

A regra de bolso tem uma lacuna que esta regra assume: com 30 ids × 3 repetições no estrato A, 5
acertos é pouco mais de 5% da soma. Um efeito de um id só (o caminho "muda nas 3 repetições") é
suficiente para ser **real**, mas quem decide se uma correção que corrige um único id vale o merge é o
Murillo, com o resultado na mão; por isso o relatório sempre traz os ids corrigidos e a soma.

## 4. Portões (a rodada tem de ser válida antes de valer)

Qualquer um falhando faz o veredito ser `INCONCLUSIVA`, nunca `ENTRA` ou `NAO_ENTRA`:

1. **Rodada completa e sem falha técnica demais.** As 3 repetições de todo id em todo braço; cota ou
   interrupção = inconclusiva. Falha técnica é HTTP diferente de 200 **ou resposta em `modoFallback`**
   (montada sem o modelo, que não mede a Ceres), depois da repetição do executor; acima de 5% das
   execuções de um braço (`TETO_FALHAS_TECNICAS`) a rodada é inconclusiva.
2. **O defeito se reproduz na base.** No estrato A, a base tem de **falhar em pelo menos 15 das 90
   execuções** (`MINIMO_FALHAS_DA_BASE`). Menos que isso e o conjunto não mostra o defeito: o problema
   já não existe, ou o conjunto não o pega. A margem foi escolhida antes da rodada: com 15 falhas, a
   melhora de 5 acertos é um terço do que há para corrigir.
3. **Os controles valem na base.** Um controle **qualifica** se a **base** o acerta em pelo menos 2 das 3
   repetições (`MINIMO_ACERTOS_DO_CONTROLE`: "hoje funciona"). Por grupo (lei, dados), **8 de 12** têm de
   qualificar (`MINIMO_CONTROLES_QUALIFICADOS`). O controle que não qualifica sai da comparação nos dois
   braços e fica listado no resultado. A qualificação olha só o braço da base, nunca o candidato.

## 5. O veredito

Depois dos portões, nesta ordem:

**`NAO_ENTRA`** se qualquer um destes ocorrer:

- **Controle piorou:** em controles de lei ou de dados qualificados, a soma de acertos do candidato fica
  **5 ou mais** abaixo da da base, **ou** algum id vai de 3 de 3 na base para **0** de 3 no candidato.
- **O estrato A piorou:** soma 5 ou mais abaixo, ou algum id vai de 3 de 3 para 0 de 3.
- **A ressalva veio à custa da resposta:** no estrato A, as respostas `SEM_TRATAMENTO` do candidato
  superam as da base em 5 ou mais (a correção trocou o defeito por recusa).
- **Sem efeito:** nenhum efeito real positivo no estrato A (seção 3).

**`ENTRA`** só se o estrato A tem **efeito real positivo** (seção 3) e nada acima ocorreu.

`ENTRA` é a conclusão desta medição, **não** um merge. O merge muda o que a banca mede e depende do OK
do Murillo.

## 6. Leitura humana e alertas

- O `resultado.md` lista, para todo id do defeito em que os braços discordam, a **resposta da repetição
  1 de cada braço**. Quem lê **não** é quem escreveu a correção. Se a leitura discordar do detector em
  um id que decidiu o veredito (corrigido ou piorado nas 3 repetições), a decisão é inválida e o
  detector é corrigido antes de repetir a decisão (a rodada não se refaz: as respostas são as mesmas).
- O detector é lexical e a lista acima só traz os ids em que a **contagem** muda. Por isso o
  `resultado.md` traz também as respostas do candidato que o detector **não** reconheceu como ressalva
  mas que falam do contador ou da contabilidade: a leitura confere se há ressalva que ele deixou passar.
- **Alertas** (aparecem, não bloqueiam): estrato B perdeu 3 acertos ou mais; id do estrato A ou de
  controle com perda de 2 repetições ou mais; controles excluídos na qualificação.

## 7. Limites que esta regra declara

- 60 perguntas, 3 repetições, um único modelo. É régua de bolso, não teste estatístico, como a que
  ela cita.
- Os detectores são lexicais. Uma resposta pode dizer "contador" sem ressalvar o percentual (ou
  ressalvar sem a palavra); a leitura humana (seção 6) e os testes de `test_decisao.py`, que usam o
  texto real do template e as recusas fixas da rota de lei, limitam o erro, não o eliminam.
- O conjunto não decide **o que o produto é** (o percentual correto): o dado vem da tabela oficial.
  Perguntas que pedem o artigo ("qual artigo…") ficaram de fora de propósito: a rota de lei as responde
  com razão e a ressalva não se aplica do mesmo jeito.
- As perguntas foram escritas sem rodar a Ceres (não havia banco, modelo nem chave no ambiente de
  escrita). "Defeito esperado" é uma hipótese a partir do desenho das rotas (seção 4, portão 2, existe
  para o caso de a hipótese não se sustentar), e as normas aceitas dos controles de lei são o que a base
  cita para o tema; o portão 3 exclui o controle que a base não acerta.

## 8. Teste cego

Este conjunto é **conjunto de teste**. Quem escreve ou revisa a correção da #443 **não lê**
`perguntas-ressalva-443.json`, não o usa em exemplo de prompt, de treino, de teste, de template ou de
glossário, e recebe só os ids (`RS07`, `CL03`). O conjunto foi escrito por uma sessão que não leu o
gabarito, o conjunto do benchmark, os conjuntos de fidelidade nem o conjunto da #447. Mudança da Ceres
feita por quem leu uma pergunta a contamina. O `ConjuntoRessalvaTratamentoTest` confere que nenhuma
pergunta está no treino nem no prompt do classificador.

## 9. Margens (espelho de `ressalva_comum.py`, conferido por `test_conjunto.py`)

- `REPETICOES = 3`
- `EFEITO_SOMA = 5`
- `MINIMO_FALHAS_DA_BASE = 15`
- `MINIMO_ACERTOS_DO_CONTROLE = 2`
- `MINIMO_CONTROLES_QUALIFICADOS = 8`
- `TETO_FALHAS_TECNICAS = 0.05`
- `ALERTA_ESTRATO_B = 3`
- `ALERTA_CONTROLE = 2`
