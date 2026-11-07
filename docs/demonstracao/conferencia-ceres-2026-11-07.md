# Conferência local da Ceres com as perguntas do roteiro da banca (2026-11-07)

> **Aviso.** Este documento traz perguntas do roteiro da banca (`docs/demonstracao-banca.md`, passos 4
> e 7), algumas iguais ou próximas de perguntas da avaliação da Ceres. **Qualquer correção da Ceres
> motivada por ele precisa ser medida num conjunto independente antes do merge.** Não use estas
> perguntas como treino nem como prova de que a correção funciona.

Conferência da issue #435: as perguntas que o roteiro faz à Ceres, rodadas localmente com o
`qwen3:4b-instruct`, contra o roteiro. Não corrige nada.

## Condições

| Item | Valor |
|---|---|
| Data e hora | 2026-11-07, 10:09 a 10:25 (America/Sao_Paulo) |
| Vale para o SHA | `4a0b3a8` (`main`). A aplicação rodou da branch de documentação `fa03163`, que é o `4a0b3a8` mais mudanças só de documentação (nenhum arquivo de `src/` nem de `application*.yml`) |
| Semeadura | 2026-11-07 10:10 (os valores do seed são relativos a este dia; cota da semana de 2026-11-02, 33 créditos, teto de uma pergunta 32) |
| Banco | `agrocontabil_conferencia`, criado e removido por esta conferência; seed: 9 produtores, 157 notas (0 recusadas), 34 guias, 5 pendências, 6 meses fechados |
| Base de legislação | 8.304 trechos, copiados do banco de desenvolvimento: LC 214/2025 5.004, LC 227/2026 2.211, Lei 8.212/1991 547, EC 132/2023 538, Lei 9.528/1997 4 |
| Modelo | `qwen3:4b-instruct`, `think` desligado, `num_ctx` 8192 (conferido no `/api/ps`); embedding `bge-m3` (exemplos do classificador carregados: 262) |
| Onde rodou | **CPU**: `size_vram` 0 nos dois modelos; o contêiner `agrocontabil-ollama` não reserva GPU (16 processadores lógicos na máquina local) |
| Configuração | a publicada: sem nenhuma variável `AI_*` no ambiente, nenhum `application*.yml` alterado; read-timeout do modelo 120 s |
| Latência | **local, de CPU de máquina de desenvolvimento; não é a da VPS** (8 OCPU ARM, sem GPU) |

Antes de cada bloco, o `/api/ps` (`size_vram` 0 em todos):

- carlos (10:12:28): `bge-m3:latest` context_length 4096, size 1218969598, size_vram 0; `qwen3:4b-instruct` context_length 8192, size 3884460276, size_vram 0
- carlos-livres-2 (10:15:46): `qwen3:4b-instruct` context_length 8192, size 3884460276, size_vram 0; `bge-m3:latest` context_length 4096, size 1218969598, size_vram 0
- icms (10:20:17): `qwen3:4b-instruct` context_length 8192, size 3884460276, size_vram 0; `bge-m3:latest` context_length 4096, size 1218969598, size_vram 0
- rogerio (10:23:20): `qwen3:4b-instruct` context_length 8192, size 3884460276, size_vram 0; `bge-m3:latest` context_length 4096, size 1218969598, size_vram 0

O contêiner do Ollama não define `OLLAMA_KEEP_ALIVE` (vale o padrão de 5 minutos depois do último
pedido): os blocos foram rodados em seguida, sem passar 5 minutos entre perguntas, depois do
aquecimento dos dois modelos (`keep_alive` de 60 minutos, `num_ctx` 8192).

## Como repetir

Pré-requisitos: Postgres e Ollama locais (`docker start agrocontabil-postgres agrocontabil-ollama`),
os modelos baixados e a base de lei no banco `agrocontabil`.

```bash
# banco separado (o seed fecha meses e gera alertas)
docker exec agrocontabil-postgres psql -U agrocontabil -d postgres -c "CREATE DATABASE agrocontabil_conferencia"

# aplicação (Git Bash, raiz do repositório; sem AI_*, sem NFE_CERT_*, sem SPRING_PROFILES_ACTIVE)
DB_PASSWORD=<a do .env> DB_NAME=agrocontabil_conferencia DEMONSTRACAO_HABILITADA=true \
  SEED_SENHA=<senha de teste de 10+ caracteres, so na variavel> \
  ./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.docker.compose.enabled=false

# base de lei (como o executor do benchmark: executor.preparar_banco)
docker exec -i agrocontabil-postgres psql -U agrocontabil -d agrocontabil \
  -c '\copy (select id, content, metadata, embedding from legislacao_trecho) to stdout' \
 | docker exec -i agrocontabil-postgres psql -U agrocontabil -v ON_ERROR_STOP=1 -d agrocontabil_conferencia \
  -c '\copy legislacao_trecho(id, content, metadata, embedding) from stdin'

# chamadas (docs/demonstracao/conferencia-ceres.py, so biblioteca padrao; SEED_SENHA no ambiente)
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl aquecer
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl ps carlos
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl carlos-atalhos
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl carlos-livres
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl icms --numero <rascunho de milho do Carlos>
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl rogerio
python3 docs/demonstracao/conferencia-ceres.py --saida chamadas.jsonl apuracao --periodo <mes passado>

# no fim: parar a aplicação e só então
docker exec agrocontabil-postgres psql -U agrocontabil -d postgres -c "DROP DATABASE agrocontabil_conferencia WITH (FORCE)"
```

A auditoria foi lida à mão, só leitura, na ordem das chamadas (o 429 não grava linha):

```bash
docker exec agrocontabil-postgres psql -U agrocontabil -d agrocontabil_conferencia -tA -F '|' -c \n  "select rota, desfecho, desfecho_dados, desfecho_legislacao, camada_classificacao, latencia_ms, tokens_entrada, tokens_saida from assistente_interacao order by criado_em"
```

O rascunho de milho usado foi a nota 3191 (`GET /api/notas?status=RASCUNHO`, venda de milho ao
Antônio Carlos Pereira, pessoa física sem IE). As colunas de auditoria vêm de `assistente_interacao`
(só leitura), na ordem das chamadas.

## Resultado

**Resumo: 3 divergências e 1 ressalva** (as divergências estão em negrito na coluna do veredito). Duas são objetivas (apuração sem função chamada; pergunta geral de ICMS com a função errada); a da soja é uma leitura da regra do #284 (afirmar os 60% sem ressalva) e pode ser discutida.

- **Apuração do mês passado** (3 de 3): "Não consegui identificar qual dado consultar". Nenhuma função
  foi chamada (desfecho `SEM_RESPOSTA`, camada `LLM`). O sistema tem os números:
  `GET /api/apuracao?periodo=2026-10` devolve ABERTO, débito IBS 192,25, crédito IBS 124,33, saldo IBS
  67,92; débito CBS 1.730,32, crédito CBS 1.118,97, saldo CBS 611,35; 9 notas.
- **A soja tem redução de alíquota?** (3 de 3, rota LEGISLACAO): cita `LC 214/2025, art. 137` e `art. 137,
  § 1º, inciso II`, mas diz "Sim, a soja tem redução de alíquota... reduzidos em 60%" sem a ressalva do
  enquadramento com o contador (#284). A linha "O que isso quer dizer" lista ICMS, ISS, PIS e Cofins,
  que a resposta não usa.
- **ICMS a definir, pergunta geral** (3 de 3): foi para `listarNotas` com `situacao=PENDENTE_SEFAZ` e
  respondeu "Não encontrei notas aguardando a SEFAZ"; o sinal não aparece. **Com o número da nota
  (3191), o sinal aparece nas 3 repetições** ("está sem o CST do ICMS... combine o CST com seu contador").
- **Ressalva (Rogério):** a primeira pergunta livre respondeu sobre 09/08 a 07/11 (5 notas), não sobre
  "este mês". O que o roteiro verifica (200, depois 429 com `Retry-After`, depois o atalho 200) funciona.
- **Falha técnica da primeira tentativa dos livres do Carlos:** a primeira chamada ao modelo depois do
  aquecimento levou 120,2 s e estourou o read-timeout; o disjuntor abriu e as 8 seguintes saíram em
  `MODO_FALLBACK`. Isso não é veredito da Ceres. Reaqueci, rodei uma sonda (19,8 s, sem fallback) e
  repeti as 9: **vale a segunda tentativa**; as duas estão registradas abaixo.
- **Sem o modelo, a pergunta da soja entrega trechos fora do assunto:** no fallback da primeira tentativa a resposta lista `LC 214/2025, Anexo VII, item 8` (óleos de soja), `LC 227/2026, art. 174` e `LC 214/2025, art. 473` (compras da administração pública), sem o art. 137. É o que a banca veria com o modelo fora (a busca textual do fallback não distingue o grão do óleo).
- **Embedding:** a camada de embedding pausou 9 vezes no log ("prazo de 500 ms vencido; a rota cai no
  modelo", pausa de 30 s), 1 na primeira tentativa e 8 depois; nas perguntas livres isso empurra a
  classificação para o modelo (camada `LLM` na auditoria), o que soma uma chamada de classificação ao modelo.
  A rota de cada veredito acima cita a camada que a decidiu.
- **Atalhos:** os três responderam em menos de 0,2 s, sem modelo (camada `ATALHO_FIXO`). O de IBS/CBS
  trouxe 34 citações; o CLAUDE.md fala em 33 fixas.
- **Rogério:** `GET /api/assistente/uso` mostrou 33 restantes (967 consumidos de 1.000); a primeira
  livre passou (200); a segunda deu 429 `LIMITE_USO_ASSISTENTE` com `Retry-After` de 135.358 s (até a
  virada da semana, 2026-11-09 00:00); o atalho de texto exato respondeu 200.

Latência do cliente na tabela; auditoria = `desfecho` · `camada_classificacao` · `latencia_ms` ·
tokens de entrada/saída. `desfecho_dados` e `desfecho_legislacao` ficaram vazios em todas as linhas
(só o MISTO os grava; nenhuma pergunta foi MISTO).

| Conta | Pergunta | Rep. | Tentativa | Status | Rota | Fontes | Citações | Fallback | Latência | Auditoria | Veredito |
|---|---|---|---|---|---|---|---|---|---|---|---|
| carlos | Quanto eu tenho que pagar este mês? | 1 | — | 200 | DADOS | `vencimentosDoMes`(mes=2026-11) | — | False | 0.1 s | RESPONDIDA · camada ATALHO_FIXO · 76 ms · tokens —/— | OK: guias de novembro e atraso, pelas funções de guias, sem modelo |
| carlos | Quando vence a minha próxima guia? | 1 | — | 200 | DADOS | `proximosVencimentos`(dias=30) | — | False | 0.1 s | RESPONDIDA · camada ATALHO_FIXO · 14 ms · tokens —/— | OK: próxima guia pela função de vencimentos, sem modelo |
| carlos | O que muda para mim com o IBS e o CBS? | 1 | — | 200 | LEGISLACAO | — | 34: LC 214/2025, art. 1º, inciso I; LC 214/2025, art. 1º, inciso II; EC 132/2023, art. 2º (redação do art. 126, inciso I, alínea a); … | False | 0.2 s | RESPONDIDA · camada ATALHO_FIXO · 115 ms · tokens —/— | OK: texto explicado, 34 citações, soja/algodão/milho do Carlos e Funrural; sem modelo |
| carlos | quanto eu comprei de fertilizante? | 1 | 1ª (falha técnica) | 200 | DADOS | — | — | True | 120.2 s | MODO_FALLBACK · camada REGRA · 120076 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | quanto eu comprei de fertilizante? | 2 | 1ª (falha técnica) | 200 | DADOS | — | — | True | 0.1 s | MODO_FALLBACK · camada REGRA · 0 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | quanto eu comprei de fertilizante? | 3 | 1ª (falha técnica) | 200 | DADOS | — | — | True | 0.1 s | MODO_FALLBACK · camada REGRA · 0 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | qual foi minha apuração de IBS e CBS do mês passado? | 1 | 1ª (falha técnica) | 200 | DADOS | — | — | True | 0.7 s | MODO_FALLBACK · camada PALAVRAS_CHAVE · 503 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | qual foi minha apuração de IBS e CBS do mês passado? | 2 | 1ª (falha técnica) | 200 | DADOS | — | — | True | 0.2 s | MODO_FALLBACK · camada PALAVRAS_CHAVE · 0 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | qual foi minha apuração de IBS e CBS do mês passado? | 3 | 1ª (falha técnica) | 200 | DADOS | — | — | True | 0.2 s | MODO_FALLBACK · camada PALAVRAS_CHAVE · 0 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | a soja tem redução de alíquota? | 1 | 1ª (falha técnica) | 200 | LEGISLACAO | — | 3: LC 214/2025, Anexo VII, item 8; LC 227/2026, art. 174 (redação do art. 544, inciso III); LC 214/2025, art. 473, § 1º, inciso I, alínea b | True | 0.2 s | MODO_FALLBACK · camada PALAVRAS_CHAVE · 42 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | a soja tem redução de alíquota? | 2 | 1ª (falha técnica) | 200 | LEGISLACAO | — | 3: LC 214/2025, Anexo VII, item 8; LC 227/2026, art. 174 (redação do art. 544, inciso III); LC 214/2025, art. 473, § 1º, inciso I, alínea b | True | 0.2 s | MODO_FALLBACK · camada PALAVRAS_CHAVE · 28 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | a soja tem redução de alíquota? | 3 | 1ª (falha técnica) | 200 | LEGISLACAO | — | 3: LC 214/2025, Anexo VII, item 8; LC 227/2026, art. 174 (redação do art. 544, inciso III); LC 214/2025, art. 473, § 1º, inciso I, alínea b | True | 0.3 s | MODO_FALLBACK · camada PALAVRAS_CHAVE · 22 ms · tokens —/— | falha técnica: primeira chamada ao modelo estourou o read-timeout de 120 s; o disjuntor abriu e as outras saíram em fallback; não é veredito |
| carlos | quanto eu comprei de fertilizante? | 4 | sonda | 200 | DADOS | `totalComprasPorInsumo`(descricaoOuNcm=fertilizante, dataInicio=2026-01-01, dataFim=2026-11-07) | — | False | 19.8 s | RESPONDIDA · camada REGRA · 19748 ms · tokens 3693/65 | OK: R$ 438.992,40, 2 notas, 114.024 KG: confere com o SELECT nas notas do Carlos |
| carlos | quanto eu comprei de fertilizante? | 1 | 2ª (vale) | 200 | DADOS | `totalComprasPorInsumo`(descricaoOuNcm=fertilizante, dataInicio=2026-01-01, dataFim=2026-11-07) | — | False | 19.7 s | RESPONDIDA · camada REGRA · 19666 ms · tokens 3693/65 | OK: R$ 438.992,40, 2 notas, 114.024 KG: confere com o SELECT nas notas do Carlos |
| carlos | quanto eu comprei de fertilizante? | 2 | 2ª (vale) | 200 | DADOS | `totalComprasPorInsumo`(descricaoOuNcm=fertilizante, dataInicio=2026-01-01, dataFim=2026-11-07) | — | False | 18.3 s | RESPONDIDA · camada REGRA · 18226 ms · tokens 3693/65 | OK: R$ 438.992,40, 2 notas, 114.024 KG: confere com o SELECT nas notas do Carlos |
| carlos | quanto eu comprei de fertilizante? | 3 | 2ª (vale) | 200 | DADOS | `totalComprasPorInsumo`(descricaoOuNcm=fertilizante, dataInicio=2026-01-01, dataFim=2026-11-07) | — | False | 18.2 s | RESPONDIDA · camada REGRA · 18125 ms · tokens 3693/65 | OK: R$ 438.992,40, 2 notas, 114.024 KG: confere com o SELECT nas notas do Carlos |
| carlos | qual foi minha apuração de IBS e CBS do mês passado? | 1 | 2ª (vale) | 200 | DADOS | — | — | False | 60.9 s | SEM_RESPOSTA · camada LLM · 60821 ms · tokens 4111/53 | **divergente**: não chamou nenhuma função (SEM_RESPOSTA); o sistema tem a apuração de 2026-10 |
| carlos | qual foi minha apuração de IBS e CBS do mês passado? | 2 | 2ª (vale) | 200 | DADOS | — | — | False | 33.8 s | SEM_RESPOSTA · camada LLM · 33725 ms · tokens 4111/53 | **divergente**: não chamou nenhuma função (SEM_RESPOSTA); o sistema tem a apuração de 2026-10 |
| carlos | qual foi minha apuração de IBS e CBS do mês passado? | 3 | 2ª (vale) | 200 | DADOS | — | — | False | 24.5 s | SEM_RESPOSTA · camada LLM · 24383 ms · tokens 4111/53 | **divergente**: não chamou nenhuma função (SEM_RESPOSTA); o sistema tem a apuração de 2026-10 |
| carlos | a soja tem redução de alíquota? | 1 | 2ª (vale) | 200 | LEGISLACAO | — | 2: LC 214/2025, art. 137; LC 214/2025, art. 137, § 1º, inciso II | False | 53.2 s | RESPONDIDA · camada LLM · 53068 ms · tokens 1356/89 | **divergente** (#284): cita o art. 137, mas afirma a redução de 60% sem a ressalva do enquadramento com o contador |
| carlos | a soja tem redução de alíquota? | 2 | 2ª (vale) | 200 | LEGISLACAO | — | 2: LC 214/2025, art. 137; LC 214/2025, art. 137, § 1º, inciso II | False | 16.2 s | RESPONDIDA · camada LLM · 16071 ms · tokens 1356/89 | **divergente** (#284): cita o art. 137, mas afirma a redução de 60% sem a ressalva do enquadramento com o contador |
| carlos | a soja tem redução de alíquota? | 3 | 2ª (vale) | 200 | LEGISLACAO | — | 2: LC 214/2025, art. 137; LC 214/2025, art. 137, § 1º, inciso II | False | 15.4 s | RESPONDIDA · camada LLM · 15301 ms · tokens 1356/89 | **divergente** (#284): cita o art. 137, mas afirma a redução de 60% sem a ressalva do enquadramento com o contador |
| carlos | Tem alguma nota minha com o ICMS sem definir? | 1 | — | 200 | DADOS | `listarNotas`(dataInicio=2026-08-09, dataFim=2026-11-07, limite=5, situacao=PENDENTE_SEFAZ) | — | False | 33.9 s | RESPONDIDA · camada EMBEDDING · 33796 ms · tokens 3697/45 | **divergente**: escolheu `listarNotas` com situação PENDENTE_SEFAZ e respondeu que não há nota; não trouxe o sinal |
| carlos | Tem alguma nota minha com o ICMS sem definir? | 2 | — | 200 | DADOS | `listarNotas`(dataInicio=2026-08-09, dataFim=2026-11-07, limite=5, situacao=PENDENTE_SEFAZ) | — | False | 28.2 s | RESPONDIDA · camada LLM · 28079 ms · tokens 4103/66 | **divergente**: escolheu `listarNotas` com situação PENDENTE_SEFAZ e respondeu que não há nota; não trouxe o sinal |
| carlos | Tem alguma nota minha com o ICMS sem definir? | 3 | — | 200 | DADOS | `listarNotas`(dataInicio=2026-08-09, dataFim=2026-11-07, limite=5, situacao=PENDENTE_SEFAZ) | — | False | 19.9 s | RESPONDIDA · camada LLM · 19842 ms · tokens 4103/66 | **divergente**: escolheu `listarNotas` com situação PENDENTE_SEFAZ e respondeu que não há nota; não trouxe o sinal |
| carlos | A nota número 3191 está com o CST do ICMS definido? | 1 | — | 200 | DADOS | `consultarNota`(numero=3191) | — | False | 34.9 s | RESPONDIDA · camada LLM · 34758 ms · tokens 4113/45 | OK: `consultarNota` traz o sinal: item sem o CST do ICMS, combinar com o contador |
| carlos | A nota número 3191 está com o CST do ICMS definido? | 2 | — | 200 | DADOS | `consultarNota`(numero=3191) | — | False | 24.5 s | RESPONDIDA · camada LLM · 24345 ms · tokens 4113/45 | OK: `consultarNota` traz o sinal: item sem o CST do ICMS, combinar com o contador |
| carlos | A nota número 3191 está com o CST do ICMS definido? | 3 | — | 200 | DADOS | `consultarNota`(numero=3191) | — | False | 28.1 s | RESPONDIDA · camada LLM · 28008 ms · tokens 4113/45 | OK: `consultarNota` traz o sinal: item sem o CST do ICMS, combinar com o contador |
| rogerio | Quais notas fiscais eu emiti neste mês? | 1 | — | 200 | DADOS | `listarNotas`(dataInicio=2026-08-09, dataFim=2026-11-07, limite=5, tipo=SAIDA) | — | False | 40.0 s | RESPONDIDA · camada LLM · 39839 ms · tokens 4103/62 | OK com ressalva: 200 como o roteiro pede (cota); a janela foi de 09/08 a 07/11, não o mês, e a lista é de 5 notas |
| rogerio | Quanto eu vendi de soja neste ano? | 1 | — | 429 LIMITE_USO_ASSISTENTE | — | — | — | None | 0.1 s | — | OK: 429 `LIMITE_USO_ASSISTENTE` com `Retry-After` |
| rogerio | Quanto eu tenho que pagar este mês? | 1 | — | 200 | DADOS | `vencimentosDoMes`(mes=2026-11) | — | False | 0.1 s | RESPONDIDA · camada ATALHO_FIXO · 65 ms · tokens —/— | OK: atalho de texto exato depois do 429: 200, fora da cota |

## Textos integrais

### Carlos · Quanto eu tenho que pagar este mês? (tentativa —)

> Em 11/2026: 2 guias para pagar, somando R$ 4.442,67.
> Em atraso: 1 guia, R$ 2.300,67:
> - FUNRURAL (contribuição previdenciária do produtor rural), venceu em 20/10/2026: R$ 2.300,67.
> A vencer de 07/11/2026 a 30/11/2026: 1 guia, R$ 2.142,00:
> - FUNRURAL, vence em 20/11/2026: R$ 2.142,00.

### Carlos · Quando vence a minha próxima guia? (tentativa —)

> De 07/11/2026 a 07/12/2026 vence 1 guia não paga, somando R$ 2.142,00:
> - FUNRURAL (contribuição previdenciária do produtor rural), vence em 20/11/2026: R$ 2.142,00.

### Carlos · O que muda para mim com o IBS e o CBS? (tentativa —)

> O que muda para você com o IBS e a CBS
>
> O QUE SÃO
> - A CBS é a contribuição da União (federal) e o IBS é o imposto de competência compartilhada entre estados, municípios e Distrito Federal (LC 214/2025, art. 1º, incisos I e II).
> - Eles substituem tributos de hoje: a partir de 2027 a CBS é cobrada e o PIS e a Cofins são extintos (EC 132/2023, art. 126 do ADCT); o ICMS e o ISS são extintos a partir de 2033 (art. 129 do ADCT).
>
> O CALENDÁRIO
> - 2026 é um ano de teste: a CBS é cobrada a 0,9% e o IBS a 0,1%, só na parcela estadual (LC 214/2025, arts. 346 e 343; EC 132/2023, art. 125 do ADCT). Quem cumpre as obrigações acessórias previstas na legislação fica dispensado de recolher esses valores em 2026 (LC 214/2025, art. 348, § 1º).
> - 2027 e 2028: a CBS passa a ser cobrada com a alíquota de referência reduzida em 0,1 ponto percentual (LC 214/2025, art. 347), e o IBS segue em teste, a 0,05% estadual e 0,05% municipal (art. 344).
> - 2029 a 2032: o ICMS e o ISS diminuem aos poucos, para 9/10 das alíquotas em 2029, 8/10 em 2030, 7/10 em 2031 e 6/10 em 2032 (EC 132/2023, art. 128 do ADCT), e o IBS cresce para repor essa receita (art. 130 do ADCT).
> - 2033: o ICMS e o ISS deixam de existir (art. 129 do ADCT).
>
> NA SUA VENDA
> - Produto agropecuário in natura tem redução de 60% nas alíquotas do IBS e da CBS (LC 214/2025, art. 137).
> - Alguns alimentos têm alíquota zero: os da Cesta Básica Nacional, no Anexo I (art. 125), e os hortícolas, frutas e ovos do Anexo XV (art. 148).
> - Pela tabela oficial, os produtos que você mais vendeu este ano, nas suas notas autorizadas:
> - Soja, mesmo triturada, exceto para semeadura (NCM 1201.90.00): redução de 60% do IBS e da CBS, como produto agropecuário in natura (LC 214/2025, art. 137; cClassTrib 200036). Condição: vendido in natura, sem industrialização (LC 214/2025, art. 137).
> - Algodao em bruto, nao cardado nem penteado (NCM 5201.00.20): redução de 60% do IBS e da CBS, como produto agropecuário in natura (LC 214/2025, art. 137; cClassTrib 200036). Condição: vendido in natura, sem industrialização (LC 214/2025, art. 137).
> - Milho em grao (exceto para semeadura) (NCM 1005.90.10): redução de 60% do IBS e da CBS, como produto agropecuário in natura (LC 214/2025, art. 137; cClassTrib 200036). Condição: vendido in natura, sem industrialização (LC 214/2025, art. 137).
> - O enquadramento final de cada venda é com o seu contador.
> - Quem é contribuinte do IBS e da CBS emite documento fiscal eletrônico nas suas operações, e o IBS e a CBS informados nele valem como confissão do valor devido (LC 214/2025, art. 60, § 1º).
>
> NAS SUAS COMPRAS
> - Os insumos agropecuários e aquícolas do Anexo IX têm redução de 60% nas alíquotas do IBS e da CBS (LC 214/2025, art. 138), se estiverem registrados como insumos no Ministério da Agricultura e Pecuária, quando a lei exige (art. 138, § 1º).
> - Na compra desses insumos de fornecedor do regime regular por quem também está no regime regular, o recolhimento do IBS e da CBS fica diferido (art. 138, § 2º, inciso I, alínea a). Quando o diferimento se encerra e quem recolhe (art. 138, §§ 5º a 7º), e o efeito disso no seu crédito, são para conferir com o contador.
> - No regime regular, o IBS e a CBS pagos nas demais compras, fora desse diferimento, podem virar crédito, exceto o que for de uso ou consumo pessoal (art. 47). O que conta como crédito na sua atividade é com o contador.
>
> NO PAGAMENTO
> - A lei prevê o split payment: nos pagamentos eletrônicos, a instituição de pagamento separa e recolhe o IBS e a CBS no momento da liquidação (LC 214/2025, arts. 31 e 32).
> - O Funrural segue a regra própria: na venda a empresa ou cooperativa, o comprador retém a contribuição do produtor pessoa física (Lei 8.212/1991, art. 30, IV).
> - O ICMS fica com o contador; o AgroConIA trata do IBS e da CBS.
>
> QUEM PAGA
> - Pela lei, o produtor rural com receita inferior a R$ 3.600.000,00 no ano-calendário não é contribuinte do IBS e da CBS (LC 214/2025, art. 164), mas pode optar pelo regime regular a qualquer tempo (art. 165).
> - Quem está no regime regular e compra de produtor não contribuinte pode apropriar crédito presumido (art. 168).
> - O AgroConIA calcula o IBS e a CBS das suas notas pelo regime regular. Confirme com o contador se esse é o seu caso.
>
> PRÓXIMO PASSO
> - Confira com o seu escritório de contabilidade o enquadramento dos seus produtos e a sua situação no IBS e na CBS.
>
> O QUE QUER DIZER
> - IBS: o novo imposto sobre a venda, dividido entre estados e municípios, que vai substituir o ICMS e o ISS aos poucos.
> - CBS: o novo tributo federal sobre a venda, que vai substituir o PIS e a Cofins.
> - PIS: contribuição federal sobre a receita, que a CBS vai substituir.
> - Cofins: contribuição federal sobre a receita, que a CBS vai substituir junto com o PIS.
> - ICMS: imposto dos estados sobre a circulação de mercadorias, que o IBS vai substituir aos poucos.
> - ISS: imposto dos municípios sobre serviços, que o IBS vai substituir aos poucos.
> - LC: lei complementar, um tipo de lei federal que detalha o que a Constituição prevê.
> - EC: emenda constitucional, uma mudança feita na Constituição; foi uma delas que criou o IBS e a CBS.
> - ADCT: parte da Constituição com as regras de transição, como o calendário da troca dos impostos.
> - NCM: o código que classifica o produto na nota fiscal.
> - Funrural: contribuição da previdência sobre a venda da produção rural.
> - cClassTrib: o código que diz como o item da nota é tratado no IBS e na CBS.
> - crédito: a parte do imposto das suas compras que você pode descontar do imposto das suas vendas.
> - split payment: forma de pagamento em que a parte do imposto é separada na hora e segue direto para o governo.

### Carlos · quanto eu comprei de fertilizante? (tentativa 1ª (falha técnica)) Repetições: 3, todas com o mesmo texto.

> Não consegui consultar os seus dados agora. Você encontra essas informações nas telas indicadas.

### Carlos · qual foi minha apuração de IBS e CBS do mês passado? (tentativa 1ª (falha técnica)) Repetições: 3, todas com o mesmo texto.

> Não consegui consultar os seus dados agora. Você encontra essas informações nas telas indicadas.

### Carlos · a soja tem redução de alíquota? (tentativa 1ª (falha técnica)) Repetições: 3, todas com o mesmo texto.

> Estou sem o modelo de linguagem no momento, então não redijo uma resposta. Estes são os trechos da legislação mais próximos da sua pergunta:
>
> - LC 214/2025, Anexo VII, item 8: ANEXO VII - ALIMENTOS DESTINADOS AO CONSUMO HUMANO SUBMETIDOS À REDUÇÃO DE 60% (SESSENTA POR CENTO) DAS ALÍQUOTAS DO IBS E DA CBS. Item 8: Óleos de soja, de milho, canola e demais óleos vegetais, em conformidade com os requisitos da legislação específica relativos ao consumo como alimento, classificados na subposição 1507.90 e nas posições 15.08, 15.11, 15.12, 15.13, 15.14 e 15.15 da NCM/SH
>
> - LC 227/2026, art. 174 (redação do art. 544, inciso III): Art. 174. A Lei Complementar nº 214, de 16 de janeiro de 2025, passa a vigorar com as seguintes alterações: Art. 544. ................................................................................................................................... III - a partir de 1º de janeiro de 2027, em relação aos arts. 168 a 171, 309 a 315, 444, 450, exceto os §§ 1º e 5º, 461, 462, 467, 499, 500, 502, 504 a 507, 509 a 515, 517, 519 a 534 e 542; ..................................................................................................................................................... ANEXO VII...
>
> - LC 214/2025, art. 473, § 1º, inciso I, alínea b: Art. 473. O produto da arrecadação do IBS e da CBS sobre as aquisições de bens e serviços pela administração pública direta, por autarquias e por fundações públicas será integralmente destinado ao ente federativo contratante, mediante redução a zero das alíquotas do IBS e da CBS devidos aos demais entes federativos e equivalente elevação da alíquota do tributo devido ao ente contratante. § 1º Para fins do atendimento ao disposto no caput deste artigo: I - nas aquisições pela União: b) será a alíquota da CBS fixada em montante equivalente à soma das alíquotas do IBS e da CBS incidentes sobre a...

### Carlos · quanto eu comprei de fertilizante? (tentativa sonda)

> Entre 01/01/2026 e 07/11/2026 você comprou R$ 438.992,40 de fertilizante, em 2 notas de compra. Quantidade: 114.024 KG (R$ 438.992,40).

### Carlos · quanto eu comprei de fertilizante? (tentativa 2ª (vale)) Repetições: 3, todas com o mesmo texto.

> Entre 01/01/2026 e 07/11/2026 você comprou R$ 438.992,40 de fertilizante, em 2 notas de compra. Quantidade: 114.024 KG (R$ 438.992,40).

### Carlos · qual foi minha apuração de IBS e CBS do mês passado? (tentativa 2ª (vale)) Repetições: 3, todas com o mesmo texto.

> Não consegui identificar qual dado consultar. Você pode perguntar, por exemplo: "tenho guia em atraso?", "quanto vendi de soja em outubro?" ou "tem algo me esperando?". Ou veja direto na tela indicada.

### Carlos · a soja tem redução de alíquota? (tentativa 2ª (vale)) Repetições: 3, todas com o mesmo texto.

> Sim, a soja tem redução de alíquota porque é um produto agropecuário in natura, e o IBS e a CBS são reduzidos em 60% sobre o fornecimento de produtos agropecuários in natura. (LC 214/2025, art. 137; LC 214/2025, art. 137, § 1º, inciso II)
>
> O que isso quer dizer:
> - IBS (imposto sobre bens e serviços): o novo imposto sobre a venda, dividido entre estados e municípios, que vai substituir o ICMS e o ISS aos poucos.
> - CBS (contribuição sobre bens e serviços): o novo tributo federal sobre a venda, que vai substituir o PIS e a Cofins.
> - LC: lei complementar, um tipo de lei federal que detalha o que a Constituição prevê.
> - ICMS (imposto sobre circulação de mercadorias e serviços): imposto dos estados sobre a circulação de mercadorias, que o IBS vai substituir aos poucos.
> - ISS (imposto sobre serviços): imposto dos municípios sobre serviços, que o IBS vai substituir aos poucos.
> - PIS (Programa de Integração Social): contribuição federal sobre a receita, que a CBS vai substituir.
> - Cofins (Contribuição para o Financiamento da Seguridade Social): contribuição federal sobre a receita, que a CBS vai substituir junto com o PIS.

### Carlos · Tem alguma nota minha com o ICMS sem definir? (tentativa —) Repetições: 3, todas com o mesmo texto.

> Não encontrei notas aguardando a SEFAZ (secretaria da fazenda) entre 09/08/2026 e 07/11/2026.

### Carlos · A nota número 3191 está com o CST do ICMS definido? (tentativa —) Repetições: 3, todas com o mesmo texto.

> A nota 3191 (série 1), de venda emitida em 05/06/2026, está em rascunho. Rascunho: a nota ainda não foi emitida. Valor: R$ 40.952,64; IBS (imposto sobre bens e serviços): R$ 16,38; CBS (contribuição sobre bens e serviços): R$ 147,43.
>
> Na nota 3191, o item 'Milho em grao a granel, segunda safra 2026' está sem o CST do ICMS. O sistema só preenche sozinho na venda dentro do estado da sua produção (CFOP 5.101) a quem tem inscrição estadual: combine o CST com seu contador antes de emitir, senão a emissão é barrada.

### Rogerio · Quais notas fiscais eu emiti neste mês? (tentativa —)

> Entre 09/08/2026 e 07/11/2026 encontrei 5 notas de venda. Somando as autorizadas: R$ 289.202,76, em 5 notas.
> - Nota 664 (série 1), venda de 04/11/2026: R$ 38.125,89, autorizada.
> - Nota 663 (série 1), venda de 15/10/2026: R$ 78.525,16, autorizada.
> - Nota 662 (série 1), venda de 07/10/2026: R$ 65.885,54, autorizada.
> - Nota 661 (série 1), venda de 06/09/2026: R$ 38.783,25, autorizada.
> - Nota 660 (série 1), venda de 14/08/2026: R$ 67.882,92, autorizada.

### Rogerio · Quanto eu vendi de soja neste ano? (tentativa —)

> (sem corpo: erro 429)

### Rogerio · Quanto eu tenho que pagar este mês? (tentativa —)

> Em 11/2026: 1 guia para pagar, somando R$ 913,50.
> A vencer de 07/11/2026 a 30/11/2026: 1 guia, R$ 913,50:
> - FUNRURAL (contribuição previdenciária do produtor rural), vence em 20/11/2026: R$ 913,50.
