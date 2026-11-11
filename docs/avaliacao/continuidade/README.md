# Conversas da continuidade da Ceres (#499)

Conjunto de conversas de 2 e 3 turnos para medir a continuidade da Ceres antes do merge do PR da
#499. Ele mede três coisas: a reescrita ("e em setembro?", "e a segunda?"), a autocorreção dos
dados e a reconferência da lei ("tem certeza?"). A regra que decide está em `regra-de-decisao.md`.

Foi escrito por uma sessão que não leu o gabarito (#158), o conjunto do benchmark nem o código ou
os testes do PR da continuidade. A sessão só seguiu o contrato da issue. Fica fora de `treino/`.

| Arquivo | O que é |
|---|---|
| `conversas-continuidade-teste.json` (+ `.sha256`) | 42 conversas que decidem (`continuidade-v1-teste`) |
| `conversas-continuidade-dev.json` (+ `.sha256`) | 8 conversas de desenvolvimento (`continuidade-v1-dev`), sem pergunta em comum com o teste |
| `regra-de-decisao.md` | métricas, limiares e decisão, gravados antes da rodada |
| `validar_conjunto.py` | confere a forma, os tokens, os marcadores e o sha256; guarda os catálogos |
| `test_validar_conjunto.py` | testes do validador, rodados na CI |

- **Quem lê o quê:** a sessão que implementa a #499 lê o dev e nunca o teste.
- **Mudança no conjunto:** pergunta mudou, mude a versão e o `.sha256`; o validador e o teste
  conferem os dois.
- **Revisão da lei:** os fatos de lei (`fatos`) e as trocas (`edicoes`) passam pela revisão da
  equipe e do orientador, sem contador. Quem revisa registra em `revisao_lei` (`status`
  `revisado`, `por`, `em`), e a rodada oficial exige isso.

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
  "versao": "continuidade-v1-teste", "parte": "teste", "produtor": "carlos.rossi@example.com",
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
