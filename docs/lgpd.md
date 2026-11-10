# LGPD — dados pessoais no AgroContabil (issue #148)

Mapa do que é dado pessoal no backend, por que é tratado e o que acontece na exclusão.
A versão para o titular é servida em `GET /api/politica-dados`.

## Campos pessoais

| Tabela / campo | Dado | Base legal | Na exclusão (`DELETE /api/usuarios/{id}/dados`) |
|---|---|---|---|
| `produtores.nome_completo` | nome | contrato / obrigação legal | anonimizado ("Titular excluido (LGPD)") |
| `produtores.cpf_cnpj`, `inscricao_estadual`, `estado` | identificação fiscal | obrigação legal (guarda fiscal) | **mantido** (identifica o contribuinte nos documentos guardados) |
| `produtores.email`, `telefone` | contato | contrato | apagado |
| `produtores.endereco_*` | endereço do emitente | contrato | apagado (o que já está no XML das notas não muda) |
| `produtores.culturas`, `faturamento_anual` | perfil econômico | contrato | apagado |
| `contadores.nome_completo`, `email`, `telefone` | identificação e contato | contrato | anonimizado / apagado |
| `contadores.cpf_cnpj`, `crc` | identificação do escritório | contrato | apagados (o contador não é parte dos documentos fiscais; juntos reidentificariam a pessoa no cadastro do CRC) |
| `carteira_vinculo` (#177, substitui `contador_produtor`) | vínculo com produtores, situação, início/fim, observações internas do escritório (ficha) | consentimento do produtor (aceite do convite, art. 7º, I) e contrato | apagado com a ficha e as etiquetas aplicadas — na exclusão do contador **e** na do produtor (o escritório deixa de alcançar a conta encerrada); observações nunca vão ao produtor nem ao log. **Encerramento (#402):** `encerrado_por` (produtor ou escritório), `encerrado_por_membro_id` (autor, zerado quando o membro sai da equipe ou exclui os dados) e `motivo_encerramento` (texto livre opcional, até 500 caracteres, pode conter dado pessoal; o produtor o vê em `GET /api/vinculos`; nunca vai a log, auditoria nem mensagem de erro; apagado com o vínculo na exclusão do produtor) |
| `convite_carteira` (#177) | e-mail convidado (cifrado, #147), situação, validade | legítimo interesse do escritório em convidar; o vínculo só nasce do aceite | apagado 30 dias depois de vencer (`ConviteCarteiraLimpezaJob`), na exclusão do contador e na do produtor (respondidos por ele ou para o e-mail dele) |
| `etiqueta_carteira` (#177) | catálogo de etiquetas do escritório | contrato | apagado com o escritório |
| `escritorio` (#228) | nome e CNPJ do escritório | contrato | apagado quando o último membro exclui os dados (com a carteira, os convites e o catálogo); com equipe, o membro só sai — e o último administrador de uma equipe precisa antes promover outro (409) |
| `escritorio.slug` e `escritorio_slug_anterior` (#464) | endereço próprio do escritório (`{slug}.agroconia.com.br`) e os anteriores em carência de 90 dias. **Pode ser dado pessoal**: o escritório unipessoal nasce com o nome do contador, e o nome empresarial pode trazer o nome civil | contrato; o administrador registra a ciência da exposição pública (`cienteExposicaoPublica`, auditada como `ESCRITORIO_ENDERECO_ALTERADO`, sem o texto do slug) | só escritório com CNPJ; nunca vai a log nem à auditoria. Troca ou remoção: o antigo fica bloqueado 90 dias (`liberado_em`) e sai na limpeza diária seguinte (`EscritorioSlugCarenciaLimpezaJob`). Escritório apagado porque o contador entrou noutra equipe: a carência continua, sem o escritório. **Exclusão de dados do titular:** o endereço e os anteriores saem na hora, sem carência. **Limite:** com o certificado sob demanda (opção A), o endereço vai para os registros públicos de Certificate Transparency e a eliminação não os alcança (por isso o aviso e a ciência) |
| `convite_membro` (#228) | e-mail convidado (cifrado), função, só o SHA-256 do token | legítimo interesse do escritório | apagado com o escritório |
| `contadores.escritorio_id`/`funcao_escritorio` (#228) | a que escritório o contador pertence | contrato | zerados ao sair da equipe ou na exclusão |
| `credencial_usuario` (e-mail, hash da senha, CPF/CNPJ) | acesso | contrato / legítimo interesse | **apagada** (sessões e links de redefinição vão junto) |
| `sessao_refresh` | sessões (só hash do token) | legítimo interesse (segurança) | apagadas |
| `redefinicao_senha` | links de redefinição (só hash) | contrato | apagados |
| `alerta_tributario` | alertas derivados | contrato | apagados |
| `pendencia` (#180) | pedido do escritório ao produtor: título, descrição, observações, prazo, motivo | contrato | apagada na exclusão do produtor e quando o escritório sai |
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
provedor por API (`AI_CHAT_PROVIDER=anthropic` ou `openai`), **cada pergunta e o resultado de cada
função de dados são enviados ao provedor**, que é operador (LGPD, art. 5º, VII) e fica fora do
país (transferência internacional, art. 33). O que vai:

- a pergunta e os resultados das funções **depois do `DataSanitizer`**: sem CPF, CNPJ, e-mail nem
  telefone;
- dados de negócio do produtor que a resposta exige: valores, datas, números de nota, categorias
  de produto e NCM;
- trechos de lei, que são públicos.

O que não vai: nome, documento, contato e endereço do produtor, e o XML das notas. A política
pública (`GET /api/politica-dados`) declara o compartilhamento em "Provedor do modelo da Ceres,
quando configurado por API". A retenção e o uso dos dados pelo provedor seguem o contrato dele;
conferir os termos da conta antes de ligar para produtores reais.

## Limites conhecidos

- **Reativação do produtor:** não há fluxo no código; o documento retido bloqueia um novo cadastro
  e a reativação, hoje, é manual (pelo encarregado).
- **Alertas recriados:** o job diário de alertas (RN010) volta a gerar alertas a partir das guias e
  notas guardadas do produtor anonimizado. Não carregam dado pessoal, mas ninguém os lê.
- **Máscara de log:** cobre CPF/CNPJ numéricos (com ou sem pontuação), e-mail e telefone brasileiro
  (com DDD entre parênteses, ou sem parênteses com o hífen). O CNPJ alfanumérico da RFB ainda não
  é reconhecido.

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
