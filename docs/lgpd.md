# LGPD — dados pessoais no AgroContabil (issue #148)

Mapa do que é dado pessoal no backend, por que é tratado e o que acontece na exclusão.
A versão para o titular é servida em `GET /api/politica-dados`.

## Campos pessoais

| Tabela / campo | Dado | Base legal | Na exclusão (`DELETE /api/usuarios/{id}/dados`) |
|---|---|---|---|
| `produtores.nome_completo` | nome | contrato / obrigação legal | anonimizado ("Titular excluido (LGPD)") |
| `produtores.cpf_cnpj`, `inscricao_estadual`, `estado` | identificação fiscal | obrigação legal (guarda fiscal) | **mantido** (identifica o contribuinte nos documentos guardados) |
| `produtores.email`, `telefone` | contato; o telefone (opcional) pode ser informado no cadastro (#519) | contrato | apagado |
| `produtores.endereco_*` | endereço do emitente | contrato | apagado (o que já está no XML das notas não muda) |
| `produtores.culturas`, `faturamento_anual` | perfil econômico | contrato | apagado |
| `contadores.nome_completo`, `email`, `telefone` | identificação e contato; o telefone (opcional) pode ser informado no cadastro (#519) | contrato | anonimizado / apagado |
| `contadores.cpf_cnpj`, `crc` | identificação da pessoa do contador: o CPF (desde a #519 o cadastro só aceita CPF; contas anteriores podem ter um CNPJ) e o registro no CRC (opcional, informado no cadastro, #519) | contrato | apagados (o contador não é parte dos documentos fiscais; juntos reidentificariam a pessoa no cadastro do CRC) |
| `carteira_vinculo` (#177, substitui `contador_produtor`) | vínculo com produtores, situação, início/fim, observações internas do escritório (ficha) | consentimento do produtor (aceite do convite, art. 7º, I) e contrato | apagado com a ficha e as etiquetas aplicadas — na exclusão do contador **e** na do produtor (o escritório deixa de alcançar a conta encerrada); observações nunca vão ao produtor nem ao log. **Encerramento (#402):** `encerrado_por` (produtor ou escritório), `encerrado_por_membro_id` (autor, zerado quando o membro sai da equipe ou exclui os dados) e `motivo_encerramento` (texto livre opcional, até 500 caracteres, pode conter dado pessoal; o produtor o vê em `GET /api/vinculos`; nunca vai a log, auditoria nem mensagem de erro; apagado com o vínculo na exclusão do produtor) |
| `convite_carteira` (#177) | e-mail convidado (cifrado, #147), situação, validade | legítimo interesse do escritório em convidar; o vínculo só nasce do aceite | apagado 30 dias depois de vencer (`ConviteCarteiraLimpezaJob`), na exclusão do contador e na do produtor (respondidos por ele ou para o e-mail dele) |
| `etiqueta_carteira` (#177) | catálogo de etiquetas do escritório | contrato | apagado com o escritório |
| `escritorio` (#228) | nome e tipo (pessoal ou empresarial, #519); CNPJ, telefone (cifrado, #147) e endereço postal, nos dois tipos: obrigatórios no cadastro empresarial (e no `tornar-empresarial`), opcionais no pessoal, e editáveis pelo administrador na aba Equipe (`PATCH /api/escritorio`). O telefone do escritório pessoal serve a outras funções, como o aviso por WhatsApp. Na conta pessoal o nome é o do contador: **o nome pode ser dado pessoal**. O CNPJ não é único; a titularidade é conferida fora do sistema pela GWNext (`cnpj_verificado_em`/`cnpj_verificacao_metodo`, #520, pelo comando manual, auditado sem o CNPJ). Só o CNPJ **verificado** é mostrado ao produtor convidado ou atendido (o selo, em `/api/convites` e `/api/vinculos`) e vai no e-mail do convite: é o identificador do escritório, não de uma pessoa | contrato | apagado quando o último membro exclui os dados (com a carteira, os convites e o catálogo); com equipe, o membro só sai — e o último administrador de uma equipe precisa antes promover outro (409) |
| `escritorio.slug` e `escritorio_slug_anterior` (#464) | endereço próprio do escritório (`{slug}.agroconia.com.br`) e os anteriores em carência de 90 dias. **Pode ser dado pessoal**: o nome empresarial pode trazer o nome civil | contrato; o administrador registra a ciência da exposição pública (`cienteExposicaoPublica`, auditada como `ESCRITORIO_ENDERECO_ALTERADO`, sem o texto do slug). No cadastro empresarial (#519) a ciência é campo obrigatório do próprio cadastro, que não passa pela auditoria (ainda não há usuário autenticado) | só escritório com CNPJ, e só servido com o CNPJ verificado (#520); nunca vai a log nem à auditoria. Troca ou remoção: o antigo fica bloqueado 90 dias (`liberado_em`) e sai na limpeza diária seguinte (`EscritorioSlugCarenciaLimpezaJob`). Escritório apagado porque o contador entrou noutra equipe: a carência continua, sem o escritório. **Exclusão de dados do titular:** o endereço e os anteriores saem na hora, sem carência. **Limite:** com o certificado sob demanda (opção A), o endereço vai para os registros públicos de Certificate Transparency e a eliminação não os alcança (por isso o aviso e a ciência) |
| `convite_membro` (#228) | e-mail convidado (cifrado), função, só o SHA-256 do token | legítimo interesse do escritório | apagado com o escritório |
| `contadores.escritorio_id`/`funcao_escritorio` (#228) | a que escritório o contador pertence | contrato | zerados ao sair da equipe ou na exclusão |
| `credencial_usuario` (e-mail, hash da senha, CPF/CNPJ) | acesso | contrato / legítimo interesse | **apagada** (sessões e links de redefinição vão junto) |
| `credencial_usuario.termos_aceitos_em`, `termos_versao` (#519) | quando o titular aceitou os termos de uso no cadastro e qual versão (`app.termos.versao`); nulos = aceite não registrado (conta anterior, ou cadastro sem o campo enquanto `app.cadastro.exigir-aceite-termos` está desligado) | execução de contrato (art. 7º, V), como prova do aceite | apagados com a credencial |
| `sessao_refresh` | sessões (só hash do token) | legítimo interesse (segurança) | apagadas |
| `redefinicao_senha` | links de redefinição (só hash) | contrato | apagados |
| `alerta_tributario` | alertas derivados | contrato | apagados |
| `pendencia` (#180) | pedido do escritório ao produtor: título, descrição, observações, prazo, motivo | contrato | apagada na exclusão do produtor e quando o escritório sai |
| `modelo_pedido` (#517) | modelos de pedido do escritório: título, descrição, observações (texto livre do escritório, que pode citar um cliente), tipos e prazo | contrato | apagado pelo administrador e quando o escritório sai (CASCADE e `EquipeEscritorioService`); os textos nunca vão a log, à auditoria (só o id) nem ao `rejectedValue` |
| `notificacao` (#182) | texto do aviso ao produtor (títulos e valores de vencimentos, ou mensagem livre que pode trazer dado pessoal), quem disparou (id e papel), quando | contrato (aviso de prazos) | apagada 1 ano depois (`NotificacaoRetencaoJob`), na exclusão do produtor e quando o escritório que a disparou sai. O telefone não é gravado aqui (vem cifrado de `produtores` na hora) nem vai ao log |
| `pendencia_anexo` (#180) | arquivo enviado pelo produtor (pode conter dado pessoal de terceiros), nome, SHA-256, quem enviou; situação do antivírus e, se infectado, o nome da assinatura (#227 — ClamAV próprio, em container na mesma rede: o arquivo não vai a terceiros) | contrato / obrigação legal (documento de escrituração); segurança (art. 46) na verificação | apagado 5 anos depois do envio (`PendenciaAnexoRetencaoJob`, mínimo 5), na exclusão do produtor e quando o escritório sai. **Infectado:** o conteúdo é descartado na hora (CHECK `ck_pendencia_anexo_conteudo`); fica o registro de segurança (nome, hash, assinatura) pelo mesmo prazo |
| `nota_fiscal`, `nota_fiscal_item`, `guias_fiscais`, `apuracao_mensal` | documentos fiscais (incluem dados de destinatários) | obrigação legal — 5 anos (CTN art. 173) | **mantidos** (LGPD art. 16, I) |
| `assistente_interacao` | metadados da Ceres, sem texto da pergunta/resposta | legítimo interesse | mantidos (não identificam conteúdo) |
| `registro_auditoria` | quem fez o quê (ids pseudônimos, papel, ação, status, código de erro) | obrigação legal / legítimo interesse (RNF012) | **mantido** 5 a 6 anos (até o fim do prazo decadencial, CTN art. 173), sem dado pessoal |

## Logs

- Toda mensagem de log **e todo stack trace** passam pela `MascaraDadosPessoais` (conversores do Logback em
  `logback-spring.xml`): CPF, CNPJ, e-mail e telefone viram `[CPF]`, `[CNPJ]`, `[EMAIL]`,
  `[TELEFONE]`. A chave de acesso da NF-e não é mascarada (não é dado pessoal e é necessária
  para investigar rejeições da SEFAZ). CPF e CNPJ só são mascarados isolados, sem letra ou dígito
  colado (desde o PR #310): sem isso, UUID, hash e `jti` com 11 dígitos seguidos de letra viravam
  `[CPF]`. Limite aceito: CPF grudado em letra sem separador (`cpf52998224725`) passa; qualquer
  separador (`_`, `:`, `=`, parêntese, aspas, hífen, espaço) basta para mascarar.
- **CNPJ alfanumérico da RFB (#313):** `12.ABC.345/01DE-35`, com e sem pontuação, vira `[CNPJ]` no
  log e no stack trace, mas só quando os dígitos verificadores conferem (sem isso, todo hash ou
  base64 de 14 caracteres terminado em 2 dígitos viraria `[CNPJ]`) e só em maiúsculas, o formato
  da RFB e do XML: o log carrega hash em hex minúsculo. Caminho típico: a mensagem do validador do
  XSD sobre um XML importado com o CNPJ novo, que o leiaute em uso ainda não aceita.
- **Acessos da página inicial no Caddy (#484)**, para contar o QR do cartão de visita:
  - **o que entra:** só a raiz `/` do `www`, sem IP, porta, cabeçalhos (cookies inclusive) e sem
    `/api` (que leva CPF/CNPJ na busca), `/healthz` ou as rotas do SPA. Ficam data, URI, status e o
    user agent;
  - **dado pessoal:** nenhum, então não há item na política de dados;
  - **retenção:** 1 MiB no arquivo ativo (~3.000 acessos) e no máximo 3 antigos, apagados 30 dias
    depois (`deploy/Caddyfile`, `deploy/README.md`);
  - **atenção:** reintroduzir IP ou cabeçalhos exige rever a política de dados.
- O IP dos logs de limite de tentativas sai truncado pelos bytes (último octeto / após o /64; IPv4 mapeado em IPv6 vira IPv4 truncado).
- Senha, token e hash nunca são logados nem ecoados em erro (`@Sensivel`, `toString` dos DTOs).

## Exclusão

`DELETE /api/usuarios/{id}/dados` com `{senha}`: só o próprio titular (404 para outro id), com a
senha atual (mesma reserva antitentativa do login). Anonimiza, apaga a credencial e encerra todas
as sessões. Irreversível. O CPF/CNPJ fica no registro anonimizado, então um novo cadastro com o
mesmo documento é recusado (409); a reativação passa pelo escritório. O contador, que não guarda
o documento, pode se cadastrar de novo.

## Provedor externo do modelo da Ceres

Com o modelo local (Ollama, o padrão), nada do que a Ceres processa sai do servidor. Com um
provedor por API (`AI_CHAT_PROVIDER=anthropic` ou `openai`), **cada pergunta é enviada ao
provedor**, que é operador (LGPD, art. 5º, VII) e fica fora do país (transferência internacional,
art. 33). O que vai, sem a continuidade:

- a pergunta **depois do `DataSanitizer`**: sem CPF, CNPJ, chave de acesso, e-mail, telefone nem
  CEP;
- os trechos de lei, que são públicos, e no MISTO os itens de anexo das compras;
- **não** vai o resultado das funções de dados: desde a #266 o texto da resposta de dados é do
  template do backend, e o modelo só escolhe as funções (o registro antigo, "o resultado de cada
  função", deixou de valer ali).

Com a explicação depois da resposta de dados ligada (#268, `AI_EXPLICACAO_HABILITADA`, desligada
por padrão), vai também o texto da resposta de dados já montado pelo backend, depois do
`DataSanitizer`, com o resumo das consultas: são os mesmos dados de negócio acima, já mostrados ao
produtor.

O que não vai: nome, documento, contato e endereço do produtor, e o XML das notas. A política
pública (`GET /api/politica-dados`) declara o compartilhamento em "Provedor do modelo da Ceres,
quando configurado por API". A retenção e o uso dos dados pelo provedor seguem o contrato dele;
conferir os termos da conta antes de ligar para produtores reais.

### Continuidade da conversa (#499)

Atrás da flag `AI_CONTINUIDADE_HABILITADA` (desligada por padrão), para **todos os produtores**
quando ligada (decisão do Murillo, 2026-11-10). Com ela ligada e o provedor por API, vão também ao
provedor, fora do país:

- na **reescrita** ("e algum crédito?" vira a pergunta completa): as até 3 perguntas e **respostas
  inteiras** das trocas anteriores, com os valores, percentuais, datas, números de nota, situação,
  produtos, NCM, referências de lei e os **textos livres** que mostram: o título de um pedido e o
  motivo de uma devolução, escritos por membros do escritório, e a descrição de alerta e de item;
- na **reconferência de lei** ("tem certeza?" depois de lei): a pergunta e a parte de lei da
  última resposta;
- pela pergunta considerada, pedaços disso nas demais chamadas.

O `DataSanitizer` tira CPF, CNPJ, chave de acesso, e-mail, telefone e CEP de cada texto, mas não um
nome escrito num texto livre. O invariante "texto livre nunca vai ao modelo" deixa de valer para o
histórico, por decisão do Murillo, para qualquer conta, inclusive as do cadastro público da VPS.
**Nada é gravado no servidor** (o histórico volta do cliente, assinado), e a exclusão de dados
(#148) **não alcança o que já foi enviado** ao provedor.

É item do relatório de impacto (art. 38). Ligar a flag na VPS exige antes: a política nova no ar
(`app.lgpd.versao-politica` 2026-11-11, que já descreve o fluxo), o aviso na tela da Ceres
(`GET /api/assistente/uso` devolve `conversaVaiAoProvedor`), o **prazo de retenção no provedor
escrito aqui e na política** (pendente: o Murillo confere os termos da organização no Console e
pede retenção zero, se houver) e o art. 33 e o contrato da #498. A subida registra um `WARN` com a
flag ligada e o provedor por API fora de `dev`/`test` (`config/AvisoContinuidadeConfig`).

## Limites conhecidos

- **Reativação do produtor:** não há fluxo no código; o documento retido bloqueia um novo cadastro
  e a reativação, hoje, é manual (pelo encarregado).
- **Alertas recriados:** o job diário de alertas (RN010) volta a gerar alertas a partir das guias e
  notas guardadas do produtor anonimizado. Não carregam dado pessoal, mas ninguém os lê.
- **Máscara de log:** cobre CPF/CNPJ numéricos (com ou sem pontuação), o CNPJ alfanumérico da RFB
  com DV válido e em maiúsculas (#313), e-mail e telefone brasileiro (com DDD entre parênteses, ou
  sem parênteses com o hífen). O alfanumérico em minúsculas passa no log (decisão de 2026-11-11,
  pelo falso positivo com hash em hex).
- **Ceres (`DataSanitizer`):** ainda não reconhece o CNPJ alfanumérico; uma pergunta com ele vai
  ao modelo com o número. A regra, em qualquer caixa, entra depois da rodada real do benchmark e
  antes de 26/11, porque muda a entrada do modelo e a recusa firme, que a banca mede; se a rodada
  atrasar, fica para depois da banca (#313).

## Criptografia em repouso (#147)

`produtores` e `contadores` (`cpf_cnpj`, `email`, `telefone`) e `credencial_usuario`
(`cpf_cnpj`, `email`) são gravados cifrados pela aplicação, com AES-256-GCM determinístico
(`CifraDeterministica`, IV sintético por HMAC): no banco só aparece `v1:` + Base64url. A cifra é
determinística para que as buscas por igualdade e as constraints únicas sigam valendo; o que ela
revela a quem lê o banco sem a chave é só que dois registros têm o mesmo valor. A chave mestra
(`CRIPTO_CHAVE`, 32 bytes em Base64) é obrigatória fora de dev/test; **perdê-la é perder esses
dados**. Linhas gravadas antes da #147 são cifradas na subida (`CifragemLegadoRunner`).

Fora do escopo, de propósito: os dados de destinatário/emitente das notas e o XML assinado (que
traz todos os dados da NF-e) — cifrar só a coluna não protegeria nada enquanto o XML estiver em
claro; fica como débito.

**Texto de busca das notas (#399).** A `nota_fiscal.busca_normalizada`, indexada por trigrama,
guardou o CPF/CNPJ do destinatário da #271 até a #399: uma cópia a mais do documento em claro,
inclusive no índice. Desde a regra 3 da `NormalizacaoBusca` ela leva só número, nome do
destinatário, natureza e descrição e NCM dos itens, e o `BuscaNotasLegadoRunner` reescreve as
notas antigas na subida, em segundo plano. A busca por documento é por igualdade na coluna
`destinatario_cpf_cnpj`, que uma cifra futura das notas cobre como cobre a busca por CPF da
carteira. O documento segue em claro em `destinatario_cpf_cnpj`, `emitente_cpf_cnpj` e
`xml_gerado`, o débito acima. As versões antigas das linhas (até o `VACUUM`) e os backups
anteriores guardam o texto da regra 2 até a rotação deles.
