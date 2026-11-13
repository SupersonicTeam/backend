# Fidelidade da rota de lei da Ceres — conjunto, regra e executor (#269)

Mede se a correção da fidelidade da rota de lei melhora as respostas **sem piorar o resto**: dois
braços no **mesmo commit** (o head do PR da correção), flag desligada (igual à `main`) contra ligada,
com o Haiku 5.5 pela API da Anthropic, 3 repetições por braço.

> **Teste cego.** O conjunto foi escrito por uma sessão que nunca viu os conjuntos anteriores nem o
> código da correção, e é **conjunto de teste**: quem escreve ou revisa a correção **não lê**
> `perguntas-fidelidade-lei.json`, não o usa em exemplo de prompt, treino ou teste, e não recebe o
> texto de nenhuma pergunta (mensagem à sessão de código cita só o id, `FL07`). O que ela sabe
> da medição é o nome da flag, o commit e o resultado. Mudança feita por quem leu uma pergunta a
> contamina.

## O que tem aqui

| Arquivo | O que é |
|---------|---------|
| `perguntas-fidelidade-lei.json` + `.sha256` | As 36 perguntas, com o gabarito de fidelidade (fatos com o dispositivo certo, armadilhas, permitidos). Congelado pelo sha256 |
| `regra-de-decisao.md` | A regra de decisão (`ENTRA` / `NAO_ENTRA` / `INCONCLUSIVA`), escrita antes de qualquer rodada |
| `perguntas-fidelidade-lei-v2.json` + `.sha256` | O **conjunto v2** (36 perguntas, ids `F2-`), da segunda medição, escrito depois do ajuste da correção (regra, seção 9). Mesma forma e mesma regra do v1 |
| `perguntas-fidelidade-lei-v3.json` + `.sha256` | O **conjunto v3** (36 perguntas, ids `F3-`), da terceira medição, escrito depois do ajuste do juiz da correção (regra, seção 10). Mesma forma e mesmas margens; a regra prevê os braços da terceira medição, com o juiz como resgate (seção 10) |
| `conferencia-do-gabarito-v3.md` | A conferência independente do gabarito do v3 contra o texto da lei e como cada divergência foi resolvida |
| `conferencia-do-gabarito.md` | A conferência independente do gabarito contra o texto da lei, as divergências e como foram resolvidas |
| `prompts/juiz.md` | O papel dos juízes (os três usam o mesmo texto) |
| `executar_fidelidade.py` | Sobe uma instância por braço (dois ou mais), roda o conjunto em todos e grava as respostas brutas e a auditoria |
| `leitura_fidelidade.py` | Cega e embaralha as respostas aceitas em lotes, valida o que os juízes entregaram, monta o lote do J3 |
| `decidir_fidelidade.py` | Aplica a regra e grava `resultado.json` e `resultado.md` |
| `fidelidade_comum.py` | Constantes da regra (as margens), classificação das execuções, kappa, percentil |
| `test_*.py` | Testes (só biblioteca padrão), rodam na CI (`test_conjunto_v2.py` e `test_conjunto_v3.py`: o v2, o v3, a escolha do conjunto e os braços da terceira medição) |

## O conjunto (36 perguntas, 6 por classe)

| Classe | Perguntas | O que testa |
|--------|-----------|-------------|
| `CAPUT` | 6 | o conteúdo está no caput do artigo |
| `PARAGRAFO` | 6 | está num parágrafo, inciso ou alínea específico |
| `EXCECAO` | 6 | regra com exceção, condição ou prazo, em que inverter o sentido é fácil |
| `AMPLA` | 6 | pede o resumo de vários dispositivos |
| `AUSENTE` | 6 | assunto fiscal que a base não cobre: a resposta certa é não afirmar |
| `SIMPLES` | 6 | controles de resposta direta |

(Os ids não são contíguos por classe: parte das perguntas foi trocada depois do portão de proximidade.)

Normas cobertas: LC 214/2025, EC 132/2023, LC 227/2026, Lei 8.212/1991 e Lei 9.528/1997 (art. 6º). O
gabarito foi escrito do texto de `legislacao_trecho` (8.304 trechos vigentes, 2026-11-12) e conferido
por um subagente independente (`conferencia-do-gabarito.md`).

Mudar uma pergunta, um fato ou o gabarito depois do congelamento **muda o sha256**: suba a `versao`
(no conjunto e em `VERSAO_REGRA` do `fidelidade_comum.py`, e no `regra-de-decisao.md`), grave o novo
hash (`sha256sum perguntas-fidelidade-lei.json > perguntas-fidelidade-lei.json.sha256`) e rode de
novo. O executor e a decisão recusam um conjunto que não bate com o hash.

## Conjunto v2 (segunda medição)

A correção foi ajustada depois da primeira rodada, olhando os números do v1; o v1 deixou de ser teste
cego para ela. A segunda medição usa o **v2** (`perguntas-fidelidade-lei-v2.json`, versão
`2026-11-13.v2`), escrito por uma sessão que nunca viu o v1 nem a correção. Mesma regra
(`2026-11-12.4`), mesmos scripts, mesmas seis classes com 6 perguntas cada; os arquivos e os testes do
v1 seguem iguais. A escolha do conjunto é por parâmetro:

```bash
python3 -I docs/avaliacao/fidelidade-lei/executar_fidelidade.py --conjunto v2 ...   # padrão: v1
```

O manifesto da rodada (`execucao.json`) grava `conjunto.arquivo` e `leitura_fidelidade.py` e
`decidir_fidelidade.py` usam o conjunto do manifesto (manifesto sem o campo = v1). A regra do teste
cego vale para o v2 do mesmo jeito: quem escreve ou revisa a correção não lê o v2 e recebe só os ids
(`F2-07`). Os testes do v2 (`test_conjunto_v2.py`) e o `ConjuntoFidelidadeLeiTest` conferem hash, forma e
a separação do treino e do prompt para os dois conjuntos.

## Conjunto v3 e os braços da terceira medição

O juiz da correção foi ajustado depois da segunda rodada, olhando os números do v1 e do v2, e os dois
deixaram de ser teste cego para esse ajuste. A terceira medição usa o **v3**
(`perguntas-fidelidade-lei-v3.json`, versão `2026-11-13.v3`), escrito por uma sessão que nunca viu o v1,
o v2, os conjuntos antigos nem a correção. A regra passou à versão `2026-11-13.6` (a `2026-11-13.5` é a com que o v3 foi escrito; as margens, os juízes,
as 3 repetições e o modelo são os mesmos) e prevê **quatro braços**: `desligada`, `camada1`,
`camada1+juiz+resgate` (juiz vetando e resgatando) e `camada1+resgate` (juiz só resgatando), cada um
comparado ao `desligada`. O braço `camada1+juiz` (só veto) saiu: já foi medido duas vezes (regra, seção 10).

```bash
python3 -I docs/avaliacao/fidelidade-lei/executar_fidelidade.py --conjunto v3 --jar <jar> --commit <sha> \
    --braco "camada1:NOME_DA_FLAG=true" \
    --braco "camada1+juiz+resgate:NOME_DA_FLAG=true,NOME_DA_CHAVE_DO_JUIZ=...,NOME_DA_CHAVE_DO_RESGATE=..." \
    --braco "camada1+resgate:NOME_DA_FLAG=true,NOME_DA_CHAVE_DO_RESGATE=..."
```

As variáveis de cada braço vêm da sessão da correção junto do head. São 36 x 3 x 4 = 432 execuções e quatro JVMs: confira a memória. Os arquivos e os testes do v1 e do v2
não mudaram. O teste cego vale para o v3 do mesmo jeito (quem escreve ou revisa a correção recebe só os
ids, `F3-07`). Os ids do v3 não são contíguos (`F3-02` a `F3-57`): as perguntas foram escolhidas entre
candidatas mais numerosas depois do portão de proximidade. Os testes do v3 estão em `test_conjunto_v3.py`.

## Como rodar (depois do merge, com o head do PR da correção e o nome da flag)

Pré-requisitos: Java 21, Docker com `agrocontabil-postgres` (com a base de lei ingerida) e o Ollama do
embedding no ar (`docker start agrocontabil-ollama`), `ANTHROPIC_API_KEY` e `DB_PASSWORD` no ambiente
(nunca em argumento, nunca em arquivo). Antes de qualquer rodada, confira a trava do Ollama
(`C:/Users/surik/AppData/Local/Temp/agroconia-ollama-lock/dono.txt`); com ela presente, não rode.

```bash
# 1. jar do head do PR da correção (checkout do commit)
./mvnw -q -DskipTests -Djacoco.skip=true package

# 2. confere o conjunto, os argumentos e o plano (não sobe nada)
python3 -I docs/avaliacao/fidelidade-lei/executar_fidelidade.py --so-verificar \
    --env-ligada NOME_DA_FLAG=true --env-desligada NOME_DA_FLAG=false

# 3. a rodada: 36 x 3 x 2 = 216 execuções (dezenas de minutos; a API limita 20/min por braço)
python3 -I docs/avaliacao/fidelidade-lei/executar_fidelidade.py \
    --jar target/backend-tcc-0.0.1-SNAPSHOT.jar --commit <sha do head> \
    --env-ligada NOME_DA_FLAG=true --env-desligada NOME_DA_FLAG=false
```

O que o executor faz, e o que ele exige da outra sessão:

- **Nome da flag e valores:** por `--env-ligada` e `--env-desligada` (repetíveis, `CHAVE=valor`). A
  `--env-ligada` (atalho do braço `ligada`) ou ao menos um `--braco` é obrigatório, e os braços têm de diferir entre si. Se a flag nasce **desligada** por
  padrão, basta a `--env-ligada` (o desligado é a ausência da variável); se nasce **ligada**, passe
  também `--env-desligada` com o valor que desliga. Se a correção precisar de mais de uma variável
  (limiar, modelo do verificador), repita o argumento: o que não é a flag é igual em todos os braços.
- **O que ele fixa e recusa sobrescrever:** perfil `dev`, banco, porta, `SEED_SENHA`, chave da API,
  `AI_CHAT_PROVIDER=anthropic`, `AI_ANTHROPIC_MODEL=claude-haiku-5-5`, `AI_ANTHROPIC_THINKING=disabled`,
  o Ollama do embedding e a cota semanal (levada a 1 bilhão para a rodada não parar; ela não é o que
  se mede). Nenhum `AI_*` do shell é herdado.
- **Banco:** um banco **novo** (`fidelidade_lei`, recriado a cada rodada) com o Liquibase do commit e a
  base de lei copiada do banco de origem (`--banco-origem`, padrão `agrocontabil`). Nunca toca no banco
  de desenvolvimento.
- **Uma instância por braço** do mesmo jar (portas `--porta`, `--porta`+1, ...), todas com a mesma conta
  semeada do `DataLoader` (a senha da rodada é sorteada e nunca impressa nem gravada). Com três braços
  são três JVMs: confira a memória. **Mais braços:** `--braco NOME:CHAVE=valor,CHAVE=valor`
  (repetível, nome em minúsculas, ambiente diferente de todos os outros); cada braço é comparado ao
  `desligada`, um a um, com a mesma regra. O manifesto grava a lista (`bracos`) e a decisão lê dela.
- **Intercala** as execuções (cada pergunta vai a todos os braços em seguida, com a ordem dos braços
  girando; a ordem das perguntas é sorteada por repetição, semente fixa).
- **Auditoria:** depois de cada resposta, lê a linha de `assistente_interacao` do produtor do braço
  (desfecho, tokens, latência do servidor). Os **tokens** da regra são a soma de todos os campos
  `tokens_entrada*` e `tokens_saida*` da linha: **se a correção fizer uma chamada nova ao modelo, ela
  precisa gravar os tokens dela em colunas com esses nomes** (ou o custo dela não entra na medida M6).
  O **desfecho** que a correção grava para a resposta que ela derruba também conta: desfecho novo vale
  como "descartada" (regra, seção 3).
- **Refaz uma vez** as execuções com falha técnica ao fim da rodada. Interrompida (Ctrl+C, cota,
  erro), grava o que foi medido e o motivo em `execucao.json`; a decisão trata como `INCONCLUSIVA`.
- Grava em `rodadas/<data>-fidelidade-lei/`: `respostas-brutas.jsonl`, `execucao.json` (manifesto: commit,
  jar, ambiente dos braços sem segredo, contagens), `logs/` (com segredos mascarados). Nada de URL,
  conta ou senha.

### Três braços (desenho previsto para a correção da #269)

```bash
python3 -I docs/avaliacao/fidelidade-lei/executar_fidelidade.py --jar <jar> --commit <sha>     --braco "camada1:NOME_DA_FLAG=true"     --braco "camada1+juiz:NOME_DA_FLAG=true,NOME_DA_FLAG_DO_JUIZ=true"
```

Cada braço ligado é comparado ao `desligada` pela mesma regra; se mais de um der `ENTRA`, a regra
escolhe entre eles (seção 7.4). Os tokens da segunda chamada entram na medida M6 desde que a
auditoria os grave em colunas `tokens_entrada*`/`tokens_saida*` (o executor e a decisão somam por
prefixo, nunca por nome exato). **As margens de tempo (M5: até 2,0 × o p50 e o p95 do `desligada` e p95 de até 10 s) e de tokens (M6: até 2,0 ×) são decisão do Murillo, de 2026-11-12**; ficam numa tabela só (`REGRA` em `fidelidade_comum.py`, espelhada na seção 7.1).

## Leitura dos juízes e decisão

```bash
R=docs/avaliacao/fidelidade-lei/rodadas/<pasta da rodada>

python3 -I docs/avaliacao/fidelidade-lei/leitura_fidelidade.py lotes $R
#  -> $R/leitura/prompts/J1/lote-NN.md e .../J2/lote-NN.md (uma por lote de até 12 respostas)
```

Cada `prompts/J1/lote-NN.md` vai, **inteiro**, a um subagente novo com `model: opus` (J1), e cada
`prompts/J2/lote-NN.md` a um subagente novo com `model: sonnet` (J2). O prompt já traz o papel, o lote e
o caminho onde o subagente grava o JSON (`leitura/juizes/J1|J2/lote-NN.json`). Os juízes não veem o
braço (a chave `leitura/chave.json` fica fora dos prompts), não leem o repositório e não falam entre si.

```bash
python3 -I docs/avaliacao/fidelidade-lei/leitura_fidelidade.py validar $R      # forma das leituras
python3 -I docs/avaliacao/fidelidade-lei/leitura_fidelidade.py desacordos $R   # lotes do J3 (opus)
#   despache leitura/prompts/J3/desempate-NN.md; depois, de novo: validar
python3 -I docs/avaliacao/fidelidade-lei/decidir_fidelidade.py $R              # resultado.json e .md
```

O veredito de cada resposta é calculado em código pelos rótulos das frases (o juiz não dá veredito).
A decisão traz uma seção por comparação (cada braço contra o `desligada`), com as três contagens (por execução, por maioria das repetições, por qualquer repetição), a
concordância dos juízes (kappa), o intervalo de confiança, cada margem com o valor medido e o
desfecho com o motivo. Ela nunca copia o texto das respostas.

## Testes

```bash
python3 -m unittest discover -s docs/avaliacao/fidelidade-lei -p "test_*.py"
```

Cobrem o conjunto (36 perguntas, 6 por classe, sha256, forma do gabarito), a identidade da regra com o
documento (cada margem do código aparece no `regra-de-decisao.md`), o ambiente da JVM, o plano
intercalado, a classificação das execuções pela auditoria, a leitura cega (lote sem o braço, chave à
parte), o consenso dos juízes e os três desfechos da decisão com rodadas sintéticas. O
`ConjuntoFidelidadeLeiTest` (Java) confere que nenhuma pergunta está no treino nem no prompt do
classificador.

## Limites

- A latência é a do cliente contra a API pela rede, medida nos dois braços intercalados: vale para
  comparar os braços, não como número de produção.
- Com 108 execuções por braço e temperatura 1.0, a regra só detecta quedas grandes (25% e 4
  execuções, com o intervalo de confiança acima de zero). Efeito menor sai `INCONCLUSIVA`, por
  desenho (regra, seção 7.3).
- O executor foi exercitado de ponta a ponta **uma vez, com chave falsa** (sobe as duas JVMs, cria o
  banco, copia a base de lei, loga, lê a auditoria, refaz a falha e grava o manifesto; as respostas
  saíram em fallback, como esperado sem API). A resposta real do Haiku, o Ollama do embedding e a
  rodada de 216 execuções só rodam na rodada de verdade; o resto está coberto pelos testes.
