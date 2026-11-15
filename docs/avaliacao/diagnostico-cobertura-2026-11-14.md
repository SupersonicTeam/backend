# Diagnóstico de cobertura da Ceres — perguntas do cotidiano do produtor

**Este documento não é um conjunto de avaliação.** É um diagnóstico de cobertura datado. As
perguntas **não entram** em treino, gabarito, benchmark nem em qualquer medição da Ceres, e
lê-lo não contamina sessão de código.

> **Se um dia estas perguntas virarem conjunto de avaliação, este documento passa a ser material
> contaminante** — ele cita o texto de várias delas e mostra a resposta que a Ceres deu. Nesse
> caso, ele entra na lista de proibidos junto com o conjunto. A lição vem de dentro deste
> trabalho: em 14/11 descobrimos que um documento de diagnóstico do projeto reproduz perguntas
> protegidas e respostas da Ceres, e o `CLAUDE.md` o aponta como referência de leitura.

- **Rodada:** 2026-11-14, `main` em `8c46bc50`, provedor Anthropic (`claude-haiku-5-5`),
  fidelidade da rota de lei desligada — a Ceres como está na VPS hoje.
- **Conjunto:** 116 perguntas. 95 levantadas pelo Murillo com outros agentes de IA, fora do
  projeto, mais 21 escritas nesta sessão para cobrir o que aquela lista não tocava (dado do
  próprio produtor, Funrural, Simples).
- **Execução:** 116 de 116, zero falhas de transporte. Quatro produtores do seed da demonstração.

## Resposta curta

**A Ceres respondeu bem ou recusou bem 46 das 116 (40%).** O problema dominante não é a base de
lei: é que ela **não sabe falar do próprio produto** e **não sabe recusar o que não é dela**. Das
44 perguntas claramente fora do escopo, 26 receberam uma falha de execução no lugar de uma recusa
— e das 18 que recusaram bem, **nenhuma** encaminhou o produtor ao contador.

| Escopo | atendeu | parcial | falhou | recusou bem | recusou mal | total |
|---|---|---|---|---|---|---|
| Dentro do escopo | 24 | 4 | 19 | — | — | 47 |
| Fronteiriça | 4 | 9 | 12 | — | — | 25 |
| Fora do escopo | — | — | — | 18 | 26 | 44 |

## Causas das 31 falhas (dentro e fronteiriça)

| Causa | O que é | Quantas | Ids |
|---|---|---|---|
| **B** | a norma **está** na base e a busca não a traz | 15 | 7, 8, 11, 12, 13, 16, 17, 20, 25, 27, 28, 29, 71, P10, P31 |
| **F2** | nenhuma das 13 funções servia | 6 | P06, P15, 64, 77, D1, C5 |
| **F1** | o classificador mandou para fora de escopo | 5 | 14, 15, P22, 5, D9 |
| **F3** | recusa **firme** indevida | 3 | 70, 76, C4 |
| **A** | a norma não está na base | 2 | 18, 35 |

E, fora do escopo, **(E) recusa correta: 18** — 9, 43, 51, 53, 57, 59, D3, 3, 32, 34, 40, 44, 46,
52, 56, 58, 74, C2. **Isso não é falha**, é o produto sabendo o próprio limite.

## 1. A busca é a maior causa isolada de falha (15 de 31)

Quinze perguntas falharam com a norma **na base**. O caso mais grave é o da pergunta mais básica
que existe sobre o produto:

> **"O que é o IBS?"** → rota de legislação → *"Não encontrei fundamento legal para isso."*
> Em 642 ms, sem sequer chamar o modelo.

A LC 214/2025 institui o IBS e está na base com 5.004 trechos. A busca não trouxe **nada** — nem
os trechos aproximados que a rota tem para esse caso. É a issue #200, e ela se reproduz em
"sou obrigado a emitir nota fiscal?" (11), "como emitir nota fiscal de produtor rural?" (12),
"posso vender soja sem emitir nota?" (13) e no prazo de recolhimento (P31).

**Quando a busca acha, a rota de lei responde bem.** "O produtor pessoa física também será
afetado?" (22) saiu com o art. 164, § 6º e o limite de R$ 3,6 milhões; "quais produtos terão
alíquota reduzida?" (24) saiu com o art. 137 e a EC 132. O problema é a recuperação, não a
redação nem a validação.

## 2. As treze funções consultam dado; nenhuma explica o produto

Confirmado pela rodada. As perguntas de uso — "qual o número da próxima nota" (P06), "quanto
paguei no ano passado" (P15) — não são de lei nem casam com nenhuma função, e caem no vão entre
as duas rotas.

**O caso do número da próxima nota:** o backend calcula isso em `GET /api/notas/proximo-numero`
(#539, entrou na `main` em 12/11) e a Ceres não alcança — não há `@Tool` que o exponha. O caso
importa menos por si do que pelo padrão: **feature nova no backend não chega à assistente, e
ninguém percebe**, porque nenhuma medição do projeto pergunta por capacidade nova.

## 3. A Ceres recusa o próprio produto (F1), e com o texto errado (F3)

Oito perguntas sobre o que o sistema faz foram recusadas. Duas delas por classificação:

> **"Errei uma nota fiscal que já emiti. Como faço para corrigir?"** (15) → `FORA_DE_ESCOPO` →
> *"Desculpe, não consigo ajudar com isso. Eu cuido só das suas notas, guias e prazos no
> AgroConIA…"*

O texto diz que cuida das suas notas para quem perguntou sobre uma nota. O sistema **faz** isso: a
nota editável se corrige, a autorizada se cancela em 30 dias (RN006).

### As três recusas firmes indevidas (F3), em detalhe

| Id | Pergunta | O que dispara | `inequivoco` | `pedeDesempate` | Camada que decidiu |
|---|---|---|---|---|---|
| **70** | "Comprei um trator usado **de outro produtor**. Como faço para declarar essa compra?" | `OUTRA_PESSOA`: `outr[oa]s? produtor\w*` | **não** (CONTRAPARTE: "comprei … de outro produtor") | **sim** | LLM |
| **76** | "**Meu pai** faleceu e deixou uma propriedade rural. Como faço para continuar a atividade e emitir notas?" | `OUTRA_PESSOA`: `POSSE + PARENTE` ("meu pai") | **não** (PRODUTOR_FALA_DE_SI: "faço", "emitir") | **sim** | LLM |
| **C4** | "Arrendei 50 hectares da minha propriedade **para outro produtor**…" | `OUTRA_PESSOA`: `outr[oa]s? produtor\w*` | **não** (CONTRAPARTE: "para outro produtor") | **sim** | LLM |

**A causa raiz é uma assimetria entre os dois limiares, e ela está no código.** O `inequivoco`
trata a contraparte explicitamente — se `CONTRAPARTE` ou `PRODUTOR_FALA_DE_SI` casam, ele devolve
`false` antes de qualquer teste de terceiro. O `pedeDesempate` **não tem essa exceção**: ele é
`marcaDeTerceiro(texto) || …`, e `marcaDeTerceiro` é só `OUTRA_PESSOA || PAPEL_OU_CREDENCIAL`.

Então a cadeia nas três foi:

1. o guarda **não** recusou sozinho (`inequivoco` = false) — nisso ele acertou, e acertou nas 22
   perguntas do conjunto que mencionam outra pessoa;
2. mas `pedeDesempate` = true, porque "outro produtor" e "meu pai" casam `OUTRA_PESSOA` sem a
   exceção da contraparte. Com isso, **as regras e as embeddings deixam de decidir** e a pergunta
   vai ao modelo (#497);
3. o modelo, recebendo o prompt com a linha sobre pedido a respeito de outra pessoa, marcou
   `recusaFirme`.

**Correção de hipótese minha, e ela é dupla.** Eu previra que o guarda determinístico recusaria
as perguntas com menção a outra pessoa; ele não recusou. Mas eu também escrevi, no primeiro
relato desta rodada, que "o remédio é o prompt do classificador, não o guarda" — isso está
incompleto: o guarda **escolheu quem decide**, e foi essa escolha que entregou ao modelo uma
pergunta que as camadas baratas resolveriam. Há duas correções possíveis, e a segunda é mais
barata e mais segura:

- ajustar o prompt do classificador para não ler contraparte como pedido sobre terceiro; ou
- **dar ao `pedeDesempate` a mesma exceção de contraparte que o `inequivoco` já tem** — uma
  condição, no mesmo arquivo, alinhando dois limiares que hoje divergem sem motivo aparente.

**Como isto foi apurado.** A camada `LLM` das três é dado da auditoria da rodada de 14/11. O
caminho pelo `pedeDesempate` era, no primeiro relato, inferência por leitura dos padrões, porque a
máquina estava com o benchmark oficial; **em 15/11 ele foi medido**, executando os dois métodos
contra o texto literal das três perguntas (`SinalRecusaFirme` é estático e puro: teste unitário,
sem banco e sem modelo). Resultado: `inequivoco` devolve `false` nas três e `pedeDesempate`
devolve `true` nas três, como as duas colunas acima registram. A assimetria entre os limiares é
comportamento observado, não leitura de código.

O teste que mediu isso **não está nesta branch**: ele é vermelho enquanto a condição não existir, e
entra no PR da correção, junto da condição que o vira. Ele cobre os três ids, mas a correção pela
contraparte alcança só a **70** e a **C4** — na **76** o `pedeDesempate` dispara por `POSSE +
PARENTE` ("meu pai"), que não é contraparte de operação nenhuma, e nenhum verbo de `CONTRAPARTE`
cobre "faleceu e deixou". A 76 pede o prompt ou um critério novo (marca de terceiro **sem** pedido
de dado não deveria, sozinha, tirar a decisão das camadas), e isso é redesenho, não ajuste.

## 4. Fora do escopo: a Ceres quase não recusa, ela falha

Esta é a parte mais séria para a banca, porque é a maior fatia do que um produtor pergunta.

- Das 44 de fora, **27 foram para a rota de legislação ou mista** em vez de serem recusadas de
  saída. Só 17 foram classificadas como fora de escopo.
- Resultado: **26 recusas ruins** — o produtor que perguntou sobre Imposto de Renda recebeu
  *"não consegui confirmar uma resposta com a legislação"* mais trechos da reforma tributária, ou
  *"não consegui identificar qual dado consultar"*. **Nenhuma dessas frases diz que o assunto não
  é coberto.** São falhas de execução exibidas como se fossem resposta.
- Um caso pior, de resposta fora do assunto (47): perguntado sobre **documentos para contratar um
  funcionário**, respondeu sobre **consórcio simplificado de produtores rurais**, com citação
  válida. É exatamente o padrão que o Murillo descreveu: conteúdo próximo, mas não o pedido.

### O encaminhamento: 2 de 44

**Das 44 perguntas fora do escopo, só 2 mandaram o produtor a algum lugar. Das 18 que recusaram
bem, zero encaminhou.** O encaminhamento existe no produto (`FrasesSinaisNota`: "veja com seu
contador"), mas não nas recusas. Quando o Murillo recebeu "o ideal é consultar seu contador ou a
Receita Federal", aquilo não era recusa: era resposta da rota de lei, e veio do modelo. **O
produtor recebe o encaminhamento por sorte.**

## 5. As fronteiriças são o pior resultado (4 de 25)

Deveriam ser o ponto forte: responder a parte que sabe e encaminhar o resto. Nove ficaram
parciais (responderam sem encaminhar) e doze falharam inteiras. A pergunta do Simples que o
Murillo recebeu é o padrão-ouro — e ela é a exceção, não a regra.

## 6. Conversas com contexto

Rodaram como pergunta única (não há memória de conversa na `main`; o request é só `{pergunta}`).
Das cinco: C3 (reforma) atendeu, C1 (cooperativa) ficou parcial, C2 (colheitadeira, fora do
escopo) recusou bem, C4 caiu na recusa firme indevida e C5 no vão sem função. **Nas compostas, a
Ceres responde a primeira parte e ignora o resto** — C1 perguntava "preciso emitir nota fiscal? E
quais impostos vou pagar?" e só os impostos foram respondidos.

## Recomendação

### A. Cabe até 26/11

1. **Texto da recusa, com encaminhamento** (`recomendacao-recusa.md`, pronto para colar). É uma
   constante, sem modelo, sem risco para o que a banca mede. Resolve a metade "para onde mandar".
   Impacto medido: 44 perguntas hoje sem encaminhamento.
2. **A exceção de contraparte no `pedeDesempate`** (F3): uma condição em `SinalRecusaFirme`,
   alinhando dois limiares que hoje divergem sem motivo aparente. Vale 2 das 3 falhas de F3 (70 e
   C4; a 76 não, pelo motivo da seção 3) e é **verificável sem o modelo**, por teste unitário —
   por isso vem antes da edição de prompt.
3. **Prompt do classificador: pergunta sobre o produto é DADOS, não fora de escopo** (F1, 5
   casos). Muda como o modelo decide, então exige medir antes do merge, pela trava do projeto. E
   **não vai sozinho**: mandada para `DADOS`, "como corrijo uma nota?" encontra a rota de dados sem
   nenhuma função que sirva e sai com "não identifiquei qual dado consultar" — troca a recusa por
   outra mensagem vazia. Precisa vir junto do texto de saída da rota de dados.
4. **Quando a rota de lei não acha fundamento, recusar em vez de despejar trechos.** Hoje
   "não consegui confirmar" sai com trechos soltos em 19 respostas, 12 delas fora do escopo. Uma
   resposta sem fundamento confirmado deveria virar a recusa com encaminhamento, não um texto que
   parece defeito.

### B. Decisão de escopo a declarar, não corrigir

As 44 de fora (46% do que um produtor pergunta) **não devem ser cobertas até a banca** — Imposto
de Renda, livro caixa, ITR, CAR, trabalhista e financiamento não estão no escopo do TCC (#280) e a
base de lei não tem essas normas. O que precisa mudar não é a cobertura, é a **forma de dizer
não**: das 44, só 18 recusam bem hoje. Com o item A.1 e o A.3, a recusa correta passaria a ser a
regra, e isso é defensável na banca — o produto sabe o próprio limite.

### C. Depois da banca

1. **A busca (#200) é o maior item isolado**: 15 falhas com a norma na base. Começar por ela.
2. **Capacidade de explicar o produto**: uma função de orientação de uso ("como emito", "como
   corrijo", "ainda dá para cancelar"), que hoje não existe em nenhuma das treze.
3. **Alcance da assistente a features novas**: o caso do `proximo-numero` mostra que o backend
   cresce e a Ceres não acompanha. Vale uma conferência na entrada de cada feature.
4. **Memória de conversa**, para as perguntas compostas.
5. **O critério do desempate por marca de terceiro.** A causa de fundo das três recusas firmes
   indevidas não é a lista de exceções, é o critério: hoje **a simples menção a outra pessoa tira
   a decisão das camadas baratas e a entrega ao modelo**, mesmo quando a pergunta não pede dado
   nenhum sobre ela ("meu pai faleceu e deixou uma propriedade"). Uma correção de exceções
   resolve os casos conhecidos; o critério certo seria exigir, junto da marca, algum sinal de
   **pedido sobre o terceiro**. Isso é redesenho de uma peça de segurança, não ajuste — não cabe
   antes do congelamento, e fica registrado para depois.

## Limites deste diagnóstico

- **Uma repetição por pergunta.** A Ceres varia entre execuções; casos isolados podem ser sorte.
- **O julgamento é meu**, por uma grade escrita antes de ver qualquer resposta (`rubrica.md`),
  aplicada por regra sobre o desfecho e o texto, com revisão dos casos duvidosos. Não houve
  revisão de contador nem do orientador.
- As fronteiriças e as de fora foram perguntadas por produtores com menos dados que o principal —
  o que não afeta o que se mede nelas (recusa e encaminhamento).
## Anexo — o seed da demonstração falha em silêncio sem certificado

Achado no preparo desta rodada, **independente do diagnóstico da Ceres**. Registrado aqui porque
atinge a demonstração da banca: o seed sobe na frente dos avaliadores e, sem certificado, a tela
abre com um produtor sem nota nenhuma **e sem nenhum sinal de erro**.

**As três variáveis.** O `.env` do checkout define `NFE_CERT_PATH`, `NFE_CERT_PASSWORD` e
`NFE_CERT_ALIAS`. O valor de `NFE_CERT_PATH` é o placeholder
`/caminho/seguro/certificado.p12`, que não existe no disco. Ele **sobrescreve o default do
profile de desenvolvimento** (`application-dev.yml`), que aponta para o certificado de teste
versionado no checkout: `file:src/test/resources/certs/nfe-teste.p12`, senha `changeit`.

**O erro, e onde ele aparece.** Durante o `SeedDemonstracao`, uma linha `WARN` por nota:

```
WARN  b.c.a.c.demonstracao.SeedDemonstracao : Demonstracao: nota <n> do produtor <uuid>
      recusada pela validacao: [ErrorDetail[code=CERTIFICADO_AUSENTE, ...
      message=Não foi possível assinar digitalmente a NF-e. Contate o suporte., ...]]
```

e, ao fim, um `ERROR` único:

```
ERROR b.c.a.c.demonstracao.SeedDemonstracao : Demonstracao da banca nao semeada;
      a aplicacao segue sem ela.
```

**A consequência medida.** Zero notas criadas; o escritório, os clientes e as guias da
demonstração não existem; **a aplicação sobe normalmente**, o health responde 200 e nenhuma rota
falha. O erro só aparece para quem lê o log da subida inteira. Nesta rodada, o banco ficou com 17
notas (resíduo de seeds antigos de avaliação) e 4 produtores — nenhum deles do cenário da banca.

**Depois da correção.** Tirando as três variáveis do ambiente (`unset NFE_CERT_PATH
NFE_CERT_PASSWORD NFE_CERT_ALIAS` depois de carregar o `.env`), de modo que valha o default de
dev, o seed criou **174 notas, 13 produtores e zero recusas**, com o Carlos em 58 notas.

**Por que isto é sério para a banca.** A falha é silenciosa por construção — o seed é desenhado
para não derrubar a aplicação, o que está certo. Mas significa que a única forma de perceber é
conferir o banco antes de usar, e essa é exatamente a conferência que não se faz sob pressão. Na
demonstração, o sintoma seria uma tela vazia sem explicação.

**Sugestão (não implementada):** um aviso na subida quando a demonstração está habilitada e o
seed terminou sem criar nota, mais uma conferência do certificado antes de começar a semear — o
`KeyStoreCertificadoProvider.conferirNaSubida` já faz algo parecido em WARN para certificado
autoassinado.

## Limites deste diagnóstico (continuação)

**O que é número bruto e o que é julgamento meu:**

| Bruto (medido) | Julgamento (meu) |
|---|---|
| rota de cada pergunta, desfecho, camada da classificação, funções chamadas, citações, fontes de dados, latência — tudo da auditoria e da resposta da API | `atendeu` / `parcial` / `falhou` / `recusou bem` / `recusou mal` |
| o texto fixo que saiu (recusa gentil, recusa firme, sem função, trechos sem confirmação, sem fundamento) | a causa (A, B, C, D, E, F1, F2, F3) |
| 116 execuções, zero falhas de transporte | a triagem de escopo das perguntas (dentro / fronteiriça / fora) |
| encaminhamento: presença de "contador", "Receita Federal" ou "contabilidade" no texto | se a recusa é "boa" ou "ruim" |

A classificação foi aplicada por regra sobre o desfecho e o texto fixo (as mesmas regras para
todas as 116, escritas antes de ver as respostas, em `rubrica.md`), com revisão manual dos casos
em que o desfecho não determinava o veredito. **Os 46 de 116 são julgamento, não medição.** Os
números que não dependem de julgamento nenhum são: 27 das 44 de fora foram para a rota de lei ou
mista; 2 das 44 encaminharam; 15 falhas têm a norma na base; 3 receberam recusa firme.
