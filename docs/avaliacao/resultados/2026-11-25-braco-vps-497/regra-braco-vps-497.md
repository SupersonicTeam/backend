# Braço complementar "composição de camadas da VPS" (#497): regra escrita antes de rodar

Escrita em 2026-11-25, antes de qualquer rodada deste braço, a pedido da orquestradora.

**Não substitui nem altera o veredito ENTRA** da medição oficial
([`medicao-depois-497.md`](../2026-11-25-medicao-depois-497/medicao-depois-497.md)), que é da rodada única com os
padrões do código. Este braço é relatado à parte, como **estimativa do comportamento em produção**, e não
decide merge.

## Por que existe

O voto das embeddings é determinístico. O que varia entre repetições é a camada de embeddings entrar em pausa
de 30 s depois de o embedding da pergunta passar do prazo de 500 ms (`AI_CLASSIFICACAO_TIMEOUT_EMBEDDING`):
toda pergunta vai então direto ao modelo. Na máquina local o Ollama descarrega o `bge-m3` depois de 5 minutos
e muitas chamadas passam de 500 ms; na VPS o `OLLAMA_KEEP_ALIVE` é de 24 h e a classificação por embeddings
leva 38 a 54 ms. Nas medições oficiais (base e depois) o log registra 10 e 7 pausas, o que mistura a mudança
do código com o estado do Ollama.

## Condições (as da medição oficial, mudando só o que aproxima a camada de embeddings da VPS)

- Mesmos comando, modelo (`anthropic/claude-haiku-5-5`, raciocínio desligado, temperatura 1,0), gabarito
  `2026-11-05.3`, seed v4, "hoje", máquina, 3 repetições e flags nos padrões do código.
- **Ollama com o `bge-m3` carregado antes e durante a rodada**, sem recriar o contêiner: uma chamada de
  aquecimento antes da rodada e uma chamada de reaquecimento a cada 60 s durante ela, as duas com
  `keep_alive` de 1 h.
- **`AI_CLASSIFICACAO_TIMEOUT_EMBEDDING=3s`**, só no ambiente da rodada (o padrão do código segue 500 ms).
- Dois lados, **uma rodada cada**, em sequência e sob a trava do Ollama: o código da base (`c7273b0d`; o que
  entrou na `main` depois é só documentação) e o PR #617 (`53979aa9`).

## Validade

Uma rodada só vale se o log da aplicação tiver **zero** INFO "embedding da pergunta sem resposta" e zero
"fila do embedding da classificacao cheia" (zero pausas). Se não for zero, **relata-se como está e não se
repete a rodada**.

## O que se relata

- As mesmas métricas da medição oficial e o recorte por camada, **lado a lado** com a base e o depois
  oficiais.
- A comparação entre os dois lados pela mesma régua do projeto (`analisar.py`, as 3 repetições de um lado
  acima das 3 do outro e soma diferindo em 5 acertos ou mais), **marcada como complementar**; a saída de
  `analisar.py --decidir` entra como leitura, **sem valor de decisão**.
- Ids que mudam de lado (certo → errado e errado → certo), só ids, rotas e camadas; p50 e p95 (a latência
  local com o `bge-m3` aquecido é de uma máquina e não é a da VPS).
