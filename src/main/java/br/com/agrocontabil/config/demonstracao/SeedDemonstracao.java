package br.com.agrocontabil.config.demonstracao;

import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.Cliente;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.Endereco;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.LinhaCompra;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.LinhaVenda;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.Membro;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.Parte;
import br.com.agrocontabil.config.demonstracao.CenarioDemonstracao.Produto;
import br.com.agrocontabil.exception.DomainException;
import br.com.agrocontabil.exception.SenhaFracaException;
import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.EtiquetaCarteira;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.ModeloPedido;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Pendencia;
import br.com.agrocontabil.model.PendenciaAnexo;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoAnexo;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.nfe.NfeAssinador;
import br.com.agrocontabil.nfe.NfeToNotaFiscalMapper;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.TributacaoIcms;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.EtiquetaCarteiraRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ModeloPedidoRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.PendenciaAnexoRepository;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.AlertaTributarioService;
import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.CreditoTributarioService;
import br.com.agrocontabil.service.GeracaoNotaFiscalService;
import br.com.agrocontabil.service.PoliticaSenha;
import br.com.agrocontabil.service.TributacaoService;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Semeia a demonstração da banca (issue #315): o escritório "Contabilidade Rural Oeste" com equipe,
 * carteira diversa e meses de movimento, montados para a apresentação mostrar o produto inteiro.
 *
 * <p><strong>Só com {@code app.demonstracao.habilitada=true}</strong>, e a aplicação recusa subir
 * com ela fora de {@code dev}/{@code homolog}/{@code test} (lista de permissão, como o modo LOG do
 * e-mail): um seed que grava contas com senha conhecida não pode existir em produção.
 *
 * <p>As notas passam pelos <strong>mesmos componentes da aplicação</strong>, para serem notas de
 * verdade do ponto de vista do sistema:
 *
 * <ul>
 *   <li>NF-e própria: {@link TributacaoService} (IBS/CBS pelo resolvedor e pela tabela oficial) e
 *       {@link GeracaoNotaFiscalService} (validação fiscal, JAXB, assinatura, XSD). A autorização é
 *       a que o mock da SEFAZ daria; a chave sai do próprio XML;</li>
 *   <li>NF-e de compra: o fornecedor fictício "emite" pelo mapper e pela assinadora, e a nota entra
 *       pelo {@link NfeToNotaFiscalMapper} da importação, com o crédito do
 *       {@link CreditoTributarioService}. O validador fiscal fica de fora só aqui: ele confere o NCM
 *       contra a referência <em>agropecuária</em> do emitente, e insumo não está nela;</li>
 *   <li>Status da fila (aguardando aprovação, devolvida, com inconsistências, rascunho, cancelada)
 *       com o histórico que o fluxo real deixaria.</li>
 * </ul>
 *
 * <p>Datas relativas ao dia da semeadura (oito meses para trás), para a demonstração nunca nascer
 * velha. Variação de quantidade e preço por {@link Random} com semente fixa por cliente: duas
 * semeaduras do mesmo dia dão as mesmas notas.
 *
 * <p><strong>Idempotente</strong> pela administradora do escritório: com ela no banco, nada é
 * recriado. Os dados vão numa transação só (falha = nada gravado); depois, fora dela, os meses
 * antigos são fechados (RN004, uma transação por produtor no {@link ApuracaoService}), os alertas
 * gerados e a cota da Ceres preparada ({@link CotaDemonstracaoCeres}).
 */
@Component
@ConditionalOnProperty(name = "app.demonstracao.habilitada", havingValue = "true")
public class SeedDemonstracao implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedDemonstracao.class);

  /** Profiles em que a demonstração pode existir. */
  static final List<String> PERFIS_PERMITIDOS = List.of("dev", "homolog", "test");

  static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
  private static final int MESES = 8;
  private static final int TIMEOUT_SEMEADURA_SEGUNDOS = 600;
  private static final Pattern CHAVE_NO_XML = Pattern.compile("Id=\"NFe(\\d{44})\"");
  private static final java.time.format.DateTimeFormatter COMPETENCIA =
      java.time.format.DateTimeFormatter.ofPattern("MM/yyyy");
  private static final String NATUREZA_VENDA = "VENDA DE PRODUCAO DO ESTABELECIMENTO";
  private static final String NATUREZA_REVENDA = "VENDA DE MERCADORIA ADQUIRIDA DE TERCEIROS";

  /** PDF mínimo e válido: o anexo da pendência enviada. */
  private static final byte[] PDF_COMPROVANTE = ("%PDF-1.4\n"
      + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
      + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
      + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj\n"
      + "trailer<</Root 1 0 R>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final EscritorioRepository escritorioRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final EtiquetaCarteiraRepository etiquetaRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final NotaFiscalRepository notaRepository;
  private final NotaFiscalRevisaoRepository revisaoRepository;
  private final NotaFiscalValidacaoRepository validacaoRepository;
  private final GuiaFiscalRepository guiaRepository;
  private final PendenciaRepository pendenciaRepository;
  private final PendenciaAnexoRepository anexoRepository;
  private final ModeloPedidoRepository modeloPedidoRepository;
  private final TributacaoService tributacaoService;
  private final CreditoTributarioService creditoService;
  private final GeracaoNotaFiscalService geracaoService;
  private final NotaFiscalNfeMapper nfeMapper;
  private final XmlGenerator xmlGenerator;
  private final NfeAssinador assinador;
  private final NfeToNotaFiscalMapper importacaoMapper;
  private final ApuracaoService apuracaoService;
  private final AlertaTributarioService alertaService;
  private final CotaDemonstracaoCeres cotaCeres;
  private final PasswordEncoder passwordEncoder;
  private final PoliticaSenha politicaSenha;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;
  private final String senhaSeed;
  private volatile Resumo ultimoResumo;

  /** Construído pelo Spring; recusa a subida fora dos profiles permitidos. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public SeedDemonstracao(ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository, EscritorioRepository escritorioRepository,
      VinculoCarteiraRepository vinculoRepository, EtiquetaCarteiraRepository etiquetaRepository,
      CredencialUsuarioRepository credencialRepository, NotaFiscalRepository notaRepository,
      NotaFiscalRevisaoRepository revisaoRepository,
      NotaFiscalValidacaoRepository validacaoRepository, GuiaFiscalRepository guiaRepository,
      PendenciaRepository pendenciaRepository, PendenciaAnexoRepository anexoRepository,
      ModeloPedidoRepository modeloPedidoRepository,
      TributacaoService tributacaoService, CreditoTributarioService creditoService,
      GeracaoNotaFiscalService geracaoService, NotaFiscalNfeMapper nfeMapper,
      XmlGenerator xmlGenerator, NfeAssinador assinador, NfeToNotaFiscalMapper importacaoMapper,
      ApuracaoService apuracaoService, AlertaTributarioService alertaService,
      CotaDemonstracaoCeres cotaCeres, PasswordEncoder passwordEncoder,
      PoliticaSenha politicaSenha, PlatformTransactionManager transactionManager, Clock clock,
      Environment ambiente, @Value("${app.seed.senha:}") String senhaSeed) {
    exigirPerfilPermitido(ambiente);
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.escritorioRepository = escritorioRepository;
    this.vinculoRepository = vinculoRepository;
    this.etiquetaRepository = etiquetaRepository;
    this.credencialRepository = credencialRepository;
    this.notaRepository = notaRepository;
    this.revisaoRepository = revisaoRepository;
    this.validacaoRepository = validacaoRepository;
    this.guiaRepository = guiaRepository;
    this.pendenciaRepository = pendenciaRepository;
    this.anexoRepository = anexoRepository;
    this.modeloPedidoRepository = modeloPedidoRepository;
    this.tributacaoService = tributacaoService;
    this.creditoService = creditoService;
    this.geracaoService = geracaoService;
    this.nfeMapper = nfeMapper;
    this.xmlGenerator = xmlGenerator;
    this.assinador = assinador;
    this.importacaoMapper = importacaoMapper;
    this.apuracaoService = apuracaoService;
    this.alertaService = alertaService;
    this.cotaCeres = cotaCeres;
    this.passwordEncoder = passwordEncoder;
    this.politicaSenha = politicaSenha;
    // Transacao propria, com prazo de semeadura: o padrao de 30 s (#98) e para requisicao, e
    // assinar e validar no XSD perto de duzentas NF-e numa VM ARM pode passar disso.
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setTimeout(TIMEOUT_SEMEADURA_SEGUNDOS);
    this.clock = clock;
    this.senhaSeed = senhaSeed;
  }

  static void exigirPerfilPermitido(Environment ambiente) {
    List<String> perfis = Arrays.asList(ambiente.getActiveProfiles());
    if (perfis.contains("prod") || perfis.stream().noneMatch(PERFIS_PERMITIDOS::contains)) {
      throw new IllegalStateException("app.demonstracao.habilitada=true grava contas de "
          + "demonstracao com a SEED_SENHA: permitido so nos profiles " + PERFIS_PERMITIDOS
          + ", ativos " + perfis);
    }
  }

  /**
   * Semeia (ou só completa as credenciais) e prepara a cota da Ceres.
   *
   * <p>Falha aqui vira {@code ERROR} e a aplicação sobe sem a demonstração: um problema de dado de
   * apresentação não pode derrubar o ambiente inteiro (o deploy faria rollback do backend).
   */
  @Override
  public void run(String... args) {
    try {
      if (jaSemeado()) {
        completarCredenciais();
        int modelos = completarModelosPedido();
        log.info("Demonstracao da banca ja semeada: nada recriado ({} modelos de pedido novos).",
            modelos);
      } else {
        Resumo resumo = semear();
        ultimoResumo = resumo;
        log.info("Demonstracao da banca semeada: {} produtores, {} notas ({} recusadas), {} guias, "
            + "{} pendencias, {} meses fechados", resumo.produtores(), resumo.notas(),
            resumo.notasRecusadas(), resumo.guias(), resumo.pendencias(), resumo.mesesFechados());
      }
      cotaCeres.prepararSemana();
    } catch (RuntimeException e) {
      log.error("Demonstracao da banca nao semeada; a aplicacao segue sem ela.", e);
    }
  }

  /**
   * Dá credencial às contas da demonstração que ainda não têm, como o {@code DataLoader} faz a cada
   * subida: banco semeado sem {@code SEED_SENHA} ganha o login quando a variável é definida, sem
   * precisar zerar. Idempotente pelo e-mail.
   */
  void completarCredenciais() {
    Execucao ex = new Execucao(hashDaSenhaSeed(), LocalDate.now(clock.withZone(ZONA)));
    for (Membro membro : CenarioDemonstracao.EQUIPE) {
      contadorRepository.findByCpfCnpj(membro.cpf()).ifPresent(contador -> credencial(ex,
          membro.email(), PapelUsuario.CONTADOR, membro.cpf(), null, contador.getId()));
    }
    for (Cliente cliente : CenarioDemonstracao.CARTEIRA) {
      produtorRepository.findByCpfCnpj(cliente.documento()).ifPresent(produtor -> credencial(ex,
          cliente.email(), PapelUsuario.PRODUTOR, cliente.documento(), produtor.getId(), null));
    }
  }

  /**
   * Dá ao escritório da demonstração os modelos de pedido (#517) quando ele não tem nenhum: o banco
   * semeado antes deles (a VPS) os ganha na subida, sem reset. Um ensaio que apague todos os vê
   * voltar na subida seguinte; um que só edite ou apague alguns fica como está.
   *
   * @return quantos modelos foram criados
   */
  int completarModelosPedido() {
    Integer criados = transactionTemplate.execute(status -> contadorRepository
        .findByCpfCnpj(CenarioDemonstracao.EQUIPE.get(0).cpf())
        .map(Contador::getEscritorioId)
        .flatMap(escritorioRepository::findById)
        .filter(e -> CenarioDemonstracao.ESCRITORIO_CNPJ.equals(e.getCnpj()))
        .filter(e -> modeloPedidoRepository.countByEscritorioId(e.getId()) == 0)
        .map(e -> modelosPedido(e.getId()))
        .orElse(0));
    return criados == null ? 0 : criados;
  }

  /** O que a última semeadura desta instância gravou ({@code null} se nada foi semeado). */
  Resumo ultimoResumo() {
    return ultimoResumo;
  }

  boolean jaSemeado() {
    return contadorRepository.findByCpfCnpj(CenarioDemonstracao.EQUIPE.get(0).cpf()).isPresent();
  }

  /**
   * Grava a demonstração inteira e, depois do commit, fecha os meses antigos e gera os alertas.
   *
   * @return contagens do que foi gravado
   */
  Resumo semear() {
    Execucao execucao = new Execucao(hashDaSenhaSeed(), LocalDate.now(clock.withZone(ZONA)));
    transactionTemplate.executeWithoutResult(status -> gravar(execucao));

    int fechados = 0;
    YearMonth atual = YearMonth.from(execucao.hoje);
    // RN004: os meses ate dois atras ficam FECHADOS; o mes passado fica aberto para o fechamento
    // automatico (e a reabertura do contador, RN005, tem meses fechados recentes para mostrar).
    for (int m = MESES - 1; m >= 2; m--) {
      fechados += apuracaoService.fecharPeriodo(atual.minusMonths(m).toString()).concluidas() > 0
          ? 1 : 0;
    }
    alertaService.gerarAlertas();
    return new Resumo(execucao.produtores, execucao.notas, execucao.notasRecusadas, execucao.guias,
        execucao.pendencias, fechados);
  }

  // ===============================================================================================
  // Gravação
  // ===============================================================================================

  private void gravar(Execucao ex) {
    Escritorio escritorio = escritorioRepository.save(Escritorio.builder()
        .nome(CenarioDemonstracao.ESCRITORIO_NOME)
        .cnpj(CenarioDemonstracao.ESCRITORIO_CNPJ)
        .slug(CenarioDemonstracao.ESCRITORIO_SLUG)
        .build());

    List<Contador> equipe = new ArrayList<>();
    for (Membro membro : CenarioDemonstracao.EQUIPE) {
      Contador contador = contadorRepository.save(Contador.builder()
          .escritorioId(escritorio.getId())
          .funcao(membro.funcao())
          .nomeCompleto(membro.nome())
          .cpfCnpj(membro.cpf())
          .crc(membro.crc())
          .email(membro.email())
          .telefone(membro.telefone())
          .build());
      credencial(ex, membro.email(), PapelUsuario.CONTADOR, membro.cpf(), null, contador.getId());
      equipe.add(contador);
    }
    modelosPedido(escritorio.getId());

    Map<String, EtiquetaCarteira> etiquetas = new HashMap<>();
    for (Cliente cliente : CenarioDemonstracao.CARTEIRA) {
      Produtor produtor = produtorRepository.save(produtor(cliente));
      ex.produtores++;
      credencial(ex, cliente.email(), PapelUsuario.PRODUTOR, cliente.documento(), produtor.getId(),
          null);
      Contador responsavel = cliente.responsavel() >= 0 ? equipe.get(cliente.responsavel()) : null;
      vinculo(ex, escritorio, produtor, cliente, responsavel, etiquetas);

      Random aleatorio = new Random(cliente.chave().hashCode());
      AtomicLong numero = new AtomicLong(cliente.numeroInicial());
      boolean comEscritorio = cliente.situacao() == SituacaoVinculo.ATIVO;
      Contador revisor = responsavel != null ? responsavel : equipe.get(0);

      // Numera depois de ordenar por data: a serie de uma NF-e real cresce com o tempo, e o laco
      // por linha de produto (soja de todos os meses, depois milho) daria nota antiga com numero
      // maior que a do mes corrente.
      List<VendaPlanejada> vendas = new ArrayList<>();
      for (LinhaVenda linha : cliente.vendas()) {
        for (int m : linha.meses()) {
          for (int k = 0; k < linha.notasPorMes(); k++) {
            vendas.add(new VendaPlanejada(emissao(ex.hoje, m, 4 + k * 9 + aleatorio.nextInt(4)),
                linha.compradores().get((m + k) % linha.compradores().size()), linha.produto(),
                variar(linha.quantidade(), aleatorio), preco(linha.produto(), m, aleatorio),
                Especial.NENHUM));
          }
        }
      }
      vendas.addAll(especiais(cliente, ex.hoje));
      vendas.sort(Comparator.comparing(VendaPlanejada::emissao));
      for (VendaPlanejada venda : vendas) {
        emitir(ex, venda, produtor, numero.getAndIncrement(), comEscritorio, revisor);
      }

      List<CompraPlanejada> compras = new ArrayList<>();
      for (LinhaCompra linha : cliente.compras()) {
        for (int m : linha.meses()) {
          compras.add(new CompraPlanejada(emissao(ex.hoje, m, 6 + aleatorio.nextInt(18)), linha,
              variar(linha.quantidade(), aleatorio)));
        }
      }
      compras.sort(Comparator.comparing(CompraPlanejada::emissao));
      AtomicLong numeroFornecedor = new AtomicLong(40_000 + Math.abs(aleatorio.nextInt(9000)));
      for (CompraPlanejada compra : compras) {
        compra(ex, produtor, compra.linha().fornecedor(), numeroFornecedor.getAndIncrement(),
            compra.emissao(), compra.linha().produto(), compra.quantidade());
      }

      if (cliente.situacao() != SituacaoVinculo.ENCERRADO) {
        guias(ex, produtor, cliente);
      }
      pendencias(ex, cliente, produtor, escritorio, revisor);
    }
  }

  private Produtor produtor(Cliente cliente) {
    Endereco e = cliente.endereco();
    return Produtor.builder()
        .nomeCompleto(cliente.nome())
        .cpfCnpj(cliente.documento())
        .tipoProdutor(cliente.pessoaJuridica() ? TipoProdutor.PESSOA_JURIDICA
            : TipoProdutor.PESSOA_FISICA)
        .regimeTributario(cliente.regime())
        .porteEmpresa(cliente.porte())
        .culturas(cliente.culturas().toArray(String[]::new))
        .faturamentoAnual(cliente.faturamento())
        .estado(e.uf())
        .inscricaoEstadual(cliente.ie())
        .endereco(EnderecoProdutor.builder()
            .logradouro(e.logradouro())
            .numero(e.numero())
            .bairro(e.bairro())
            .codigoMunicipioIbge(e.codigoIbge())
            .municipio(e.municipio())
            .cep(e.cep())
            .build())
        .email(cliente.email())
        .telefone(cliente.telefone())
        .build();
  }

  private void vinculo(Execucao ex, Escritorio escritorio, Produtor produtor, Cliente cliente,
      Contador responsavel, Map<String, EtiquetaCarteira> etiquetas) {
    Set<EtiquetaCarteira> doCliente = new LinkedHashSet<>();
    for (String nome : cliente.etiquetas()) {
      doCliente.add(etiquetas.computeIfAbsent(nome, n -> etiquetaRepository.save(
          EtiquetaCarteira.builder()
              .escritorioId(escritorio.getId())
              .nome(n)
              .nomeNormalizado(NormalizacaoBusca.normalizar(n))
              .build())));
    }
    boolean novo = cliente.etiquetas().contains("Novo cliente");
    OffsetDateTime inicio = ex.hoje.minusMonths(novo ? 2 : 26).atStartOfDay(ZONA)
        .toOffsetDateTime();
    vinculoRepository.save(VinculoCarteira.builder()
        .escritorioId(escritorio.getId())
        .responsavelId(responsavel != null ? responsavel.getId() : null)
        .produtorId(produtor.getId())
        .situacao(cliente.situacao())
        .inicio(inicio)
        .fim(cliente.situacao() == SituacaoVinculo.ENCERRADO
            ? ex.hoje.minusMonths(4).atStartOfDay(ZONA).toOffsetDateTime() : null)
        .observacoes(cliente.observacoes())
        .etiquetas(doCliente)
        .build());
  }

  // ===============================================================================================
  // Notas
  // ===============================================================================================

  private NotaFiscal venda(Produtor produtor, Parte comprador, long numero,
      OffsetDateTime emissao, Produto produto, BigDecimal quantidade, BigDecimal preco) {
    String cfop = produtor.getEstado().equals(comprador.endereco().uf()) ? "5101" : "6101";
    return nota(produtor, comprador, numero, emissao, NATUREZA_VENDA,
        item(1, produto, cfop, quantidade, preco));
  }

  private NotaFiscal nota(Produtor emitente, Parte destinatario, long numero,
      OffsetDateTime emissao, String natureza, NotaFiscalItem... itens) {
    Endereco e = destinatario.endereco();
    NotaFiscal nota = NotaFiscal.builder()
        .numero(numero)
        .serie(1)
        .modelo("55")
        .naturezaOperacao(natureza)
        .dataEmissao(emissao)
        .dataSaida(emissao)
        .produtor(emitente)
        .destinatarioNome(destinatario.nome())
        .destinatarioCpfCnpj(destinatario.documento())
        .destinatarioIe(destinatario.ie())
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro(e.logradouro())
            .numero(e.numero())
            .municipio(e.municipio())
            .codigoMunicipioIbge(e.codigoIbge())
            .uf(e.uf())
            .cep(e.cep())
            .build())
        .status(StatusNotaFiscal.RASCUNHO)
        .tipoDocumento(TipoDocumentoFiscal.NFE)
        .origem(OrigemNotaFiscal.PROPRIA)
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .build();
    BigDecimal total = BigDecimal.ZERO;
    for (NotaFiscalItem item : itens) {
      nota.addItem(item);
      total = total.add(item.getValorTotal());
    }
    nota.setValorTotalProdutos(total);
    nota.setValorTotalNota(total);
    tributacaoService.calcular(nota);
    return nota;
  }

  private static NotaFiscalItem item(int numero, Produto produto, String cfop,
      BigDecimal quantidade, BigDecimal preco) {
    return NotaFiscalItem.builder()
        .numeroItem(numero)
        .codigoProduto(produto.codigo())
        .descricao(produto.descricao())
        .ncm(produto.ncm())
        .cClassTrib(produto.cClassTrib())
        .cfop(cfop)
        .unidade(produto.unidade())
        .quantidade(quantidade)
        .valorUnitario(preco)
        .valorTotal(quantidade.multiply(preco).setScale(2, RoundingMode.HALF_UP))
        .build();
  }

  /**
   * Gera e assina o XML pelo pipeline de emissão e grava a nota como o mock da SEFAZ a
   * autorizaria. Recusa do validador vira log e contagem: o teste exige zero recusas.
   */
  private boolean autorizar(Execucao ex, NotaFiscal nota) {
    try {
      String xml = geracaoService.gerarXml(nota);
      nota.setXmlGerado(xml);
      nota.setVersaoLayoutNfe("4.00");
      nota.setDataGeracaoXml(nota.getDataEmissao().plusSeconds(20));
      nota.setChaveAcesso(chave(xml));
      autorizada(ex, nota);
      notaRepository.save(nota);
      ex.notas++;
      return true;
    } catch (DomainException e) {
      ex.notasRecusadas++;
      log.warn("Demonstracao: nota {} do produtor {} recusada pela validacao: {}",
          nota.getNumero(), nota.getProdutor().getId(), e.getErrors());
      return false;
    }
  }

  private void autorizada(Execucao ex, NotaFiscal nota) {
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setProtocoloAutorizacao(protocolo(ex, nota.getChaveAcesso()));
    nota.setDataAutorizacao(nota.getDataEmissao().plusSeconds(84));
    nota.setSefazCstat("100");
    nota.setSefazMotivo("Autorizado o uso da NF-e");
    nota.setSefazAmbiente(SefazAmbiente.HOMOLOGACAO);
  }

  /**
   * Compra de insumo: o fornecedor emite (mapper + assinatura, sem o validador de emissão, que
   * confere o NCM contra a referência agropecuária) e o produtor importa pelo mapper real.
   */
  private void compra(Execucao ex, Produtor produtor, Parte fornecedor, long numero,
      OffsetDateTime emissao, Produto produto, BigDecimal quantidade) {
    Endereco ef = fornecedor.endereco();
    Produtor emitente = Produtor.builder()
        .nomeCompleto(fornecedor.nome())
        .cpfCnpj(fornecedor.documento())
        .tipoProdutor(TipoProdutor.PESSOA_JURIDICA)
        .regimeTributario(RegimeTributario.REAL)
        .estado(ef.uf())
        .inscricaoEstadual(fornecedor.ie())
        .endereco(EnderecoProdutor.builder()
            .logradouro(ef.logradouro())
            .numero(ef.numero())
            .bairro(ef.bairro())
            .codigoMunicipioIbge(ef.codigoIbge())
            .municipio(ef.municipio())
            .cep(ef.cep())
            .build())
        .build();
    EnderecoProdutor ep = produtor.getEndereco();
    Parte comoDestinatario = new Parte(produtor.getNomeCompleto(), produtor.getCpfCnpj(),
        produtor.getInscricaoEstadual(), new Endereco(ep.getLogradouro(), ep.getNumero(),
            ep.getBairro(), ep.getCodigoMunicipioIbge(), ep.getMunicipio(), produtor.getEstado(),
            ep.getCep()));
    String cfop = ef.uf().equals(produtor.getEstado()) ? "5102" : "6102";
    NotaFiscal doFornecedor = nota(emitente, comoDestinatario, numero, emissao, NATUREZA_REVENDA,
        item(1, produto, cfop, quantidade, produto.preco()));

    NFe nfe = nfeMapper.toNfe(doFornecedor);
    String xml = assinador.assinar(xmlGenerator.marshal(nfe), nfe.getInfNFe().getId());
    NotaFiscal importada = importacaoMapper.toNotaFiscal(nfe, produtor, xml);
    autorizada(ex, importada);
    creditoService.calcular(importada);
    notaRepository.save(importada);
    ex.notas++;
  }

  private void historicoAprovado(NotaFiscal nota, Produtor produtor, Contador revisor) {
    revisao(nota, AcaoRevisao.ENVIADA, null, produtor.getId(), PapelUsuario.PRODUTOR,
        nota.getDataEmissao().minusHours(3));
    revisao(nota, AcaoRevisao.APROVADA, null, revisor.getId(), PapelUsuario.CONTADOR,
        nota.getDataEmissao().minusHours(1));
  }

  private void revisao(NotaFiscal nota, AcaoRevisao acao, String motivo, UUID usuarioId,
      PapelUsuario papel, OffsetDateTime quando) {
    revisaoRepository.save(NotaFiscalRevisao.builder()
        .notaFiscalId(nota.getId())
        .acao(acao)
        .motivo(motivo)
        .usuarioId(usuarioId)
        .papel(papel.name())
        .ocorridoEm(quando)
        .build());
  }

  /** Algum item sem CST de ICMS, que o pipeline de emissão barraria (422 ICMS_A_DEFINIR). */
  private static boolean icmsADefinir(NotaFiscal nota) {
    return nota.getItens().stream().anyMatch(i -> TributacaoIcms.problema(nota, i).isPresent());
  }

  /** O que a venda planejada vira: nota autorizada ou um dos estados da fila. */
  enum Especial { NENHUM, NO_PRAZO, CANCELADA, AGUARDANDO, DEVOLVIDA, RASCUNHO, COM_INCONSISTENCIAS }

  /** Compra antes de ganhar o número do fornecedor. */
  private record CompraPlanejada(OffsetDateTime emissao, LinhaCompra linha,
      BigDecimal quantidade) {}

  /** Venda antes de ganhar número: o número só sai depois da ordenação por data. */
  record VendaPlanejada(OffsetDateTime emissao, Parte comprador, Produto produto,
      BigDecimal quantidade, BigDecimal preco, Especial especial) {}

  /** As notas que a apresentação procura: fila de revisão, erro de validação, cancelamento. */
  private static List<VendaPlanejada> especiais(Cliente cliente, LocalDate hoje) {
    Produto soja = CenarioDemonstracao.soja();
    return switch (cliente.chave()) {
      case CenarioDemonstracao.CHAVE_PRINCIPAL -> List.of(
          // Dentro do prazo de cancelamento (RN006), perto do fim: gera o alerta da RN010.
          new VendaPlanejada(noDia(hoje.minusDays(27), 10),
              CenarioDemonstracao.graneleiraTelesPires(), soja, new BigDecimal("36850"),
              soja.preco(), Especial.NO_PRAZO),
          new VendaPlanejada(noDia(hoje.minusDays(52), 15),
              CenarioDemonstracao.graneleiraTelesPires(), soja, new BigDecimal("37120"),
              soja.preco(), Especial.CANCELADA),
          new VendaPlanejada(noDia(hoje.minusDays(3), 14),
              CenarioDemonstracao.tradingLucas(), soja, new BigDecimal("37980"),
              soja.preco(), Especial.DEVOLVIDA),
          new VendaPlanejada(noDia(hoje.minusDays(1), 9),
              CenarioDemonstracao.tradingLucas(), soja, new BigDecimal("37400"),
              soja.preco(), Especial.AGUARDANDO),
          new VendaPlanejada(noDia(hoje, 8), CenarioDemonstracao.graneleiraTelesPires(), soja,
              new BigDecimal("36200"), soja.preco(), Especial.RASCUNHO));
      case "santaclara" -> List.of(new VendaPlanejada(noDia(hoje, 8),
          CenarioDemonstracao.torrefacaoUberlandia(), CenarioDemonstracao.cafe(),
          new BigDecimal("310"), CenarioDemonstracao.cafe().preco(), Especial.AGUARDANDO));
      case "luiz" -> List.of(new VendaPlanejada(noDia(hoje.minusDays(2), 7),
          CenarioDemonstracao.supermercadoToledo(), CenarioDemonstracao.leite(),
          new BigDecimal("20850"), CenarioDemonstracao.leite().preco(), Especial.AGUARDANDO));
      case "helena" -> List.of(new VendaPlanejada(noDia(hoje.minusDays(1), 6),
          CenarioDemonstracao.distribuidoraSaoPaulo(), CenarioDemonstracao.tomate(),
          new BigDecimal("640"), CenarioDemonstracao.tomate().preco(),
          Especial.COM_INCONSISTENCIAS));
      default -> List.of();
    };
  }

  private void emitir(Execucao ex, VendaPlanejada venda, Produtor produtor, long numero,
      boolean comEscritorio, Contador revisor) {
    if (venda.especial() == Especial.COM_INCONSISTENCIAS) {
      comInconsistencias(ex, produtor, venda, numero);
      return;
    }
    NotaFiscal nota = venda(produtor, venda.comprador(), numero, venda.emissao(), venda.produto(),
        venda.quantidade(), venda.preco());
    switch (venda.especial()) {
      case CANCELADA -> {
        if (autorizar(ex, nota)) {
          historicoAprovado(nota, produtor, revisor);
          nota.setStatus(StatusNotaFiscal.CANCELADA);
          nota.setDataCancelamento(nota.getDataEmissao().plusHours(2));
          nota.setMotivoCancelamento("Nota emitida em duplicidade para a mesma carga "
              + "(romaneio 18442); a carga saiu na nota anterior.");
          nota.setProtocoloCancelamento(protocolo(ex, nota.getChaveAcesso()));
          notaRepository.save(nota);
        }
      }
      case AGUARDANDO -> {
        nota.setStatus(StatusNotaFiscal.AGUARDANDO_APROVACAO);
        notaRepository.save(nota);
        ex.notas++;
        revisao(nota, AcaoRevisao.ENVIADA, null, produtor.getId(), PapelUsuario.PRODUTOR,
            nota.getDataEmissao());
      }
      case DEVOLVIDA -> {
        nota.setStatus(StatusNotaFiscal.DEVOLVIDA);
        notaRepository.save(nota);
        ex.notas++;
        revisao(nota, AcaoRevisao.ENVIADA, null, produtor.getId(), PapelUsuario.PRODUTOR,
            nota.getDataEmissao());
        revisao(nota, AcaoRevisao.DEVOLVIDA, "Peso divergente do romaneio: a balança do "
            + "armazém da trading registrou 36.420 kg. Corrija a quantidade antes de reenviar.",
            revisor.getId(), PapelUsuario.CONTADOR, nota.getDataEmissao().plusHours(5));
      }
      case RASCUNHO -> {
        notaRepository.save(nota);
        ex.notas++;
      }
      default -> {
        if (icmsADefinir(nota)) {
          // Sem CST de ICMS defensavel (milho ao pecuarista sem IE, touro reprodutor
          // interestadual): fica rascunho e mostra na banca o sinal e o 422 ICMS_A_DEFINIR.
          notaRepository.save(nota);
          ex.notas++;
          return;
        }
        // NENHUM e NO_PRAZO: autorizada, com o historico da aprovacao quando ha escritorio.
        if (autorizar(ex, nota) && comEscritorio) {
          historicoAprovado(nota, produtor, revisor);
        }
      }
    }
  }

  /**
   * O erro mais comum de quem começa: CFOP interestadual (6101) para comprador do mesmo estado. A
   * validação de verdade roda e o lote dela vai para o histórico, como no {@code gerar-xml}. Se o
   * validador um dia aceitar a nota, ela fica rascunho (o teste acusa), sem derrubar a semeadura.
   */
  private void comInconsistencias(Execucao ex, Produtor produtor, VendaPlanejada venda,
      long numero) {
    NotaFiscal nota = nota(produtor, venda.comprador(), numero, venda.emissao(), NATUREZA_VENDA,
        item(1, venda.produto(), "6101", venda.quantidade(), venda.preco()));
    List<ErrorDetail> erros = List.of();
    try {
      geracaoService.gerarXml(nota);
      log.warn("Demonstracao: a nota com CFOP 6101 interno passou na validacao; fica rascunho.");
    } catch (DomainException e) {
      erros = e.getErrors();
      nota.setStatus(StatusNotaFiscal.COM_INCONSISTENCIAS);
    }
    notaRepository.save(nota);
    ex.notas++;
    UUID lote = UUID.randomUUID();
    OffsetDateTime quando = nota.getDataEmissao().plusMinutes(3);
    for (ErrorDetail erro : erros) {
      validacaoRepository.save(NotaFiscalValidacao.builder()
          .notaFiscal(nota)
          .loteValidacao(lote)
          .code(erro.code())
          .field(erro.field())
          .message(erro.message())
          .suggestion(erro.suggestion())
          .rejectedValue(erro.rejectedValue() != null ? String.valueOf(erro.rejectedValue())
              : null)
          .expectedValue(erro.expectedValue() != null ? String.valueOf(erro.expectedValue())
              : null)
          .validadoEm(quando)
          .build());
    }
  }

  // ===============================================================================================
  // Guias e pendências
  // ===============================================================================================

  /**
   * Guia mensal dos últimos quatro meses (Funrural do PF, ICMS da PJ — tributos atuais seguem por
   * guia, premissa da #280), vencendo dia 20 do mês seguinte. Vencida = paga, menos a do mês
   * retrasado do produtor principal, que fica VENCIDA (atenção da carteira). Duas guias perto do
   * vencimento alimentam o alerta (RN010) e o aviso de "vence amanhã" (#182).
   */
  private void guias(Execucao ex, Produtor produtor, Cliente cliente) {
    TipoGuiaFiscal tipo = cliente.pessoaJuridica() ? TipoGuiaFiscal.ICMS : TipoGuiaFiscal.FUNRURAL;
    YearMonth atual = YearMonth.from(ex.hoje);
    for (int m = 4; m >= 1; m--) {
      YearMonth competencia = atual.minusMonths(m);
      LocalDate vencimento = competencia.plusMonths(1).atDay(20);
      boolean vencida = vencimento.isBefore(ex.hoje);
      boolean emAtraso = vencida && m == 2 && cliente.chave().equals(
          CenarioDemonstracao.CHAVE_PRINCIPAL);
      guia(ex, produtor, tipo, vencimento, valorGuia(cliente, m), "Competência " + competenciaBr(competencia),
          emAtraso ? StatusGuiaFiscal.VENCIDA
              : vencida ? StatusGuiaFiscal.PAGA : StatusGuiaFiscal.PENDENTE);
    }
    switch (cliente.chave()) {
      case "helena" -> guia(ex, produtor, TipoGuiaFiscal.GPS, ex.hoje.plusDays(3),
          new BigDecimal("1840.00"), "GPS dos empregados rurais", StatusGuiaFiscal.PENDENTE);
      case "granjatoledo" -> guia(ex, produtor, TipoGuiaFiscal.DARF, ex.hoje.plusDays(1),
          new BigDecimal("3275.40"), "DARF IRPJ trimestral (lucro real)",
          StatusGuiaFiscal.PENDENTE);
      default -> {
        // Só as mensais.
      }
    }
  }

  /** Competência como o produtor lê: {@code MM/AAAA}, nunca o {@code AAAA-MM} cru (#408). */
  private static String competenciaBr(YearMonth competencia) {
    return competencia.format(COMPETENCIA);
  }

  private void guia(Execucao ex, Produtor produtor, TipoGuiaFiscal tipo, LocalDate vencimento,
      BigDecimal valor, String observacoes, StatusGuiaFiscal status) {
    guiaRepository.save(GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(tipo)
        .dataVencimento(vencimento)
        .valor(valor)
        .observacoes(observacoes)
        .status(status)
        .dataPagamento(status == StatusGuiaFiscal.PAGA
            ? vencimento.minusDays(2).atTime(LocalTime.of(10, 30)).atZone(ZONA).toOffsetDateTime()
            : null)
        .build());
    ex.guias++;
  }

  private static BigDecimal valorGuia(Cliente cliente, int mesesAtras) {
    BigDecimal base = cliente.faturamento().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    BigDecimal aliquota = cliente.pessoaJuridica() ? new BigDecimal("0.0125")
        : new BigDecimal("0.0035");
    BigDecimal sazonal = BigDecimal.ONE.add(BigDecimal.valueOf(mesesAtras % 3)
        .multiply(new BigDecimal("0.08")));
    return base.multiply(aliquota).multiply(sazonal).setScale(2, RoundingMode.HALF_UP);
  }

  /** Pedidos do escritório: pendente, atrasado, enviado com anexo e concluído. */
  private void pendencias(Execucao ex, Cliente cliente, Produtor produtor, Escritorio escritorio,
      Contador revisor) {
    switch (cliente.chave()) {
      case CenarioDemonstracao.CHAVE_PRINCIPAL -> pendencia(ex, escritorio, produtor, revisor,
          "Extrato bancário de " + competenciaBr(YearMonth.from(ex.hoje).minusMonths(1)),
          "Extrato da conta de movimento da fazenda, para conciliar as vendas de soja.",
          ex.hoje.plusDays(5), StatusPendencia.PENDENTE, TipoDocumentoPendencia.EXTRATO);
      case "luiz" -> pendencia(ex, escritorio, produtor, revisor,
          "Contrato de fornecimento de leite 2027",
          "Contrato assinado com a rede de supermercados, com o preço por litro e o prazo de"
              + " entrega.",
          ex.hoje.plusDays(12), StatusPendencia.PENDENTE, TipoDocumentoPendencia.CONTRATO);
      case CenarioDemonstracao.CHAVE_COTA_NO_FIM -> pendencia(ex, escritorio, produtor, revisor,
          "Contrato de arrendamento da Fazenda Boa Vista",
          "Contrato vigente e o último recibo do arrendamento, para o livro caixa.",
          ex.hoje.minusDays(3), StatusPendencia.PENDENTE, TipoDocumentoPendencia.CONTRATO);
      case "helena" -> pendencia(ex, escritorio, produtor, revisor,
          "Comprovante de pagamento do Funrural",
          "Comprovante da guia de " + competenciaBr(YearMonth.from(ex.hoje).minusMonths(2)) + ".",
          ex.hoje.minusDays(9), StatusPendencia.CONCLUIDA,
          TipoDocumentoPendencia.COMPROVANTE_PAGAMENTO);
      case "santaclara" -> {
        Pendencia enviada = pendencia(ex, escritorio, produtor, revisor,
            "Nota de compra do cloreto de potássio",
            "A NF-e do fornecedor não chegou ao e-mail do escritório; envie o PDF ou o XML.",
            ex.hoje.plusDays(4), StatusPendencia.ENVIADA, TipoDocumentoPendencia.NOTA_FISCAL);
        anexoRepository.save(PendenciaAnexo.builder()
            .pendenciaId(enviada.getId())
            .nomeArquivo("NF-e cloreto de potássio.pdf")
            .mime("application/pdf")
            .tamanho(PDF_COMPROVANTE.length)
            .sha256(sha256(PDF_COMPROVANTE))
            .conteudo(PDF_COMPROVANTE)
            .enviadoPor(produtor.getId())
            .enviadoEm(OffsetDateTime.now(clock).minusHours(5))
            .situacao(SituacaoAnexo.LIMPO)
            .liberaPendencia(true)
            .verificadoEm(OffsetDateTime.now(clock).minusHours(5))
            .build());
      }
      default -> {
        // Sem pedido em aberto.
      }
    }
  }

  /** Os modelos de pedido do escritório (#517), sem referência aos pedidos semeados. */
  private int modelosPedido(UUID escritorioId) {
    for (CenarioDemonstracao.ModeloDePedido modelo : CenarioDemonstracao.MODELOS_PEDIDO) {
      modeloPedidoRepository.save(ModeloPedido.builder()
          .escritorioId(escritorioId)
          .titulo(modelo.titulo())
          .tituloNormalizado(ModeloPedido.normalizarTitulo(modelo.titulo()))
          .descricao(modelo.descricao())
          .tiposDocumento(EnumSet.copyOf(modelo.tipos()))
          .prazoEmDias(modelo.prazoEmDias())
          .observacoes(modelo.observacoes())
          .build());
    }
    return CenarioDemonstracao.MODELOS_PEDIDO.size();
  }

  private Pendencia pendencia(Execucao ex, Escritorio escritorio, Produtor produtor,
      Contador autor, String titulo, String descricao, LocalDate limite, StatusPendencia status,
      TipoDocumentoPendencia tipo) {
    ex.pendencias++;
    return pendenciaRepository.save(Pendencia.builder()
        .escritorioId(escritorio.getId())
        .produtorId(produtor.getId())
        .titulo(titulo)
        .descricao(descricao)
        .dataLimite(limite)
        .tiposDocumento(new LinkedHashSet<>(List.of(tipo)))
        .status(status)
        .criadoPor(autor.getId())
        .build());
  }

  // ===============================================================================================
  // Credenciais
  // ===============================================================================================

  private String hashDaSenhaSeed() {
    if (senhaSeed == null || senhaSeed.isBlank()) {
      log.warn("Demonstracao sem SEED_SENHA: contas criadas sem credencial (ninguem entra).");
      return null;
    }
    String senha = PoliticaSenha.normalizar(senhaSeed);
    try {
      politicaSenha.validar(senha, null, null, "SEED_SENHA");
    } catch (SenhaFracaException e) {
      log.warn("SEED_SENHA nao atende a politica de senha: demonstracao sem credencial.");
      return null;
    }
    return passwordEncoder.encode(senha);
  }

  private void credencial(Execucao ex, String email, PapelUsuario papel, String documento,
      UUID produtorId, UUID contadorId) {
    if (ex.hashSenha == null) {
      return;
    }
    String normalizado = CadastroService.normalizarEmail(email);
    if (credencialRepository.existsByEmail(normalizado)) {
      return;
    }
    credencialRepository.save(CredencialUsuario.builder()
        .email(normalizado)
        .senhaHash(ex.hashSenha)
        .papel(papel)
        .cpfCnpj(documento)
        .produtorId(produtorId)
        .contadorId(contadorId)
        .build());
  }

  // ===============================================================================================
  // Datas, valores e identificadores
  // ===============================================================================================

  /** Dia do mês {@code mesesAtras} (limitado ao fim do mês e, no mês corrente, a ontem). */
  private static OffsetDateTime emissao(LocalDate hoje, int mesesAtras, int dia) {
    YearMonth mes = YearMonth.from(hoje).minusMonths(mesesAtras);
    LocalDate data = mes.atDay(Math.min(dia, mes.lengthOfMonth()));
    if (!data.isBefore(hoje)) {
      data = hoje.minusDays(1 + (dia % 3));
    }
    return noDia(data, 8 + dia % 9);
  }

  private static OffsetDateTime noDia(LocalDate data, int hora) {
    return data.atTime(LocalTime.of(hora, 12)).atZone(ZONA).toOffsetDateTime();
  }

  /** ±15% em torno da referência, inteiro (carga, saca, cabeça). */
  private static BigDecimal variar(BigDecimal referencia, Random aleatorio) {
    double fator = 0.85 + aleatorio.nextDouble() * 0.30;
    BigDecimal valor = referencia.multiply(BigDecimal.valueOf(fator))
        .setScale(0, RoundingMode.HALF_UP);
    return valor.signum() > 0 ? valor : BigDecimal.ONE;
  }

  /** Preço com a tendência do ano (até 3% em oito meses) e ruído de ±2%. */
  private static BigDecimal preco(Produto produto, int mesesAtras, Random aleatorio) {
    double tendencia = 1.0 + (MESES - 1 - mesesAtras) * 0.004;
    double ruido = 0.98 + aleatorio.nextDouble() * 0.04;
    return produto.preco().multiply(BigDecimal.valueOf(tendencia * ruido))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static String chave(String xml) {
    Matcher m = CHAVE_NO_XML.matcher(xml);
    if (!m.find()) {
      throw new IllegalStateException("XML sem a chave de acesso no Id da infNFe");
    }
    return m.group(1);
  }

  /** nProt de 15 dígitos: autorizador (1), cUF e ano da chave, sequência. */
  private static String protocolo(Execucao ex, String chave) {
    return "1" + chave.substring(0, 4) + String.format("%010d", 6_120_000_000L + ex.protocolos++);
  }

  private static String sha256(byte[] conteudo) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponivel", e);
    }
  }

  /** Estado de uma semeadura. */
  private static final class Execucao {
    private final String hashSenha;
    private final LocalDate hoje;
    private int produtores;
    private int notas;
    private int notasRecusadas;
    private int guias;
    private int pendencias;
    private long protocolos;

    private Execucao(String hashSenha, LocalDate hoje) {
      this.hashSenha = hashSenha;
      this.hoje = hoje;
    }
  }

  /** O que a semeadura gravou. */
  record Resumo(int produtores, int notas, int notasRecusadas, int guias, int pendencias,
      int mesesFechados) {}
}
