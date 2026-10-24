# Backlog pós-Sprint 8 — plano de execução

Revisão de 2026-10-24 das issues sem sprint: features #174–#182 e #89, segurança #221–#225.
As de segurança ficam **por último**, num lote só: o que as features abaixo revelarem de
segurança vira issue nova e entra nesse lote.

## Decisões (Murillo, 2026-10-24)

- **Produtor-alvo do contador:** o contador escolhe um produtor da carteira (#177) e o id vai
  **no path** — `/api/contador/produtores/{produtorId}/...` —, sempre pelo
  `ProdutorEmContextoResolver` (404 fora da carteira). Vale para #178, #180, #182 e, no lote de
  segurança, para a #224.
- **#178:** produtor **sem** contador vinculado emite direto; a aprovação só é exigida com linha
  em `contador_produtor`. Rascunhos já existentes de quem tem contador entram no fluxo novo.
  Contador **não edita**: devolve com motivo, e os motivos têm histórico (padrão de
  `nota_fiscal_validacao`).
- **#180:** entra em **versão mínima** — pendência com ciclo completo + anexo com tipo e tamanho
  limitados. Contrato próprio (lista de tipos, não string JSON), combinado com o frontend.
- **#182:** **registro no banco + `GET` do histórico**, sem provedor real de WhatsApp; o contador
  também dispara a cobrança dos produtores da carteira.

## Ordem

| Passo | Issue | Tamanho | Depende de | Observação |
|---|---|---|---|---|
| 0 | #176 fechar · #175 resto | P | — | #176 já resolvida pelo `GuiaFiscalStatusSyncJob`. #175: o guarda já existe (`RotaLegislacao` → `SEM_FUNDAMENTO` com índice vazio); falta WARN na subida e o passo de ingestão no roteiro do ambiente |
| 1 | #174 filtros de `/api/notas` | M | — | `Specification`; `translate()` sem acento (como a Ceres); faixa invertida → 400; índice da #102 já cobre |
| 2 | #179 faixa de `/api/apuracao` | P | — | Teto de 12 meses. **Não expor `id` no `ApuracaoResponse`** (só depois da #224) |
| 3 | #89 preview de DANFE | P/M | — | Parâmetro de marca d'água no `.jrxml`, chave em branco, só RASCUNHO/COM_INCONSISTENCIAS |
| 4 | #177 carteira + padrão de path | M | — | Lista com resumo, busca/filtro/ordenação, vínculo por convite com aceite do produtor, ficha do cliente; fixa o padrão `/api/contador/produtores/{produtorId}/...` |
| 4b | #228 escritório com equipe | G | #177 | Funções, responsável por cliente, migração do contador para escritório unipessoal; daqui em diante o dono é o escritório |
| 5 | #181 obrigações no calendário | M | #228 | Catálogo fixo por `RegimeTributario`, ocorrências dentro da janela `de`/`ate`; reaproveitar a regra do `AlertaTributarioService`; uniformizar a grafia de status (resto da #176) |
| 6 | #178 aprovação | G | #177 | Estados `AGUARDANDO_APROVACAO`, `APROVADA`, `DEVOLVIDA`; definir o que acontece se `gerar-xml` falhar numa nota aprovada; `@Auditado` nas transições |
| 7 | #180 pendências e documentos | G | #177 | Prazo de pendência entra no calendário como `solicitacao`; upload com MIME/tamanho limitados |
| 8 | #182 notificação | M | #181 | Registro + `GET`; contador dispara pela carteira |
| 9 | Segurança: #221 + #222 + #225 → #224 → #223 + achados novos | M/G | 4–8 | #221/#222/#225 são config (um PR; #222/#225 também no compose de orquestração). #224 usa o padrão do passo 4. #223 é a maior |

Depois da #180: backend#227 (antivírus nos anexos). Frontend: SupersonicTeam/frontend#94
(contrato da #182).

Ordem de corte se faltar tempo para a defesa: #227 → #180 → #182 → #178.

## Decisões por issue

Fechadas em [`decisoes-por-issue.md`](decisoes-por-issue.md) (contrato, estados, limites e o
que cada uma deixa de fora). Segunda rodada do Murillo, 2026-10-24: falha do `gerar-xml` derruba
a aprovação; envio para revisão valida antes; anexos no banco (PDF/JPEG/PNG/XML, 5 MB, 5 anos);
catálogo de obrigações **em tabela configurável pelo contador**.

## Regra para os PRs de #177, #178, #180 e #182

São os que criam superfície nova de contador agindo sobre dados do produtor. Cada um fecha com
security review; achado vira issue `[Segurança]` e entra no passo 9, sem corrigir no meio da
feature (salvo IDOR na própria rota nova, que bloqueia o merge).
