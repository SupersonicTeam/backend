---
name: project-status
description: >
  Levanta o estado atual do projeto AgroContabil em uma passagem só:
  issues abertas, último PR mergeado, contagem de testes e próximos passos.
  Use no início de qualquer sessão de desenvolvimento ou ao precisar de contexto rápido.
---

# Project Status — AgroContabil Backend

Execute os comandos abaixo (em paralelo quando possível) e sintetize o resultado:

## 1. Issues abertas (próxima sprint)

```bash
gh issue list --state open --json number,title,labels --limit 20
```

Filtre por sprint atual. As issues sem milestone são candidatas à próxima sprint.

## 2. PRs mergeados recentemente (contexto do que foi feito)

```bash
gh pr list --state merged --limit 5 --json number,title,mergedAt
```

## 3. Contagem e saúde dos testes

```bash
./mvnw test -q 2>&1 | grep -E "Tests run:|BUILD"
```

Meta do TCC: 140+ testes, BUILD SUCCESS.

## 4. Branch e commits recentes

```bash
git log --oneline -5
git branch --show-current
```

## Síntese esperada

Após rodar os comandos, produza um resumo em ~5 bullets:
- Sprint atual (número + status)
- Última entrega (PR + o que foi implementado)
- Issues abertas ordenadas por prioridade
- Saúde dos testes (X/Y passando)
- Próxima ação recomendada

## Contexto fixo (não precisa buscar toda vez)

- Stack: Java 21 + Spring Boot 3.5.14 + PostgreSQL 16
- Auth: JWT real desde a Sprint 8 (`JwtAutenticacaoFilter`, refresh em cookie); mock `X-Usuario-Id` só em dev/homolog e só sem `Authorization`
- SEFAZ: transporte plugável; `MockSefazTransport` em uso até Sprint 7
- Pacotes principais: `controller/`, `service/`, `nfe/`, `danfe/`, `sefaz/`, `exception/`, `common/`
- Worktrees isolados para cada feature (nunca commitar direto em `main`)
