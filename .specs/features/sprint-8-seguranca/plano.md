# Sprint 8 — plano de execução (login, JWT e segurança)

Data: 2026-10-19. Issues com label `sprint-8`: #147 (AES-256), #148 (LGPD), #149 (auditoria),
#150 (rate limiting). Login, cadastro, JWT e recuperação de senha não tinham issue; foram criadas
nesta data (ver a tabela) a pedido do Murillo.

Regra do plano: um PR por issue, na ordem abaixo, cada um deployável sozinho. Depois de cada PR,
code review de qualidade **e** de segurança (OWASP Top 10 / ASVS nível 2 para autenticação e
sessão), achados corrigidos no próprio PR, CI verde e review do Copilot antes do merge.

## Decisões do Murillo (2026-10-19)

- **Login por e-mail.** O cadastro exige **CPF/CNPJ obrigatório**, com dígito verificador.
- Os PRs são mergeados na `main` depois do review, um de cada vez.

## Ordem e motivo

| # | Issue | Entrega | Por que nesta posição | Risco principal |
|---|-------|---------|-----------------------|-----------------|
| 1 | #205 | `credencial_usuario` + `POST /api/auth/cadastro` | Sem credencial não há login | Tomada de conta pelo CPF de produtor já existente; hash fraco; enumeração |
| 2 | #206 | `POST /api/auth/login`, filtro Bearer, `GET /api/auth/me` | Troca a identidade declarada pela provada | `alg` não fixado; segredo fraco; mock aceitando Bearer inválido; timing de e-mail inexistente |
| 3 | #207 | refresh opaco rotativo em cookie HttpOnly, logout | Access token curto sem obrigar novo login | Reuso de refresh roubado; refresh em texto puro no banco |
| 4 | #208 | bloqueio temporário por conta + limite por IP no login | Login exposto sem freio | Bloqueio virar DoS de conta alheia; IP forjado por `X-Forwarded-For` |
| 5 | #209 | esqueci / redefinir / alterar | Precisa do refresh para revogar sessões | Token previsível ou reutilizável; enumeração por resposta |
| 6 | #150 | rate limit geral e do chat, headers de segurança | Usa o mesmo mecanismo do item 4 | Limite por IP atrás de proxy |
| 7 | #148 | LGPD: anonimização, política, CPF/CNPJ mascarado em log | Precisa da credencial para o direito ao esquecimento | Apagar dado fiscal com guarda legal de 5 anos |
| 8 | #149 | auditoria com retenção de 5 anos | Precisa da identidade provada para "quem fez" | Gravar dado pessoal no log de auditoria |
| 9 | #147 | AES-256-GCM em repouso | Último: mexe em colunas consultadas | Busca por CPF/CNPJ quebrar com coluna cifrada |

## Decisões técnicas (tomadas sem consulta, registradas para revisão)

### Credencial
- Tabela própria `credencial_usuario`, separada de `produtores`/`contadores`: uma linha por
  identidade, com `papel` e **exatamente uma** das FKs (`produtor_id` / `contador_id`), garantido
  por CHECK. Mantém o agregado do produtor (emissão, apuração) intocado.
- E-mail normalizado (trim + minúsculas, `Locale.ROOT`) e único. CPF/CNPJ só dígitos, único por
  papel (a tabela de origem já guarda).
- **CPF/CNPJ que já existe em `produtores`/`contadores` não é "reivindicado" pelo cadastro**: quem
  soubesse o CPF de um produtor tomaria a conta dele e os dados fiscais. Responde 409 genérico.
  Vincular credencial a cadastro preexistente é ato administrativo (issue futura).
- Hash: `DelegatingPasswordEncoder` com **BCrypt custo 12** como padrão (o prefixo `{bcrypt}` permite
  migrar para Argon2 sem invalidar senhas). BCrypt ignora o que passa de 72 bytes: a política
  rejeita senha acima de 72 bytes UTF-8 em vez de truncar em silêncio.
- Política de senha (NIST 800-63B): 10 a 72 bytes, sem regra de composição, recusa senha que
  contém o e-mail ou o CPF/CNPJ e uma lista de senhas comuns embutida.

### JWT
- jjwt 0.12.6 (já no `pom.xml`). HS256 com segredo de `JWT_SECRET`; a aplicação **não sobe** com
  segredo ausente, abaixo de 32 bytes ou com o valor de exemplo do `.env.example`.
- O parser fixa a chave (e, com ela, o algoritmo): `alg: none` e troca de algoritmo falham.
  `iss` e `aud` exigidos; `exp` curto (15 min); `jti` aleatório; tolerância de relógio 30 s.
- `sub` = id da credencial. A cada requisição o filtro recarrega a credencial: conta desativada
  ou sessões revogadas derrubam o token na hora: o token leva a `versao_sessao` da credencial, e
  troca/redefinição de senha a incrementam (contador, não timestamp: o `iat` tem resolução de segundo).
- Bearer presente e inválido → 401, **sem** cair no mock. O `MockIdentidadeFilter` só age quando
  não há `Authorization`; fica em `dev`/`homolog` enquanto o Angular não tem tela de login.

### Refresh
- Token opaco de 256 bits (`SecureRandom`), guardado como SHA-256. Rotação a cada uso; reuso de um
  refresh já trocado revoga a família inteira (detecção de roubo). Validade 7 dias.
- Entregue em cookie `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/auth` — o JavaScript do
  frontend nunca o lê (XSS não exfiltra a sessão longa). Atributos por propriedade.

### Força bruta e rate limit
- 5 falhas seguidas → conta bloqueada por 15 min (resposta igual à de senha errada até o bloqueio,
  depois 423 genérico sem dizer se a senha estava certa). Login com sucesso zera o contador.
- Limite por IP com bucket4j (já no `pom.xml`) em memória. O IP vem do `remoteAddr`; só se
  `server.forward-headers-strategy` estiver ligado atrás de proxy confiável o `X-Forwarded-For` vale.

### Recuperação de senha
- `esqueci` responde sempre 202, exista ou não o e-mail. Token de 256 bits, SHA-256 no banco, uso
  único, 30 min. Redefinir revoga todos os refresh e invalida os access tokens emitidos antes.
- Envio por porta (`EnvioEmail`); em `dev` o link vai para o log só com flag explícita, nunca em
  `prod`. SMTP real por `spring.mail.*`.

## Bloqueios externos conhecidos

- O Angular ainda não tem tela de login (SupersonicTeam/Agrocontabil). O mock por header continua
  em `dev`/`homolog` para não quebrar as telas.
- E-mail real depende de um SMTP; sem ele a recuperação de senha só funciona em `dev`.
