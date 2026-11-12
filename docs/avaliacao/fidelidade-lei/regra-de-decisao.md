# Regra de decisão — fidelidade da rota de lei da Ceres (#269)

Esta regra foi escrita **antes de qualquer rodada** e fica congelada junto do conjunto
(`perguntas-fidelidade-lei.json`, sha256 ao lado). Mudar um número, uma lista ou um prompt depois de
uma rodada invalida a rodada: muda-se a regra, sobe-se a versão do conjunto/da regra e roda-se de
novo. Quem escreveu esta regra e o conjunto nunca viu os conjuntos anteriores da Ceres nem o código
da correção.

Versão da regra: `2026-11-12.2` (a mesma do conjunto).

## 1. O que se compara

Dois ou mais braços **no mesmo commit** (o head do PR da correção), com o mesmo jar, o mesmo banco e
o mesmo modelo. O desenho padrão tem dois:

| Braço | Configuração |
|-------|--------------|
| `desligada` | flag da correção desligada (comportamento da `main`): o braço de **referência** |
| `ligada` | flag da correção ligada |

A rodada pode trazer **outros braços** (por exemplo, a mesma correção em camadas: só a camada em
código, ou a camada em código mais uma segunda chamada que só reprova). **Cada braço extra é
comparado ao `desligada`, um a um, com a mesma regra** (seções 5 a 7); não se comparam braços entre si
e cada comparação tem o seu desfecho. A leitura dos juízes é uma só (as respostas aceitas de todos os
braços, embaralhadas), e a concordância entre os juízes vale para todas as comparações. O `desligada`
é sempre o primeiro braço do manifesto da rodada.

- Modelo: `claude-haiku-5-5` pela API da Anthropic (`AI_CHAT_PROVIDER=anthropic`,
  `AI_ANTHROPIC_MODEL=claude-haiku-5-5`, `AI_ANTHROPIC_THINKING=disabled`). A temperatura é a do
  padrão da API (1.0), então as respostas variam de uma repetição para outra: por isso as 3
  repetições e a regra por maioria.
- Conjunto: as 36 perguntas de `perguntas-fidelidade-lei.json` (30 com lei na base e 6 de controle de
  lei ausente), **3 repetições por braço**: 108 execuções por braço.
- Uma **execução** é uma pergunta, em um braço, em uma repetição.
- As variáveis de ambiente de cada braço chegam por parâmetro ao executor
  (`--env-desligada`, `--env-ligada`, e `--braco NOME:CHAVE=valor,...` para os extras); o que não é a
  flag é igual em todos os braços.
- As execuções dos braços são **intercaladas** (cada pergunta vai a todos os braços em seguida, com a
  ordem dos braços girando de uma pergunta para a outra), para que a latência da API e a hora do dia
  pesem igual em todos.

## 2. Falha técnica

Execução com **falha técnica** não é lida e não entra em nenhum denominador:

- HTTP diferente de 200 depois das esperas permitidas (429 `MUITAS_TENTATIVAS` e 503
  `ASSISTENTE_OCUPADO` esperam o `Retry-After`, até 5 vezes), tempo esgotado ou erro de rede;
- `modoFallback: true` (o modelo ficou indisponível e a resposta saiu sem ele);
- desfecho de auditoria `MODELO_INDISPONIVEL`, `MODO_FALLBACK`, `TEMPO_ESGOTADO`, `CANCELADA` ou `ERRO`;
- sem linha de auditoria correspondente depois da espera do executor.

Tratamento:

1. Ao fim de cada braço, o executor **refaz uma vez** só as execuções com falha técnica (a tentativa
   original e a nova ficam nas respostas brutas; vale a nova).
2. Continuando a falhar, a execução é **falha técnica** e é só contada.
3. A comparação é **inválida** (veredito `INCONCLUSIVA`) se, em qualquer dos dois braços, mais de **10%** das
   execuções (mais de 10 de 108) forem falha técnica, ou se `429 LIMITE_USO_ASSISTENTE` interromper a
   rodada.
4. Por pergunta, só entra nas leituras por pergunta (seção 6) quem tem **ao menos 2 execuções
   válidas em cada braço**; senão a pergunta sai das duas colunas, nos dois braços, e vai à tabela de
   excluídas.

## 3. Classificação de cada execução válida

Pelo desfecho da auditoria (`assistente_interacao`), que o executor guarda com cada resposta bruta:

| Classe | Critério |
|--------|----------|
| **aceita** | `rota = LEGISLACAO`, desfecho `RESPONDIDA`, sem fallback: texto do modelo entregue com citações |
| **recusa** | desfecho `SEM_FUNDAMENTO_LEGAL` ou rota `FORA_DE_ESCOPO` |
| **descartada** | qualquer outra execução válida que não seja aceita (`CITACAO_INVALIDA`, `NUMERO_SEM_FONTE`, `RESPOSTA_CORTADA`, `REGIME_ESPECIFICO_SEM_SETOR`, `AFIRMACAO_SOBRE_DADOS`, rota diferente de lei e **qualquer desfecho novo que a correção criar**) |

A lista de desfechos de recusa é da regra, não da correção: desfecho novo da correção conta como
**descartada**, salvo decisão registrada antes da rodada (e então a versão da regra sobe).

Uma execução aceita que os juízes leem como `tipoResposta = RECUSA` (o texto aceito diz que não há
fundamento, sem afirmar regra) conta como **recusa** (e não como aceita) para a medida de recusa falsa
e **sai** da leitura de fidelidade.

## 4. A leitura de fidelidade

Só as execuções **aceitas** são lidas, de todas as classes de pergunta, de todos os braços.

### 4.1 Juízes

- Dois juízes independentes (J1 e J2) leem **todos** os lotes, **cegos ao braço** (o executor tira o
  braço, a repetição e a ordem: os itens vão embaralhados com id opaco e a chave fica só com quem
  aplica a regra, em arquivo à parte).
- Um terceiro juiz (J3) lê só os itens em que J1 e J2 deram **veredito de resposta diferente**.
- Os juízes são subagentes com **modelo fixado**, diferente do Haiku medido:
  **J1 = `opus`**, **J2 = `sonnet`**, **J3 = `opus`**, em contexto novo, sem acesso ao repositório do
  código da Ceres nem ao nome do braço. A concordância entre J1 e J2 (% e kappa de Cohen no veredito:
  `FIEL`, `COM_RESSALVA`, `INFIEL` ou `RECUSA`) é registrada no resultado.
- O juiz recebe, por item: a pergunta, o texto da resposta, as citações (norma, dispositivo e o texto
  literal do trecho) e o **gabarito de fidelidade** da pergunta (fatos com o dispositivo certo,
  armadilhas, permitidos; nas de lei ausente, o que não pode ser afirmado). Os prompts estão em
  `prompts/`.
- O juiz lê **frase a frase** o texto que o **modelo** escreveu (ignora o bloco final "O que isso quer
  dizer", que é do backend) e dá um rótulo por frase:

| Rótulo da frase | Significa |
|-----------------|-----------|
| `SUPORTADA` | o trecho citado (ou o dispositivo certo do gabarito) sustenta a frase, sem inverter condição, exceção, prazo, percentual ou destinatário |
| `CITACAO_ERRADA` | o conteúdo é verdadeiro pelo gabarito, mas a citação ligada à frase não é o dispositivo que o traz (nem equivalente dito no gabarito) |
| `ALEM_DO_TRECHO` | afirma regra, valor, prazo ou percentual que nenhum trecho citado nem o gabarito sustenta |
| `CONTRARIO` | afirma o contrário do trecho ou do gabarito (inverte condição, exceção, prazo, percentual, destinatário) |
| `OUTRA_RESSALVA` | verdadeira e citada certo, mas induz a erro (omite condição essencial do gabarito, vaga a ponto de enganar) |
| `NAO_JURIDICA` | conectivo, saudação, sugestão de procurar o contador: não conta |

### 4.2 Veredito da resposta (calculado em código, nunca pelo juiz)

Da pior frase para a melhor:

1. `INFIEL` se alguma frase é `CONTRARIO` (subtipo **afirma o contrário do trecho**) ou `ALEM_DO_TRECHO`
   (subtipo **afirma além do trecho**); com os dois, o subtipo é o contrário.
2. senão `COM_RESSALVA`/**citação no dispositivo errado** se alguma frase é `CITACAO_ERRADA`;
3. senão `COM_RESSALVA`/**outra** se alguma frase é `OUTRA_RESSALVA`;
4. senão `FIEL`.

Cada execução tem um veredito só (a lista é exclusiva: uma resposta `INFIEL` com citação errada conta
como `INFIEL`).

### 4.3 Desacordo e concordância

- Veredito final = o de J1 e J2 quando iguais; se diferentes, o **voto majoritário** de J1, J2 e J3 no
  veredito; se os três divergem, vale o do meio na ordem `RECUSA` < `FIEL` < `COM_RESSALVA` < `INFIEL` (com
  `FIEL`, `COM_RESSALVA` e `INFIEL`, vale `COM_RESSALVA`). O subtipo é o dos juízes que formaram a maioria (com subtipos diferentes, o
  contrário prevalece sobre além do trecho, e `CITACAO` sobre `OUTRA`).
- `tipoResposta` (`AFIRMA`/`RECUSA`) também é por maioria entre os juízes que o leram.
- **Leitura não confiável:** se o **kappa de J1 × J2 for menor que 0,40**, ou o desacordo (itens que
  foram a J3) passar de **30%** das respostas lidas, o veredito é `INCONCLUSIVA` e a leitura é refeita
  com um prompt ajustado (a versão da regra sobe). Entre 0,40 e 0,60 o resultado vale, com aviso.

## 5. As medidas

Todas por braço, sobre execuções **válidas**, separando as 30 perguntas com lei na base (**com base**)
das 6 de lei ausente (**ausentes**).

| Id | Medida | Como se conta |
|----|--------|---------------|
| M1 | **defeitos de fidelidade entregues** | nas execuções válidas **com base**: número de execuções com veredito `INFIEL` e número com `COM_RESSALVA`/citação. Denominador: todas as execuções válidas com base, também as não aceitas, porque uma resposta descartada não entrega defeito ao produtor. A taxa entre as aceitas lidas é calculada à parte, só para leitura |
| M2 | **aceitas** (informativa) | % de execuções válidas **com base** classificadas como aceitas (e que não são `RECUSA` pelos juízes). **Não decide**: descartar uma resposta infiel é o que a correção deve fazer, e punir a queda da taxa puniria o acerto |
| M2b | **falso descarte por pergunta** | número de perguntas **com base**, com 2 ou mais execuções válidas em cada braço, que no braço de referência **não são defeituosas** (menos da metade das execuções válidas com defeito, seção 6) **e** são **aceitas na maioria** das repetições (mais da metade das execuções válidas aceitas), e que no braço comparado **deixam de ser aceitas na maioria**. Pergunta que já era defeituosa no `desligada` nunca conta: derrubar a resposta ruim não é falso descarte |
| M3 | **recusa falsa** | % de execuções válidas **com base** classificadas como recusa (seção 3) ou aceitas lidas como `RECUSA` |
| M4 | **lei ausente** | nas execuções válidas **ausentes**: execuções com veredito `INFIEL` (a resposta passou a afirmar). Um acerto é recusa, descartada, ou aceita lida como `RECUSA` |
| M5 | **tempo** | p50 e p95 da latência medida pelo cliente (ms), pelo método nearest-rank (igual ao relatório da avaliação), nas execuções válidas **de todas** as perguntas |
| M6 | **tokens** | média de tokens por execução válida = soma dos campos `tokens_entrada*` e `tokens_saida*` da linha de auditoria (qualquer chamada que a correção acrescentar entra, desde que grave nesses campos) |

M1 e M4 são **medidas de fidelidade**; M2b, M3, M5 e M6 são as **medidas de não piora** (M2 só informa).

## 6. Três formas de contar

A decisão olha os três lados; um deles contradizendo o outro impede `ENTRA`.

1. **Por execução** (a base das margens): a seção 5 como está.
2. **Por pergunta, pela maioria das repetições:** uma pergunta é **defeituosa** num braço se a
   **maioria** das suas execuções válidas (mais da metade; 2 de 3, 2 de 2) tem veredito `INFIEL` ou
   `COM_RESSALVA`/citação. Conta-se por braço o número de perguntas defeituosas **com base**, e as
   perguntas **ausentes** em que a maioria das execuções afirma. Só perguntas com 2 ou mais execuções
   válidas em cada braço.
3. **Por pergunta, qualquer repetição (informativo):** perguntas com defeito em pelo menos uma das 3
   execuções. Não decide; mostra a instabilidade.

Há ainda uma **regressão por pergunta**: pergunta com 0 defeitos nas execuções válidas do braço
`desligada` e maioria defeituosa no braço `ligada`.

## 7. A decisão

### 7.1 Margens e limiares

| Item | Valor |
|------|-------|
| Defeitos mínimos no `desligada` para haver o que melhorar | **8** execuções (M1: `INFIEL` + `COM_RESSALVA`/citação) |
| Melhora mínima de M1 (defeitos totais) | queda de **25%** em relação ao `desligada` **e** de pelo menos **4** execuções |
| Intervalo de confiança da melhora | bootstrap por pergunta (10.000 reamostragens, semente fixa 20261112), **IC de 80%** da queda em defeitos: o limite inferior tem de ser **maior que 0** |
| Piora de cada tipo isolado (`INFIEL`, `COM_RESSALVA`/citação) | no máximo **+1** execução em relação ao `desligada` |
| M2b falso descarte por pergunta | no máximo **3** perguntas (10% das 30 perguntas com base) |
| M3 recusa falsa | `ligada` ≤ `desligada` + **5 pontos percentuais** |
| M4 lei ausente | `INFIEL` de `ligada` ≤ `desligada` + **1** execução, e **nenhuma** pergunta ausente com maioria afirmando no `ligada` que não afirme em maioria no `desligada` |
| M5 tempo | p50 `ligada` ≤ **1,25 ×** p50 `desligada` e p95 `ligada` ≤ **1,40 ×** p95 `desligada` |
| M6 tokens | média `ligada` ≤ **1,50 ×** a média `desligada` |
| Regressões por pergunta (seção 6) | no máximo **1** |
| Perguntas defeituosas por maioria (seção 6.2, com base) | `ligada` ≤ `desligada` (não pode haver mais) |

### 7.2 Desfechos

Os desfechos saem de um procedimento **em ordem**; vale o primeiro passo que se aplica:

1. **`INCONCLUSIVA`** se a rodada é inválida (seção 2, item 3) ou foi interrompida.
2. **`INCONCLUSIVA`** se a leitura não é confiável (seção 4.3: kappa abaixo de 0,40 ou desacordo
   acima de 30%).
3. **`NAO_ENTRA`** se qualquer medida de não piora (M2b, M3, M4, M5, M6, regressões por pergunta,
   perguntas defeituosas por maioria, piora de um tipo isolado) está **fora da margem**, qualquer que
   seja a melhora e **mesmo que a base tenha poucos defeitos**: uma piora clara não fica escondida
   atrás de "pouco a medir".
4. **`INCONCLUSIVA`** se o `desligada` tem **menos de 8** defeitos de fidelidade (M1): a falha que a
   correção quer eliminar não aparece o bastante na base para ser medida; é resultado, não aprovação.
5. **`NAO_ENTRA`** se M1 do `ligada` é **maior ou igual** ao do `desligada` (sem melhora, com defeitos
   para melhorar).
6. **`ENTRA`** se M1 atinge a melhora mínima **e** o IC de 80% fica acima de zero (nenhuma margem foi
   violada, pelo passo 3).
7. **`INCONCLUSIVA`** nos demais casos: há melhora em M1, mas ela não atinge a melhora mínima ou o IC
   de 80% inclui zero (a diferença pode ser ruído das 3 repetições); a ação sugerida é repetir com mais
   repetições (5) sem mudar o resto.

O procedimento vale para **cada comparação** (cada braço extra contra o `desligada`).

Os três desfechos usam só os números e as margens desta tabela. O que o resultado traz além disso
(taxas entre as aceitas, subtipos, instabilidade, lista de perguntas) é para leitura humana e não
decide.

### 7.3 O que a regra não faz

- Não usa critério absoluto, como "todas as respostas fiéis": uma falha que já existe no
  `desligada` não reprova a correção; só conta a **diferença** entre os braços.
- Não compara braços de commits diferentes, de modelos diferentes ou de dias diferentes.
- Não decide por uma única repetição, por uma única pergunta nem pela média sem a maioria.
- Com 108 execuções por braço, a regra só detecta quedas grandes: a melhora mínima e o IC existem para
  que um acaso de amostragem não vire `ENTRA`. Se a correção tiver efeito menor que isso, o resultado
  honesto é `INCONCLUSIVA`.

## 8. Contagem e registro

O script `decidir_fidelidade.py` aplica esta regra aos arquivos de uma rodada e grava
`resultado.json` e `resultado.md`, com uma seção por comparação (cada braço contra o `desligada`) e: os números de M1 a M6 por braço (por execução, por maioria e
por qualquer repetição), a concordância dos juízes, o IC, cada margem com o valor medido e o
resultado de cada teste, as perguntas excluídas e as falhas técnicas por braço, o desfecho e o
motivo em uma frase. O resultado nunca traz o texto das respostas.
