---
name: codebase-map
description: >
  Mapa estático do repositório AgroContabil: pacotes → classes-chave → responsabilidade.
  Use ANTES de ler arquivos para saber exatamente onde procurar, economizando tokens.
  Atualizar este mapa ao adicionar pacotes ou classes significativas.
---

# Codebase Map — AgroContabil Backend

Use este mapa para navegar sem precisar fazer `find` ou `ls` em cada pasta.

## Raiz: `src/main/java/br/com/agrocontabil/`

### `config/`
| Classe | Responsabilidade |
|--------|-----------------|
| `ProfileObrigatorioEnvironmentPostProcessor` | Recusa a subida sem exatamente um profile de ambiente (`dev`/`homolog`/`prod`/`test`) — fim do default `dev` (#219); registrado em `META-INF/spring.factories` |
| `SecurityConfig` | Regras por papel (#93): público só health/OpenAPI/Actuator, `/api/**` exige papel; CSRF off, stateless; headers de segurança (HSTS, DENY, nosniff, no-referrer, Permissions-Policy, CSP só em `/api/**` — #150) |
| `RefreshProperties` | `app.seguranca.refresh.*` (#207): validade (7d), vida máxima (30d), atributos do cookie, retenção |
| `LimiteTaxaProperties` | `app.seguranca.limite.*` (#150): geral e chat por minuto, teto de chaves |
| `ForcaBrutaProperties` | `app.seguranca.forca-bruta.*` (#208): max-falhas, bloqueio, limites por IP por rota, teto de IPs; valores não positivos impedem a subida |
| `JwtProperties` / `JwtConfig` | `app.seguranca.jwt.*` (#206): segredo (`JWT_SECRET`), emissor, audiência, validade (15 min), tolerância, `permitir-segredo-efemero` (só dev/test); `toString` sem o segredo |
| `SenhaConfig` | `PasswordEncoder` delegante, BCrypt custo `app.seguranca.senha.bcrypt-custo` (12; 4 nos testes) — fora do `SecurityConfig` para valer sem web (#205) |
| `CriptografiaConfig` / `CriptografiaProperties` | Chave dos dados pessoais em repouso (#147, `app.seguranca.cripto.*`) |
| `TributosLegadoRunner` | Recalcula na subida o IBS/CBS das notas não emitidas sem `percentual_reducao_aplicado` (calculadas antes da tabela oficial, #272), uma transação por nota; `app.notas.tributos.recalcular-legado` |
| `BuscaNotasLegadoRunner` | Preenche na subida a `busca_normalizada` das notas anteriores ao changeSet 0037 com a própria `NormalizacaoBusca` (sem backfill SQL, que divergiria da regra Java), idempotente; `app.notas.busca.preencher-legado` (#174) |
| `CifragemLegadoRunner` | Cifra na subida as linhas em claro de antes da #147 (`COLUNAS_CIFRADAS`), idempotente |
| `CorsConfig` | CORS via `APP_CORS_ALLOWED_ORIGINS` |
| `OpenApiConfig` | springdoc / Swagger UI |
| `MockIdentidadeFilter` | Resolve o produtor por requisição pelo header `X-Usuario-Id` (só profile dev desde a #221; homolog só JWT — `HomologSemMockIdentidadeIntegrationTest`); sem header usa o semente (#91) |
| `TributosProperties/Config` | Alíquotas IBS/CBS do `application.yml` |
| `CertificadoDigitalProperties/Config` | Caminho/senha do `.p12` via env `NFE_CERT_*` |
| `SefazProperties/Config` | Ambiente (HOMOLOGACAO/PRODUCAO), timeouts |
| `RetryConfig` | Spring Retry para chamadas SEFAZ |
| `SchedulingConfig` | Habilita `@Scheduled` e o lock distribuído ShedLock (`@EnableSchedulerLock`, `JdbcTemplateLockProvider` na tabela `shedlock`, relógio do banco — issue #87) |
| `DataLoader` | Seed em dev/homolog: **dois** produtores (A = semente/fallback, B = outro dono) + referências NCM/CFOP |
| `demonstracao/SeedDemonstracao` | Seed da banca (#315), só com `app.demonstracao.habilitada=true` e nunca em `prod` (lista de profiles): escritório "Contabilidade Rural Oeste" com as três funções, nove clientes diversos (`CenarioDemonstracao`: CPF/CNPJ com DV calculado, IBGE real, preços de 2026), ~160 notas — própria pelo `TributacaoService` + `GeracaoNotaFiscalService`, compra como XML assinado do fornecedor lido pelo `NfeToNotaFiscalMapper` —, fila de revisão com histórico, guias, pendências, meses antigos fechados e alertas. Idempotente pela administradora; transação própria de 600 s. Contas e roteiro em `docs/demonstracao-banca.md` |
| `demonstracao/CotaDemonstracaoCeres` | Deixa a cota semanal da Ceres de um produtor da demonstração com saldo para uma pergunta (teto + 1): na subida e no job de segunda 00:05 (ShedLock). Só sobe o consumo |
| `avaliacao/AvaliacaoCeresRunner` | Comando da avaliação da Ceres (issue #158, profiles `dev,avaliacao`, sem web): `SeedAvaliacao` (avaliado + vizinho fictícios), `ValoresEsperados` (valor esperado = função real com os parâmetros do gabarito), `AvaliadorResposta` (régua pura), `RelatorioAvaliacao` (uma rodada: métricas, por rota, adversários), `RelatorioRepeticoes` (#264: `avaliacao.repeticoes`, média/faixa e perguntas instáveis; .md + .json em `docs/avaliacao/resultados`), `ClarezaTexto` (palavras por frase, siglas sem explicação), `CsvCego` (CSV cego + chave da nota humana, #275), `SeparacaoTreinoTeste` (gabarito nunca em `docs/avaliacao/treino/`). Gabarito em `docs/avaliacao/gabarito-ceres.json`; harness da API publicada em `docs/avaliacao/harness-api/` |

### `controller/`
| Classe | Endpoints |
|--------|-----------|
| `HealthController` | `GET /api/v1/health` |
| `AutenticacaoController` | `/api/auth/**` — `POST /cadastro`, `/login`, `/refresh`, `/logout` públicos (#205–#207), `POST /logout-todos` e `GET /me` autenticados; só com web |
| `PrivacidadeController` | `GET /api/politica-dados` (público) e `DELETE /api/usuarios/{id}/dados` (#148) |
| `AuditoriaController` | `GET /api/auditoria?produtorId=` — só contador, só carteira; array + `X-Total-Count` (#149) |
| `SenhaController` | `/api/auth/senha/**` — `esqueci` e `redefinir` públicos, `alterar` autenticado (#209); só com web |
| `NotaFiscalController` | `/api/notas/**` — todos os 12 endpoints (ver tabela abaixo) |
| `ApuracaoController` | `/api/apuracao` — consulta mensal (RN004), faixa `de`/`ate`/`ultimos` + `agrupamento` (`ApuracaoFaixaService`, issue #179) + `/{id}/reabrir` (rota antiga: 403 a todos desde a #224) |
| `DashboardController` | `GET /api/dashboard?periodo=YYYY-MM` — painel agregador do produtor (issue #65) |
| `RevisaoNotaController` | `/api/notas/{id}/enviar-para-revisao`, `/retirar-da-revisao`, `/revisoes` e `/api/contador/produtores/{produtorId}/notas/{id}/aprovar`, `/devolver`, `/revisoes` (#178) |
| `PendenciaController` | `/api/contador/produtores/{produtorId}/pendencias/**` (escritório) e `/api/pendencias/**` (produtor, anexo multipart e download) — pendências e documentos (#180) |
| `NotificacaoController` | `/api/notificacao/**` (produtor) e `/api/contador/produtores/{produtorId}/notificacao/**` (escritório): próximas, atrasados, mensagem, histórico (#182) |
| `ObrigacaoController` | `/api/contador/obrigacoes` (+ `/{id}`, `/reativar`, `/restaurar-padrao`) — catálogo de obrigações do escritório (#181) |
| `GuiaFiscalController` | `/api/contador/guias` — CRUD de guias fiscais do Contador (issue #74) |
| `CarteiraController` | `/api/contador/produtores` (lista), `/{produtorId}/ficha`, `/etiquetas`, `/suspender`/`/reativar`/`/encerrar`, `/api/contador/etiquetas`, `/api/contador/convites` — só contador (#177) |
| `CarteiraApuracaoController` | `GET /api/contador/produtores/{produtorId}/apuracao` (mesmos parâmetros e response de `/api/apuracao`, #255) e `POST .../apuracao/{periodo}/reabrir` — reabertura RN005 pelo escritório (`ApuracaoService.reabrirPeloContador`: carteira ativa → 404, `ADMINISTRADOR`/`CONTADOR` → 403, lock por produtor+período; resultado `ReaberturaApuracao` leva id e produtor só à auditoria) (#224) |
| `CarteiraIndicadoresController` | `GET /api/contador/produtores/{produtorId}/relatorios/conformidade` (+ `/pdf`), `/creditos`, `/dashboard` — números do cliente, mesmos responses das rotas do produtor (#255). O `GET .../apuracao` (mesmos parâmetros de `/api/apuracao`) fica no `CarteiraApuracaoController` |
| `CarteiraNotasController` | `/api/contador/produtores/{produtorId}/notas` (+ `/{id}`, `/{id}/validacoes`) — leitura, padrão de rota do contador (#177) |
| `EscritorioController` | `/api/escritorio`, `/api/escritorio/membros/**` (+ convites e aceite) e o público `POST /api/auth/cadastro/equipe` (#228) |
| `ConviteController` | Lado do produtor (#177): `/api/convites` (+ aceitar/recusar) e `/api/vinculos` (+ encerrar) |
| `CalendarioFiscalController` | `GET /api/calendario-fiscal/{produtorId}/vencimentos` — calendário fiscal do produtor (issue #74) |

### `service/`
| Classe | Responsabilidade |
|--------|-----------------|
| `NotaFiscalService` | CRUD de notas + orquestra geração XML, DANFE, envio, cancelamento (anti-IDOR 404). **Não é `@Transactional` na classe** (issue #99): `gerarXml` roda em 3 fases via `TransactionTemplate` (lê e carrega o grafo → assina/XSD **fora de transação** → recarrega sob lock, recheca e persiste); `gerarPdf` só na fase de leitura. CRUD segue declarativo |
| `GeracaoNotaFiscalService` | Pipeline NF-e mod. 55: validar → mapear → gerar XML → assinar → validar XSD |
| `GeracaoNfaService` | Geração de NFA (Nota Fiscal Avulsa do produtor rural — issue #18) |
| `ImportacaoNotaFiscalService` | Importa XML(s) de NF-e recebida (multipart; issue #19). Desde a #223: `dest` = produtor, `AssinaturaValidator`, dedup `existsByProdutorIdAndChaveAcessoAndOrigem`, consulta à SEFAZ pela chave fora de transação + `DigestAssinatura.confere` (digVal); sem resposta → `PENDENTE_SEFAZ` |
| `NotaFiscalValidacaoService` | Persiste lote de inconsistências em transação **única** (`REQUIRED` desde a issue #97 — era `REQUIRES_NEW` e exigia 2 conexões); marca nota `COM_INCONSISTENCIAS`. **Só pode ser chamado fora de transação** |
| `SefazService` | Envio, consulta de status e cancelamento na SEFAZ. **Nao e `@Transactional` na classe** (issue #96): cada transmissao roda em 3 transacoes curtas via `TransactionTemplate` — reserva sob lock pessimista, I/O fora de transacao, desfecho — mais a compensacao e `reconciliarTransmissoesPresas` |
| `TributacaoService` | Débito: calcula IBS/CBS por item, classifica natureza (DEBITO/CREDITO) e agrega totais; alíquota via `AliquotaIbsCbsResolver`, redução via `ReducaoNcmResolver` |
| `CreditoTributarioService` | Crédito nas notas IMPORTADAS (RN003): mesma base e mesma alíquota do débito (resolvedores compartilhados); só lê `credito.percentualAproveitamento` das properties |
| `AliquotaIbsCbsResolver` | **Único** ponto de resolução de alíquota (issues #86/#85): IBS por UF aplicável (RN002; nota importada = UF do destinatário) + CBS, ambos pela **vigência** na `dataEmissao` em BRT; devolve `AliquotasVigentes` com origem (TABELA_UF/TABELA_VIGENCIA/FALLBACK_YML); fallback conservador para `TributosProperties` com WARN |
| `ReducaoNcmResolver` | **Único** ponto de resolução da redução por item (issues #52/#272): percentual informado no item → tratamento do NCM na tabela oficial (`ClassificacaoTributariaService`), na vigência da emissão em BRT; `tratamento(item)` devolve `ReducaoAplicada` (percentual + cClassTrib), gravada pelo `TributacaoService` em `percentualReducaoAplicado`/`cClassTribAplicado` |
| `ClassificacaoTributariaService` | Tabela oficial de cClassTrib e anexos da LC 214 por NCM (#272, IT 2025.002 v1.70, changeSet 0048). Serviço de domínio **independente do cálculo** (a Ceres o reusa, #283): `tratamentoPorNcm(ncm, data)` → `TratamentoTributarioNcm` (precedência Anexo I/XV 100% → in natura art. 137 60% → Anexo IX/VII 60% → integral 000001), `enquadramentos(ncm, data)` (itens de anexo com texto, condição e exceção; VEDADO tira o NCM do cClassTrib), `classificacao(cClassTrib, data)` |
| `ChaveAcessoGenerator` | Chave 44 dígitos + DV módulo 11 |
| `AuthenticatedProdutorProvider` | Extrai produtor do `SecurityContext`; sem identidade lança `NaoAutenticadoException` (401) |
| `CadastroService` | Cadastro de conta (#205): política + hash **fora** de transação, checagem e gravação (identidade + credencial) num `TransactionTemplate` curto; CPF/CNPJ já existente (qualquer grafia) → 409, nunca reivindica identidade; `normalizarEmail` |
| `LoginService` | Login (#206): e-mail normalizado + senha NFKC; e-mail sem conta confere contra hash fictício (tempo igual); falha de qualquer tipo = `CredenciaisInvalidasException` (401); rehash por `UPDATE` só da coluna; `usuarioLogado()` p/ `/me`. Só web |
| `SessaoService` | Par access+refresh (#207): abre família no login, `renovar` (rotação, reuso → revoga família, lock pessimista, vida máxima, desfecho gravado antes de recusar), `encerrar`, `encerrarTodas`/`revogarTodas` (ponto único de revogação em massa). Só web |
| `SessaoRefreshLimpezaJob` | Diário 3h + ShedLock (#207): apaga sessões vencidas há mais que `retencao` |
| `ExclusaoDadosService` | Direito ao esquecimento (#148): só o próprio titular + senha atual; anonimiza dados pessoais, mantém os fiscais, apaga credencial/sessões/links/alertas e os vínculos produtor×contador |
| `auditoria/Auditado` + `AuditoriaAspect` | Auditoria das operações críticas (#149): anotação nos métodos de escrita; aspecto por fora do `@Transactional` grava sucesso/falha sem dado pessoal; `EstadoRecursoLeitor` lê o status "antes" |
| `auditoria/AuditoriaService` | Grava (nunca lança) e lista (só contador, só carteira) |
| `auditoria/RegistroAuditoriaRetencaoJob` | Retenção diária (ShedLock), corte no 1º de janeiro de (ano − `app.auditoria.retencao-anos`, mín. 5) |
| `PoliticaDadosService` | Política de dados (#148) a partir de `app.lgpd.*` |
| `SenhaService` | Recuperação/troca de senha (#209): `solicitarRedefinicao` (202 sempre, trabalho no pool `agro-email-`), `redefinir` (valida → BCrypt fora de tx → regrava sob lock), `alterar` (senha atual com a reserva antitentativa); troca = hash + links apagados + `revogarTodas` + bloqueio zerado. Só web |
| `EnvioEmail` | Porta de e-mail (#209); implementação escolhida no `config/EmailConfig` (SMTP / LOG só dev / DESLIGADO) |
| `BloqueioContaService` | Bloqueio por conta (#208): `reservarTentativa` (conta a tentativa sob lock pessimista **antes** do BCrypt; 5 → 15 min; vencido recomeça), `reservarFicticia` (e-mail sem conta, mesmo custo), `liberar` (sucesso zera) |
| `AutenticacaoTokenService` | JWT → `UsuarioAutenticado` (#206): relê a credencial a cada requisição (ativa, mesmo papel, mesma `versaoSessao`). Só web |
| `PoliticaSenha` | NIST 800-63B (#205): 10 caracteres a 72 bytes UTF-8 (limite do BCrypt), sem repetição, sem e-mail/CPF, fora de `seguranca/senhas-comuns.txt`; `normalizar` (NFKC) antes de validar e de gerar/conferir hash |
| `ApuracaoService` | Consolida/consulta/reabre apuração mensal de IBS/CBS (RN004/RN005); reúsa agregações do `NotaFiscalRepository`; fechamento do job por produtor em transação própria, lendo IDs em lotes por keyset (issue #100) |
| `ApuracaoFaixaService` | Faixa de meses da apuração (issue #179): valida a combinação (`FaixaApuracaoInvalidaException`, teto 60, `ultimos` 6/12/36/60), lê as persistidas numa consulta e agrega os meses abertos num `GROUP BY` nativo com `AT TIME ZONE 'America/Sao_Paulo'` (`NotaFiscalRepository.agregarApuracaoPorMes`); agrupa por `AgrupamentoApuracao` |
| `ApuracaoMes` | Regra única de mês → `ApuracaoResponse` (persistido ou ao vivo: arredondamento, saldo, `parcial`), usada pelo `ApuracaoService` e pelo `ApuracaoFaixaService` para a faixa nunca divergir do `?periodo=` (issue #179) |
| `ResumoLoteApuracao` | Record interno com o resultado do lote do job de fechamento: concluídas + IDs com falha (issue #100) |
| `DashboardService` | Agrega o painel do produtor (issue #65): reúsa `ApuracaoService` (KPIs) + `NotaFiscalRepository` (série mensal, últimas notas); zero-fill de 6 meses; anti-IDOR |
| `GuiaFiscalService` | CRUD de guias fiscais (issues #74/#94): `criar`/`listar`/`marcarComoPaga` exigem que o `produtorId` seja o do produtor autenticado — 404 se divergir (anti-IDOR) |
| `GuiaFiscalStatusSyncJob` | `@Scheduled` diário — marca `PENDENTE` vencida como `VENCIDA` (issue #74) |
| `AlertaTributarioService` | Alertas RN010 (issue #140): gera (guia pendente, fechamento do mês, prazo de cancelamento RN006 — a menos de 5 dias, BRT) e resolve os obsoletos; `listarAtivos(produtorId)` confere o motivo na consulta e o dono da referência. `AlertaTributarioJob` = `@Scheduled` 9h + ShedLock |
| `RetencaoService` | RN009 (issue #145): `detectarRetencao(NotaFiscal)` e `resumir(produtorId, inicio, fim)`. Funrural do produtor PF = 1,2% + 0,1% + 0,2% SENAR sobre `valorTotalProdutos`, **retido** na venda a CNPJ (art. 30, III/IV) e recolhido pelo próprio produtor na venda a CPF; venda ao exterior (UF `EX`) é imune (Constituição, art. 149, § 2º, I); produtor PJ fora; split payment = IBS + CBS destacados. Agregação por tipo de comprador no banco (`NotaFiscalRepository.agregarVendasPorAdquirente`). Na Ceres: função `consultarRetencoes` + `orquestracao/FundamentoRetencao` (parágrafo e citações fixos das duas regras) |
| `CotaUsoAssistenteService` | Cota semanal da Ceres (issue #166): créditos = ⌈(entrada×peso + saída×peso)×fator÷tokensPorCredito⌉; reserva do teto por `UPDATE` condicional atômico, acerto pelo real, estorno integral; semana = segunda em BRT. `PlanoUsoProvider` (hoje `PlanoUsoPorConfiguracao`) é o ponto de troca para as assinaturas. Tabela `assistente_uso_semanal` (0029). Na Ceres: `porta/CotaUsoPorta` + `adaptador/CotaUsoAdaptador`; `AssistenteService` reserva na thread da requisição e encerra (acerto/estorno) uma vez só |
| `CarteiraService` | Carteira do escritório (#177): `listar` (vínculos + etiquetas + uma agregação `GROUP BY produtor` por indicador de atenção no `VinculoCarteiraRepository`; filtra, ordena por nome/urgência e pagina em memória), `suspender`/`reativar`/`encerrar` (escritório, lock pessimista, `@Auditado`), `listarDoProdutor`/`encerrarPeloProdutor` (lado do produtor) e `ativarPorConvite` |
| `ConviteCarteiraService` | Convite (#177): grava pelo e-mail sem olhar se há conta (sempre 202), teto diário, e-mail depois do commit no pool `agro-email-convite-` (porta `EnvioConviteCarteira`, `config/CarteiraConfig`); o produtor vê os do e-mail da credencial e aceita/recusa sob lock. `ConviteCarteiraLimpezaJob` (ShedLock) apaga os vencidos há 30 dias |
| `EquipeEscritorioService` | Escritório e equipe (#228): dados do escritório, membros, troca de função e remoção sob lock dos membros (nunca sem administrador → 409), convite de equipe (token no fragmento, 202 sempre, e-mail no pool `agro-email-equipe-`), aceite com a conta (mesmo e-mail; unipessoal vazio sai) e cadastro pelo convite (`CadastroService.cadastrar(request, ingresso)`); `sairPorExclusao` para a LGPD |
| `FichaClienteService` | Ficha do cliente (observações, etiquetas, catálogo) — só vínculo ATIVO, via `ProdutorEmContextoResolver.exigirContadorComAcesso` (#177) |
| `CarteiraIndicadoresService` | Números do cliente pelo escritório (#255): `exigirContadorComAcesso` primeiro, depois as variantes com produtor explícito — `ApuracaoService.consultarParaProdutor`, `ApuracaoFaixaService.consultarDoProdutor`, `RelatorioConformidadeService.gerarDoProdutor`/`gerarPdfDoProdutor`, `CreditoService.consultarSaldoDoProdutor`, `DashboardService.montarDoProdutor`. Nunca duplicar a regra: rota nova do contador = variante `...DoProdutor` + autorização aqui |
| `CarteiraNotasService` | Leitura das notas do cliente pelo escritório (#177): resolver primeiro, depois `NotaFiscalService.findAllDoProdutor`/`findByIdDoProdutor` e `NotaFiscalValidacaoService.listarValidacoesDoProdutor` |
| `ObrigacaoFiscalService` | Catálogo de obrigações (#181): efetivo = próprias + versões + modelos sem versão; editar/desativar modelo cria a versão do escritório (`toBuilder` do modelo); `ocorrencias(produtor, escritorios, de, ate)` = todas as datas da janela (dia inexistente → último; `AjusteFimDeSemana`) + fechamento da apuração (RN004) |
| `PendenciaService` | Pendências e documentos (#180): escritório cria/edita/conclui/rejeita/cancela/reabre sob lock (`findDoEscritorioForUpdate`, carteira ativa → 404); produtor lista e anexa em 3 fases desde a #227 — confere, antivírus fora de transação, grava sob lock — (`ConferenciaAnexo`: tipo pelos bytes, XML pelo `SecureXmlFactories`, nome sanitizado, SHA-256; até 10 por pendência) — anexo em `pendencia_anexo` (bytea, 0042), nunca carregado com a pendência (`AnexoResumo`). `PendenciaAnexoRetencaoJob` apaga anexos com mais de 5 anos (ShedLock). Calendário (`solicitacao`) e atenção da carteira (`pendenciasEnviadas`) leem o `PendenciaRepository` |
| `VerificacaoAnexoService` + `VerificacaoAnexoJob` | Antivírus dos anexos (#227): porta `VerificacaoAntivirus` (`ClamdCliente` = INSTREAM do clamd por TCP; `SEM_VERIFICACAO` só dev/test, `config/AntivirusConfig`), sempre fora de transação; `aplicar` (LIMPO → pendência ENVIADA; INFECTADO descarta o conteúdo) e `auditarInfectado`; o job (2 min, ShedLock) reverifica os `EM_VERIFICACAO` por UPDATE condicional, para o lote com o antivírus fora do ar e pula a recusa de um arquivo só; `libera_pendencia` impede que anexo de antes da 0043 reabra pendência rejeitada. Health `clamav` (`config/ClamAvHealthIndicator`, PING com cache de 30 s) fica `DEGRADED`, nunca `DOWN` |
| `NotificacaoService` + `AvisoVencimentoJob` + `NotificacaoRetencaoJob` | Notificação por WhatsApp (#182), só registro (`notificacao`, 0044; porta `EnvioNotificacao`, `config/NotificacaoConfig`, chamada depois do commit). Itens de `CalendarioFiscalService.vencimentos` (extraído sem autorização: produtor = todos os escritórios ativos, membro = o próprio): URGENTE hoje/amanhã, PROXIMO até 7 dias, ATRASADO guia não paga ou pedido com prazo passado. 422 `CONTATO_AUSENTE` (sem telefone) antes de tudo; teto de 10 disparos manuais por produtor por hora sob lock do produtor (`ProdutorRepository.findByIdForUpdate`). Job das 9h: `AVISO_URGENTE` por item que vence amanhã, idempotente por `chave_item`, uma transação por produtor. Retenção 1 ano |
| `RevisaoNotaService` | Aprovação do contador (#178): `enviarParaRevisao` (exige contador ATIVO; validação fiscal + pré-condições da NFA fora de tx, falha → `registrarInconsistencias` + 422; recheca status e versão sob lock), `retirarDaRevisao`, `aprovar`/`devolver` (carteira ativa → 404, função ADMINISTRADOR/CONTADOR → 403), histórico em `nota_fiscal_revisao` (0041). `NotaFiscalService.gerarXml` exige `APROVADA` com contador; `NotaFiscalValidacaoService` grava `APROVACAO_CADUCADA` |
| `CalendarioFiscalService` | Monta o calendário fiscal do produtor (issue #74): reúsa `GuiaFiscalRepository` como única fonte de vencimento nesta entrega |

### `nfe/`
| Classe | Responsabilidade |
|--------|-----------------|
| `FiscalRulesValidator` | Valida regras de negócio fiscal antes de gerar XML (inclui NCM/CFOP — issue #20; NCM na referência — #282; cClassTrib aplicado vigente na tabela oficial, CST = prefixo e redução = a oficial do código — #278) |
| `NotaFiscalNfeMapper` | Entidade → classes JAXB (formatação fiscal). IBS/CBS (#278): CST/cClassTrib do tratamento aplicado (`cClassTribAplicado`, ou o informado; CST = prefixo), sem padrão fixo; `vBC` = valor cheio; com CST 011/200/515, `gRed` (`pRedAliq`, `pAliqEfet`) em `gIBSUF`, `gIBSMun` e `gCBS` |
| `NfeToNotaFiscalMapper` | Mapper reverso: XML NF-e importado → entidade (issue #19), sempre em `PENDENTE_SEFAZ` (#223: o `protNFe` do upload não prova nada) |
| `DigestAssinatura` | DigestValue da assinatura do XML e `confere(xml, digVal)` contra o protocolo da SEFAZ (#223) |
| `NfeXmlImporter` | Parser XXE-safe do XML importado |
| `XmlGenerator` | JAXB marshall → String XML |
| `XsdValidator` | Valida XML contra XSD oficial layout 4.00 |
| `SecureXmlFactories` | Fábrica XML com hardening XXE (centralizado) |
| `NfeAssinador` | Assinatura enveloped (Apache Santuario, RSA-SHA1) |
| `AssinaturaValidator` | Verifica assinatura + tamper detection |
| `KeyStoreCertificadoProvider` | Carrega `.p12` PKCS#12; lazy + validação; `conferirNaSubida` (ApplicationReadyEvent) avisa em WARN certificado autoassinado ou que não carrega, sem impedir a subida (#225) |
| `nfe/model/` | Classes JAXB puras do layout NF-e 4.00 (NFe, InfNFe, Emit, Dest…) |

### `assistente/` — Ceres, a assistente fiscal (Sprint 6)

**Módulo isolado (issue #141, `ArquiteturaAssistenteTest`):** o módulo só enxerga o backend por `porta/`; só `adaptador/` chama services (nunca repository); fora do módulo só `controller`/`config` dependem dele; nada no módulo é `@Transactional`.

| Classe | Responsabilidade |
|--------|-----------------|
| `AssistenteService` | Entrada da Ceres: identidade (porta) → vaga (global + 1 por produtor, 503 `ASSISTENTE_OCUPADO`) → orquestração no pool `agro-ceres-` com timeout total → auditoria; SSE (`conversarEmStream`) com prazo próprio |
| `orquestracao/OrquestradorService` | Classifica (`ClassificadorIntencao`, Fase 1 `LlmClassificadorIntencao`) e despacha: DADOS→`RotaDados`, LEGISLACAO→`RotaLegislacao`, MISTO→as duas, FORA_DE_ESCOPO/confiança baixa→texto fixo. Antes do classificador, `FiltroInjecao` (determinístico) manda prompt injection direto para FORA_DE_ESCOPO; depois da rota, `FundamentoRetencao` (RN009) acrescenta as duas regras de retenção. No MISTO, a parte de lei recebe a `perguntaLei` do classificador, conferida por `PerguntaLei` (tamanho, injeção, nenhum número novo), e o desfecho de cada parte vai ao `ContextoAssistente` → auditoria (changeSet 0030) |
| `PerguntaInterrompidaException` | Pergunta interrompida (tempo esgotado/cliente saiu): as rotas param via `ContextoAssistente.verificarAtiva()` |
| `porta/DadosProdutorPorta` | Leitura dos dados do produtor (compras, apuração, créditos, notas, vencimentos) com records próprios, sem entidade nem projeção de repository |
| `porta/IdentidadePorta` | Produtor autenticado, resolvido uma vez na thread da requisição |
| `porta/AuditoriaPorta` | Gravação do `RegistroInteracao` (sem texto da pergunta/resposta) |
| `adaptador/*Adaptador` | Implementam as portas sobre `ConsultaDadosProdutorService`, `ApuracaoService`, `CreditoService`, `AuthenticatedProdutorProvider` e `service/AssistenteInteracaoService` |
| `auditoria/AuditoriaAssistente` | Monta o registro de auditoria (parâmetros mascarados) e o entrega à porta |

| Classe | Responsabilidade |
|--------|-----------------|
| `llm/ModeloLinguagem` | **Porta única** para o `ChatModel` (Ollama ou API compatível com a da OpenAI, por `spring.ai.model.chat`). Falha do provedor → `AssistenteIndisponivelException` (503); `identificacao()` = `provedor/modelo` para auditoria (issue #141) |
| `llm/OllamaHealthIndicator` | Health `ollama`: modelos baixados? Inacessível/ausente → `DEGRADED` (HTTP 200), nunca `DOWN`; cliente próprio com timeout curto + cache (issue #141) |
| `llm/DisponibilidadeModelo` | Disjuntor do modelo (issue #146): depois de uma queda real (I/O, timeout, 5xx, sem ChatModel), as perguntas pulam o modelo por `app.assistente.fallback.pausa-apos-falha`. Recusa 4xx da pergunta, resposta vazia e thread interrompida **não** abrem (senão um produtor derrubaria todos) |
| `fallback/ModoFallback` | Resposta sem modelo (issue #146): intenção por palavras-chave, trechos de lei verbatim (busca textual) e `TelaAtalho` (NOTAS/APURACAO/CREDITOS/CALENDARIO) para perguntas de dados. Nunca um número do produtor |

| `ContextoAssistente` | Produtor + "hoje" + fontes da pergunta, entregue às funções pelo `ToolContext` (nunca visto pelo modelo). `de(toolContext)` **falha fechado** (issue #143) |
| `RotaDados` | Laço modelo → função → modelo **do backend** (`internalToolExecutionEnabled=false` + `ToolCallingManager`), teto `max-rodadas-ferramentas`; resposta passa pelo `ValidadorNumeros` (issue #142) |
| `ValidadorNumeros` | Todo número/data da resposta tem de aparecer nos resultados das funções, na pergunta ou no prompt de sistema; senão a resposta é descartada (issue #142) |
| `DataSanitizer` | Máscara de CPF/CNPJ/chave/e-mail/telefone/CEP em tudo que vai ao modelo (pergunta + resultados). Aplicado nos resultados por `ferramentas/ResultadoSanitizadoConverter`, declarado em cada `@Tool` (issue #144) |
| `ResultadoRota` | Texto + `DesfechoInteracao` da rota; fora de `RESPONDIDA` a resposta foi descartada |
| `RotaLegislacao` | RAG (issue #156): trechos vigentes → modelo sem funções, trechos só com marca `[Tn]` → `ValidadorCitacao` + `ValidadorNumeros` contra os trechos. Sem trecho: "não encontrei fundamento legal" sem chamar o modelo |
| `VerificadorNegativaDados` | Rota de dados (#274, item 6): com função que voltou vazia (`ContextoAssistente.fontesVazias()`), frase com negativa absoluta ("não tem", "não há", "nenhum"...) **sem o recorte** (datas, mês ou número consultados) sai e entra a frase fixa de cada consulta vazia, com período e filtro reais. Desfecho `NEGATIVA_SUBSTITUIDA` (conta como aceita: fontes exibidas) |
| `VerificadorAfirmacaoDados` | MISTO (issue #170): a parte de lei não pode falar dos dados do produtor ("você", "suas compras", "Sim, ..."). Se falar, `RotaLegislacao.responderRegraGeral` troca o texto por `SO_TRECHOS` e mantém as citações (desfecho `AFIRMACAO_SOBRE_DADOS`). Dado vazio no MISTO vira frase fixa: toda função informa `vazio` no `ContextoAssistente.registrarFonte` (obrigatório) |
| `orquestracao/TrechosDasCompras` | MISTO (issue #170): NCMs das compras consultadas pela parte de dados (`DadosProdutorPorta.ncmsComprados`) → itens de anexo da lei (`BuscaTextualLegislacaoService.buscarAnexosPorNcm`), entregues a `RotaLegislacao.responderRegraGeral` como trechos complementares. Best-effort: falha vira lista vazia, interrupção propaga |
| `rag/FatiadorDispositivos` | HTML do Planalto (jsoup) → um `DispositivoLegal` por artigo/§/inciso/alínea, com o texto do caput; descarta tachado e anotações; nova redação de emenda vira "art. 1º (redação do art. 156-A, § 1º)" |
| `rag/IngestaoLegislacaoService` | `PgVectorStore.add` idempotente pelo hash; nova redação = nova vigência (fecha só o `vigenciaFim` da anterior, ordem que se repara sozinha). `IngestaoLegislacaoRunner` = comando no profile `ingestao` |
| `rag/BuscaLegislacaoService` | Top-k vigentes na data, com limiar de similaridade (`app.assistente.rag.*`); filtro de vigência via `FilterExpressionBuilder` |
| `rag/BuscaTextualLegislacaoService` | Busca textual do fallback (issue #146): OU dos lexemas (máx. 20, cada um com `quote_literal`), mínimo de 2 casados, mesma vigência; coluna `conteudo_tsv_sem_acento` (0027). Mapa de acentos travado pelo `BuscaTextualMapaAcentosTest` |
| `rag/VerificacaoBaseLegislacao` | WARN na subida quando `legislacao_trecho` está vazia (issue #175), só com a Ceres ligada (`spring.ai.model.chat` ≠ `none`) e fora do profile `ingestao`; nunca derruba a subida nem o health |
| `rag/ValidadorCitacao` | Marca `[Tn]` inexistente ou ausente → "não encontrei fundamento legal"; senão troca a marca pela referência dos metadados e devolve `CitacaoLegal` com link |
| `PromptsCeres` | Instruções de sistema (orientam; as garantias estão em código) |
| `ferramentas/FerramentasDadosProdutor` | Funções `@Tool` (`totalComprasPorInsumo`, `consultarApuracao`, `consultarCreditos`, `listarNotas`, `proximosVencimentos`, `listarAlertas` — #140, `consultarRetencoes` — #145, `guiasEmAtraso` e `vencimentosDoMes` — #274: atraso pela data, "este mês" = atraso + a vencer no mês; `consultarNota` — #256: nota pelo número, com motivo da devolução e último lote de validação, `sinais` vazio para a #276; `totalVendasPorProduto` — #248: espelho das compras nas saídas). **Nenhum parâmetro de produtor**; valida parâmetros antes de consultar; DTOs enxutos em `RespostasFerramentas` (issues #142/#143) |
| `ferramentas/ProcessadorErroFerramenta` | O que volta ao modelo quando a função falha: mensagem de parâmetro (nossa) ou genérica; contexto ausente aborta |

`service/AssistenteInteracaoService` (chamado pelo `AuditoriaAdaptador`): grava `AssistenteInteracao` (tabela `assistente_interacao`, changeSet 0025) — rota, desfecho, funções mascaradas, modelo, latência, tokens, tamanho da pergunta; **nunca o texto** (issue #144). `config/LimiteCorpoAssistenteFilter`: 413 acima de 16 KB em `/api/assistente/**`, antes do Jackson.

`service/ConsultaDadosProdutorService`: consultas fixas da Ceres com `produtorId` explícito (compras por NCM/descrição com `LIKE` escapado, notas top-N, guias não pagas). `ApuracaoService.consultarParaProdutor` e `CreditoService.somarCreditosNoIntervalo` são as variantes com produtor explícito (a antiga delega). `controller/AssistenteController`: `POST /api/assistente/chat` e `POST /api/assistente/chat/stream` (SSE) → `assistente/AssistenteService`. `DadosProdutorAdaptador` roda cada consulta numa transação curta com timeout `app.assistente.timeout-funcao`.

`config/AssistenteProperties` (+ `AssistenteConfig`): `app.assistente.*` (timeout/cache do health, `max-rodadas-ferramentas`, `max-conversas-simultaneas`).
Testes: `support/ModelosSimuladosConfig` registra `ChatModelSimulado` (programável) e `EmbeddingModelSimulado` (1024 dim) no profile `test`.

### `security/` (issue #93)
| Classe | Responsabilidade |
|--------|-----------------|
| `PapelUsuario` | Enum PRODUTOR/CONTADOR + authority `ROLE_*` |
| `UsuarioAutenticado` | Principal da requisição: papel + produtor **ou** contador (invariante na construção) |
| `UsuarioAutenticadoProvider` | Porta única para a identidade autenticada (`get()` → 401; `tentar()` → `Optional`) |
| `MembroEscritorioProvider` | Contador como membro (#228): `membro()` (403 para produtor e para contador sem escritório, `SEM_ESCRITORIO`), `exigirFuncao(...)` (403 `PAPEL_NAO_AUTORIZADO` com a função), `contador()` (com ou sem escritório) |
| `ProdutorEmContextoResolver` | Autoriza o `produtorId` alvo: produtor só a si, contador só a carteira **ATIVA** (`VinculoCarteiraRepository`) → 404; `exigirContadorComAcesso` = padrão `/api/contador/produtores/{produtorId}/...` (produtor 403) — #177 |
| `SecurityErrorResponder` | Envelope `ApiError` nas negativas da cadeia de filtros (401 + `WWW-Authenticate: Bearer`, 403); `recusarToken` = 401 `error="invalid_token"` (#206) |
| `JwtService` | Emite/confere o JWT HS256 (#206): claims `sub`/`papel`/`sv`/`jti`/`iss`/`aud`; só HS256 (checagem explícita do `alg`); recusa subir com segredo ausente/curto, placeholder conhecido, segredo publicado ou < 16 caracteres distintos (#222; dev/test: placeholder vira efêmero) |
| `TokenOpaco` | Token de 256 bits (base64url) + SHA-256 hex — fonte única do refresh (#207) e da redefinição (#209) |
| `CifraDeterministica` | AES-256-GCM determinístico (IV sintético por HMAC) dos dados pessoais em repouso (#147); `v1:` + Base64url; legado sem prefixo passa; chave `CRIPTO_CHAVE` obrigatória fora de dev/test |
| `CookieRefresh` | Monta/lê/apaga o cookie `agro_refresh` (HttpOnly, Secure, SameSite=Strict, Path=/api/auth) — #207 |
| `LimitadorTaxa` | Balde de fichas (bucket4j) por chave, em memória, com teto LRU (#208; reusado pelo rate limit da #150) |
| `LimiteTaxaGeralFilter` | Limite geral `/api/**` 100/min e chat 20/min por identidade (IP sem identidade) — #150; depois da identificação, antes da autorização; não é bean |
| `LimiteTaxaAutenticacaoFilter` | 429 `MUITAS_TENTATIVAS` + `Retry-After` por IP em login/cadastro/refresh, antes do filtro JWT (#208); não é bean |
| `RotasAutenticacao` | Fonte única das rotas públicas de autenticação (SecurityConfig + filtros); `caminho(request)` = caminho **decodificado** (comparar o `getRequestURI()` cru deixava `/logi%6E` escapar do limite) |
| `JwtAutenticacaoFilter` | Bearer → identidade; inválido = 401 na hora (antes do mock); não é bean (instanciado no `SecurityConfig`) |

### `danfe/`
| Classe | Responsabilidade |
|--------|-----------------|
| `DanfePdfService` | Gera PDF via JasperReports (`reports/danfe.jrxml`) no **leiaute retrato da NT 2026.010** (#279): canhoto; emitente (logo `reports/agrocontabil-logo.png`) \| DANFE \| chave; natureza \| protocolo; IE \| IE ST \| CNPJ; **CRT \| tipo de regime de apuração do IBS/CBS** (reservado, vazio); destinatário (com bairro e hora de saída); cálculo do imposto (ICMS/IPI com os zeros do XML + totais da nota); **TOTAL DO IBS / CBS / IS** (CBS, IBS UF, IBS Município; IS vazio: o XML não tem o grupo); itens com `[NCM][cClassTrib]`, CST/CFOP, base, alíquotas (efetiva com `gRed`) e valores de CBS/IBS UF/IBS Mun. Quadros facultativos (fatura, transportador, ISSQN, monofásicos) omitidos: o XML não os tem (item 4.4). **Os valores fiscais vêm do `DanfeValores`**: com `xmlGerado` (o assinado, ou o do terceiro na importada), lidos do XML; sem XML (preview), da nota pelos helpers públicos do `NotaFiscalNfeMapper` (`cst`, `cClassTrib`, `parcelasIbs`, `totaisIbs`, `aliquotaEfetiva`, `crt`, `BAIRRO_DESTINATARIO`) que montam o XML. Situação/documento/origem nas informações complementares; NF-e `CANCELADA` leva a marca d'água `NF-e CANCELADA`; logo e "Emitido com o AgroContábil" só na nota própria. "Página X de Y" pela variável `PAGINA_ATUAL`. `gerarPreview` = mesma DANFE com `MARCA_DAGUA`, chave e protocolo em branco (issue #89) |
| `DanfeValores` | Fonte única dos valores fiscais da DANFE (#279): `doXml` (parser sem DTD) ou `daNota` (preview) |
| `DanfeItemRow` | Linha de item do relatório Jasper (JavaBean): células de várias linhas (`\n`) da NT 2026.010 |

### `sefaz/`
| Classe | Responsabilidade |
|--------|-----------------|
| `SefazTransport` | Interface plugável de transmissão |
| `MockSefazTransport` | Mock fiel à SEFAZ nos casos da reconciliação: 204 no reenvio, 573 no evento repetido, 217 → `NAO_ENCONTRADA`, 101 → `CANCELADA` (issue #83); guarda o DigestValue de cada XML transmitido e o devolve como `digVal` na consulta; `autorizar-terceiros` (só dev) autoriza chave desconhecida sem `digVal` (#223) |
| `SoapSefazTransport` | Transporte real (issue #128), com `mock.enabled=false`: SOAP 1.2 + mTLS (certificado A1 do emitente) nos webservices NF-e 4.00, endereço pela UF do cUF da chave ou `PADRAO` (`app.nfe.sefaz.webservices.*`), truststore ICP-Brasil configurável. `ClienteSoapSefaz` (HttpClient do JDK, teto de 1 MB, classificação conexão × indeterminada), `RetornoSefazParser` (cStat → `SituacaoSefaz`), `MensagensSefaz` (envelopes). Testado contra `HttpsServer` com mTLS. Cancelamento: evento 110111 com `infEvento` assinado (`NfeAssinador.assinarElemento`) e autor pelo certificado (`DocumentoEmitente`). Ver `docs/sefaz-transporte-soap.md` |
| `SefazClient` | Retry pela idempotência: `enviar`/`cancelar` só re-tentam `SefazConexaoException`; `consultarStatus` re-tenta qualquer falha de comunicação (issue #83) |
| `SefazRetorno` | Record com cStat, motivo, protocolo, situação e `digestValue` (`infProt/digVal` da consulta, #223; construtor de 5 argumentos = sem digVal) |
| `SituacaoSefaz` | Enum: AUTORIZADA, REJEITADA, PENDENTE, DENEGADA; só na consulta: NAO_ENCONTRADA, CANCELADA (issue #83) |
| `SefazAmbiente` | Enum: HOMOLOGACAO, PRODUCAO |
| `SefazStatusSyncJob` | `@Scheduled` — reconcilia transmissões presas e sincroniza notas PENDENTE_SEFAZ (keyset por IDs + disjuntor — issue #83) |

### `model/`
| Classe | Responsabilidade |
|--------|-----------------|
| `DadoPessoalCifradoConverter` | `@Convert` de CPF/CNPJ, e-mail e telefone de `Produtor`/`Contador`/`CredencialUsuario` (#147); monta a cifra do `Environment` (vale em `@DataJpaTest`) |
| `NotaFiscal` | Entidade principal (tabela `notas_fiscais`) |
| `NotaFiscalItem` | Itens da nota (tabela `nota_fiscal_itens`) |
| `EnderecoDestinatario` | Embedded address do destinatário |
| `Produtor` | Produtor rural autenticado |
| `NotaFiscalValidacao` | Histórico de inconsistências de validação (issue #22) |
| `ClassificacaoTributaria` / `ClassificacaoTributariaAnexoItem` / `ClassificacaoTributariaAnexoNcm` | Tabela oficial do IBS/CBS (#272, changeSet 0048, carregada dos CSVs em `db/changelog/data/`, que o espelho H2 lê por `CSVREAD`): 104 cClassTrib da NF-e; itens dos Anexos I, VII, IX e XV; NCM PERMITIDO/VEDADO com vigência |
| `CfopReferencia` / `NcmReferencia` | Tabelas de referência p/ validação NCM/CFOP (issue #20). Todo NCM semeado precisa existir na nomenclatura oficial versionada em `src/test/resources/ncm/nomenclatura-ncm-oficial.txt` (`NcmSemeadoExisteNaNomenclaturaTest`, #282) |
| `AliquotaIbsUf` (+ `AliquotaIbsUfId`) | Alíquota de IBS (estadual/municipal) por UF **e vigência** — PK composta `(uf, data_inicio)`; `data_fim` NULL = aberta (issues #31/#85) |
| `AliquotaCbs` | Alíquota de CBS por vigência (`aliquota_cbs`, issue #85); substitui o valor fixo do yml como fonte primária |
| `EnderecoProdutor` | `@Embeddable` com o endereço completo do emitente (issue #88) — alimenta `<enderEmit>` e o `cMunFG`; a UF fica em `Produtor.estado` |
| `GuiaFiscal` | Guia fiscal (ICMS/FUNRURAL/DARF/GPS/OUTRO) anexada pelo Contador a um produtor (issue #74) |
| `AlertaTributario` | Alerta RN010 (issue #140): tipo (`TipoAlertaTributario`), referência (guia/nota/apuração do produtor), data-limite, descrição sem PII, status (`StatusAlertaTributario`). Tabela `alerta_tributario` (0028), única por `(tipo, referencia_id, data_limite)` |
| `Contador` | Escritório de contabilidade — 2ª identidade do sistema (issue #93); a carteira é `VinculoCarteira` desde a #177 |
| `Escritorio` / `FuncaoEscritorio` | Escritório (#228, `escritorio`, 0039): dono da carteira, catálogos e convites; o `Contador` é membro (`escritorioId` + `funcao` ADMINISTRADOR/CONTADOR/ASSISTENTE). Cadastro de contador cria o unipessoal; a 0039 migra cada contador para um com o **mesmo id** |
| `ObrigacaoFiscal` | Obrigação do calendário (#181, `obrigacao_fiscal`, 0040): modelo (sem escritório, 11 semeados com ids fixos `00000000-0000-4000-8181-0000000000NN`), versão do escritório (`modeloId`) ou própria; `Periodicidade`, `AjusteFimDeSemana`, `tipoProdutor`, `regimes` (`RegimesTributariosConverter`: lista na coluna), `aplicaA(tipo, regime)` |
| `ConviteMembro` | Convite de equipe (#228, `convite_membro`): e-mail cifrado, função, só o SHA-256 do token, uso único, validade |
| `VinculoCarteira` | Vínculo escritório × produtor (`carteira_vinculo`, 0038, #177, substitui `contador_produtor`; dono = `escritorioId` + `responsavelId` desde a 0039): `SituacaoVinculo` ATIVO/SUSPENSO/ENCERRADO, início/fim, observações internas (ficha) e etiquetas (N:N `carteira_vinculo_etiqueta`). Só ATIVO autoriza; um não encerrado por par (índice parcial PG) |
| `ConviteCarteira` / `EtiquetaCarteira` | Convite por e-mail cifrado (`SituacaoConvite` PENDENTE/ACEITO/RECUSADO, validade) e catálogo de etiquetas do escritório (único pelo nome normalizado) — #177 |
| `RedefinicaoSenha` | Pedido de redefinição (`redefinicao_senha`, 0034, #209): só o SHA-256 do token, validade, uso; um por credencial |
| `SessaoRefresh` | Refresh emitido (`sessao_refresh`, 0032, #207): só o SHA-256, família, versão de sessão, início da família, expiração, `usadoEm`, `revogadoEm` |
| `CredencialUsuario` | E-mail de login + hash da senha (`credencial_usuario`, 0031, #205): papel + **exatamente uma** referência (`produtorId`/`contadorId`, CHECK), `cpfCnpj` só dígitos (único por papel), `ativo`, `versaoSessao` (revogação em massa: o token leva a versão). Sem relacionamento JPA; hash e documento fora do `toString` |
| **Enums** | `StatusNotaFiscal` (RASCUNHO→XML_GERADO→[ENVIANDO]→AUTORIZADA/REJEITADA/PENDENTE_SEFAZ; AUTORIZADA→[CANCELANDO]→CANCELADA; COM_INCONSISTENCIAS — `ENVIANDO`/`CANCELANDO` sao transitorios e bloqueiam toda operacao, issue #96) · `OrigemNotaFiscal` (PROPRIA/IMPORTADA) · `TipoDocumentoFiscal` (NFE/NFA) · `TipoOperacaoCfop` · `AmbitoCfop` · `TipoProdutor` · `TipoGuiaFiscal` (ICMS/FUNRURAL/DARF/GPS/OUTRO) · `StatusGuiaFiscal` (PENDENTE/PAGA/VENCIDA) |
| `Conversa`, `Diagnostico`, `Feedback`, `ImpactoGeral`, `PorteEmpresa`, `TipoFeedback`, `RegimeTributario` | **Scaffolding de sprint futura (AI consultoria)** — sem controller/service |

### `dto/`
- `NotaFiscalRequest/Response` — CRUD principal
- `NotaFiscalItemRequest/Response` — itens da nota
- `CancelamentoRequest/Response` — cancelamento de NF-e (issue #21)
- `ImportacaoNotaResultado` — resultado da importação de XML (issue #19)
- `NotaFiscalValidacaoResponse` — histórico de inconsistências
- `SefazEnvioResponse` / `SefazStatusResponse` — retornos SEFAZ
- `GuiaFiscalRequest/Response` — guia fiscal do Contador (issue #74)
- `VencimentoFiscalResponse` — item do calendário fiscal do produtor (issue #74)
- `CadastroRequest/Response` — cadastro de conta (#205); `toString` do request sem a senha

### `mapper/`
- `NotaFiscalMapper` — entidade ↔ DTO (request/response)
- `GuiaFiscalMapper` — entidade ↔ DTO de guia fiscal e item do calendário (issue #74)
- `CredencialMapper` — credencial → DTO, nunca com hash nem documento (#205)

### `validation/`
Bean Validation customizada (anotação + validator):
| Anotação | Validator | Valida |
|----------|-----------|--------|
| `@ValidCpfCnpj` | `CpfCnpjValidator` | Dígitos verificadores de CPF/CNPJ (issue #18) |
| `@ValidNcm` | `NcmValidator` | NCM contra tabela de referência (issue #20) |
| `@ValidCfop` | `CfopValidator` | CFOP contra tabela de referência (issue #20) |
| `@CodigoIbgeCoerenteComUf` | `CodigoIbgeCoerenteComUfValidator` | Constraint de **classe** no endereço do destinatário: prefixo do código IBGE = cUF da UF (issue #88) |

### `exception/`
| Classe | HTTP | Quando |
|--------|------|--------|
| `ResourceNotFoundException` | 404 | Nota não encontrada / outro produtor |
| `NotaFiscalNaoEditavelException` | 409 | Edição fora de RASCUNHO/COM_INCONSISTENCIAS |
| `NotaFiscalNaoEnviavelException` | 409 | Envio sem XML gerado |
| `NotaFiscalNaoConsultavelException` | 409 | Consulta sem chave de acesso |
| `NotaFiscalNaoCancelavelException` | 409 | Cancelamento em status inválido |
| `CancelamentoForaPrazoException` | 422 | Cancelamento após 30 dias (RN006) |
| `CancelamentoRejeitadoException` | 422 | SEFAZ recusou o evento de cancelamento (`rejectedValue`=cStat, `message`=xMotivo — issue #84) |
| `NotaFiscalInvalidaParaEmissaoException` | 422 | Dados inválidos para emissão |
| `NfaInvalidaParaEmissaoException` | 422 | Dados inválidos para NFA |
| `XmlImportacaoInvalidaException` | 422 | XML importado inválido/malformado |
| `DanfeIndisponivelException` | 409 | PDF pedido antes do XML |
| `XmlIndisponivelException` | 409 | XML pedido antes de ser gerado (issue #89) |
| `DanfePreviewIndisponivelException` | 409 | Preview de DANFE pedido para nota já emitida (issue #89) |
| `DanfeGenerationException` | 500 (genérico) | Falha interna no JasperReports |
| `AssinaturaDigitalException` | 500 (genérico) | Falha na assinatura/cert |
| `SefazComunicacaoException` | 502 | Base das falhas de comunicação; instância direta = indeterminada |
| `SefazConexaoException` | 502 | Requisição comprovadamente não chegou (conexão/DNS/TLS) — retry seguro (issue #83) |
| `SefazRespostaIndeterminadaException` | 502 | Requisição pode ter sido processada (read-timeout etc.) — nunca re-tentar; consultar pela chave (issue #83) |
| `XmlGenerationException` | 422/500 | Falha na geração ou validação XSD |
| `GuiaJaPagaException` | 409 | Guia fiscal já está PAGA (issue #74) |
| `JanelaCalendarioInvalidaException` | 400 | `de > ate` ou janela > 36 meses no calendário fiscal (issue #103) |
| `AssistenteIndisponivelException` | 503 | Modelo da Ceres não respondeu / não configurado (issue #141) |
| `AssistenteOcupadoException` | 503 + `Retry-After` | Sem vaga: limite `app.assistente.max-conversas-simultaneas` ou pergunta do mesmo produtor em andamento (#142, #157) |
| `AssistenteTempoEsgotadoException` | 503 | Pergunta além de `app.assistente.timeout-pergunta` (#157) |
| `NaoAutenticadoException` | 401 | Requisição sem identidade válida: header `X-Usuario-Id` que não resolve, ou profile sem filtro de identidade (issue #92) |
| `DomainException` | configurável | Base para exceções de domínio |
| `ComMensagemCliente` | — | 4xx cujo `getMessage()` é texto de log define o `message` de topo do envelope (`XmlGenerationException`, `LimiteUsoAssistenteException`); 5xx usam o `errors[0]` (#251) |
| `GlobalExceptionHandler` | — | `@RestControllerAdvice` → envelope `ApiError.errors[]` |

### `common/`
- `ApiError` — envelope `{ errors: [] }` de resposta de erro
- `ErrorDetail` — `code` / `field` / `message` / `suggestion` / `rejectedValue` / `expectedValue`
- `ErrorCode` — catálogo de códigos de erro
- `NormalizacaoBusca` — minúsculas, sem acento (NFD sem marcas + NFC, para não expandir escritas como o coreano), espaços colapsados, até 600 caracteres; regra única do que vai para `nota_fiscal.busca_normalizada` e do termo buscado (#174) + `escaparLike`
- `MascaraDadosPessoais` — CPF/CNPJ/e-mail/telefone → marcadores em todo log (via `config/MensagemLogMascaradaConverter` + `logback-spring.xml`) e `truncarIp` (#148); `referencia(UUID)` = `ref:` + 12 hex do SHA-256 em duas metades com hífen (`ref:6bbd4e-b564e9`, imune à regra de CPF da máscara), para o id da credencial nos logs (#222)
- `UfIbge` — cUF das 27 UFs (`Optional`, sem default silencioso) + `prefixoCoerente(codigoIbge, uf)` (issue #88)
- `DocumentoFiscalValidator` — utilitário de validação de documento fiscal (CPF/CNPJ)

### `repository/`
`NotaFiscalRepository` (paginado + ownership `findByIdAndProdutorId`; `JpaSpecificationExecutor` com as peças de `NotaFiscalEspecificacoes` — `doProdutor` é sempre a base, #174), `NotaFiscalValidacaoRepository`, `ProdutorRepository`, `CfopReferenciaRepository`, `NcmReferenciaRepository`, `AliquotaIbsUfRepository`/`AliquotaCbsRepository` (`findVigente` — lista ordenada por `data_inicio DESC`; só o resolvedor consome), `ContadorRepository` (`findByCpfCnpj`), `VinculoCarteiraRepository` (autorização ATIVO, vínculo aberto sob lock, agregações de atenção `ContagemPorProdutor`, etiquetas `EtiquetaDoVinculo` — #177), `ConviteCarteiraRepository`, `EtiquetaCarteiraRepository`, `CredencialUsuarioRepository` (`findByEmail`, `existsByEmail`, `existsByPapelAndCpfCnpj` — #205; `ProdutorRepository`/`ContadorRepository` ganharam `existsByCpfCnpjIn` p/ checar as grafias com e sem máscara), `GuiaFiscalRepository` (ownership `findByIdAndProdutorId` + `findByStatusAndDataVencimentoBefore` p/ job — issue #74; listagem paginada `findByProdutorId(Pageable)` e janela do calendário — issue #103) · e os do scaffolding AI: `ConversaRepository`, `DiagnosticoRepository`, `FeedbackRepository`.

## Endpoints REST (`/api/notas`)
| Método | Path | Descrição |
|--------|------|-----------|
| GET | `/api/notas` | Lista paginada do produtor autenticado, com filtros `status`/`tipoDocumento`/`emitidaDe`/`emitidaAte`/`busca` (`FiltroNotasRequest` → `NotaFiscalEspecificacoes`, issue #174) |
| GET | `/api/notas/{id}` | Busca por ID (anti-IDOR 404) |
| POST | `/api/notas` | Cria nota (status RASCUNHO) |
| POST | `/api/notas/importar` | Importa XML(s) de NF-e (multipart; origem=IMPORTADA; destinatário, assinatura e SEFAZ conferidos — #223) |
| PUT | `/api/notas/{id}` | Atualiza (só RASCUNHO/COM_INCONSISTENCIAS) |
| DELETE | `/api/notas/{id}` | Exclui (só RASCUNHO/COM_INCONSISTENCIAS) |
| POST | `/api/notas/{id}/gerar-xml` | Gera e assina XML NF-e (RASCUNHO→XML_GERADO) |
| GET | `/api/notas/{id}/xml` | Baixa XML assinado (`application/xml`, `attachment`; 409 se ainda nao gerado) |
| GET | `/api/notas/{id}/pdf` | Baixa DANFE (`application/pdf`) |
| GET | `/api/notas/{id}/danfe-preview` | Preview da DANFE de rascunho com marca d'água, sem validar nem gravar (issue #89) |
| POST | `/api/notas/{id}/enviar-sefaz` | Transmite à SEFAZ (XML_GERADO→AUTORIZADA/REJEITADA) |
| GET | `/api/notas/{id}/status-sefaz` | Consulta e sincroniza status na SEFAZ |
| POST | `/api/notas/{id}/cancelar` | Cancela NF-e autorizada (limite 30 dias — RN006) |
| GET | `/api/notas/{id}/validacoes` | Histórico de inconsistências (anti-IDOR 404) |

## Endpoints REST (`/api/contador/guias`, `/api/calendario-fiscal` — issue #74)
| Método | Path | Descrição |
|--------|------|-----------|
| POST | `/api/contador/guias` | Cria guia fiscal (produtorId explícito, sem auth de Contador) |
| GET | `/api/contador/guias?produtorId=&page=&size=` | Lista paginada das guias do produtor (corpo array + `X-Total-Count`; padrão 100, máx. 200 — issue #103) |
| PATCH | `/api/contador/guias/{id}/pagar` | Marca guia como paga (produtor autenticado, anti-IDOR real) |
| GET | `/api/calendario-fiscal/{produtorId}/vencimentos?de=&ate=` | Vencimentos do produtor na janela (padrão mês corrente ±12 meses; máx. 36 — issue #103) |

## Recursos em `src/main/resources/`
- `xsd/` — XSD oficial NF-e layout 4.00, pacote de liberação **PL_010g v1.03** (NT 2025.002-RTC v1.50, NT 2026.006/2026.008; #278)
- `reports/` — templates `.jrxml` do JasperReports (DANFE)
- `application.yml` / `application-test.yml` / `application-dev.yml` / `application-homolog.yml` / `application-prod.yml`

## Testes em `src/test/`
- `resources/certs/` — certificado autoassinado para testes de assinatura
- Padrão: `*Test.java` (unitário Mockito) + teste de integração (Spring + H2)
- `ProdutorTestFixtures` — emitente com IE e endereço (`produtorCompleto`, `enderecoParaUf`, variantes inválidas) exigido pelos fluxos de emissão desde a issue #88

## Migrations (Liquibase)
- `db/changelog/db.changelog-master.yaml` — master changelog
- `db/changelog/changes/` — changesets `.xml` individuais: `0001-initial-schema` … `0038-carteira-contador` (0045-nota-fiscal-chave-por-produtor = chave única por produtor+chave+origem, #223; 0046-ncm-codigos-oficiais = NCMs inexistentes trocados pelos oficiais, #282; 0047-ibs-2026-estadual = IBS 2026 todo estadual, #277; 0048-classificacao-tributaria-oficial = tabela oficial de redução, #272; próximo número livre: **0049**; 0038 migra `contador_produtor` → `carteira_vinculo` e tem índices únicos parciais só no PG; 0037 usa `pg_trgm` + `btree_gin`; 0026 e 0027 são só PostgreSQL — extensão `vector`, `tsvector`)
- **Nunca editar changesets já aplicados.** Nova mudança = novo arquivo.
