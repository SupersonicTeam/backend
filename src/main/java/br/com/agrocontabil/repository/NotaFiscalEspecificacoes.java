package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Peças de {@link Specification} da listagem de notas (issue #174).
 *
 * <p>{@link #doProdutor(UUID)} é a base obrigatória: os filtros são sempre combinados por
 * {@code and} sobre ela, nunca no lugar dela — quem monta a consulta é o
 * {@code NotaFiscalService}, com o produtor autenticado.
 */
public final class NotaFiscalEspecificacoes {

  /** Dígitos do maior número de NF-e do layout ({@code nNF}). */
  private static final int DIGITOS_NUMERO_MAXIMO = 9;

  private NotaFiscalEspecificacoes() {
  }

  /** Notas do produtor. */
  public static Specification<NotaFiscal> doProdutor(UUID produtorId) {
    return (root, query, cb) -> cb.equal(root.get("produtor").get("id"), produtorId);
  }

  /** Status em qualquer um dos informados (OU). */
  public static Specification<NotaFiscal> comStatus(Collection<StatusNotaFiscal> status) {
    return (root, query, cb) -> root.get("status").in(status);
  }

  /** Tipo de documento. */
  public static Specification<NotaFiscal> doTipo(TipoDocumentoFiscal tipo) {
    return (root, query, cb) -> cb.equal(root.get("tipoDocumento"), tipo);
  }

  /** Emissão a partir do instante, inclusivo. */
  public static Specification<NotaFiscal> emitidaAPartirDe(OffsetDateTime inicio) {
    return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dataEmissao"), inicio);
  }

  /** Emissão antes do instante, exclusivo. */
  public static Specification<NotaFiscal> emitidaAntesDe(OffsetDateTime fim) {
    return (root, query, cb) -> cb.lessThan(root.get("dataEmissao"), fim);
  }

  /**
   * Texto pesquisável contendo o termo ({@code LIKE '%termo%'} sobre {@code busca_normalizada},
   * atendido pelo índice de trigrama no PostgreSQL).
   *
   * @param termoEscapado termo já normalizado e com os curingas escapados por {@code \}
   */
  public static Specification<NotaFiscal> contendo(String termoEscapado) {
    return (root, query, cb) ->
        cb.like(root.get("buscaNormalizada"), "%" + termoEscapado + "%", '\\');
  }

  /**
   * Número da nota começando pelos dígitos. "12" = 12, 120–129, 1200–1299, … até 9 dígitos: uma
   * faixa {@code BETWEEN} por tamanho de número, em vez de converter a coluna em texto.
   *
   * @param digitos só dígitos, sem zero à esquerda (número de nota não tem)
   */
  public static Specification<NotaFiscal> numeroComPrefixo(String digitos) {
    return (root, query, cb) -> {
      List<Predicate> faixas = new ArrayList<>();
      for (long[] faixa : faixasDoPrefixo(digitos)) {
        faixas.add(cb.between(root.get("numero"), faixa[0], faixa[1]));
      }
      return faixas.isEmpty() ? cb.disjunction() : cb.or(faixas.toArray(Predicate[]::new));
    };
  }

  /** Faixas {@code [inicio, fim]} dos números que começam pelos dígitos. */
  static List<long[]> faixasDoPrefixo(String digitos) {
    List<long[]> faixas = new ArrayList<>();
    if (digitos.isEmpty() || digitos.startsWith("0")
        || digitos.length() > DIGITOS_NUMERO_MAXIMO) {
      return faixas;
    }
    long prefixo = Long.parseLong(digitos);
    long escala = 1;
    for (int tamanho = digitos.length(); tamanho <= DIGITOS_NUMERO_MAXIMO; tamanho++) {
      faixas.add(new long[] {prefixo * escala, (prefixo + 1) * escala - 1});
      escala *= 10;
    }
    return faixas;
  }
}
