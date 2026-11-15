# Ressalva do enquadramento na resposta sobre tratamento de produto — conjunto, regra e executor (#443)

Mede se a correção da #443 faz a resposta sobre redução ou tratamento de um produto trazer **o
tratamento da tabela oficial e a ressalva do enquadramento com o contador, venha pela rota que
vier**, **sem piorar** a rota de lei nem a de dados. Dois braços (a `main` e o head da correção), o
Haiku 5.5 pela API, 3 repetições.

> **Teste cego.** O conjunto foi escrito por uma sessão que nunca leu o gabarito, o conjunto do
> benchmark, os conjuntos de fidelidade nem o conjunto da #447, e é **conjunto de teste**: quem
> escreve ou revisa a correção **não lê** `perguntas-ressalva-443.json`, não o usa em exemplo de
> prompt, treino, template ou teste, e recebe só os ids (`RS07`). Mudança feita por quem leu uma
> pergunta a contamina. A medição **muda o que a banca mede**: o merge depende do OK do Murillo.

## O que tem aqui

| Arquivo | O que é |
|---------|---------|
| `perguntas-ressalva-443.json` + `.sha256` | As 60 perguntas, com o que se espera de cada uma. Congelado pelo sha256 |
| `regra-de-decisao.md` | A regra (`ENTRA` / `NAO_ENTRA` / `INCONCLUSIVA`), escrita antes de qualquer rodada |
| `ressalva_comum.py` | Margens da regra, detectores (ressalva, tratamento, recusa) e classificação das execuções |
| `executar_ressalva.py` | Roda o conjunto nos braços (instâncias já no ar), intercalado, e grava as respostas brutas |
| `decidir_ressalva.py` | Aplica a regra e grava `resultado.json` e `resultado.md` (com a leitura humana) |
| `test_conjunto.py`, `test_decisao.py` | Testes (só biblioteca padrão), rodam na CI |

## O conjunto (60 perguntas)

| Grupo | Perguntas | O que testa |
|-------|-----------|-------------|
| **Defeito** (`RS01`–`RS36`) | 36 (estrato A: 30, estrato B: 6) | pergunta de redução ou tratamento de um produto, com ressalva esperada em qualquer rota |
| **Controle de lei** (`CL01`–`CL12`) | 12 (9 comuns, 3 de fronteira) | perguntas de lei que não podem mudar: rota `LEGISLACAO`, citação da norma, sem texto da tabela |
| **Controle de dados** (`CD01`–`CD12`) | 12 (9 de tratamento, 3 de dado puro) | o que já vai à rota de dados: com ressalva (tratamento) e sem ela (dado puro) |

**As 36 de defeito variam o produto (33 distintos, do in natura aos Anexos I, VII, IX e XV) e a
forma de perguntar**, porque é a forma que decide a rota (`RegrasClassificacao`: termo de lei como
"IBS" sem o produto na lista do classificador vira lei com confiança 1; caso hipotético com "imposto"
também; só o NCM cai no kNN):

| Forma | n | Por que está aqui |
|-------|---|-------------------|
| `nome_termo_lei` | 6 | produto fora da lista do classificador + IBS/CBS: a regra manda à lei |
| `ncm` | 6 | só o código: sem produto, o termo de lei decide |
| `hipotese` | 5 | "se eu vender…", "suponha…": a regra de hipótese manda à lei |
| `indireta` | 5 | descreve a situação do produtor, sem "redução" nem "alíquota" |
| `contexto_venda` | 5 | nota, quantidade, comprador |
| `lei_produto` | 5 | pergunta o que a lei diz do produto |
| `coloquial` | 4 | curta, sem acento, elíptica |

## Como rodar (depois do head da correção estar no ar)

Pré-requisitos: duas instâncias no ar (a `main` e o head), com o Haiku 5.5 pela API, a mesma conta de
produtor semeada e a mesma base de lei. URL e credenciais **só por ambiente**, nunca em argumento nem
em arquivo:

```bash
export RESSALVA_BASE_URL=... RESSALVA_BASE_EMAIL=...        # e a senha: read -rs RESSALVA_BASE_SENHA
export RESSALVA_CANDIDATO_URL=... RESSALVA_CANDIDATO_EMAIL=...
python3 -I docs/avaliacao/ressalva-tratamento-443/executar_ressalva.py --so-verificar --braco base --braco candidato
python3 -I docs/avaliacao/ressalva-tratamento-443/executar_ressalva.py --braco base --braco candidato \
    --commit base=<sha da main> --commit candidato=<sha do head>
python3 -I docs/avaliacao/ressalva-tratamento-443/decidir_ressalva.py --rodada <pasta gravada>
```

360 execuções. O limite de 20 perguntas por minuto por usuário (#150) é por conta: o executor espera o
`Retry-After`. A pasta `rodadas/` fica fora do git (respostas da conta de demonstração).

Se a correção for atrás de uma flag, os dois braços podem ser o **mesmo jar** com a flag desligada e
ligada, como na fidelidade (`docs/avaliacao/fidelidade-lei/`).

## Depois do congelamento

Mudar uma pergunta, uma norma aceita ou uma margem **muda o sha256**: suba a versão do conjunto (no
JSON, em `VERSAO_CONJUNTO` e na regra), grave o novo hash
(`sha256sum perguntas-ressalva-443.json > perguntas-ressalva-443.json.sha256`) e rode de novo.
O executor e a decisão recusam um conjunto que não bate com o hash. O arquivo é binário para o git
(`-text` no `.gitattributes`): CRLF quebraria o hash.
