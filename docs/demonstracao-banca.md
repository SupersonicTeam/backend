# Demonstração da banca: contas e cenários

Seed `config/demonstracao/SeedDemonstracao` (issue #315), ligado por `DEMONSTRACAO_HABILITADA=true`
(`app.demonstracao.habilitada`). Só sobe em `dev`, `homolog` e `test`; em `prod` a aplicação recusa
a subida. Na VPS (`https://www.agroconia.com.br`, profile `homolog`) ele está ligado no
`deploy/compose.yaml`.

- **Senha de todas as contas:** a `SEED_SENHA` do `.env` da VPS. Ela nunca vai para o repositório nem
  para este documento.
- **Idempotente:** com a administradora no banco, nada é recriado. Para semear de novo (as datas
  andam com o dia da semeadura), zere o banco como no `deploy/README.md`.
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
| `financeiro.santaclara@example.com` | Produtor PJ, Patrocínio/MG | Café para torrefação e exportadora de Santos (interestadual), nota aguardando aprovação, pendência **enviada** com anexo |
| `luiz.bertoldo@example.com` | Produtor PF, Cascavel/PR | Leite para laticínio, optante do art. 165, nota aguardando aprovação |
| `helena.takahashi@example.com` | Produtora PF, Mogi das Cruzes/SP | Hortifrúti, **nota com inconsistência** (CFOP 6101 para comprador do mesmo estado), GPS vencendo em 3 dias |
| `contato.trespontes@example.com` | Produtor PJ, Campo Grande/MS | Pecuária: venda de reprodutores para MS e MG |
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
4. **Erro de validação (Helena).** A nota de tomate está `COM_INCONSISTENCIAS`: o histórico de
   validação explica o CFOP 6101 para comprador de SP. Trocar o CFOP para 5101, gerar de novo e
   enviar para revisão.
5. **Reabertura (RN005, #224).** Como Patrícia ou Rafael, reabrir um mês fechado de até três meses
   atrás de um cliente; além disso, 422 `REABERTURA_FORA_PRAZO`.
6. **Ceres.** Com o Carlos: "quanto eu comprei de fertilizante?", "qual foi minha apuração de
   IBS e CBS do mês passado?", "a soja tem redução de alíquota?" (lei com citação). Com o **Rogério**: a
   primeira pergunta responde e a segunda recebe **429 `LIMITE_USO_ASSISTENTE`** com o
   `Retry-After` da virada da semana (`GET /api/assistente/uso` mostra o saldo). O job
   `CotaDemonstracaoCeres` repõe esse cenário toda segunda-feira às 00:05 e o seed o repõe na
   subida.
7. **Notificação (#182).** "Próximas obrigações" da Granja Toledo: o DARF que vence amanhã sai como
   **urgente**; na Helena, a GPS de daqui a 3 dias sai como **próximo**.

## O que o cálculo mostra (e por quê)

- **IBS/CBS de 2026** são as alíquotas de teste (CBS 0,9%, IBS 0,1%), então os valores são
  pequenos.
Reduções aplicadas pela tabela oficial, conferidas no banco da VPS em 2026-11-01:

| Redução | Produtos |
|---|---|
| **100%** (IBS/CBS zero) | ovos, leite, arroz, tomate e café cru (Anexos I e XV da LC 214) |
| **60%** | soja, milho, trigo e algodão em grão (in natura, art. 137), touro reprodutor, e os insumos: fertilizantes, defensivos, sementes e ração (Anexo IX) |
| **0%** (alíquota cheia) | diesel e peças da colhedora |

- O IBS/CBS zero é o comportamento esperado, não falta de cálculo.
- O 60% da soja e do milho (art. 137) segue em validação com o contador (#284, pergunta 1). Não
  afirmar na banca que esse enquadramento é definitivo.
- **ICMS e Funrural** ficam por guia (premissa da #280): as guias mensais são de Funrural (PF) e
  ICMS (PJ).
- **Regime:** todo produtor pessoa física é de regime normal (CRT 3), coerente com a premissa do
  regime regular. Pessoa física não entra no Simples Nacional nem no MEI (LC 123/2006, art. 3º;
  issue #298): a NF-e e o DANFE saem com CRT 3 pelo tipo do produtor, o calendário nunca mostra DAS,
  DEFIS nem as obrigações do MEI a ela, e o cadastro com `SIMPLES`/`MEI` é recusado.

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

Nota fiscal já autorizada não muda: o XML assinado guarda o CRT da emissão. A NF-e com CRT 1 que já
existir na VPS continua com ele; as novas saem com CRT 3.
