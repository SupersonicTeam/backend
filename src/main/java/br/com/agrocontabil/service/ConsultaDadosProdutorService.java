package br.com.agrocontabil.service;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.CompraInsumoAgregado;
import br.com.agrocontabil.repository.CompraInsumoTotal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaResumo;
import br.com.agrocontabil.repository.VencimentoGuiaResumo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas de dados do produtor usadas pelas funções da Ceres (issue #142).
 *
 * <p>Todo método recebe o {@code produtorId} explicitamente e toda consulta filtra por ele. As
 * funções da Ceres não podem ler o {@code SecurityContext} (com streaming elas rodam em outra
 * thread, issue #143), então quem chama resolve o produtor antes e o passa adiante.
 *
 * <p><strong>Contrato de segurança:</strong> {@code produtorId} tem de vir de identidade resolvida
 * no servidor (o {@code ContextoAssistente}), nunca de entrada do cliente ou do modelo.
 *
 * <p>As regras de recorte (entrada = {@code IMPORTADA} + {@code AUTORIZADA}, competência pela
 * emissão em {@code America/Sao_Paulo}) são as mesmas do crédito e da apuração: estão nas queries
 * do {@link NotaFiscalRepository}, não aqui.
 */
@Service
@Transactional(readOnly = true)
public class ConsultaDadosProdutorService {

  /** Fuso de referência do projeto para o recorte por data. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  /** Maior lista que uma consulta devolve (minimização, issue #144). */
  public static final int LIMITE_MAXIMO = 10;

  private static final Pattern PREFIXO_NCM = Pattern.compile("\\d{2,8}");

  private final NotaFiscalRepository notaFiscalRepository;
  private final GuiaFiscalRepository guiaFiscalRepository;

  /** Constrói o serviço com os repositórios de notas e de guias. */
  public ConsultaDadosProdutorService(
      NotaFiscalRepository notaFiscalRepository, GuiaFiscalRepository guiaFiscalRepository) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.guiaFiscalRepository = guiaFiscalRepository;
  }

  /**
   * Compras de um insumo identificado por NCM (código completo ou prefixo de 2 a 8 dígitos).
   *
   * @param produtorId produtor da sessão
   * @param prefixoNcm 2 a 8 dígitos (ex.: {@code 3808} = inseticidas, herbicidas, fungicidas)
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @return total, notas e quebra por unidade
   * @throws IllegalArgumentException se o prefixo não tiver só 2 a 8 dígitos
   */
  public ComprasInsumoResultado comprasPorNcm(
      UUID produtorId, String prefixoNcm, LocalDate inicio, LocalDate fim) {
    if (prefixoNcm == null || !PREFIXO_NCM.matcher(prefixoNcm).matches()) {
      throw new IllegalArgumentException("Prefixo de NCM deve ter de 2 a 8 digitos.");
    }
    String padrao = prefixoNcm + "%";
    return resultado(
        notaFiscalRepository.totalizarComprasPorNcm(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)),
        notaFiscalRepository.somarComprasPorNcm(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)));
  }

  /**
   * Compras de um insumo pela descrição do item (contém, sem diferenciar maiúsculas). Curingas do
   * {@code LIKE} digitados pelo usuário são tratados como texto.
   *
   * @param produtorId produtor da sessão
   * @param termo      trecho da descrição (ex.: {@code fertilizante})
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @return total, notas e quebra por unidade
   */
  public ComprasInsumoResultado comprasPorDescricao(
      UUID produtorId, String termo, LocalDate inicio, LocalDate fim) {
    String padrao = "%" + escaparLike(termo.trim().toLowerCase(Locale.ROOT)) + "%";
    return resultado(
        notaFiscalRepository.totalizarComprasPorDescricao(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)),
        notaFiscalRepository.somarComprasPorDescricao(
            produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim)));
  }

  /**
   * NCMs comprados de um insumo, pelo mesmo critério de {@link #comprasPorNcm} (termo de 2 a 8
   * dígitos) ou {@link #comprasPorDescricao} (texto), no mesmo recorte e maior valor primeiro.
   * Issue #170: no MISTO, a busca na lei inclui os itens de anexo desses NCMs.
   *
   * @param produtorId     produtor da sessão
   * @param descricaoOuNcm o termo já normalizado pela função {@code totalComprasPorInsumo}
   * @param inicio         primeiro dia (inclusivo)
   * @param fim            último dia (inclusivo)
   * @param limite         quantos NCMs (1 a {@link #LIMITE_MAXIMO})
   * @return NCMs de 8 dígitos, sem repetição
   */
  public List<String> ncmsComprados(
      UUID produtorId, String descricaoOuNcm, LocalDate inicio, LocalDate fim, int limite) {
    PageRequest pagina = PageRequest.of(0, limitar(limite));
    if (PREFIXO_NCM.matcher(descricaoOuNcm).matches()) {
      return notaFiscalRepository.listarNcmsCompradosPorNcm(produtorId, descricaoOuNcm + "%",
          inicioDoDia(inicio), fimExclusivo(fim), pagina);
    }
    String padrao = "%" + escaparLike(descricaoOuNcm.trim().toLowerCase(Locale.ROOT)) + "%";
    return notaFiscalRepository.listarNcmsCompradosPorDescricao(
        produtorId, padrao, inicioDoDia(inicio), fimExclusivo(fim), pagina);
  }

  /**
   * Notas do produtor no período, mais recentes primeiro, limitadas a {@link #LIMITE_MAXIMO}.
   *
   * @param produtorId produtor da sessão
   * @param origens    origens aceitas (nunca vazio)
   * @param status     situações aceitas (nunca vazio)
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @param limite     quantas devolver (1 a {@link #LIMITE_MAXIMO})
   * @return página com as notas e o total encontrado
   */
  public Page<NotaResumo> listarNotas(
      UUID produtorId, Set<OrigemNotaFiscal> origens, Set<StatusNotaFiscal> status,
      LocalDate inicio, LocalDate fim, int limite) {
    return notaFiscalRepository.listarResumos(
        produtorId, origens, status, inicioDoDia(inicio), fimExclusivo(fim),
        PageRequest.of(0, limitar(limite)));
  }

  /**
   * Guias não pagas com vencimento em {@code [de, ate]}, da mais próxima para a mais distante.
   *
   * @param produtorId produtor da sessão
   * @param de         primeiro dia (inclusivo)
   * @param ate        último dia (inclusivo)
   * @param limite     quantas devolver (1 a {@link #LIMITE_MAXIMO})
   * @return página com as guias e o total encontrado
   */
  public Page<VencimentoGuiaResumo> vencimentosNaoPagos(
      UUID produtorId, LocalDate de, LocalDate ate, int limite) {
    return guiaFiscalRepository.listarNaoPagasNaJanela(
        produtorId, de, ate, PageRequest.of(0, limitar(limite)));
  }

  /**
   * Escapa os curingas do {@code LIKE} com {@code !} (o {@code ESCAPE} das queries): o texto do
   * usuário é literal. O próprio {@code !} é escapado primeiro.
   */
  static String escaparLike(String texto) {
    return texto.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static ComprasInsumoResultado resultado(
      CompraInsumoTotal total, List<CompraInsumoAgregado> porUnidade) {
    BigDecimal valor = total == null || total.valorTotal() == null
        ? BigDecimal.ZERO
        : total.valorTotal();
    long notas = total == null || total.quantidadeNotas() == null ? 0 : total.quantidadeNotas();
    return new ComprasInsumoResultado(valor.setScale(2, RoundingMode.HALF_UP), notas, porUnidade);
  }

  private static int limitar(int limite) {
    return Math.max(1, Math.min(limite, LIMITE_MAXIMO));
  }

  private static OffsetDateTime inicioDoDia(LocalDate dia) {
    return dia.atStartOfDay(ZONE).toOffsetDateTime();
  }

  private static OffsetDateTime fimExclusivo(LocalDate ultimoDia) {
    return ultimoDia.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
  }
}
