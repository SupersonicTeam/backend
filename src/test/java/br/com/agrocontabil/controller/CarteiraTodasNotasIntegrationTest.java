package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/contador/notas} e {@code /resumo} (issue #341): as notas da carteira ativa do
 * escritório, sem escolher o cliente. O ponto central é o escopo: o escritório E1 só vê os
 * clientes com vínculo ATIVO nele (nunca suspenso, encerrado, de outro escritório nem sem
 * vínculo), com qualquer combinação de filtros.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Notas de toda a carteira do escritório (#341)")
class CarteiraTodasNotasIntegrationTest {

  private static final String LISTA = "/api/contador/notas";
  private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-09-01T10:00:00-03:00");

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Contador admin;
  private Contador assistente;
  private Contador deOutroEscritorio;
  private Contador semEscritorio;
  private Produtor clienteA;
  private Produtor clienteB;
  private Produtor suspenso;
  private Produtor encerrado;
  private Produtor deOutroEscritorioCliente;
  private Produtor semVinculo;
  private int proximoNumero;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    proximoNumero = 1;
    Escritorio e1 = escritorioRepository.save(Escritorio.builder().nome("Escritorio Um").build());
    Escritorio e2 = escritorioRepository.save(
        Escritorio.builder().nome("Escritorio Dois").build());
    admin = membro(e1, FuncaoEscritorio.ADMINISTRADOR, "Admin Um", "11111111000191");
    assistente = membro(e1, FuncaoEscritorio.ASSISTENTE, "Assistente Um", "22222222000191");
    deOutroEscritorio = membro(e2, FuncaoEscritorio.ADMINISTRADOR, "Admin Dois", "33333333000191");
    semEscritorio = contadorRepository.save(Contador.builder()
        .nomeCompleto("Sem Escritorio").cpfCnpj("44444444000191").build());

    clienteA = produtor("Fazenda A", "52998224725");
    clienteB = produtor("Fazenda B", "11144477735");
    suspenso = produtor("Sitio Suspenso", "12345678909");
    encerrado = produtor("Sitio Encerrado", "98765432100");
    deOutroEscritorioCliente = produtor("Fazenda do Outro", "45317828791");
    semVinculo = produtor("Fazenda Solta", "86288366757");
    vincular(e1, clienteA, SituacaoVinculo.ATIVO, admin.getId());
    vincular(e1, clienteB, SituacaoVinculo.ATIVO, null);
    vincular(e1, suspenso, SituacaoVinculo.SUSPENSO, null);
    vincular(e1, encerrado, SituacaoVinculo.ENCERRADO, null);
    vincular(e2, deOutroEscritorioCliente, SituacaoVinculo.ATIVO, null);

    // A: 1 rascunho, 1 em revisao (soja), 1 autorizada
    nota(clienteA, StatusNotaFiscal.RASCUNHO, "Milho em graos", "10059010");
    nota(clienteA, StatusNotaFiscal.AGUARDANDO_APROVACAO, "Soja em graos", "12019000");
    nota(clienteA, StatusNotaFiscal.AUTORIZADA, "Cafe cru", "09011110");
    // B: um de cada outro estado
    nota(clienteB, StatusNotaFiscal.AGUARDANDO_APROVACAO, "Leite", "04012090");
    nota(clienteB, StatusNotaFiscal.COM_INCONSISTENCIAS, "Trigo", "10019900");
    nota(clienteB, StatusNotaFiscal.REJEITADA, "Arroz", "10061092");
    nota(clienteB, StatusNotaFiscal.DEVOLVIDA, "Feijao", "07133319");
    nota(clienteB, StatusNotaFiscal.CANCELADA, "Ovos", "04072100");
    nota(clienteB, StatusNotaFiscal.APROVADA, "Soja em graos", "12019000");
    nota(clienteB, StatusNotaFiscal.XML_GERADO, "Sorgo", "10079000");
    // Fora da carteira ativa: suspenso, encerrado, outro escritorio, sem vinculo
    nota(suspenso, StatusNotaFiscal.AGUARDANDO_APROVACAO, "Soja em graos", "12019000");
    nota(encerrado, StatusNotaFiscal.AUTORIZADA, "Soja em graos", "12019000");
    nota(deOutroEscritorioCliente, StatusNotaFiscal.AGUARDANDO_APROVACAO, "Soja em graos",
        "12019000");
    nota(semVinculo, StatusNotaFiscal.RASCUNHO, "Soja em graos", "12019000");

    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("lista so a carteira ativa do escritorio, com o cliente de cada nota e sem itens nem documento")
  void listaSoACarteiraAtiva() throws Exception {
    mockMvc.perform(como(admin, get(LISTA).param("size", "50")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "10"))
        .andExpect(jsonPath("$.length()").value(10))
        // 1 a 10 = A e B; 11 a 14 = suspenso, encerrado, outro escritorio e sem vinculo.
        .andExpect(jsonPath("$[*].numero", containsInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
        .andExpect(jsonPath("$[?(@.numero == 2)].produtorNome").value(contains("Fazenda A")))
        .andExpect(jsonPath("$[?(@.numero == 2)].produtorId")
            .value(contains(clienteA.getId().toString())))
        .andExpect(jsonPath("$[0].itens").doesNotExist())
        .andExpect(jsonPath("$[0].xmlGerado").doesNotExist())
        .andExpect(jsonPath("$[0].destinatarioCpfCnpj").doesNotExist())
        .andExpect(jsonPath("$[0].produtorCpfCnpj").doesNotExist());
  }

  @Test
  @DisplayName("qualquer membro lista (assistente tambem); outro escritorio so ve a propria carteira")
  void membrosEOutroEscritorio() throws Exception {
    mockMvc.perform(como(assistente, get(LISTA)))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "10"));
    mockMvc.perform(como(deOutroEscritorio, get(LISTA)))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].numero").value(13))
        .andExpect(jsonPath("$[0].produtorNome").value("Fazenda do Outro"));
  }

  @Test
  @DisplayName("produtor recebe 403 PAPEL_NAO_AUTORIZADO; contador sem escritorio, 403 SEM_ESCRITORIO")
  void papeisSemAcesso() throws Exception {
    mockMvc.perform(como(clienteA, get(LISTA)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    mockMvc.perform(como(clienteA, get(LISTA + "/resumo")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    mockMvc.perform(como(semEscritorio, get(LISTA)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("SEM_ESCRITORIO"));
    mockMvc.perform(como(semEscritorio, get(LISTA + "/resumo")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("SEM_ESCRITORIO"));
  }

  @Test
  @DisplayName("produtorId restringe ao cliente; fora da carteira ativa e 404, sem dizer por que")
  void filtroPorCliente() throws Exception {
    mockMvc.perform(como(admin, get(LISTA).param("produtorId", clienteA.getId().toString())))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(jsonPath("$[*].produtorNome", contains("Fazenda A", "Fazenda A", "Fazenda A")));
    for (Produtor fora : new Produtor[] {suspenso, encerrado, deOutroEscritorioCliente,
        semVinculo}) {
      mockMvc.perform(como(admin, get(LISTA).param("produtorId", fora.getId().toString())))
          .andExpect(status().isNotFound());
      mockMvc.perform(como(admin, get(LISTA + "/resumo")
              .param("produtorId", fora.getId().toString())))
          .andExpect(status().isNotFound());
    }
    mockMvc.perform(como(admin, get(LISTA).param("produtorId", UUID.randomUUID().toString())))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("responsavel: eu, nenhum e id do membro; valor invalido e 400")
  void filtroPorResponsavel() throws Exception {
    mockMvc.perform(como(admin, get(LISTA).param("responsavel", "eu")))
        .andExpect(header().string("X-Total-Count", "3"));
    mockMvc.perform(como(assistente, get(LISTA).param("responsavel", "eu")))
        .andExpect(header().string("X-Total-Count", "0"));
    mockMvc.perform(como(admin, get(LISTA).param("responsavel", "nenhum")))
        .andExpect(header().string("X-Total-Count", "7"));
    mockMvc.perform(como(assistente, get(LISTA).param("responsavel", admin.getId().toString())))
        .andExpect(header().string("X-Total-Count", "3"));
    mockMvc.perform(como(admin, get(LISTA).param("responsavel", "ninguem")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FILTRO_CARTEIRA_INVALIDO"));
  }

  @Test
  @DisplayName("filtros de GET /api/notas: status repetido, busca por produto; nada escapa do escopo")
  void filtrosDaListaDeNotas() throws Exception {
    mockMvc.perform(como(admin, get(LISTA).param("status", "AGUARDANDO_APROVACAO")
            .param("status", "REJEITADA")))
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(jsonPath("$[*].numero", containsInAnyOrder(2, 4, 6)));
    // "soja" existe em 5 notas do banco; so as 2 da carteira ativa de E1 aparecem.
    mockMvc.perform(como(admin, get(LISTA).param("busca", "soja")))
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[*].numero", containsInAnyOrder(2, 9)));
    mockMvc.perform(como(admin, get(LISTA).param("busca", "soja").param("produtorId",
            clienteB.getId().toString())))
        .andExpect(jsonPath("$[*].numero", contains(9)));
    mockMvc.perform(como(admin, get(LISTA).param("emitidaDe", "2026-09-05")
            .param("emitidaAte", "2026-09-02")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FILTRO_NOTAS_INVALIDO"));
  }

  @Test
  @DisplayName("tipoDocumento e faixa de emissao filtram; produtorId com outro responsavel da lista vazia")
  void outrosFiltros() throws Exception {
    // As emissoes sao BASE + numero dias: 2026-09-02 (nota 1) a 2026-09-11 (nota 10).
    mockMvc.perform(como(admin, get(LISTA).param("emitidaDe", "2026-09-04")
            .param("emitidaAte", "2026-09-06")))
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(jsonPath("$[*].numero", containsInAnyOrder(3, 4, 5)));
    mockMvc.perform(como(admin, get(LISTA).param("tipoDocumento", "NFA")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"));
    mockMvc.perform(como(admin, get(LISTA).param("tipoDocumento", "NFE")))
        .andExpect(header().string("X-Total-Count", "10"));
    // O cliente A e do admin; pedir A com "nenhum" (sem responsavel) nao traz nada.
    mockMvc.perform(como(admin, get(LISTA).param("produtorId", clienteA.getId().toString())
            .param("responsavel", "nenhum")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"));
  }

  @Test
  @DisplayName("page gigante (offset acima de int) devolve pagina vazia com o total, nao 500")
  void paginaAlemDoFim() throws Exception {
    mockMvc.perform(como(admin, get(LISTA).param("page", "2147483647").param("size", "50")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "10"))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("ordenacao: padrao mais recente primeiro; sort permitido; fora da lista, 400; paginacao com total")
  void ordenacaoEPaginacao() throws Exception {
    // A emissao cresce com o numero: o padrao (dataEmissao desc) devolve o 10 primeiro.
    mockMvc.perform(como(admin, get(LISTA).param("size", "3")))
        .andExpect(jsonPath("$[*].numero", contains(10, 9, 8)))
        .andExpect(header().string("X-Total-Count", "10"));
    mockMvc.perform(como(admin, get(LISTA).param("size", "3").param("page", "3")))
        .andExpect(jsonPath("$[*].numero", contains(1)));
    mockMvc.perform(como(admin, get(LISTA).param("sort", "numero,asc").param("size", "2")))
        .andExpect(jsonPath("$[*].numero", contains(1, 2)));
    mockMvc.perform(como(admin, get(LISTA).param("sort", "destinatarioNome")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FILTRO_NOTAS_INVALIDO"));
  }

  @Test
  @DisplayName("resumo: contagem por caixa de triagem, 'todas' soma tudo, e respeita os filtros")
  void resumo() throws Exception {
    mockMvc.perform(como(admin, get(LISTA + "/resumo")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisao").value(2))
        .andExpect(jsonPath("$.travadas").value(2))
        .andExpect(jsonPath("$.rascunhos").value(1))
        .andExpect(jsonPath("$.devolvidas").value(1))
        .andExpect(jsonPath("$.autorizadas").value(1))
        .andExpect(jsonPath("$.canceladas").value(1))
        // 8 nas caixas + APROVADA e XML_GERADO, que so a "Todas" mostra (GrupoNotasCarteira).
        .andExpect(jsonPath("$.todas").value(10));
    mockMvc.perform(como(admin, get(LISTA + "/resumo").param("busca", "soja")))
        .andExpect(jsonPath("$.revisao").value(1))
        .andExpect(jsonPath("$.travadas").value(0))
        .andExpect(jsonPath("$.todas").value(2));
    mockMvc.perform(como(admin, get(LISTA + "/resumo").param("produtorId",
            clienteA.getId().toString())))
        .andExpect(jsonPath("$.revisao").value(1))
        .andExpect(jsonPath("$.rascunhos").value(1))
        .andExpect(jsonPath("$.autorizadas").value(1))
        .andExpect(jsonPath("$.todas").value(3));
    // status e ignorado no resumo: ele ja separa por grupo.
    mockMvc.perform(como(admin, get(LISTA + "/resumo").param("status", "RASCUNHO")))
        .andExpect(jsonPath("$.revisao").value(2))
        .andExpect(jsonPath("$.todas").value(10));
    mockMvc.perform(como(deOutroEscritorio, get(LISTA + "/resumo")))
        .andExpect(jsonPath("$.revisao").value(1))
        .andExpect(jsonPath("$.todas").value(1));
  }

  @Test
  @DisplayName("sem N+1: o numero de consultas nao cresce com o tamanho da pagina nem com os clientes")
  void semNMaisUm() throws Exception {
    Statistics estatisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    estatisticas.setStatisticsEnabled(true);
    try {
      mockMvc.perform(como(admin, get(LISTA).param("size", "50"))).andExpect(status().isOk());
      long comDezNotas = consultas(estatisticas, () -> mockMvc.perform(
          como(admin, get(LISTA).param("size", "50"))).andExpect(status().isOk()));

      // Mais 30 notas em mais 30 clientes ativos: a pagina de 40 tem 40 clientes diferentes.
      Escritorio e1 = escritorioRepository.findById(admin.getEscritorioId()).orElseThrow();
      for (int i = 0; i < 30; i++) {
        Produtor novo = produtor("Cliente Extra " + i, "00000000" + String.format("%03d", i));
        vincular(e1, novo, SituacaoVinculo.ATIVO, null);
        nota(novo, StatusNotaFiscal.AGUARDANDO_APROVACAO, "Soja em graos", "12019000");
      }
      long comQuarentaNotas = consultas(estatisticas, () -> mockMvc.perform(
          como(admin, get(LISTA).param("size", "50"))).andExpect(status().isOk()));
      mockMvc.perform(como(admin, get(LISTA).param("size", "50")))
          .andExpect(header().string("X-Total-Count", "40"))
          .andExpect(jsonPath("$.length()").value(40));

      assertThat(comQuarentaNotas).isEqualTo(comDezNotas);
      // Pagina + total + identidade/escopo: poucas consultas, qualquer que seja o tamanho.
      assertThat(comQuarentaNotas).isLessThanOrEqualTo(8);
    } finally {
      estatisticas.setStatisticsEnabled(false);
    }
  }

  // -------------------------------------------------------------------------

  private interface Chamada {
    void executar() throws Exception;
  }

  private long consultas(Statistics estatisticas, Chamada chamada) throws Exception {
    estatisticas.clear();
    chamada.executar();
    return estatisticas.getPrepareStatementCount();
  }

  private MockHttpServletRequestBuilder como(Contador contador,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId());
  }

  private MockHttpServletRequestBuilder como(Produtor produtor,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId());
  }

  private Contador membro(Escritorio escritorio, FuncaoEscritorio funcao, String nome,
      String cnpj) {
    return contadorRepository.save(Contador.builder().escritorioId(escritorio.getId())
        .funcao(funcao).nomeCompleto(nome).cpfCnpj(cnpj).build());
  }

  private Produtor produtor(String nome, String cpf) {
    return produtorRepository.save(Produtor.builder().nomeCompleto(nome).cpfCnpj(cpf).build());
  }

  private void vincular(Escritorio escritorio, Produtor produtor, SituacaoVinculo situacao,
      UUID responsavelId) {
    vinculoRepository.save(VinculoCarteira.builder().escritorioId(escritorio.getId())
        .produtorId(produtor.getId()).situacao(situacao).responsavelId(responsavelId)
        .inicio(BASE).build());
  }

  /** Nota com um item; a emissao cresce com o numero (um dia por nota). */
  private void nota(Produtor produtor, StatusNotaFiscal status, String descricaoItem,
      String ncm) {
    int numero = proximoNumero++;
    NotaFiscal nota = NotaFiscal.builder()
        .numero((long) numero).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(BASE.plusDays(numero))
        .produtor(produtor)
        .destinatarioNome("Cooperativa Compradora").destinatarioCpfCnpj("12345678000195")
        .status(status)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.addItem(NotaFiscalItem.builder().numeroItem(1).codigoProduto("P1")
        .descricao(descricaoItem).ncm(ncm).cfop("5101").unidade("KG")
        .quantidade(new BigDecimal("10.0000")).valorUnitario(new BigDecimal("100.0000"))
        .valorTotal(new BigDecimal("1000.00")).build());
    notaFiscalRepository.save(nota);
  }
}
