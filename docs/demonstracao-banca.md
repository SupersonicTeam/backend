# Demonstração da banca: contas e cenários

Seed `config/demonstracao/SeedDemonstracao` (issue #315), ligado por `DEMONSTRACAO_HABILITADA=true`
(`app.demonstracao.habilitada`). Só sobe em `dev`, `homolog` e `test`; em `prod` a aplicação recusa
a subida. Na VPS (`https://www.agroconia.com.br`, profile `homolog`) ele está ligado no
`deploy/compose.yaml`.

- **Senha de todas as contas:** a `SEED_SENHA` do `.env` da VPS. Ela nunca vai para o repositório nem
  para este documento.
- **Idempotente:** com a administradora no banco, nada é recriado. Para semear de novo (as datas
  andam com o dia da semeadura), zere o banco como no `deploy/README.md`.
- **Passo manual na VPS depois do grupo de ICMS (#376):** o banco já semeado guarda o cenário
  antigo (terminal exportador no PR, exportadora em Santos, milho e touro autorizados) e as notas
  autorizadas com o XML sem o grupo `ICMS`. O passo 4 do roteiro (ICMS a definir) só existe depois
  de **zerar o banco e semear de novo**, como no `deploy/README.md` (preservando a base da Ceres).
  Sem isso, não mostrar o passo 4.
- **Dados fictícios com forma de dado real:** CPF/CNPJ com DV válido, município com o código IBGE
  real, NCM da referência, CFOP pela UF das partes, preços de 2026 e IBS/CBS calculados pelo próprio
  sistema. Nenhuma razão social é de empresa real.
- **Datas relativas:** oito meses de movimento até o dia da semeadura. Os meses até dois atrás saem
  **fechados** (RN004); o mês passado e o corrente ficam abertos.

## Contas

E-mails no domínio reservado `example.com`.

| Conta | Papel | Para mostrar |
|---|---|---|
| `patricia.moreira@example.com` | Contador, **ADMINISTRADOR** | Carteira inteira, aprovação, equipe, convites, ciclo do cliente (suspender/reativar/encerrar), reabertura de apuração |
| `rafael.costa@example.com` | Contador, **CONTADOR** | Aprova e devolve nota, reabre apuração; não gerencia a equipe (403) |
| `juliana.prado@example.com` | Contador, **ASSISTENTE** | Atende a carteira, mas **não aprova** nota (403 `PAPEL_NAO_AUTORIZADO`) |
| `carlos.rossi@example.com` | Produtor PF, Sorriso/MT | **O principal:** grãos e algodão, R$ 6,8 mi/ano (acima do limite do art. 164), ~60 notas, compras de insumo com crédito, fila de revisão completa |
| `financeiro.santaclara@example.com` | Produtor PJ, Patrocínio/MG | Café para torrefação de Uberlândia e cerealista de Patrocínio (vendas internas, ICMS diferido), nota aguardando aprovação, pendência **enviada** com anexo |
| `luiz.bertoldo@example.com` | Produtor PF, Cascavel/PR | Leite para laticínio, optante do art. 165, nota aguardando aprovação |
| `helena.takahashi@example.com` | Produtora PF, Mogi das Cruzes/SP | Hortifrúti, **nota com inconsistência** (CFOP 6101 para comprador do mesmo estado), GPS vencendo em 3 dias |
| `contato.trespontes@example.com` | Produtor PJ, Campo Grande/MS | Pecuária: venda de gado para MS; o touro reprodutor para MG fica **rascunho com o ICMS a definir** |
| `rogerio.antunes@example.com` | Produtor PF, Rio Verde/GO | **Cota da Ceres no limite:** uma pergunta passa, a segunda recebe 429 |
| `granja.toledo@example.com` | Produtor PJ, Toledo/PR | Ovos, DARF que **vence amanhã** (aviso urgente) |
| `arrozeira.schmitt@example.com` | Produtor PJ, Pelotas/RS | Vínculo **suspenso** (some da carteira ativa) |
| `marcos.lacerda@example.com` | Produtor PF, Ponta Grossa/PR | Vínculo **encerrado** (só o nome no filtro `ENCERRADO`) |

O escritório é a **Contabilidade Rural Oeste** (Cascavel/PR). As contas do `DataLoader` (João,
Maria e o Escritório Campo Certo) continuam existindo, num escritório à parte.

## Roteiro sugerido

1. **Contador (Patrícia): a carteira.** Ordenar por urgência: o Carlos sobe por causa da guia
   **vencida** e da nota aguardando aprovação. Filtrar por etiqueta ("Grãos", "Optante (art. 165)",
   "Acima do limite do art. 164") e por situação (`SUSPENSO`, `ENCERRADO`). Abrir a ficha: as
   observações dizem se o cliente é optante ou contribuinte obrigatório.
2. **Aprovação (#178).** Na fila há três notas `AGUARDANDO_APROVACAO` (Carlos, Santa Clara, Luiz).
   Abrir o preview da DANFE, aprovar uma e devolver outra com motivo. A nota **devolvida** do Carlos
   já mostra o histórico (enviada → devolvida por peso divergente do romaneio). Entrar como a
   Juliana e tentar aprovar: 403.
3. **Produtor (Carlos): o dia a dia.** Dashboard com oito meses de vendas; apuração com meses
   fechados e o mês aberto ao vivo; créditos das compras de insumo (fertilizante, sementes,
   defensivos, diesel), importadas como XML assinado do fornecedor; baixar o XML e a DANFE de uma
   nota autorizada; a nota emitida há 27 dias gera o alerta de **fim do prazo de cancelamento**
   (RN006/RN010); a **cancelada** mostra o motivo e o protocolo; o pedido do escritório (extrato
   bancário) está no calendário.
4. **ICMS a definir (Carlos e Três Pontes).** Os rascunhos de venda de milho do Carlos ao
   pecuarista Antônio Carlos Pereira (pessoa física **sem IE**) e o do touro reprodutor da Três
   Pontes para MG não têm CST de ICMS. O sistema só preenche sozinho o **51 (diferimento)** na venda
   interna de produção própria a quem tem IE. Mostrar: a Ceres aponta o sinal "esta sem o CST do
   ICMS" na nota; enviar para revisão ou gerar o XML responde **422 `ICMS_A_DEFINIR`**, com a
   sugestão "Informe o CST do ICMS combinado com o contador: 40, 41, 50, 51 ou 90". O sistema nunca
   escolhe o CST. Sem o grupo de ICMS, a SEFAZ rejeitaria a NF-e (rejeição 1002, NT 2025.002, regra
   B25-90).
5. **Erro de validação (Helena).** A nota de tomate está `COM_INCONSISTENCIAS`: o histórico de
   validação explica o CFOP 6101 para comprador de SP. Trocar o CFOP para 5101, gerar de novo e
   enviar para revisão.
6. **Reabertura (RN005, #224).** Como Patrícia ou Rafael, reabrir um mês fechado de até três meses
   atrás de um cliente; além disso, 422 `REABERTURA_FORA_PRAZO`.
7. **Ceres.** Os três atalhos da tela respondem mesmo sem o modelo (#343): "Quanto eu tenho que
   pagar este mês?" e "Quando vence a minha próxima guia?" pelas funções de guias, e "O que muda
   para mim com o IBS e o CBS?" com o texto explicado (#347): o que são, o calendário de 2026 a
   2033, venda, compras, pagamento, quem paga e o próximo passo, cada item com o dispositivo.
   **Entrar como o Carlos** (pessoa
   física): o texto mostra a soja, o milho e o algodão que ele mais vendeu, com o tratamento da
   tabela oficial, e o Funrural retido pelo comprador. O texto já explica que o recolhimento do
   IBS/CBS dos insumos do Anexo IX fica diferido entre contribuintes do regime regular (LC 214/2025,
   art. 138, § 2º, inciso I, alínea a) e deixa o encerramento (§§ 5º a 7º) e o efeito no crédito
   com o contador (#284). **Limitação declarada (#356):** a
   tela de créditos do Carlos (e a apuração, na tela e na Ceres) ainda estima crédito de
   fertilizante, semente e defensivo, porque o sistema não modela o diferimento (premissas, seção
   1). Não apontar esses créditos junto com o atalho; se a banca perguntar, é essa a resposta. Com o Carlos: "quanto eu comprei de fertilizante?", "qual foi minha apuração de
   IBS e CBS do mês passado?", "a soja tem redução de alíquota?" (lei com citação). Com o **Rogério**, digitando perguntas que não sejam as três da tela (o
   texto exato de um atalho, clicado ou digitado, fica fora da cota e não leva 429): a primeira pergunta responde e a segunda recebe **429 `LIMITE_USO_ASSISTENTE`** com o
   `Retry-After` da virada da semana (`GET /api/assistente/uso` mostra o saldo). O job
   `CotaDemonstracaoCeres` repõe esse cenário toda segunda-feira às 00:05 e o seed o repõe na
   subida.
8. **Notificação (#182).** "Próximas obrigações" da Granja Toledo: o DARF que vence amanhã sai como
   **urgente**; na Helena, a GPS de daqui a 3 dias sai como **próximo**.

## O que o cálculo mostra (e por quê)

- **IBS/CBS de 2026** são as alíquotas de teste (CBS 0,9%, IBS 0,1%), então os valores são
  pequenos.
Reduções aplicadas pela tabela oficial, conferidas no banco da VPS em 2026-11-01:

| Redução | Produtos |
|---|---|
| **100%** (IBS/CBS zero) | ovos, leite pasteurizado (com o cClassTrib 200003 informado no item), arroz, tomate e café cru (Anexos I e XV da LC 214) |
| **60%** | soja, milho, trigo e algodão em grão (in natura, art. 137), touro reprodutor, e os insumos: fertilizantes, defensivos, sementes e ração (Anexo IX) |
| **0%** (alíquota cheia) | diesel e peças da colhedora |

- O IBS/CBS zero é o comportamento esperado, não falta de cálculo.
- **Leite (#284, decisão de 2026-11-06):** o NCM não separa o leite cru do de consumo direto, então
  o leite sai por padrão **in natura, 60%** (art. 137, cClassTrib 200036), e a alíquota zero do
  Anexo I (item 2, art. 125) só vale com o cClassTrib **200003** informado no item. O leite
  pasteurizado de 1 litro do Luiz é de consumo direto e vai com o 200003: os totais da
  demonstração não mudam. Uma nota nova de leite sem o cClassTrib sai com 60%.
- O 60% da soja e do milho (art. 137) segue em validação com o contador (#284, pergunta 1). Não
  afirmar na banca que esse enquadramento é definitivo.
- **ICMS e Funrural** ficam por guia (premissa da #280): as guias mensais são de Funrural (PF) e
  ICMS (PJ). A NF-e leva o **grupo de ICMS por item**, sem calcular o ICMS (premissas, seção 3):
  - venda interna de produção própria a contribuinte → **CST 51**, diferimento total, ICMS zero.
    A **soja em MT** (Carlos → Graneleira Teles Pires e Trading Chapadão do Norte) tem o
    diferimento confirmado pelo escritório (#284, pergunta 9). O **café em MG** (Santa Clara →
    Torrefação de Uberlândia e Cerealista Alto Paranaíba) é de demonstração, **a confirmar com o
    contador**. As demais vendas internas do seed (milho, algodão, leite, ovos e outras) também
    saem com o padrão 51, igualmente a confirmar;
  - toda venda autorizada do seed é interna a contribuinte. As sem CST defensável ficam rascunho
    com o ICMS a definir (roteiro, passo 4). Antes, o Carlos vendia soja a um terminal exportador no
    PR e a Santa Clara, café a uma exportadora em Santos. A exportação indireta traria a suspensão
    do IBS/CBS (LC 214/2025, art. 82) e a imunidade do Funrural, que o sistema não modela; por isso
    mudou o comprador, e não a operação.
- **Regime:** todo produtor pessoa física é de regime normal (CRT 3), coerente com a premissa do
  regime regular. Pessoa física não entra no Simples Nacional nem no MEI (LC 123/2006, art. 3º;
  issue #298): a NF-e e o DANFE saem com CRT 3 pelo tipo do produtor, o calendário nunca mostra DAS,
  DEFIS nem as obrigações do MEI a ela, e o cadastro com `SIMPLES`/`MEI` é recusado.

## Checklist da VPS: base de legislação da Ceres (issue #343)

A base de lei (`legislacao_trecho`) **não vem pelo seed**: é copiada à parte (`deploy/README.md`,
"Base de legislação da Ceres"). Banco zerado sem a cópia = toda pergunta de lei cai em "não
encontrei fundamento legal" e o atalho de legislação sai sem as citações (o texto segue). Antes da
banca, conferir na pasta do `deploy/` da VPS:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT norma, count(*) FROM legislacao_trecho GROUP BY norma ORDER BY norma;
SELECT count(*) FROM legislacao_trecho;
SQL
```

**Esperado: 8.304 trechos** — EC 132/2023 538, LC 214/2025 5.004, LC 227/2026 2.211, Lei
8.212/1991 547 e Lei 9.528/1997 4 (conferido na VPS e no banco local em 2026-11-04). Contagem
diferente = recopiar a base. Depois, testar na tela o atalho "O que muda para mim com o IBS e o
CBS?" (#347): a resposta lista 33 citações (34 para produtor pessoa física, com o Funrural, mais
as dos produtos vendidos que não se repetem); com dispositivo faltando, o log do backend
mostra, na pergunta, `Atalho IBS/CBS: x de N dispositivos na base de legislacao`.

## Passo manual na VPS: cClassTrib do leite da demonstração (#284, leite in natura)

O seed da banca é idempotente e não regrava nota já semeada. As notas de leite gravadas antes da
regra do leite in natura têm o tratamento **aplicado** 200003 (zero) mas não o cClassTrib
**informado**: emitidas, nada muda (o XML é o documento); a nota não emitida (a do Luiz aguardando
aprovação) segue zero até ser editada, e uma edição depois de devolvida a recalcularia para 60%.
Para a demonstração ficar coerente com o seed novo, informar o 200003 nesses itens (idempotente;
só o leite pasteurizado do seed, nunca changeSet). **Rodar antes do deploy desta versão** (a coluna
já existe): na subida, o `TributosLegadoRunner` recalcula pelo padrão novo (60%) todo item de leite
não emitido com o aplicado 200003 e sem o informado — a nota do Luiz em aprovação voltaria a
`RASCUNHO` com `APROVACAO_CADUCADA`. Se o deploy já rodou, o passo ainda informa o 200003, e basta o
Luiz editar e reenviar a nota para ela voltar a zero:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
UPDATE nota_fiscal_item SET c_class_trib = '200003'
 WHERE ncm = '04012010' AND c_class_trib IS NULL AND c_class_trib_aplicado = '200003'
   AND descricao = 'Leite pasteurizado integral, embalagem de 1 litro';
SQL
```

Conferir: o mesmo `WHERE` com `SELECT COUNT(*)` volta 0; os totais do Luiz não mudam.

## Passo manual na VPS: regime dos produtores pessoa física (issue #298)

O `DataLoader` não reescreve produtor já gravado, e a correção não pode ir num changeSet (rodaria em
`prod`, em dado de produtor real). Na VPS, o João e a Maria do `DataLoader` foram gravados como
pessoa física em `SIMPLES`. Desde a #298 a NF-e, o DANFE e o calendário já os tratam como regime
normal; o passo abaixo corrige o regime gravado, que aparece na carteira e na ficha. Enquanto ele
não roda, a subida loga um `WARN` "Produtor semeado ... e pessoa fisica com regime SIMPLES".

1. Na pasta do `deploy/` da VPS, conferir quem será alterado (o CPF é cifrado; o nome não). O
   `psql` usa o usuário e o banco do próprio container (`POSTGRES_USER`/`POSTGRES_DB`, que vêm de
   `DB_USER`/`DB_NAME` do `.env`):

   ```bash
   docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
   SELECT id, nome_completo, regime_tributario FROM produtores
    WHERE tipo_produtor = 'PESSOA_FISICA' AND regime_tributario IN ('SIMPLES', 'MEI');
   SQL
   ```

2. Corrigir para o regime normal (idempotente: na segunda vez não altera nada):

   ```bash
   docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
   UPDATE produtores SET regime_tributario = 'REAL', updated_at = NOW()
    WHERE tipo_produtor = 'PESSOA_FISICA' AND regime_tributario IN ('SIMPLES', 'MEI');
   SQL
   ```

3. Conferir: o `SELECT` do passo 1 volta vazio; a ficha do João
   (`GET /api/contador/produtores/{produtorId}/ficha`) mostra `REAL`; o preview do DANFE de uma
   nota dele mostra "3 - REGIME NORMAL"; o calendário dele não tem DAS nem DEFIS.
4. Se o vínculo do João com o escritório do `DataLoader` foi criado antes da #280, registrar a
   observação de optante pela ficha (`PATCH /api/contador/produtores/{produtorId}/ficha`, campo
   `observacoes`, texto do `DataLoader.OBSERVACAO_OPTANTE`).
5. Marca nova na observação (issue #336): o vínculo semeado antes da #336 guarda "O AgroContábil
   calcula..." (o `DataLoader` não reescreve banco já semeado). Trocar a marca, idempotente:

   ```bash
   docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
   UPDATE carteira_vinculo
      SET observacoes = REPLACE(observacoes, 'AgroContábil calcula', 'AgroConIA calcula')
    WHERE observacoes LIKE '%AgroContábil calcula o IBS/CBS%';
   SQL
   ```

   Notificações já registradas ficam com o texto da época (histórico).

Nota fiscal já autorizada não muda: o XML assinado guarda o CRT da emissão. A NF-e com CRT 1 que já
existir na VPS continua com ele; as novas saem com CRT 3.
