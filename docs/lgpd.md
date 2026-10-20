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
| `contador_produtor` | vínculo com produtores | contrato | apagado |
| `credencial_usuario` (e-mail, hash da senha, CPF/CNPJ) | acesso | contrato / legítimo interesse | **apagada** (sessões e links de redefinição vão junto) |
| `sessao_refresh` | sessões (só hash do token) | legítimo interesse (segurança) | apagadas |
| `redefinicao_senha` | links de redefinição (só hash) | contrato | apagados |
| `alerta_tributario` | alertas derivados | contrato | apagados |
| `nota_fiscal`, `nota_fiscal_item`, `guias_fiscais`, `apuracao_mensal` | documentos fiscais (incluem dados de destinatários) | obrigação legal — 5 anos (CTN art. 173) | **mantidos** (LGPD art. 16, I) |
| `assistente_interacao` | metadados da Ceres, sem texto da pergunta/resposta | legítimo interesse | mantidos (não identificam conteúdo) |

## Logs

- Toda mensagem de log passa pela `MascaraDadosPessoais` (conversor do Logback em
  `logback-spring.xml`): CPF, CNPJ, e-mail e telefone viram `[CPF]`, `[CNPJ]`, `[EMAIL]`,
  `[TELEFONE]`. A chave de acesso da NF-e não é mascarada (não é dado pessoal e é necessária
  para investigar rejeições da SEFAZ).
- O IP dos logs de limite de tentativas sai truncado (último octeto / após o /64).
- Senha, token e hash nunca são logados nem ecoados em erro (`@Sensivel`, `toString` dos DTOs).

## Exclusão

`DELETE /api/usuarios/{id}/dados` com `{senha}`: só o próprio titular (404 para outro id), com a
senha atual (mesma reserva antitentativa do login). Anonimiza, apaga a credencial e encerra todas
as sessões. Irreversível. O CPF/CNPJ fica no registro anonimizado, então um novo cadastro com o
mesmo documento é recusado (409); a reativação passa pelo escritório.
