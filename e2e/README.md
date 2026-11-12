# Testes end-to-end (Playwright)

Suíte E2E dos casos de uso da ERS (issue #321, RNF010), com o navegador de verdade sobre o
frontend Angular e o backend Spring Boot. Fica no backend por decisão do Murillo (2026-11-12); o
repositório do frontend não muda.

| Caso | Arquivo | O que faz |
|------|---------|-----------|
| UC001 Emitir NF-e | `tests/uc001-emitir-nfe.spec.ts` | Carlos cria a nota de venda de soja pela tela, envia para revisão; Patrícia aprova na ficha do cliente; Carlos gera o XML, transmite à SEFAZ (simulada) e vê `AUTORIZADA` |
| UC002 Importar XML | `tests/uc002-importar-xml.spec.ts` | A Maria (fornecedora do `DataLoader`) emite pela API uma NF-e ao Carlos, assinada e autorizada; Carlos sobe o XML na aba "Carregar XML" e a compra aparece autorizada |
| UC003 Ceres | `tests/uc003-ceres.spec.ts` | Carlos clica no atalho "Quanto eu tenho que pagar este mês?" e vê a resposta com a origem dos números; a API confirma rota de dados, sem modelo e sem fallback |
| UC004 Relatório de conformidade | `tests/uc004-relatorio-conformidade.spec.ts` | Carlos baixa o PDF do mês na tela de impostos e o arquivo é um PDF |
| UC005 Cancelar NF-e | `tests/uc005-cancelar-nfe.spec.ts` | Uma nota do Carlos autorizada hoje (preparada pela API) é cancelada pela tela, com justificativa, e fica `CANCELADA` |

Cada caso cria os próprios dados (número de nota aleatório, chave nova) e não depende da ordem. A
API só prepara o que não é o caso de uso em teste e confere o estado final no servidor; o caso de
uso passa pela tela.

## A stack

`compose.yaml` sobe uma stack **isolada** (projeto `agroconia-e2e`): PostgreSQL com pgvector em
tmpfs (começa vazio a cada subida), o backend (profile `homolog`, como a VPS) e o frontend (nginx,
que repassa `/api` ao backend). Só a porta do frontend sai, em `127.0.0.1:18321`. Ela nunca toca nos
containers de desenvolvimento (`agrocontabil-postgres`, `agrocontabil-ollama`) nem na VPS.

- **Seed da banca ligado** (`DEMONSTRACAO_HABILITADA=true`): escritório Contabilidade Rural Oeste,
  Carlos e Patrícia (contas em `docs/demonstracao-banca.md`), mais a Maria do `DataLoader`.
- **SEFAZ simulada** (`MockSefazTransport`) com `autorizar-terceiros`, como na VPS. A UC002 transmite
  a nota do fornecedor antes de importar, então a importação confere o `digVal` de verdade.
- **Sem modelo de IA:** `AI_CHAT_PROVIDER=none`, sem chave de API nem Ollama. As perguntas de atalho
  da tela (#343) respondem sem modelo; o health do Ollama e o do ClamAV ficam `DEGRADED`, o que não
  impede a subida.
- **Limites de taxa altos só nesta stack:** a suíte entra várias vezes por minuto do mesmo IP. Os
  padrões de produção (#150, #208) não mudam.
- **Segredos de teste:** o `rodar.sh` gera no `e2e/.env` (fora do git) a senha das contas
  (`SEED_SENHA`), o `JWT_SECRET`, a `CRIPTO_CHAVE` e a senha do banco, aleatórios e só desta stack.
  Nunca use os da VPS. O trace do Playwright grava a senha digitada no login: é a de teste, de uma
  stack que já não existe quando o artefato é lido.

## Rodar localmente

Precisa de Docker, Node 22+ e Bash (no Windows, o Git Bash).

```bash
cd e2e
npm ci
npx playwright install chromium
bash rodar.sh
```

O `rodar.sh` constrói o backend deste checkout, sobe a stack (o seed da banca leva um ou dois
minutos), roda a suíte e derruba tudo, inclusive quando falha. O log da stack fica em
`e2e/stack.log` e o relatório em `e2e/playwright-report` (`npm run relatorio`).

| Opção | Efeito |
|-------|--------|
| `bash rodar.sh --manter` | não derruba no fim (para abrir `http://localhost:18321` e depurar) |
| `bash rodar.sh --sem-subir` | roda contra a stack já no ar |
| `bash rodar.sh --derrubar` | só derruba |
| `bash rodar.sh -- tests/uc003-ceres.spec.ts --headed` | repassa o resto ao `playwright test` |

| Variável | Padrão | Para quê |
|----------|--------|----------|
| `E2E_BACKEND_IMAGE` | vazio (constrói deste checkout) | testar uma imagem publicada, ex. `ghcr.io/supersonicteam/backend:latest` |
| `E2E_FRONTEND_IMAGE` | `ghcr.io/supersonicteam/frontend:latest` | outra imagem do frontend |
| `E2E_PORTA` | `18321` | porta do frontend no host |

### Imagem do frontend

A imagem do GHCR é privada. Com um token com `read:packages`, `docker login ghcr.io` basta. Sem
ele, construa a imagem do código do frontend e aponte a variável para a tag local (sem `/`, o
`rodar.sh` não tenta baixá-la):

```bash
gh repo clone SupersonicTeam/frontend /tmp/frontend -- --depth 1
docker build -t agroconia-e2e-frontend:local /tmp/frontend
E2E_FRONTEND_IMAGE=agroconia-e2e-frontend:local bash rodar.sh
```

## No GitHub Actions

`.github/workflows/e2e.yml`, **só sob demanda** (`workflow_dispatch`): nunca em PR nem em push, e a
CI normal (`ci.yml`) não roda a suíte. Dispare pela aba Actions ou com:

```bash
gh workflow run e2e.yml --ref <branch>
```

Sem `backend_image`, o backend é o do branch (build com cache do Maven); o frontend vem do GHCR. O
artefato `e2e-playwright` traz o relatório HTML, os traces de todos os casos e o `stack.log`.

## Seletores

Só por papel, rótulo e texto (`getByRole`, `getByLabel`, `getByText`), que aguentam as mudanças de
tela do frontend (frontend#186). Quando faltar nome acessível para um seletor estável, abra issue no
frontend em vez de usar classe CSS. Mudou um rótulo no frontend? O teste quebra no passo que o usa,
com o trace mostrando a tela.

O cenário (CPF do Carlos, comprador da soja) espelha o `CenarioDemonstracao` do backend em
`support/cenario.ts`: mudou lá, mude aqui.
