# Harness da Ceres contra o ambiente publicado

Mede a latência **real** da Ceres no ambiente publicado (VPS), pela API pública, como o produtor
a sente: login, `POST /api/assistente/chat`, tempo de ponta a ponta. Foi o roteiro usado na
comparação 4B × 0.6B, agora versionado (issue #264).

Não mede número nem citação (isso é do comando `dev,avaliacao`, que conhece o seed); só tempo e
rota, com p50/p95 por rota esperada.

## Uso

Só Python 3 da biblioteca padrão. **URL e credenciais só por variável de ambiente**, nunca no
repositório:

```bash
export AGRO_API_URL='https://<host da demonstração>'   # o nome final (www.), sem redirecionamento:
                                                    # o urllib troca POST por GET num 301
export AGRO_EMAIL='<conta de produtor>'
read -rs AGRO_SENHA && export AGRO_SENHA
# Só se o host tiver o Basic Auth do Caddy (deploy/Caddyfile): o login passa por ele.
export AGRO_BASIC_USUARIO='<usuário>'; read -rs AGRO_BASIC_SENHA && export AGRO_BASIC_SENHA
python3 -I docs/avaliacao/harness-api/harness.py
```

Opcionais: `AGRO_REPETICOES` (padrão 3), `AGRO_IDS` (ex.: `D01,T01,X03`), `AGRO_GABARITO`,
`AGRO_SAIDA` (padrão `docs/avaliacao/resultados`).

O resultado vai para `<saída>/<data>-<hora>-harness-api.json` (hora de Brasília), sem URL nem
e-mail (pode ser versionado). Ele é gravado mesmo se a medição parar no meio (cota esgotada,
Ctrl+C), com o motivo em `interrompida`; falha de rede ou tempo esgotado de uma pergunta vira o
resultado dela (`status` 0), sem derrubar as outras. As respostas são as da conta usada: use a conta de demonstração, nunca a de um
cliente real.

## Cuidados

- Cada pergunta gasta créditos da cota semanal da Ceres (#166). O gabarito inteiro em 3 rodadas são
  ~240 perguntas: confira o saldo em `GET /api/assistente/uso` antes. Com `429
  LIMITE_USO_ASSISTENTE` o harness para.
- O limite de 20 perguntas por minuto por usuário (#150) e a fila da Ceres (#157) devolvem `429`/
  `503` com `Retry-After`; o harness espera e tenta de novo, e o tempo de espera **não** entra na
  latência medida.
- O access token dura 15 minutos (#206); o harness faz login de novo a cada 12.
