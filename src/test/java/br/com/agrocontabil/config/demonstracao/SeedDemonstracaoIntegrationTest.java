package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.LimiteUsoAssistenteException;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.nfe.VersaoRegraFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.service.CotaUsoAssistenteService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * A demonstração da banca (issue #315) semeada de verdade, na subida do contexto, contra o schema
 * de teste com a referência de NCM e a tabela oficial do IBS/CBS.
 *
 * <p>Banco H2 próprio: a semeadura grava ~200 notas e 12 contas que contaminariam as contagens dos
 * outros testes, que compartilham o banco em memória ({@code DB_CLOSE_DELAY=-1}). O schema entra
 * pelo {@code spring.sql.init} (antes dos runners), não pelo {@code @Sql}, que rodaria depois da
 * semeadura.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:demonstracao315;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
        + "NON_KEYWORDS=VALUE",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-nota-fiscal-test.sql,"
        + "classpath:schema-shedlock-test.sql",
    "app.demonstracao.habilitada=true",
    "app.assistente.cota.habilitada=true",
    "app.seed.senha=Banca-AgroConIA-demonstracao-2026"})
@ActiveProfiles("test")
@DisplayName("Seed da demonstracao da banca (#315)")
class SeedDemonstracaoIntegrationTest {

  @Autowired private SeedDemonstracao seed;
  @Autowired private CotaDemonstracaoCeres cotaCeres;
  @Autowired private CotaUsoAssistenteService cotaService;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private NotaFiscalRepository notaRepository;
  @Autowired private NotaFiscalRevisaoRepository revisaoRepository;
  @Autowired private NotaFiscalValidacaoRepository validacaoRepository;
  @Autowired private GuiaFiscalRepository guiaRepository;
  @Autowired private PendenciaRepository pendenciaRepository;
  @Autowired private ApuracaoMensalRepository apuracaoRepository;
  @Autowired private AssinaturaValidator assinaturaValidator;

  @Test
  @DisplayName("escritorio com tres funcoes, nove clientes e conta para todos")
  void semeiaEscritorioCarteiraEContas() {
    assertThat(seed.ultimoResumo()).isNotNull();
    assertThat(seed.ultimoResumo().produtores()).isEqualTo(CenarioDemonstracao.CARTEIRA.size());
    assertThat(produtorRepository.count()).isEqualTo(CenarioDemonstracao.CARTEIRA.size());
    assertThat(contadorRepository.count()).isEqualTo(CenarioDemonstracao.EQUIPE.size());
    assertThat(credencialRepository.count())
        .isEqualTo(CenarioDemonstracao.CARTEIRA.size() + CenarioDemonstracao.EQUIPE.size());

    Map<SituacaoVinculo, Long> porSituacao = vinculoRepository.findAll().stream()
        .collect(Collectors.groupingBy(VinculoCarteira::getSituacao, Collectors.counting()));
    assertThat(porSituacao).containsEntry(SituacaoVinculo.SUSPENSO, 1L)
        .containsEntry(SituacaoVinculo.ENCERRADO, 1L)
        .containsEntry(SituacaoVinculo.ATIVO, (long) CenarioDemonstracao.CARTEIRA.size() - 2);
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName("toda NF-e propria autorizada tem XML assinado que confere, com a chave do XML")
  void notasPropriasPassamPeloPipelineDeEmissao() {
    assertThat(seed.ultimoResumo().notasRecusadas()).isZero();

    List<NotaFiscal> autorizadas = notaRepository.findAll().stream()
        .filter(n -> n.getOrigem() == OrigemNotaFiscal.PROPRIA)
        .filter(n -> n.getStatus() == StatusNotaFiscal.AUTORIZADA
            || n.getStatus() == StatusNotaFiscal.CANCELADA)
        .toList();
    assertThat(autorizadas).hasSizeGreaterThan(80);
    assertThat(autorizadas).allSatisfy(nota -> {
      assertThat(nota.getChaveAcesso()).hasSize(44);
      assertThat(nota.getXmlGerado()).contains("Id=\"NFe" + nota.getChaveAcesso() + "\"");
      assertThat(nota.getProtocoloAutorizacao()).hasSize(15);
      // O seed chama o pipeline direto, sem o NotaFiscalService: a marca da regra fiscal (#389)
      // tem de vir dele, senao o enviar-sefaz recusaria as notas semeadas diante da banca.
      assertThat(nota.getVersaoRegraFiscal()).isEqualTo(VersaoRegraFiscal.ATUAL);
      // Zero e valido: ovos, leite, arroz, tomate e cafe tem reducao de 100% (Anexos I e XV).
      assertThat(nota.getValorTotalCbs()).isNotNull().isNotNegative();
    });
    assertThat(autorizadas).filteredOn(n -> n.getValorTotalCbs().signum() > 0)
        .hasSizeGreaterThan(40);
    autorizadas.stream().limit(5).forEach(n -> assertThat(validaAssinatura(n.getXmlGerado()))
        .isTrue());

    // Regime normal: a premissa do regime regular (#280) no CRT da nota.
    Produtor principal = produtor(CenarioDemonstracao.CHAVE_PRINCIPAL);
    assertThat(NotaFiscalNfeMapper.crt(principal.getTipoProdutor(),
        principal.getRegimeTributario())).isEqualTo("3");
    // Pessoa fisica nunca no Simples nem no MEI (#298).
    assertThat(produtorRepository.findAll())
        .noneMatch(p -> RegimeTributario.incompativel(p.getTipoProdutor(),
            p.getRegimeTributario()));
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName("compras entram pelo mapper da importacao, com XML do fornecedor e credito")
  void comprasSaoImportadasComCredito() {
    List<NotaFiscal> importadas = notaRepository.findAll().stream()
        .filter(n -> n.getOrigem() == OrigemNotaFiscal.IMPORTADA)
        .toList();

    assertThat(importadas).hasSizeGreaterThan(20).allSatisfy(nota -> {
      assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
      assertThat(nota.getEmitenteCpfCnpj()).hasSize(14);
      assertThat(nota.getXmlGerado()).contains("Signature>");
    });
    BigDecimal credito = importadas.stream()
        .map(n -> n.getValorTotalCreditoCbs() != null ? n.getValorTotalCreditoCbs()
            : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(credito).isPositive();
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName("a fila tem aguardando aprovacao, devolvida, com inconsistencias, rascunho e "
      + "cancelada, com historico")
  void filaDeRevisaoComHistorico() {
    Map<StatusNotaFiscal, Long> porStatus = notaRepository.findAll().stream()
        .collect(Collectors.groupingBy(NotaFiscal::getStatus, Collectors.counting()));

    assertThat(porStatus).containsEntry(StatusNotaFiscal.AGUARDANDO_APROVACAO, 3L)
        .containsEntry(StatusNotaFiscal.DEVOLVIDA, 1L)
        .containsEntry(StatusNotaFiscal.COM_INCONSISTENCIAS, 1L)
        // 1 rascunho da fila + 6 vendas sem CST de ICMS defensavel (5 de milho ao pecuarista sem
        // IE e o touro reprodutor interestadual), que mostram o ICMS_A_DEFINIR na banca.
        .containsEntry(StatusNotaFiscal.RASCUNHO, 7L)
        .containsEntry(StatusNotaFiscal.CANCELADA, 1L);
    assertThat(notaRepository.findAll()).filteredOn(n -> n.getStatus() == StatusNotaFiscal.RASCUNHO)
        .filteredOn(n -> n.getItens().stream()
            .anyMatch(i -> br.com.agrocontabil.nfe.TributacaoIcms.problema(n, i).isPresent()))
        .extracting(NotaFiscal::getDestinatarioNome).containsOnly("Antonio Carlos Pereira",
            "Central de Genetica Bovina Triangulo Ltda").hasSize(6);

    NotaFiscal comErro = notaRepository.findAll().stream()
        .filter(n -> n.getStatus() == StatusNotaFiscal.COM_INCONSISTENCIAS).findFirst().orElseThrow();
    assertThat(validacaoRepository.findAll())
        .anySatisfy(v -> assertThat(v.getNotaFiscal().getId()).isEqualTo(comErro.getId()));

    NotaFiscal devolvida = notaRepository.findAll().stream()
        .filter(n -> n.getStatus() == StatusNotaFiscal.DEVOLVIDA).findFirst().orElseThrow();
    assertThat(revisaoRepository.findAll())
        .filteredOn(r -> r.getNotaFiscalId().equals(devolvida.getId()))
        .extracting(r -> r.getMotivo() != null)
        .containsExactlyInAnyOrder(false, true);
  }

  @Test
  @DisplayName("guias, pendencias e meses antigos fechados")
  void guiasPendenciasEApuracao() {
    assertThat(guiaRepository.count()).isEqualTo(seed.ultimoResumo().guias());
    assertThat(pendenciaRepository.count()).isEqualTo(5);
    assertThat(apuracaoRepository.findAll()).extracting(ApuracaoMensal::getStatus)
        .contains(StatusApuracao.FECHADO);
    assertThat(seed.ultimoResumo().mesesFechados()).isPositive();
  }

  @Test
  @DisplayName("cota da Ceres: a primeira pergunta passa, a segunda recebe 429")
  void cotaDaCeresNoLimite() {
    Produtor noFim = produtor(CenarioDemonstracao.CHAVE_COTA_NO_FIM);
    long teto = cotaService.tetoDaPergunta(null);

    assertThat(cotaCeres.prepararSemana()).hasValue(teto + 1);

    CotaUsoAssistenteService.Reserva reserva =
        cotaService.reservar(TipoTitularCota.PRODUTOR, noFim.getId(), null);
    cotaService.acertar(reserva, 3_000, 400, null);
    assertThatThrownBy(() -> cotaService.reservar(TipoTitularCota.PRODUTOR, noFim.getId(), null))
        .isInstanceOf(LimiteUsoAssistenteException.class);

    // O produtor principal tem a semana inteira.
    Produtor principal = produtor(CenarioDemonstracao.CHAVE_PRINCIPAL);
    assertThat(cotaService.consultar(TipoTitularCota.PRODUTOR, principal.getId()).consumidos())
        .isZero();
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName("a serie de cada produtor cresce com a data de emissao, sem numero repetido")
  void numeracaoCronologica() {
    Map<UUID, List<NotaFiscal>> porProdutor = notaRepository.findAll().stream()
        .filter(n -> n.getOrigem() == OrigemNotaFiscal.PROPRIA)
        .collect(Collectors.groupingBy(n -> n.getProdutor().getId()));

    assertThat(porProdutor).hasSize(CenarioDemonstracao.CARTEIRA.size());
    porProdutor.values().forEach(notas -> {
      List<Long> pelaData = notas.stream()
          .sorted(Comparator.comparing(NotaFiscal::getDataEmissao))
          .map(NotaFiscal::getNumero).toList();
      assertThat(pelaData).doesNotHaveDuplicates().isSorted();
    });
  }

  @Test
  @DisplayName("conta sem credencial ganha o login na proxima subida, sem zerar o banco")
  void completaCredenciaisQueFaltam() {
    String email = CenarioDemonstracao.cliente(CenarioDemonstracao.CHAVE_COTA_NO_FIM).email();
    credencialRepository.findByEmail(email).ifPresent(credencialRepository::delete);
    assertThat(credencialRepository.existsByEmail(email)).isFalse();

    seed.completarCredenciais();

    assertThat(credencialRepository.existsByEmail(email)).isTrue();
  }

  @Test
  @DisplayName("idempotente: rodar de novo nao duplica nada")
  void idempotente() throws Exception {
    long notas = notaRepository.count();
    long produtores = produtorRepository.count();

    seed.run();

    assertThat(notaRepository.count()).isEqualTo(notas);
    assertThat(produtorRepository.count()).isEqualTo(produtores);
  }

  private Produtor produtor(String chave) {
    Map<String, Produtor> porDocumento = produtorRepository.findAll().stream()
        .collect(Collectors.toMap(Produtor::getCpfCnpj, Function.identity()));
    return porDocumento.get(CenarioDemonstracao.cliente(chave).documento());
  }

  private boolean validaAssinatura(String xml) {
    assinaturaValidator.validar(xml);
    return true;
  }
}
