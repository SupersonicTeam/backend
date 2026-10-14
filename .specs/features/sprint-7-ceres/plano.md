# Sprint 7 — plano de execução

Data: 2026-10-14. Issues com label `sprint-7` + a #128 (SEFAZ real, título "[Sprint 7]").

Regra do plano: um PR por issue, na ordem abaixo, cada um deployável sozinho. Depois de cada PR,
code review com foco em lógica, segurança, regra de negócio, vazamento de recurso e de dado.

## Ordem e motivo

| # | Issue | Por que nesta posição | Risco principal |
|---|-------|-----------------------|-----------------|
| 1 | #169 Total dos vencimentos/notas | Menor, sem schema, destrava pergunta comum | Soma com escopo errado (rascunho/cancelada contando como venda) |
| 2 | #170 MISTO sem afirmação sobre dados | Corrige resposta enganosa já em produção de demo | Parte de legislação afirmando sobre compras do produtor |
| 3 | #146 Modo fallback (busca textual) | Ceres útil com o Ollama fora; usa só a base já ingerida | Busca textual sem vigência; `modoFallback` mentindo |
| 4 | #140 Alertas tributários (RN010) | Job + endpoint + função; reusa ShedLock e anti-IDOR | Alerta de outro produtor; job sem lock |
| 5 | #166 Cota semanal de créditos | Maior e mais sensível (concorrência, 429 antes do SSE) | Reserva não atômica; vazamento de reserva em falha |
| 6 | #145 Retenção interestadual (RN009) | Regra fiscal precisa de definição; depende do RAG | Regra fiscal inventada sem base legal |
| 7 | #158 Avaliação com gabarito | Depende do seed rico (#155, Sprint 10) e do Ollama real | Métrica não reprodutível |
| 8 | #128 SOAP + mTLS real | Depende de certificado ICP-Brasil e credenciamento | Classificação errada de falha (envio duplicado) |

## Decisões tomadas sem consulta (registradas para revisão)

- **#169:** sem filtro de situação, o `valorTotal` de `listarNotas` soma só as notas
  **AUTORIZADAS**. Somar rascunho, rejeitada e cancelada daria um "quanto vendi?" errado e com
  fonte, que o `ValidadorNumeros` aceitaria. O campo `abrangenciaTotal` diz ao modelo o que entrou.
  Com filtro de situação, a soma é do próprio filtro.
- **#169:** a soma é um método novo da porta (`valorTotalNotas`, `valorTotalVencimentosNaoPagos`),
  não um campo novo em `Pagina`: `Pagina` é genérica e não tem valor monetário.

## Bloqueios externos conhecidos

- **#158** precisa do seed da #155 para o valor esperado de cada pergunta.
- **#128** precisa de certificado A1 ICP-Brasil e credenciamento em homologação; dá para entregar o
  transporte testado contra WireMock, mas não o critério "cStat reais".
- **#145** precisa da definição de RN009 (quais operações sofrem retenção e como calcular).
