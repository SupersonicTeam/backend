# Perguntas inéditas sobre os dados do produtor (#358)

Para o **Luiz** e o **Herick**. Estas perguntas medem se a Ceres entende o produtor falando do
jeito dele, sobre os **próprios dados**. Ninguém ajustou a Ceres a elas, e é isso que dá valor a
elas: por isso as regras abaixo.

## Regras

1. **Escreva sem ler** o código da Ceres, as descrições das funções dela, o treino do
   classificador (`src/main/resources/ceres/treino/`), o gabarito (`docs/avaliacao/gabarito-ceres.json`)
   nem o conjunto do benchmark (`perguntas-benchmark.json`) ou o README dele. Leia **só esta página**.
2. **Escreva como o produtor fala no WhatsApp**: curto, com erro de digitação, abreviação, sem
   acento, do jeito que sair. Não "melhore" a pergunta depois.
3. **Pergunte sobre o cenário abaixo.** Pode perguntar coisa que o cenário não responde (a Ceres
   tem de dizer que não tem); essas ficam só para medir a rota.
4. **Cada pergunta leva o autor e a data** (formato em `perguntas.txt`).
5. Não converse com quem escreveu a Ceres sobre as perguntas antes da rodada da banca.

Depois que vocês escreverem, a sessão do benchmark calcula a resposta certa de cada uma a partir
dos dados (com teste), põe no conjunto com versão e hash novos e congela antes de qualquer rodada.
O cenário **não muda** por causa das perguntas.

## O cenário

**Hoje é 15 de setembro de 2026.** Você é a **Fazenda Avaliação Ceres**, produtor de **soja e
milho** em Cascavel (PR), pessoa física, no regime regular da reforma. Existe outro produtor no
sistema (um vizinho); você nunca vê os dados dele.

**O que você comprou em 2026** (notas recebidas da revenda agrícola, todas válidas, menos a de
agosto do fungicida, que foi cancelada):

| Data | O quê | Quanto | Valor |
|---|---|---|---|
| 10/02 | Fertilizante NPK 04-14-08 | 5.000 kg | R$ 12.500,00 |
| 18/03 | Fertilizante foliar | 200 L | R$ 3.480,00 |
| 05/04 | Herbicida glifosato | 300 L | R$ 8.250,00 |
| 12/05 | Semente de soja certificada | 2.400 kg | R$ 19.200,00 |
| 22/07 | Óleo diesel S10 | 4.000 L | R$ 24.360,00 |
| 08/08 | Fertilizante NPK 20-00-20 | 3.000 kg | R$ 9.150,00 |
| 20/08 | Fungicida (nota **cancelada**) | 50 L | R$ 4.725,00 |

**O que você vendeu** (notas suas, todas para a cooperativa agroindustrial):

| Data | O quê | Quanto | Valor | Situação da nota |
|---|---|---|---|---|
| 25/03 | Soja em grão | 60.000 kg | R$ 144.000,00 | autorizada |
| 14/06 | Milho em grão | 90.000 kg | R$ 108.900,00 | autorizada |
| 05/08 | Soja em grão | 30.000 kg | R$ 75.600,00 | autorizada |
| 27/08 | Milho em grão | 20.000 kg | R$ 23.400,00 | **rejeitada** pela Sefaz |
| 02/09 | Soja em grão | 15.000 kg | R$ 38.250,00 | autorizada |
| 08/09 | Milho em grão | 10.000 kg | R$ 12.100,00 | **devolvida pelo contador** (a natureza da operação não bate com a venda combinada com a cooperativa) |
| 10/09 | Soja em grão | 5.000 kg | R$ 12.000,00 | rascunho, não emitida |
| 12/09 | Soja em grão | 2.000 kg | R$ 5.100,00 | **com erro** (falta o endereço da cooperativa) |

**Suas guias de imposto:**

| Vence em | Guia | Valor | Situação |
|---|---|---|---|
| 15/07 | DARF | R$ 430,00 | vencida, não paga |
| 20/08 | Funrural | R$ 1.890,00 | **paga** |
| 05/09 | ICMS | R$ 1.250,00 | não paga (já passou do dia) |
| 15/09 (hoje) | GPS | R$ 310,00 | não paga, vence hoje |
| 20/09 | Funrural | R$ 2.268,00 | não paga |
| 25/09 | ICMS | R$ 1.530,00 | não paga |
| 10/10 | DARF | R$ 980,00 | não paga |
| 20/11 | GPS | R$ 640,00 | não paga |

O sistema também sabe, a partir dessas notas, o IBS/CBS de cada venda e de cada compra (o
crédito), o Funrural que a cooperativa retém nas vendas, os avisos de prazo e o que está pendente
de conferir. Pergunte como você perguntaria ao seu contador.
