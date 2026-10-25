# Backlog pós-Sprint 8 — decisões por issue

Fecha os pontos em aberto de cada issue para o desenvolvimento começar sem nova rodada de
perguntas. Ordem e decisões gerais em [`plano.md`](plano.md).

Legenda: **[Murillo]** = decidido pelo Murillo em 2026-10-24. **[padrão]** = escolha proposta
na revisão, não discutida item a item; vale até alguém mudar aqui.

Regras que valem para todas:

- Contador age sobre um produtor por `/api/contador/produtores/{produtorId}/...`, sempre pelo
  `ProdutorEmContextoResolver` (404 fora da carteira). **[Murillo]**
- Produtor age sobre si mesmo pelas rotas atuais (sem `produtorId`). O produtor **não é
  escolhido pelo cliente**: vem do token (JWT `sub` → credencial → produtor) pelo
  `AuthenticatedProdutorProvider`, e toda consulta filtra por esse id (`findByIdAndProdutorId`,
  404 para recurso de outro). Não há parâmetro que o produtor possa trocar para ver dados de
  outro. Contador nessas rotas continua 403. **[padrão]**
- Toda rota nova (produtor ou contador) tem teste de integração com **dois produtores**: A não
  enxerga nem altera nada de B (404). **[padrão — nasceu da dúvida do Murillo sobre isolamento]**
- Lista devolve array + total em `X-Total-Count`, como guias e auditoria (#103). **[padrão]**
- Toda escrita nova que muda estado fiscal ou de fluxo leva `@Auditado`. **[padrão]**

---

## #175 — legislação no ambiente de demonstração

Conferido em 2026-10-24 no banco local: base carregada (LC 214: 5.004, EC 132: 538,
LC 227: 2.211, Lei 8.212: 547, Lei 9.528: 4). Com índice vazio a Ceres já responde o texto fixo
`SEM_FUNDAMENTO`, sem afirmar nada (`RotaLegislacao`).

Falta:
- WARN na subida quando `legislacao_trecho` está vazia (só com a Ceres habilitada). Não derruba
  a subida nem o health. **[padrão]**
- Passo de ingestão no roteiro de montar o ambiente (README). **[padrão]**

## #176 — guia vencida

Resolvida pelo `GuiaFiscalStatusSyncJob`; fechar. A grafia diferente entre `/contador/guias`
(`VENCIDA`) e o calendário (`vencido`) **fica como está**: o frontend já traduz as duas, e mudar
agora quebraria o contrato à toa. **[padrão]**

## #174 — filtros em `GET /api/notas`

- Contrato exatamente o da issue (`status` repetível, `tipoDocumento`, `emitidaDe`, `emitidaAte`,
  `busca`).
- `Specification` montada **sobre** o predicado `produtor_id = autenticado`, nunca no lugar.
- `emitidaAte` inclusivo: `dataEmissao < (emitidaAte + 1 dia) 00:00 America/Sao_Paulo`.
- **`busca` otimizada por índice, não por varredura. [Murillo: "bem otimizada"]**
  - Coluna nova `nota_fiscal.busca_normalizada`: número + nome do destinatário + natureza da
    operação, em minúsculas e sem acento, mantida pela aplicação no `@PrePersist`/`@PreUpdate`
    (mesma regra nos dois bancos; H2 sem coluna gerada). O termo buscado passa pela mesma
    normalização, em Java.
  - Índice **GIN trigrama** composto `(produtor_id, busca_normalizada gin_trgm_ops)` — extensões
    `pg_trgm` e `btree_gin`, as duas disponíveis na imagem atual (conferido 2026-10-24). Atende
    `LIKE '%termo%'` sem ler a tabela do produtor inteira. ChangeSet novo com backfill das notas
    existentes; no H2 só a coluna.
  - Termo só de dígitos também casa `numero` por prefixo (índice btree já existente).
  - Mínimo **3** caracteres (o trigrama não ajuda abaixo disso), máximo 100; abaixo de 3 a busca
    é ignorada, exceto termo numérico.
  - **Meta medida, como na #102:** base com 200 mil notas (20 mil de um produtor),
    `EXPLAIN (ANALYZE, BUFFERS)` da consulta e do `count` usando o índice GIN, consulta abaixo
    de 50 ms no banco e `GET /api/notas?busca=` com p95 abaixo de 300 ms. Resultado no PR.
  - Pedido ao frontend: *debounce* de 300 ms no campo, para não disparar uma consulta por tecla.
  - Ressalva: `busca_normalizada` guarda nome do destinatário em claro. Se a cifra dos dados das
    notas (débito da #147) for adiante, essa coluna precisa entrar na mesma decisão — busca
    parcial e cifra determinística não convivem.
- Faixa invertida → 400 `FILTRO_NOTAS_INVALIDO` com `field` = `emitidaDe`. **[padrão]**
- `sort` só aceita propriedades da lista branca (`dataEmissao`, `numero`, `status`,
  `valorTotal`, `createdAt`); fora dela, 400. Hoje uma propriedade desconhecida pode virar 500 —
  conferir antes e, se for, já sai corrigido aqui. **[padrão]**
- Sem índice novo (o `(produtor_id, status, data_emissao)` da #102 cobre).

## #179 — faixa em `GET /api/apuracao`

- `?de=AAAA-MM&ate=AAAA-MM` → `ApuracaoResponse[]` em ordem crescente; `?periodo=` intacto.
  `periodo` junto com `de`/`ate` → 400.
- **Faixas práticas: semestre, 1 ano, 3 anos e 5 anos. [Murillo]**
  - Atalho `?ultimos=6|12|36|60` (meses, terminando no mês corrente); outro valor → 400.
  - Faixa livre `de`/`ate` com teto de **60 meses** (5 anos, a mesma guarda legal dos documentos
    fiscais). Combinar `ultimos` com `de`/`ate`, faixa invertida ou acima do teto → 400
    `FAIXA_APURACAO_INVALIDA`.
  - `?agrupamento=MES|TRIMESTRE|SEMESTRE|ANO` (padrão `MES`): 5 anos por mês são 60 pontos,
    por ano são 5. Grupo soma débito, crédito e saldo dos meses; `status` do grupo é `FECHADO`
    só se todos os meses estiverem fechados. Grupo incompleto (ano corrente) vem marcado
    `parcial: true`. **[Murillo, aprovado 2026-10-24: visão geral sem estender o conteúdo]**
- Desempenho com 60 meses: **uma** consulta para as apurações persistidas da faixa e **uma**
  agregação `GROUP BY mês` para os meses ainda não consolidados — nunca uma projeção ao vivo por
  mês. **[padrão]**
- Mês sem nota aparece na lista (a faixa não tem buraco), com zeros e **`totalNotas: 0`**.
  O frontend mostra **"Sem notas emitidas"** quando `totalNotas == 0` — distinto de um mês
  com notas e imposto zero. `totalNotas` vale também no `?periodo=` e soma no grupo.
  **[Murillo]**
  - **Reaproveita o `totalNotas`, sem campo `quantidadeNotas`. [Murillo, 2026-10-24]** O
    `ApuracaoResponse` já tinha `totalNotas` (saídas autorizadas + entradas importadas
    autorizadas), que já saía 0 no mês sem nota; um segundo campo com o mesmo número só
    duplicaria o contrato.
- **Formato do item. [Murillo, 2026-10-24]**
  - `periodo` do grupo: `2026-07` (MES), `2026-T1` (TRIMESTRE), `2026-S1` (SEMESTRE) e `2026`
    (ANO).
  - Todo item, inclusive o do `?periodo=`, traz `inicio` e `fim` (`AAAA-MM`, os meses que a faixa
    cobre de fato) e `parcial`. No MES, `inicio` = `fim` = o mês.
  - `dataFechamento` do grupo = o fechamento mais recente dos meses, preenchido só quando todos
    fecharam; senão `null`.
- **Não expor `id`** no `ApuracaoResponse` (só depois da #224).

## #89 — preview de DANFE de rascunho

- `GET /api/notas/{id}/danfe-preview` (produtor) e
  `GET /api/contador/produtores/{produtorId}/notas/{id}/danfe-preview` (contador, para a revisão
  da #178). **[padrão]**
- Só `RASCUNHO`, `COM_INCONSISTENCIAS`, `AGUARDANDO_APROVACAO`, `APROVADA` e `DEVOLVIDA`; outro
  status → 409 (lá já existe o `/pdf` de verdade).
- Marca d'água "RASCUNHO — SEM VALOR FISCAL" por parâmetro no `.jrxml`; chave de acesso e
  protocolo em branco. Nada é gravado, nada é assinado.
- Não roda o `FiscalRulesValidator`: preview de nota incompleta é justamente o caso de uso.

## #177 — carteira do contador

Escopo ampliado **[Murillo]**: vínculo por convite, busca/filtro/ordenação e ficha do cliente,
além da lista com resumo. A equipe do escritório é a #228, logo em seguida.

**Lista**
- `GET /api/contador/produtores` → `[{id, nomeCompleto, cpfCnpj, tipoProdutor, regime, uf,
  situacaoVinculo, etiquetas[], atencao{...}}]`; produtor → 403 `PAPEL_NAO_AUTORIZADO`.
- `cpfCnpj` completo: é o cliente do próprio escritório. **[padrão]**
- `atencao`: `notasComInconsistencia`, `notasAguardandoAprovacao`, `guiasVencidas`,
  `vencimentosProximos7Dias` (e `pendenciasEnviadas` quando a #180 entrar), por consulta
  agregada (`GROUP BY produtor_id`), nunca N consultas. **[padrão]**

**Busca, filtro e ordenação [Murillo]**
- `busca`: parcial no **nome** (mesma normalização da #174). CPF/CNPJ só por **igualdade**
  (documento completo, com ou sem máscara): a coluna é cifrada de forma determinística (#147),
  então busca parcial nela é impossível — limitação aceita, não contornar decifrando no Java.
- Filtros: `regime`, `tipoProdutor`, `uf`, `etiqueta` (repetível), `situacao` (padrão `ATIVO`),
  `precisaAtencao=true`; `responsavel` (`eu` / id / `nenhum`) entra com a #228.
- `sort`: `nome` (padrão) ou `urgencia` (guias vencidas, depois inconsistências, depois
  vencimentos próximos). Paginação `page`/`size` (padrão 50, máx. 200) + `X-Total-Count`.

**Vínculo por convite [Murillo]** — hoje a carteira só nasce no seed.
- `POST /api/contador/convites` `{email}` → **sempre 202**, exista ou não conta com aquele
  e-mail (sem oráculo de enumeração, como o `esqueci` da #209). Convite expira em 14 dias;
  reenviar substitui o anterior. **[padrão]**
- Produtor com conta: vê em `GET /api/convites` e responde `POST /api/convites/{id}/aceitar` ou
  `/recusar`. Sem conta: recebe e-mail; o convite aparece depois do cadastro com aquele e-mail.
  O vínculo **só existe depois do aceite** do produtor (consentimento; LGPD art. 7º).
- Nunca vincular por CPF/CNPJ informado pelo contador: quem soubesse o documento puxaria o
  cliente (mesmo motivo do cadastro da #205).
- Tabela nova de vínculo (substitui `contador_produtor`, com migração das linhas atuais como
  `ATIVO`): `situacao` `ATIVO` / `SUSPENSO` / `ENCERRADO`, `inicio`, `fim`. **[padrão]**
  - `SUSPENSO` (pelo escritório): sai do alcance do `ProdutorEmContextoResolver` (404), mas a
    ficha fica; o escritório reativa sem novo convite.
  - `ENCERRADO` (pelo produtor ou pelo escritório): acesso cortado na hora; voltar exige novo
    convite. Linha mantida como histórico.
- `@Auditado` em convidar, aceitar, recusar, suspender, reativar e encerrar.

**Ficha do cliente [Murillo]**
- `GET`/`PATCH /api/contador/produtores/{produtorId}/ficha`: início do vínculo, observações
  internas (até 1000 caracteres, **nunca visíveis ao produtor**, mascaradas no log) e
  etiquetas (catálogo do escritório, até 10 por cliente, `PUT .../etiquetas`). **[padrão]**
- Na exclusão LGPD do produtor (#148) a ficha é apagada junto com o vínculo.

**Leitura das notas**
- Nasce aqui `GET /api/contador/produtores/{produtorId}/notas` (+ `/{id}` e `/{id}/validacoes`),
  só leitura, com os filtros da #174. É pré-requisito da revisão da #178.

## #228 — escritório com equipe (nova, entra logo após a #177)

**[Murillo]**: issue própria, para #181, #178, #180 e #182 já nascerem com o **escritório como
dono**. Escopo completo na issue (SupersonicTeam/backend#228). Pontos que afetam as outras:

- Funções `ADMINISTRADOR`, `CONTADOR`, `ASSISTENTE`; contador num escritório só (v1). Todo
  membro vê a carteira inteira; cada cliente tem um responsável.
- Contador existente vira administrador de um escritório unipessoal (changeSet de migração).
- **A partir da #228, onde este documento diz que o contador é dono** (catálogo de obrigações,
  carteira, ficha, etiquetas, pendências, disparos de notificação), **leia "escritório"**; a
  ação continua registrada com o membro que agiu.
- `ASSISTENTE` não aprova nota (#178) nem reabre apuração; administrador é quem gerencia
  catálogo de obrigações e equipe.

## #181 — obrigações no calendário

**Catálogo em tabela, configurável pelo contador. [Murillo]**

- Tabela `obrigacao_fiscal` (changeSet novo): `contador_id` (dono), `titulo`, `descricao`,
  `periodicidade` (`MENSAL` / `ANUAL`), `dia` (1–31), `mes` (1–12, só `ANUAL`),
  `ajuste_fim_de_semana` (`NENHUM` / `ANTECIPA` / `POSTERGA`), `tipo_produtor` e
  `regime_tributario` (ambos nulos = vale para todos), `origem` (texto livre, ex. "LC 214/2025,
  art. ..."), `ativa`. **[padrão]**
- Dono é o **contador**: ele cadastra as obrigações, que valem para os produtores da carteira
  dele que casarem com o filtro de tipo/regime. Dois contadores do mesmo produtor = obrigações
  dos dois. **[Murillo]**
- CRUD em `/api/contador/obrigacoes` (só contador; produtor 403; obrigação de outro contador
  → 404). Excluir = `ativa = false` (o calendário é derivado, mas o contador pode reativar).
  Sem auditoria: não é operação fiscal. **[padrão]**
- Dia inexistente no mês (31 em abril) cai no último dia do mês. Feriados fora da v1: só fim de
  semana é ajustado. **[padrão]**
- Calendário: `GET /vencimentos` passa a devolver também `tipo: "obrigacao"` com `id`
  = `<obrigacaoId>:<data>`, `titulo`, `descricao`, `periodicidade`, `origem`, gerando **todas
  as ocorrências dentro da janela `de`/`ate`** que o endpoint já tem (responde à pergunta 4).
- Além da tabela, o **fechamento da apuração do mês (RN004)** entra como obrigação do sistema,
  derivada (sem linha na tabela), para todo produtor — inclusive sem contador. Mesma regra do
  `AlertaTributarioService`. **[padrão]**
- **Modelos (templates) no changeSet, que o escritório desativa ou edita. [Murillo]**
  - Linhas com `contador_id NULL` e `modelo = true`, semeadas por changeSet. Valem para todo
    contador até ele mexer.
  - Editar um modelo cria a **versão do escritório** (linha com `contador_id` e `modelo_id`
    apontando o modelo), que passa a valer no lugar dele só para aquele contador. Desativar =
    versão do escritório com `ativa = false`. "Restaurar padrão" apaga a versão do escritório.
    **[padrão]**
  - Catálogo efetivo do contador = as próprias + os modelos sem versão do escritório. Assim um
    modelo novo (changeSet futuro) chega a quem não personalizou, e a correção de data de um
    modelo não passa por cima da personalização de ninguém. **[padrão]**
  - Modelo é somente leitura pela API (o contador nunca altera a linha global). **[padrão]**
  - Modelos da v1 (todos com `origem` e **datas a conferir com o contador do grupo antes do
    merge**). "Último dia útil" = dia 31 + `ANTECIPA`, sem feriado:

    | Obrigação | Periodicidade | Prazo | Vale para | Origem (a conferir) |
    |---|---|---|---|---|
    | DAS / PGDAS-D | mensal | dia 20 | PJ Simples | LC 123/2006, art. 21 |
    | DAS-MEI | mensal | dia 20 | MEI | LC 123/2006, art. 18-A |
    | DASN-SIMEI | anual | 31/05 | MEI | Res. CGSN 140/2018 |
    | DEFIS | anual | 31/03 | PJ Simples | Res. CGSN 140/2018 |
    | DCTFWeb | mensal | dia 15 | PJ Presumido/Real | IN RFB 2.005/2021 |
    | EFD-Reinf | mensal | dia 15 | PJ Presumido/Real | IN RFB 2.043/2021 |
    | Funrural (recolhimento) | mensal | dia 20 | todos | Lei 8.212/1991, art. 30 |
    | DIRPF com atividade rural (+ LCDPR) | anual | 31/05, antecipa | PF | IN anual da RFB; IN RFB 1.848/2018 |
    | DITR | anual | 30/09, antecipa | todos (dono de imóvel rural) | Lei 9.393/1996 |
    | ECD | anual | último dia útil de junho | PJ Real/Presumido | IN RFB 2.003/2021 |
    | ECF | anual | último dia útil de julho | PJ Real/Presumido | IN RFB 2.004/2021 |

    Fora da v1 por exigir regra que o catálogo não modela: EFD-Contribuições (10º dia útil do
    2º mês), obrigações estaduais (EFD ICMS/IPI, GIA) e eSocial por evento.
- **Datas dos 11 modelos conferidas, sem correções; prazo totalmente editável pelo escritório.
  [Murillo, 2026-10-25]** Cada escritório pode ter prazos de entrega diferentes: na versão do
  escritório, `dia`, `mes`, `ajuste_fim_de_semana` (e a `periodicidade`) são livres, sem amarra ao
  valor do modelo — só a coerência do prazo (1–31; mês só na anual) é conferida. Coberto pelo
  `ObrigacaoCalendarioIntegrationTest.prazoDoModeloLivre`.
- **Regime como lista** (`regimes_tributarios`), não coluna única: DCTFWeb, EFD-Reinf, ECD e ECF
  valem para Presumido e Real. **[padrão, PR #237]**
- O `DataLoader` de dev cria uma versão do escritório de exemplo (um modelo desativado e um
  editado) para a demonstração mostrar a personalização. **[padrão]**
- Prazo de pedido de documento **não** entra aqui: é da #180 (pergunta 3 da issue).

## #178 — aprovação do contador antes da emissão

Decisões **[Murillo]**: produtor sem contador emite direto; rascunhos já existentes de quem tem
contador entram no fluxo novo; contador não edita, só devolve, com histórico; falha do
`gerar-xml` numa nota aprovada **derruba a aprovação**; o envio para revisão **roda a validação
fiscal antes**.

Estados novos: `AGUARDANDO_APROVACAO`, `APROVADA`, `DEVOLVIDA` (cabem no `VARCHAR(20)`).

| De | Ação (quem) | Para |
|---|---|---|
| `RASCUNHO`, `COM_INCONSISTENCIAS`, `DEVOLVIDA` | `POST /api/notas/{id}/enviar-para-revisao` (produtor) | `AGUARDANDO_APROVACAO`; se a validação fiscal falhar → `COM_INCONSISTENCIAS` com o lote em `nota_fiscal_validacao` (422, como o `gerar-xml`) |
| `AGUARDANDO_APROVACAO` | `POST /api/notas/{id}/retirar-da-revisao` (produtor) | `RASCUNHO` **[padrão]** |
| `AGUARDANDO_APROVACAO` | `POST /api/contador/produtores/{produtorId}/notas/{id}/aprovar` | `APROVADA` |
| `AGUARDANDO_APROVACAO` | `POST /api/contador/produtores/{produtorId}/notas/{id}/devolver` `{motivo}` | `DEVOLVIDA` |
| `APROVADA` | `gerar-xml` (produtor), sucesso | `XML_GERADO` |
| `APROVADA` | `gerar-xml`, inconsistência | `COM_INCONSISTENCIAS` (aprovação caduca) |

- **Editáveis** (PUT/DELETE): `RASCUNHO`, `COM_INCONSISTENCIAS`, `DEVOLVIDA`. `AGUARDANDO_APROVACAO`
  e `APROVADA` não: mudar o conteúdo depois do aval anularia o que o contador viu.
- **`gerar-xml`** com contador vinculado exige `APROVADA`; sem vínculo, aceita os editáveis como
  hoje. O vínculo é conferido **no momento** da chamada. Produtor que perde o contador com nota
  em `AGUARDANDO_APROVACAO` usa o `retirar-da-revisao`. **[padrão]**
- Aprova ou devolve qualquer membro do escritório do produtor com função `ADMINISTRADOR` ou
  `CONTADOR` (#228); `ASSISTENTE` → 403. **[padrão]**
- Histórico em tabela `nota_fiscal_revisao` (nota, ação `ENVIADA`/`RETIRADA`/`APROVADA`/
  `DEVOLVIDA`/`APROVACAO_CADUCADA`, motivo, credencial que agiu, quando), no padrão de
  `nota_fiscal_validacao`. `GET /api/notas/{id}/revisoes` e o equivalente no path do contador.
- `motivo` obrigatório na devolução, 5 a 500 caracteres. **[padrão]**
- Concorrência: o `@Version` da #95 resolve aprovar × retirar simultâneos; transições sob o lock
  pessimista já usado na emissão.
- `@Auditado` em enviar, retirar, aprovar e devolver.
- Fora do escopo: certificado A3 (a assinatura continua no servidor, com A1).
- Frontend: status novos caem no "status desconhecido = em processamento" já combinado; a tela
  precisa conhecê-los para mostrar as ações.

## #180 — pendências e documentos (versão mínima)

Decisões **[Murillo]**: entra em versão mínima; anexos no banco, PDF/JPEG/PNG/XML, conferidos
pelos bytes iniciais, 5 MB por arquivo, retenção de 5 anos, apagados na exclusão LGPD.

Estados da pendência (nomes do frontend): `PENDENTE` → `ENVIADA` → `CONCLUIDA`; `CANCELADA`.

| Ação | Quem | De → Para |
|---|---|---|
| criar `{titulo, descricao, dataLimite?, tiposDocumento[], observacoes?}` | contador | → `PENDENTE` |
| editar | contador | só `PENDENTE` |
| anexar (multipart, 1 arquivo por chamada) | produtor | `PENDENTE`/`ENVIADA` → `ENVIADA` |
| concluir | contador | `ENVIADA` → `CONCLUIDA` |
| rejeitar `{motivo}` | contador | `ENVIADA` → `PENDENTE` (anexos ficam, motivo guardado) |
| cancelar `{motivo}` | contador | `PENDENTE`/`ENVIADA` → `CANCELADA` |
| reabrir | contador | `CONCLUIDA`/`CANCELADA` → `PENDENTE` |

- Rotas do contador em `/api/contador/produtores/{produtorId}/pendencias/...`; do produtor em
  `/api/pendencias/...` (só as dele). `pendencias-recebidos` vira filtro `?status=ENVIADA`.
  **Contrato muda em relação ao que o frontend chama hoje** — combinar com o Luiz antes do PR.
- `tiposDocumento` é **lista** (enum: `NOTA_FISCAL`, `COMPROVANTE_PAGAMENTO`, `EXTRATO`,
  `CONTRATO`, `OUTRO`), não string JSON. **[padrão]**
- Anexo em tabela própria `pendencia_anexo` (`bytea`, nome original, MIME conferido, tamanho,
  SHA-256, quem enviou, quando), nunca carregado junto com a pendência. Download por
  `GET .../anexos/{anexoId}` com `Content-Disposition: attachment` e o MIME conferido, nunca o
  declarado. Até 10 anexos por pendência. **[padrão]**
- Nome do arquivo sanitizado (sem caminho, sem caractere de controle, até 120 caracteres).
  XML passa pelo `SecureXmlFactories` só para conferir que é XML bem formado. **[padrão]**
- `spring.servlet.multipart.max-file-size` sobe de 2 MB para 5 MB (afeta também a importação de
  NF-e; o limite por arquivo do XML de NF-e continua conferido no service). Revisar conforme o
  uso real. **[Murillo]**
- Retenção: o job de retenção da auditoria ganha irmão para anexos com mais de 5 anos.
  Exclusão LGPD do produtor apaga anexos e pendências. Atualizar `docs/lgpd.md`.
- Calendário: pendência com `dataLimite` em `PENDENTE` aparece como `tipo: "solicitacao"`.
- `@Auditado` em criar, anexar, concluir, rejeitar, cancelar e reabrir.
- Sem antivírus na v1; a verificação vira issue própria (ver o fim deste documento). **[Murillo]**

## #182 — notificação (registro, sem WhatsApp real)

Decisões **[Murillo]**: registro no banco + `GET`; contador também dispara.

- Tabela `notificacao` (produtor, quem disparou — credencial e papel —, tipo
  `PROXIMAS_OBRIGACOES`/`ATRASADOS`/`MENSAGEM`, canal `WHATSAPP`, status `REGISTRADA`, texto,
  quantos itens, quando). Canal real fica atrás de uma porta `EnvioNotificacao` com só a
  implementação de registro, como o `EnvioEmail` da #209. **[padrão]**
- Produtor: `POST /api/notificacao/proximas-obrigacoes`, `/todos-atrasados`, `/enviar`
  `{mensagem}` e `GET /api/notificacao`. Contador: o mesmo em
  `/api/contador/produtores/{produtorId}/notificacao/...`. Sai o `{produtorId}` do path da
  rota do produtor (o frontend ajusta). **[padrão]**
- **Duas faixas de urgência. [Murillo]** Cada item notificado leva uma classificação:
  - `URGENTE` — vence **hoje ou amanhã**;
  - `PROXIMO` — vence de 2 a 7 dias;
  - `ATRASADO` — guia `VENCIDA` ou pendência com `dataLimite` passada.

  "Próximas obrigações" envia `URGENTE` + `PROXIMO` (os próximos 7 dias), com os urgentes
  primeiro e marcados no texto. Resposta `{enviados, urgentes}`; `enviados = 0` é resposta
  legítima. Fonte: vencimentos do calendário (guias pendentes, obrigações da #181,
  solicitações da #180).
- **Aviso urgente automático:** job diário às 9h (ShedLock, como o `AlertaTributarioJob`)
  registra uma notificação `URGENTE` por item que vence **amanhã**, uma vez só por item e data
  (idempotente), sem depender do botão. **[padrão — leitura do "precisa de algo para
  notificar"]**
- Produtor sem `whatsapp` cadastrado → 422 `CONTATO_AUSENTE`, nada registrado (o frontend
  precisa distinguir isso de "nada a avisar"). **[padrão]**
- `mensagem` livre: 1 a 500 caracteres, passa pela `MascaraDadosPessoais` antes do log. Limite
  de 10 disparos por produtor por hora contra abuso. **[padrão]**
- Retenção de 1 ano (não é documento fiscal). **[padrão]**

---

## Issues derivadas desta revisão

- **backend#228** — escritório com equipe (membros, funções, responsável). Logo após a #177.
- **backend#227** — verificação de antivírus (ClamAV) nos anexos da #180. Depois da #180.
- **frontend#94** — cliente de notificação no contrato novo da #182 (sem `produtorId` no path,
  rota do contador, `{enviados, urgentes}`, `422 CONTATO_AUSENTE`).
