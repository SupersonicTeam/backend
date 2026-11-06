# sinais-nota

Listas versionadas do sinal `PRODUTO_PARECE_SERVICO` (issues #276 e #364): avisa quando um item
de NF-e de produto parece prestação de serviço. Quem lê: `SinaisNotaConferirService`. Mudar uma
lista é mudar a regra: o teste de tabela (`SinaisNotaConferirServiceTest.produtoPareceServico`)
tem de ganhar o caso novo.

| Arquivo | O que tem |
|---|---|
| `termos-servico.txt` | Todo termo que, na descrição do item, sugere serviço: genéricos ("serviço", "frete", "aluguel", "mão de obra", "diária") e operações agrícolas (colheita, plantio, pulverização, gradagem, subsolagem, aração, destoca, desmatamento, calagem, secagem, ensilagem...). |
| `termos-ambiguos-servico.txt` | O subconjunto que também aparece em **mercadoria**: "bag de colheita", "adubo de plantio", "bico de pulverização", "milho seco e limpo". Todo termo daqui vale como termo de serviço sem repetir na lista anterior. |
| `excecoes-servico.txt` | Expressões que tiram o sentido de serviço de um termo ("semente para plantio", "bico de pulverização", "seco e limpo", "para calagem"). Saem da descrição antes de procurar os termos. |

Formato: um termo por linha; `#` abre comentário; acento, caixa e pontuação são ignorados
("mão-de-obra" = "mao de obra"). Casam palavra ou expressão inteira, então o plural entra como
linha própria. Fora de propósito: "transporte" e "armazenagem" sozinhos (aparecem em produto).

## Como o sinal decide (#364)

1. Sem termo (depois das exceções): sem sinal.
2. Só termos ambíguos, com **NCM** informado e **unidade comercial de produto**
   (kg, g, t, saca, litro, caixa, un...): sem sinal. É a mercadoria que carrega o nome da
   operação.
3. CFOP 5949/6949 (outra saída não especificada): sinal `CFOP_GENERICO`.
4. **Unidade de serviço** (hora, diária, hectare, serviço), mesmo com NCM de mercadoria e CFOP de
   venda: sinal `UNIDADE_DE_SERVICO`. Ex.: "Serviço de gradagem — 10 ha", "Colheita de soja —
   15 ha".
5. NCM ausente ou fora da referência agropecuária: sinal `NCM_FORA_DA_REFERENCIA`.

Unidade ausente ou que não está em nenhuma das duas listas (`SinaisNotaConferirService`) não
desfaz nem confirma o termo. Termo forte ("serviço", "frete", "aluguel") nunca é desfeito pela
unidade de produto.

A frase do aviso (`FrasesSinaisNota`) manda conferir com o contador e cita a NFS-e de padrão
nacional, que os Municípios devem autorizar o contribuinte a emitir a partir de 2026
(LC 214/2025, art. 62, § 1º, inciso I, conferido na base de legislação).

## Pendente de validação do contador

As listas de unidades (serviço × produto) e a regra "unidade de produto desfaz o termo ambíguo"
seguem a leitura das respostas 13 e 14 do escritório (#284) e ainda não foram revisadas por ele.
