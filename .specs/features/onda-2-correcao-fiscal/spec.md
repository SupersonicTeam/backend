# Onda 2 — Correção fiscal (issues #86 → #85 → #88)

**Status:** aguardando aprovação do Murillo · **Data:** 2026-09-21 · **Escopo:** Large (3 PRs, 2 changeSets, modelo/DTO/mapper/validador)

## Problem Statement

Três bugs alteram os **valores da apuração IBS/CBS** ou a **validade do XML perante a SEFAZ real**:

1. **#86** — o crédito de IBS (`CreditoTributarioService`) usa a alíquota fixa do `application.yml`, enquanto o débito (`TributacaoService.resolveAliquotaIbs`) usa a tabela `aliquota_ibs_uf`. Coincidem hoje só porque o seed é uniforme (0,0005 + 0,0005 = 0,0010). Nenhum teste captura a divergência.
2. **#85** — `aliquota_ibs_uf` e `TributosProperties` não têm vigência. A transição 2026→2033 (LC 214/2025, arts. 87–92 e regime de transição) muda as alíquotas a cada ano; qualquer nota calculada com `dataEmissao` fora de 2026 usa alíquota errada.
3. **#88** — o XML passa em XSD + assinatura local, mas seria rejeitado pela SEFAZ: emitente com IE `ISENTO`, endereço `SEM ENDERECO`/`S/N`, `cMun = cUF+"99999"` (IBGE inválido); `cMunFG` usa o município do **destinatário** (deveria ser o do emitente). Além disso `numero > 999.999.999` e `serie > 999` são truncados silenciosamente por `ChaveAcessoGenerator.zeroPad`, gerando chave inconsistente com o XML.

## Goals

- [ ] Um **único resolvedor de alíquota** (`AliquotaIbsCbsResolver`) consumido por débito e crédito, com seleção por UF **e** por data de emissão.
- [ ] Alíquotas de IBS (por UF) e CBS com **vigência** no banco, migradas sem editar changeSets aplicados.
- [ ] XML NF-e com emitente real (endereço + IE) e `nNF`/`serie` dentro dos limites do layout, com erro explícito em vez de truncamento.
- [ ] Cada correção coberta por um teste que **falha antes e passa depois**.

## Out of Scope

| Item | Motivo |
|------|--------|
| Endpoint de cadastro/edição de `Produtor` (`/api/produtor/me`) | Decisão do Murillo (2026-09-21): fica para a Sprint 8 junto de auth/RBAC. Endereço e IE entram só por modelo + migration + seed do `DataLoader`. |
| Valores oficiais das alíquotas de 2027–2033 | RFB/CONFAZ/Comitê Gestor ainda não publicaram atos. O seed traz apenas a vigência de 2026 (aberta); anos seguintes entram por novo changeSet quando publicados. |
| Persistir UF do emitente terceiro em notas importadas | Desnecessário: pelo princípio do destino (EC 132/2023, art. 156-A, § 1.º, VII) a UF aplicável à nota importada é sempre a do destinatário = produtor. Ver design. |
| Alíquota de IBS **por município** | LC 214 prevê parcela municipal própria por município; sem tabela oficial, mantém-se a parcela municipal por UF. Débito registrado. |
| Tabela de referência de municípios IBGE | Validação de `codigoMunicipioIbge` limita-se a formato (7 dígitos) + coerência do prefixo com o `cUF` da UF. Tabela completa (5.570 municípios) fica para a Sprint 7. |
| `codigoMunicipioIbge` obrigatório no DTO | Decisão do Murillo: opcional no rascunho (não quebra o Angular); obrigatório só na emissão (já é hoje). |
| Leitura do IBS/CBS destacado no XML importado | Layout 4.00 ainda não traz o grupo; crédito continua **estimado** pelas alíquotas de referência (débito da Sprint 7). |

---

## User Stories

### P1: Crédito usa a mesma alíquota do débito (#86) ⭐ MVP

**User Story:** Como contador, quero que o crédito de IBS de uma nota de entrada seja calculado com a mesma alíquota por UF usada no débito, para que a apuração mensal (débito − crédito) seja simétrica e auditável.

**Why P1:** Base para a #85 — a dimensão temporal deve entrar em **um** resolvedor, não em dois serviços.

**Acceptance Criteria:**

1. WHEN a UF aplicável tem alíquota `0,0030 + 0,0020` na tabela e o yml tem `0,0010` THEN `CreditoTributarioService.calcular` SHALL usar `0,0050` (tabela), não `0,0010`.
2. WHEN débito e crédito são calculados para a mesma UF/data THEN ambos SHALL obter a alíquota do **mesmo** componente (`AliquotaIbsCbsResolver`); nenhum dos dois serviços SHALL injetar `AliquotaIbsUfRepository` ou ler `tributosProperties.getIbs()` diretamente.
3. WHEN a UF não é determinável ou não consta na tabela THEN o resolvedor SHALL cair para a alíquota do yml dividida 50/50 (comportamento atual preservado) e logar `WARN`.
4. WHEN a nota é `IMPORTADA` THEN a UF aplicável SHALL ser a do destinatário (= produtor), pois `emitente_uf` não é persistido (ver design §2.3).

**Independent Test:** `CreditoTributarioServiceTest.creditoUsaAliquotaPorUfDivergenteDoYml` — stub do resolvedor devolvendo IBS por UF ≠ yml; asserta `valorCreditoIbs` pelo valor da tabela. Falha no código atual.

---

### P1: Alíquotas com vigência temporal (#85) ⭐ MVP

**User Story:** Como contador, quero que a alíquota aplicada a uma nota seja a vigente na **data de emissão**, para que a reemissão/recálculo de períodos passados e futuros (transição 2026–2033) use o valor correto.

**Why P1:** Sem isso, qualquer nota de 2027+ (ou nota antiga recalculada) sai errada — afeta apuração, dashboard e relatório de conformidade.

**Acceptance Criteria:**

1. WHEN `aliquota_ibs_uf` tem para `MT` as linhas `[2026-01-01, 2026-12-31] = 0,0005+0,0005` e `[2027-01-01, ∅] = 0,0010+0,0010` AND a nota tem `dataEmissao = 2027-03-15T10:00-03:00` THEN o resolvedor SHALL devolver `0,0020`.
2. WHEN a mesma nota tem `dataEmissao = 2026-06-10T10:00-03:00` THEN SHALL devolver `0,0010`.
3. WHEN `aliquota_cbs` tem `[2026-01-01, ∅] = 0,0090` THEN CBS SHALL vir da tabela; WHEN não há linha vigente THEN SHALL cair para `tributosProperties.getCbs().getAliquota()` com `WARN`.
4. WHEN a data de emissão está fora de qualquer vigência da UF (ex.: `2025-12-31`) THEN o resolvedor SHALL aplicar o fallback do yml (§AC3 da #86) e logar `WARN` — não lança.
5. WHEN a data de emissão é convertida para o dia da vigência THEN SHALL usar `America/Sao_Paulo` (uma nota `2026-12-31T23:30-03:00` é 2026; `2027-01-01T01:00Z` é 2026-12-31 em BRT → 2026).
6. WHEN o changeSet `00NN-aliquota-vigencia` roda sobre um banco com as 27 UFs da 0013 THEN todas as linhas SHALL ficar com `data_inicio = 2026-01-01`, `data_fim = NULL` (vigência aberta), sem perda de dados; a 0013 SHALL permanecer intacta.
7. WHEN os testes rodam (profile `test`, Liquibase off) THEN `schema-nota-fiscal-test.sql` SHALL espelhar o novo schema e seed.
8. WHEN o recálculo por evento (#64) ou a projeção do mês corrente (#53) rodam THEN SHALL continuar somando os valores **persistidos por item** — nenhuma alteração em `ApuracaoService`; a vigência atua somente no cálculo (`create`/`update`/`importar`).

**Independent Test:** `AliquotaIbsCbsResolverTest.selecionaVigenciaPelaDataDeEmissao` (unitário, repositório mockado com duas vigências) + `AliquotaVigenciaIntegrationTest` (H2, duas vigências reais inseridas, `POST /api/notas` com `dataEmissao` em 2027 → `valorIbs` pela 2.ª vigência).

---

### P1: Emitente real no XML + limites de nNF/série (#88) ⭐ MVP

**User Story:** Como produtor, quero que o XML da NF-e carregue meu endereço e IE reais e que número/série inválidos sejam rejeitados com erro claro, para que a nota seja aceita pela SEFAZ de homologação na Sprint 7.

**Why P1:** Pré-requisito de qualquer teste em homologação.

**Acceptance Criteria — emitente:**

1. WHEN o produtor tem `inscricaoEstadual`, `endereco.{logradouro, numero, bairro, codigoMunicipioIbge, municipio, cep}` preenchidos THEN o XML SHALL conter esses valores em `emit.IE`, `enderEmit.*` e `ide.cMunFG = endereco.codigoMunicipioIbge` do **emitente**.
2. WHEN `tipoDocumento = NFE` AND `inscricaoEstadual` está em branco THEN `FiscalRulesValidator` SHALL rejeitar com 422 (`produtor.inscricaoEstadual`).
3. WHEN `tipoDocumento = NFA` AND `inscricaoEstadual` está em branco THEN o mapper SHALL emitir `IE = ISENTO` (produtor PF sem IE é o caso de uso da NFA).
4. WHEN qualquer campo obrigatório do endereço do emitente está em branco (NFE ou NFA) THEN `FiscalRulesValidator` SHALL rejeitar com 422, um `FieldViolation` por campo (`produtor.endereco.<campo>`).
5. WHEN `produtor.endereco.codigoMunicipioIbge` não começa com o `cUF` de `produtor.estado` (ex.: estado `MT`, IBGE `3550308`) THEN SHALL rejeitar com 422.
6. WHEN o XML é gerado com o produtor do seed THEN SHALL validar contra o XSD 4.00 sem nenhum literal `SEM ENDERECO`, `S/N`, `99999`, `MUNICIPIO NAO INFORMADO`, `00000000` ou `0000000`.
7. WHEN o `DataLoader` semeia o produtor mock THEN SHALL incluir IE e endereço completo e válido (Cuiabá/MT, IBGE `5103403`, CEP real de 8 dígitos).

**Acceptance Criteria — destinatário:**

8. WHEN `destinatarioEndereco.codigoMunicipioIbge` é informado no request AND não começa com o `cUF` de `destinatarioEndereco.uf` THEN `POST/PUT /api/notas` SHALL responder 400 (Bean Validation, `errors[]`).
9. WHEN o request omite `codigoMunicipioIbge` THEN o rascunho SHALL ser aceito (comportamento atual); a emissão SHALL continuar exigindo-o (já implementado).
10. WHEN o mapper monta `enderDest` THEN SHALL usar o valor do domínio sem fallback `"0000000"` (o validador garante presença).

**Acceptance Criteria — nNF / série:**

11. WHEN `numero > 999.999.999` THEN `POST/PUT /api/notas` SHALL responder 400 (`@Max`), e `FiscalRulesValidator` SHALL rejeitar com 422 na emissão (defesa em profundidade para notas importadas/legadas).
12. WHEN `serie > 999` THEN idem (`@Max(999)` + validador).
13. WHEN `ChaveAcessoGenerator.zeroPad(value, length)` recebe `value.length() > length` THEN SHALL lançar `IllegalArgumentException` em vez de truncar.
14. WHEN `numero = 999.999.999` e `serie = 999` THEN a chave SHALL ser gerada e `nNF`/`serie` da chave SHALL coincidir com `ide.nNF`/`ide.serie` do XML.

**Independent Test:** `NotaFiscalNfeMapperTest.xmlComEmitenteRealValidaXsdSemPlaceholders` + `ChaveAcessoGeneratorTest.zeroPadLancaEmOverflow` + `NotaFiscalControllerValidationTest` (400 para numero/serie/IBGE).

---

## Edge Cases

- WHEN duas vigências da mesma UF se sobrepõem (erro de seed) THEN o resolvedor SHALL escolher a de `data_inicio` mais recente (`ORDER BY data_inicio DESC LIMIT 1`) — determinístico; sobreposição é impedida por PK `(uf, data_inicio)` + `CHECK (data_fim IS NULL OR data_fim >= data_inicio)`, não por exclusion constraint (H2 não suporta).
- WHEN a nota não tem `dataEmissao` (impossível via API — `@NotNull`; possível em fixture de teste) THEN o resolvedor SHALL usar a data atual em `America/Sao_Paulo` e logar `WARN`.
- WHEN `produtor.estado` é nulo (rascunho) THEN o cálculo SHALL usar fallback do yml (hoje já é assim); a emissão rejeita com 422.
- WHEN a nota importada tem `enderDest.UF` diferente de `produtor.estado` (XML de terceiro com endereço divergente) THEN a UF aplicável SHALL ser a de `destinatarioEndereco.uf` (dado do XML prevalece sobre cadastro), com `WARN`.
- WHEN o CPF do emitente tem menos de 11 dígitos THEN `zeroPad` completa; WHEN tem mais de 11 e não é CNPJ (14) THEN lança — antes truncava silenciosamente.

---

## Requirement Traceability

| ID | Story | Fase | Status |
|----|-------|------|--------|
| FISC-01 | #86 AC1–AC2 — resolvedor único IBS por UF | Design | Pending |
| FISC-02 | #86 AC3 — fallback yml 50/50 + WARN | Design | Pending |
| FISC-03 | #86 AC4 — UF aplicável em nota importada = destinatário | Design | Pending |
| FISC-04 | #85 AC1–AC2, AC5 — seleção de vigência IBS por `dataEmissao` (BRT) | Design | Pending |
| FISC-05 | #85 AC3 — tabela `aliquota_cbs` com vigência + fallback | Design | Pending |
| FISC-06 | #85 AC4 — data fora de vigência → fallback, não lança | Design | Pending |
| FISC-07 | #85 AC6–AC7 — changeSet de migração + espelho H2 | Design | Pending |
| FISC-08 | #85 AC8 — apuração/projeção inalteradas | Design | Pending |
| NFE-01 | #88 AC1, AC6–AC7 — endereço + IE reais no XML, `cMunFG` do emitente, seed | Design | Pending |
| NFE-02 | #88 AC2–AC5 — validação do emitente (IE por tipo, endereço, prefixo IBGE) | Design | Pending |
| NFE-03 | #88 AC8–AC10 — IBGE do destinatário coerente com UF; sem fallback | Design | Pending |
| NFE-04 | #88 AC11–AC14 — limites nNF/série + `zeroPad` lança | Design | Pending |

**Coverage:** 12 requisitos, 0 mapeados a tasks (tasks.md é gerado na Fase 3 após aprovação).

---

## Success Criteria

- [ ] Suite: 367 testes atuais verdes + novos (≥ 12) verdes; nenhum teste existente removido, só fixtures ajustadas.
- [ ] Teste de divergência por UF **comprovadamente vermelho** no commit anterior à correção (registrar no PR o hash/saída).
- [ ] `./mvnw spring-boot:run` contra Postgres com a 0013 já aplicada: migração roda sem erro; `SELECT COUNT(*) FROM aliquota_ibs_uf WHERE data_inicio='2026-01-01' AND data_fim IS NULL` = 27.
- [ ] `POST /api/notas/{id}/gerar-xml` com o produtor do seed gera XML sem nenhum placeholder listado em NFE-01 AC6 e válido no XSD.
- [ ] `ApuracaoService`, `DashboardService`, `RelatorioConformidade*` sem diff.
- [ ] CLAUDE.md + `codebase-map` + `project-conventions` atualizados (débito "Produtor sem endereço/IE" removido; "divisão IBS 50/50" já removido na #31).
