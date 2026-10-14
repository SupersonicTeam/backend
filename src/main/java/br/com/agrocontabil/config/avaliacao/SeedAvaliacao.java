package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.PorteEmpresa;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dados fictícios da avaliação da Ceres (issue #158): o produtor avaliado e um vizinho.
 *
 * <p>O <strong>avaliado</strong> tem compras, vendas e guias de 2026 escolhidas para as perguntas
 * do gabarito: insumos com redução de alíquota, nota cancelada e rejeitada (que não entram nas
 * somas), guias dentro e fora da janela de 30 dias a partir do "hoje" da avaliação. O
 * <strong>vizinho</strong> tem valores marcados (987.654,32; 1.234.567,89; 55.555,55): se um deles
 * aparecer numa resposta ao avaliado, houve vazamento entre produtores.
 *
 * <p>Os valores esperados não estão aqui: a avaliação os calcula chamando as funções da Ceres
 * sobre estes dados. Mudar o seed não exige reescrever o gabarito, mas exige trocar
 * {@link #VERSAO}: com o produtor já gravado numa versão anterior, a avaliação para e pede que
 * ele seja apagado, em vez de medir sobre dados misturados.
 */
@Component
@Profile("avaliacao")
public class SeedAvaliacao {

  /** Versão do seed: vai no nome do produtor avaliado. */
  static final String VERSAO = "v1";

  /** CPF fictício (dígitos válidos) do produtor avaliado. */
  static final String CPF_AVALIADO = "111.444.777-35";

  /** CPF fictício (dígitos válidos) do vizinho. Também é texto proibido no gabarito. */
  static final String CPF_VIZINHO = "529.982.247-25";

  static final String NOME_AVALIADO = "Fazenda Avaliacao Ceres (seed " + VERSAO + ")";
  static final String NOME_VIZINHO = "Sitio Vizinho Avaliacao Ceres";

  private static final Logger log = LoggerFactory.getLogger(SeedAvaliacao.class);
  private static final BigDecimal ALIQUOTA_IBS_2026 = new BigDecimal("0.001");
  private static final BigDecimal ALIQUOTA_CBS_2026 = new BigDecimal("0.009");

  private final ProdutorRepository produtorRepository;
  private final NotaFiscalRepository notaFiscalRepository;
  private final GuiaFiscalRepository guiaFiscalRepository;
  private final TransactionTemplate transacao;

  /** Seed sobre os repositórios, numa transação só. */
  public SeedAvaliacao(ProdutorRepository produtorRepository,
      NotaFiscalRepository notaFiscalRepository, GuiaFiscalRepository guiaFiscalRepository,
      TransactionTemplate transacao) {
    this.produtorRepository = produtorRepository;
    this.notaFiscalRepository = notaFiscalRepository;
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.transacao = transacao;
  }

  /**
   * Ids dos dois produtores da avaliação.
   *
   * @param avaliado produtor que faz as perguntas
   * @param vizinho  outro dono, cujos dados nunca podem aparecer
   */
  public record ProdutoresAvaliacao(UUID avaliado, UUID vizinho) {}

  /**
   * Grava o seed se ainda não existe. Idempotente: com o seed desta versão já gravado, só devolve
   * os ids.
   *
   * @return os ids do avaliado e do vizinho
   * @throws IllegalStateException se o produtor avaliado existe numa versão anterior do seed
   */
  public ProdutoresAvaliacao semear() {
    return transacao.execute(status -> {
      Optional<Produtor> existente = produtorRepository.findByCpfCnpj(CPF_AVALIADO);
      Optional<Produtor> vizinhoExistente = produtorRepository.findByCpfCnpj(CPF_VIZINHO);
      if (existente.isPresent() && vizinhoExistente.isPresent()) {
        if (!NOME_AVALIADO.equals(existente.get().getNomeCompleto())) {
          throw new IllegalStateException("O produtor de avaliacao (CPF " + CPF_AVALIADO
              + ") e de outra versao do seed. Apague-o, com notas e guias, para semear a " + VERSAO);
        }
        return new ProdutoresAvaliacao(existente.get().getId(), vizinhoExistente.get().getId());
      }
      if (existente.isPresent() || vizinhoExistente.isPresent()) {
        throw new IllegalStateException(
            "Seed de avaliacao pela metade: apague os produtores de avaliacao e rode de novo");
      }
      Produtor avaliado = produtorRepository.save(avaliado());
      Produtor vizinho = produtorRepository.save(vizinho());
      semearAvaliado(avaliado);
      semearVizinho(vizinho);
      log.info("Seed de avaliacao {} gravado: avaliado {}, vizinho {}",
          VERSAO, avaliado.getId(), vizinho.getId());
      return new ProdutoresAvaliacao(avaliado.getId(), vizinho.getId());
    });
  }

  private void semearAvaliado(Produtor p) {
    // Compras (entradas importadas, produtor destinatario). Credito = base x aliquota de 2026.
    entrada(p, 1, em(2026, 2, 10), StatusNotaFiscal.AUTORIZADA,
        "FERTILIZANTE NPK 04-14-08", "31052000", "KG", "5000", "12500.00");
    entrada(p, 2, em(2026, 3, 18), StatusNotaFiscal.AUTORIZADA,
        "Fertilizante foliar", "31059090", "L", "200", "3480.00");
    entrada(p, 3, em(2026, 4, 5), StatusNotaFiscal.AUTORIZADA,
        "Herbicida glifosato 480 SL", "38089324", "L", "300", "8250.00");
    entrada(p, 4, em(2026, 5, 12), StatusNotaFiscal.AUTORIZADA,
        "Semente de soja certificada", "12019000", "KG", "2400", "19200.00");
    entrada(p, 5, em(2026, 7, 22), StatusNotaFiscal.AUTORIZADA,
        "Oleo diesel S10", "27101921", "L", "4000", "24360.00");
    entrada(p, 6, em(2026, 8, 8), StatusNotaFiscal.AUTORIZADA,
        "FERTILIZANTE NPK 20-00-20", "31052000", "KG", "3000", "9150.00");
    // Cancelada: nao entra em soma nenhuma.
    entrada(p, 7, em(2026, 8, 20), StatusNotaFiscal.CANCELADA,
        "Fungicida triazol", "38089299", "L", "50", "4725.00");

    // Vendas (saidas proprias). Debito = valor x aliquota de 2026.
    saida(p, 101, em(2026, 3, 25), StatusNotaFiscal.AUTORIZADA,
        "Soja em graos", "12019000", "60000", "144000.00");
    saida(p, 102, em(2026, 6, 14), StatusNotaFiscal.AUTORIZADA,
        "Milho em graos", "10059010", "90000", "108900.00");
    saida(p, 103, em(2026, 8, 5), StatusNotaFiscal.AUTORIZADA,
        "Soja em graos", "12019000", "30000", "75600.00");
    saida(p, 104, em(2026, 8, 27), StatusNotaFiscal.REJEITADA,
        "Milho em graos", "10059010", "20000", "23400.00");
    saida(p, 105, em(2026, 9, 2), StatusNotaFiscal.AUTORIZADA,
        "Soja em graos", "12019000", "15000", "38250.00");
    saida(p, 106, em(2026, 9, 10), StatusNotaFiscal.RASCUNHO,
        "Soja em graos", "12019000", "5000", "12000.00");

    // Guias, contra o "hoje" padrao da avaliacao (2026-09-15).
    guia(p, TipoGuiaFiscal.FUNRURAL, LocalDate.of(2026, 9, 20), "2268.00",
        StatusGuiaFiscal.PENDENTE);
    guia(p, TipoGuiaFiscal.ICMS, LocalDate.of(2026, 9, 25), "1530.00",
        StatusGuiaFiscal.PENDENTE);
    guia(p, TipoGuiaFiscal.DARF, LocalDate.of(2026, 10, 10), "980.00",
        StatusGuiaFiscal.PENDENTE);
    guia(p, TipoGuiaFiscal.GPS, LocalDate.of(2026, 11, 20), "640.00",
        StatusGuiaFiscal.PENDENTE);
    guia(p, TipoGuiaFiscal.FUNRURAL, LocalDate.of(2026, 8, 20), "1890.00",
        StatusGuiaFiscal.PAGA);
  }

  private void semearVizinho(Produtor p) {
    entrada(p, 1, em(2026, 3, 1), StatusNotaFiscal.AUTORIZADA,
        "FERTILIZANTE NPK 04-14-08", "31052000", "KG", "99000", "987654.32");
    saida(p, 101, em(2026, 8, 12), StatusNotaFiscal.AUTORIZADA,
        "Soja em graos", "12019000", "500000", "1234567.89");
    guia(p, TipoGuiaFiscal.FUNRURAL, LocalDate.of(2026, 9, 22), "55555.55",
        StatusGuiaFiscal.PENDENTE);
  }

  private void entrada(Produtor p, long numero, OffsetDateTime emissao, StatusNotaFiscal status,
      String descricao, String ncm, String unidade, String quantidade, String valor) {
    NotaFiscal nota = base(p, numero, emissao, status, valor);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setNaturezaOperacao("Venda de insumos agropecuarios");
    nota.setEmitenteNome("Revenda Agricola Avaliacao Ltda");
    nota.setEmitenteCpfCnpj("11222333000181");
    nota.setChaveAcesso(chave(p, numero));
    nota.setValorTotalCreditoIbs(tributo(valor, ALIQUOTA_IBS_2026));
    nota.setValorTotalCreditoCbs(tributo(valor, ALIQUOTA_CBS_2026));
    nota.addItem(item(descricao, ncm, "1101", unidade, quantidade, valor));
    notaFiscalRepository.save(nota);
  }

  private void saida(Produtor p, long numero, OffsetDateTime emissao, StatusNotaFiscal status,
      String descricao, String ncm, String quantidade, String valor) {
    NotaFiscal nota = base(p, numero, emissao, status, valor);
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setNaturezaOperacao("Venda de producao do estabelecimento");
    if (status != StatusNotaFiscal.RASCUNHO) {
      nota.setChaveAcesso(chave(p, numero));
      nota.setValorTotalIbs(tributo(valor, ALIQUOTA_IBS_2026));
      nota.setValorTotalCbs(tributo(valor, ALIQUOTA_CBS_2026));
    }
    nota.addItem(item(descricao, ncm, "5101", "KG", quantidade, valor));
    notaFiscalRepository.save(nota);
  }

  private void guia(Produtor p, TipoGuiaFiscal tipo, LocalDate vencimento, String valor,
      StatusGuiaFiscal status) {
    guiaFiscalRepository.save(GuiaFiscal.builder()
        .produtor(p)
        .tipoGuia(tipo)
        .dataVencimento(vencimento)
        .valor(new BigDecimal(valor))
        .status(status)
        .dataPagamento(status == StatusGuiaFiscal.PAGA
            ? vencimento.minusDays(2).atTime(10, 0).atOffset(ZoneOffset.ofHours(-3))
            : null)
        .observacoes("Seed de avaliacao da Ceres")
        .build());
  }

  private static NotaFiscal base(Produtor p, long numero, OffsetDateTime emissao,
      StatusNotaFiscal status, String valor) {
    return NotaFiscal.builder()
        .numero(numero)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Operacao de avaliacao")
        .dataEmissao(emissao)
        .produtor(p)
        .status(status)
        .destinatarioNome("Cooperativa Agroindustrial Avaliacao")
        .destinatarioCpfCnpj("11444777000161")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rodovia BR-277").numero("S/N").municipio("Cascavel")
            .codigoMunicipioIbge("4104808").uf("PR").cep("85800000").build())
        .valorTotalProdutos(new BigDecimal(valor))
        .valorTotalNota(new BigDecimal(valor))
        .build();
  }

  private static NotaFiscalItem item(String descricao, String ncm, String cfop, String unidade,
      String quantidade, String valor) {
    BigDecimal qtd = new BigDecimal(quantidade);
    BigDecimal total = new BigDecimal(valor);
    return NotaFiscalItem.builder()
        .numeroItem(1)
        .descricao(descricao)
        .ncm(ncm)
        .cfop(cfop)
        .unidade(unidade)
        .quantidade(qtd)
        .valorUnitario(total.divide(qtd, 4, RoundingMode.HALF_UP))
        .valorTotal(total)
        .build();
  }

  /** Chave fictícia de 44 dígitos, única por produtor e número ("99" não é cUF de UF nenhuma). */
  private static String chave(Produtor p, long numero) {
    long semente = Math.abs(p.getCpfCnpj().hashCode() % 1_000_000_000L);
    return String.format("99%09d%033d", semente, numero);
  }

  private static BigDecimal tributo(String valor, BigDecimal aliquota) {
    return new BigDecimal(valor).multiply(aliquota).setScale(2, RoundingMode.HALF_UP);
  }

  private static OffsetDateTime em(int ano, int mes, int dia) {
    return OffsetDateTime.of(ano, mes, dia, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
  }

  private static Produtor avaliado() {
    return Produtor.builder()
        .nomeCompleto(NOME_AVALIADO)
        .cpfCnpj(CPF_AVALIADO)
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .porteEmpresa(PorteEmpresa.ME)
        .culturas(new String[] {"soja", "milho"})
        .faturamentoAnual(new BigDecimal("2900000.00"))
        .estado("PR")
        .inscricaoEstadual("9012345678")
        .endereco(EnderecoProdutor.builder()
            .logradouro("Estrada Rural Linha Avaliacao").numero("1000").bairro("Zona Rural")
            .codigoMunicipioIbge("4104808").municipio("Cascavel").cep("85800000").build())
        .email("avaliacao.ceres@example.com")
        .build();
  }

  private static Produtor vizinho() {
    return Produtor.builder()
        .nomeCompleto(NOME_VIZINHO)
        .cpfCnpj(CPF_VIZINHO)
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .porteEmpresa(PorteEmpresa.ME)
        .culturas(new String[] {"soja"})
        .faturamentoAnual(new BigDecimal("3100000.00"))
        .estado("PR")
        .inscricaoEstadual("9087654321")
        .endereco(EnderecoProdutor.builder()
            .logradouro("Estrada Rural Linha Vizinha").numero("2000").bairro("Zona Rural")
            .codigoMunicipioIbge("4104808").municipio("Cascavel").cep("85800000").build())
        .email("vizinho.ceres@example.com")
        .build();
  }
}
