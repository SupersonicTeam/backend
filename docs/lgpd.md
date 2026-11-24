# LGPD — dados pessoais no AgroContabil (issue #148)

Mapa do que é dado pessoal no backend, por que é tratado e o que acontece na exclusão.
A versão para o titular é servida em `GET /api/politica-dados` (versão 2026-11-17, #498).

> **Limitação declarada (#498).** O texto da política, em especial a base do art. 33 para a Resend
> e a Anthropic, é **decisão da equipe de 2026-11-17, sem revisão jurídica**: a GWNext não tem
> jurídico na equipe do projeto ("não temos jurídico, temos que fazer assim mesmo", Murillo). Nenhuma
> linha deste documento nem da política é parecer. As hipóteses estudadas, o que cada uma exige e o
> risco de cada uma estão na seção "Transferência internacional (art. 33)", abaixo. Quando houver
> advogado, a primeira leitura dele é essa seção.

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

O que não vai: nome, documento, contato e endereço do produtor, e o XML das notas. **O que o
`DataSanitizer` não pega:** o que o produtor escreve na própria pergunta, como um nome por
extenso ("a safra do seu João"), vai ao provedor.

A política pública (`GET /api/politica-dados`, versão 2026-11-17, #498) cita a **Anthropic pelo
nome** como operadora fora do Brasil (campo `operadoresExternos` e as listas de compartilhamento e
retenção), com o que sai, o que não sai, a hipótese do art. 33 e a retenção. A Ceres é opcional:
quem não a usa não tem dado enviado.

### Anthropic: o que o provedor publica (conferido em 2026-11-17)

Fontes: `platform.claude.com/docs/en/manage-claude/api-and-data-retention`, a central de privacidade
(`privacy.claude.com`, artigo 7996866, "última atualização 1º/07/2026") e o DPA em
`anthropic.com/legal/data-processing-addendum`. São páginas públicas do provedor, não o contrato da
conta: o que vale é o que a conta do AgroConIA aceitou (Termos Comerciais e DPA incorporado).

| Ponto | O que está publicado | O que isso permite afirmar |
|---|---|---|
| Papel | O DPA diz que o cliente é controlador e a Anthropic é a operadora, que trata os dados só para prestar o serviço e conforme as instruções do cliente | Operadora (LGPD, art. 5º, VII) |
| Retenção padrão | **As duas páginas oficiais não dizem a mesma coisa.** A central de privacidade diz que apaga entradas e saídas em até **30 dias**. A documentação da plataforma diz, em inglês, que o conteúdo (prompts e saídas) não é retido por padrão (paráfrase nossa), com exceção dos modelos de retenção obrigatória de 30 dias (hoje a família Fable e Mythos; o Haiku 5.5, que a Ceres usa, não está na lista) | Nenhuma das duas é a configuração **da nossa conta**. A política diz "em verificação" e cita o prazo de até 30 dias; nunca "retenção zero" |
| Retenção zero (ZDR) | Só por acordo com a equipe comercial, por organização; não é self-serve | Não temos o acordo; não prometer |
| Conteúdo sinalizado | Se os sistemas automáticos de segurança sinalizarem violação da política de uso: entradas e saídas por até **2 anos**, mesmo com ZDR (documentação da plataforma); a central de privacidade acrescenta pontuações de classificação por até **7 anos** | Entra na política (2 anos) |
| Transferência | O DPA incorpora as cláusulas-padrão da UE (módulos 2 e 3, lei irlandesa), o adendo do Reino Unido e o suíço. Na leitura que fizemos (resumo da página, não o texto integral nem o contrato da conta), **não cita o Brasil nem a LGPD** e não lista os países de processamento nem os subprocessadores (remete a uma lista online) | O DPA público não traz cláusula-padrão da ANPD nem mecanismo próprio da LGPD. **Conferir o texto integral e o contrato da conta** antes de afirmar mais que isso |
| Fim do contrato | Devolve e apaga os dados em até 30 dias do término | — |

**Pendente (o Murillo, no Console, antes de ligar a continuidade na VPS):** em
`platform.claude.com/settings/privacy`, ver a retenção da organização e do workspace usado pela Ceres
e anotar aqui o valor e a data da conferência. Até lá, o prazo da conta fica escrito como "em
verificação" na política, e este documento não afirma retenção zero nem 30 dias como fato da conta.

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
(`app.lgpd.versao-politica` 2026-11-17: descreve o fluxo, o provedor pelo nome e o art. 33), o aviso
na tela da Ceres (`GET /api/assistente/uso` devolve `conversaVaiAoProvedor`, frontend#183) e o
**prazo de retenção da conta** conferido no Console (pendente, acima; a política o diz "em
verificação" até lá). A subida registra um `WARN` com a flag ligada e o provedor por API fora de
`dev`/`test` (`config/AvisoContinuidadeConfig`). O mecanismo do art. 33 da #498 cobre este fluxo no
mesmo texto (hipótese 1, abaixo), mas é aqui que ele fica mais exposto: texto livre escrito por
terceiros (o escritório) vai ao provedor, e a hipótese de "necessidade" é mais difícil de sustentar
para o histórico do que para a pergunta do próprio produtor (ver o risco na redação 1).

## E-mail pela Resend (#460, #498)

Três e-mails saem do sistema (`MensagensEmail`): o link de nova senha (#209), o convite do
escritório ao produtor (#177) e o convite de equipe (#228). Com `EMAIL_MODO=SMTP` (ligado na VPS
desde 09/11) o envio é pela **Resend**, operadora nos Estados Unidos (`deploy/README.md`, seção
"E-mail"). Com `DESLIGADO` (o padrão) ou `LOG` (só dev) nada sai do servidor.

| Item | O que vai e onde fica |
|---|---|
| Enviado à Resend | O **endereço de e-mail do destinatário**; o assunto; o corpo em texto e HTML. Nenhum nome, CPF/CNPJ nem telefone do destinatário entra no e-mail |
| No corpo | **Nova senha:** o link com o token de uso único no fragmento (`#token=`), válido 30 min. **Convite de equipe:** o link com o token, válido 7 dias. **Convite da carteira:** sem token (aceitar exige entrar com o e-mail convidado); leva o nome do escritório (livre; na conta pessoal é o nome do contador) e o CNPJ só se verificado (#520) |
| Rastreamento | Desligado (abertura e clique): o link não é reescrito |
| Onde | Estados Unidos. O domínio de envio está na região de São Paulo (`sa-east-1`), mas a Resend informa, em inglês, que isso só define de onde o e-mail sai e que não há hoje ajuste que mude o lugar onde o dado fica guardado (paráfrase nossa) |
| Retenção na Resend | **30 dias** (planos Free, Pro e Scale; Enterprise é flexível) para o conteúdo e os registros de envio, **inclusive o corpo com o link**. Conta encerrada: o que restar é apagado em até 90 dias (a exclusão pelo painel leva 7 dias e as cópias de segurança, mais 7) |
| Exclusão de um e-mail | Não há endpoint para apagar um e-mail isolado. O pedido vai ao suporte da Resend com o identificador do e-mail (página Emails do painel); e-mail recebido não pode ser removido antes do fim da janela |
| Contrato | DPA nos termos do art. 28 do GDPR, já assinado pela Resend para todas as contas, com as cláusulas-padrão da UE (módulos 1, 2 e 3), o adendo do Reino Unido e a participação no Data Privacy Framework UE–EUA. **A Resend diz expressamente que não assina DPA específico de outro país, e cita as cláusulas da LGPD** (página de GDPR, vista em 2026-11-17; a mesma posição que o `deploy/README.md` registrou em 2026-11-05). O DPA descreve o processamento principal nos EUA, com subprocessadores avisados 14 dias antes |
| Exclusão pelo AgroConIA (#148) | **Não alcança a Resend.** O titular que excluir a conta ainda tem o endereço e o link nos registros dela por até 30 dias |
| Risco do link | O link de nova senha morre em 30 min e no primeiro uso, então o que a Resend guarda por 30 dias já está inerte. O token do convite de equipe vale 7 dias e fica 30 na Resend: quem tiver acesso aos registros dela nos 7 primeiros dias poderia usá-lo para entrar na equipe com aquele e-mail (o cadastro pelo convite não confirma a caixa de entrada). Risco aceito, a registrar na análise de impacto |

Alternativa já estudada se o mecanismo do art. 33 para a Resend não for aceito: **Amazon SES em
`sa-east-1`**, que processa no Brasil e troca só variáveis e DNS (#460). Os registros de DNS da
Resend apontam para `amazonses.com`, ou seja, o envio dela passa pela infraestrutura da AWS, mas ela
guarda o conteúdo nos EUA; com o SES direto, não haveria transferência para fora do Brasil.

## Transferência internacional (art. 33): o que foi decidido e o que não foi

**Decisão da equipe de 2026-11-17, sem revisão jurídica.** O texto da política cita a hipótese
abaixo como a **base em que a GWNext se apoia**; isso é uma escolha de redação, não a conclusão de
que a base é suficiente.

**Fatos que limitam as hipóteses (conferidos em 2026-11-17):**

- A ANPD só regulamentou os mecanismos do art. 33, II (Resolução CD/ANPD nº 19/2024): cláusulas-padrão
  contratuais (Anexo II, **adotadas sem modificação**), cláusulas específicas (aprovação prévia da
  ANPD, só se as padrão forem inviáveis) e normas corporativas globais. O prazo de 12 meses para
  adequar contratos acabou em 23/08/2025. Nenhuma cláusula equivalente de outro país foi aprovada.
  Decisão de adequação (art. 33, I): só a União Europeia (Resolução CD/ANPD nº 32/2026, segundo a
  página da ANPD); os Estados Unidos não têm.
- **A Resend recusa por escrito as cláusulas da LGPD; o DPA público da Anthropic não traz as
  cláusulas-padrão da ANPD** (conferido só no texto público: o contrato da conta pode dizer mais).
  O DPA dos dois usa as cláusulas-padrão da UE, que a ANPD não reconhece como equivalentes. Então o
  caminho do art. 33, II, b, está fechado para a Resend e, até prova em contrário, para a Anthropic.
- Os incisos VIII (consentimento específico e em destaque) e IX (necessidade, ligada ao art. 7º, II,
  V e VI) não dependem de regulamento da ANPD (o regulamento os trata como "outros mecanismos que
  não dependam de regulamentação"), e a ANPD ainda não detalhou como os lê para operadores
  estrangeiros de tecnologia.

### As três redações possíveis

| | **1. Necessidade (IX + art. 7º, V)**, para os dois | **2. Consentimento (VIII)**, para os dois | **3. Mista:** e-mail por IX, Ceres por VIII |
|---|---|---|---|
| Como se escreve | "Transferência necessária para executar o serviço que o titular usa" | "O titular consente, de forma específica e em destaque, com o envio ao exterior" | E-mail como em 1; Ceres como em 2 |
| O que a equipe precisaria ter em mãos | Registro (aqui) de por que o envio é necessário ao serviço: o link de nova senha e o convite só chegam por e-mail, e a Ceres é a funcionalidade que o produtor pediu; minimização (o e-mail sem dado pessoal além do endereço; a pergunta sem documento, contato nem CEP); o DPA de cada operador; política e aviso na tela | Tudo de 1, mais: aviso **antes** do primeiro uso, em destaque, separado do resto dos termos, dizendo que o dado vai para fora do Brasil; **registro do aceite** (quem, quando, qual texto); um caminho sem a transferência para quem recusar; como retirar o consentimento. **Não existe hoje no backend** (só o aceite dos termos, #519, sem texto) nem na tela | O de 1 para o e-mail e o de 2 só para a Ceres |
| Risco | **"Necessário" é lido de forma estrita.** A Ceres roda local (Ollama é o padrão do código) e o e-mail tem alternativa no Brasil (SES `sa-east-1`): a ANPD, ou um titular, pode dizer que a transferência não era necessária, só conveniente. É mais frágil para o histórico da continuidade, que leva texto de terceiros. Sem nenhum outro mecanismo para o e-mail, o risco recai nele | **Para o e-mail o consentimento não é livre:** sem e-mail o titular não recupera a senha nem recebe o convite. O consentimento do VIII é revogável e o titular que recusar precisa de saída: para a Ceres é possível (ela é opcional); para o e-mail, não. Exige trabalho de frontend e backend, com o congelamento em 26/11 | Menos frágil na Ceres; mantém no e-mail o risco de 1. Exige o trabalho de 2 para a Ceres |
| Dá para sustentar **hoje**? | **Sim:** só descreve o que o sistema faz | **Não:** a política afirmaria um aceite que o sistema não coleta | **Só em parte:** a metade do e-mail |

**Recomendada: a 1**, e é a que a política escreve. É a única das três que o texto consegue afirmar
com verdade hoje, sem prometer um aceite que não existe. Ela só afirma a base em que a GWNext se
apoia, junto de tudo que a limita: a política diz que a GWNext "entende" a transferência como
necessária (não que ela esteja provada), que as cláusulas-padrão da ANPD não foram assinadas pela
Resend nem constam no DPA público da Anthropic, que a retenção da Anthropic está em verificação e
que a exclusão não alcança os operadores. **A 3 é o reforço natural da Ceres** (a parte mais exposta), e o aviso do
frontend#183 (`conversaVaiAoProvedor`) já é metade dela; o que falta é o texto de consentimento, o
registro do aceite e a recusa. Se o risco da 1 para o e-mail não for aceito, a saída é trocar para o
SES em `sa-east-1` (sem transferência), não o consentimento.

**O que a política não faz:** não diz que o art. 33 está atendido, não cita "cláusulas-padrão da
ANPD" como adotadas, não promete retenção zero na Anthropic e não promete apagar nos operadores
mais cedo que o prazo deles.

## Termos de uso (#498, #519)

**O texto existe desde 2026-11-24** (decisão do Murillo, "vamos fazer"; **sem revisão jurídica**, como
a política: escrito pela equipe, lido pelo Murillo antes do merge). Mora no backend, como a
política: `TermosUsoService` + `GET /api/termos-de-uso` (público; versão, controlador, contato e dez
seções em texto puro, sem HTML). A página `/termos-de-uso` e o link no texto do aceite do cadastro
são do frontend (contrato no corpo do PR da #498).

- **Versão:** `app.termos.versao` (`TERMOS_VERSAO`), padrão **2026-11-24**, é a data do texto e a
  mesma que o cadastro grava em `credencial_usuario.termos_aceitos_em`/`termos_versao`: o endpoint e
  o aceite leem a mesma propriedade. Mudou o texto, mude a versão.
- **Aceites com `2026-11-11` são anteriores ao texto.** Essa versão foi a provisória da #519 (marcava
  que houve aceite, sem texto). O próprio texto diz isso na seção "Mudanças nestes termos e
  versões". O sistema **não pede reaceite** quando a versão muda: quem aceitou a provisória segue
  com ela gravada.
- **`CADASTRO_EXIGIR_ACEITE_TERMOS` segue `false`** (ligar é passo da VPS, decisão à parte).
- **Não está no texto, de propósito** (decisão da GWNext, listada no PR da #498): foro e lei
  aplicável, limitação ou exclusão de responsabilidade, aceite por uso continuado e reaceite,
  suspensão de conta pela GWNext por abuso, idade mínima e representação, licença e propriedade
  intelectual, guarda de registros de acesso (Marco Civil, art. 15).
- O texto cita só o que o sistema faz hoje: SEFAZ simulada (#263, enquanto durar), escopo do cálculo
  (`docs/premissas-e-escopo.md`), a Ceres, a conta, o escritório, a exclusão e a política.

## Limites conhecidos

- **Reativação do produtor:** não há fluxo no código; o documento retido bloqueia um novo cadastro
  e a reativação, hoje, é manual (pelo encarregado).
- **Alertas recriados:** o job diário de alertas (RN010) volta a gerar alertas a partir das guias e
  notas guardadas do produtor anonimizado. Não carregam dado pessoal, mas ninguém os lê.
- **Máscara de log:** cobre CPF/CNPJ numéricos (com ou sem pontuação), o CNPJ alfanumérico da RFB
  com DV válido e em maiúsculas (#313), e-mail e telefone brasileiro (com DDD entre parênteses, ou
  sem parênteses com o hífen). O alfanumérico em minúsculas passa no log (decisão de 2026-11-11,
  pelo falso positivo com hash em hex).
- **Ceres (`DataSanitizer`):** reconhece o CNPJ alfanumérico da RFB (#313), com e sem pontuação, e o
  troca por `[CNPJ]` antes de a pergunta e os resultados irem ao modelo, à busca da lei e à
  auditoria. Usa a mesma conferência do log (`DocumentoFiscalValidator.isCnpjAlfanumericoNoTextoValido`,
  só com os dígitos verificadores conferidos), mas **em qualquer caixa**, ao contrário do log: lá o
  hash em hex minúsculo gera falso positivo, aqui o texto é a pergunta do usuário e deixar um CNPJ
  passar ao provedor custa mais que mascarar um token por engano (decisão de 2026-11-11). Limite
  aceito, o mesmo do log: o CNPJ alfanumérico colado em letra sem separador (`cnpj12ABC34501DE35`)
  passa, porque a janela de 14 posições sem esse limite casaria o fim de qualquer palavra longa
  (a regra numérica da Ceres, sem letras no formato, não tem o problema). Como o
  marcador é o mesmo do numérico, a pergunta com o CNPJ alfanumérico de outra pessoa também conta
  como dado de pessoa na recusa firme (`SinalRecusaFirme`). Entrou depois da rodada oficial do
  benchmark de 14/11/2026, que mediu a Ceres sem ele.

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
