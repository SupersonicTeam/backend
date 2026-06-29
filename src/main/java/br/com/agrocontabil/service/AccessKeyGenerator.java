package br.com.agrocontabil.service;

import org.springframework.stereotype.Service;

/**
 * Gerador da key de acesso de 44 dígitos da NF-e model 55.
 *
 * <p>Composição da key conforme Manual de Orientação do Contribuinte (MOC) da NF-e,
 * Layout 4.00, Tabela de Composição da Chave de Acesso:
 *
 * <pre>
 *  Campo    | Tamanho | Descrição
 * ----------+---------+------------------------------------------------------------
 *  cUF      |    2    | Código da UF do emitente (IBGE)
 *  AAMM     |    4    | Ano/Mês de emissão (ex.: 2612 = dezembro de 2026)
 *  CNPJ/CPF |   14    | CNPJ (14 dígitos) ou CPF zero-padded à esquerda até 14 dígitos
 *  mod      |    2    | Modelo: 55 (NF-e)
 *  series    |    3    | Série (zero-padded)
 *  nNF      |    9    | Número da NF-e (zero-padded)
 *  tpEmis   |    1    | Forma de emissão: 1 = normal
 *  cNF      |    8    | Código numérico determinístico (derivado de número/série)
 *  cDV      |    1    | Dígito verificador (módulo 11)
 * ----------+---------+------------------------------------------------------------
 *  Total    |   44    |
 * </pre>
 *
 * <h3>Cálculo do DV (módulo 11):</h3>
 * <ol>
 *   <li>Toma os 43 dígitos anteriores ao DV.</li>
 *   <li>Multiplica cada dígito, da direita para a esquerda, pelos pesos 2, 3, 4, ..., 9,
 *       repetindo ciclicamente (2..9 cycling).</li>
 *   <li>Soma os produtos.</li>
 *   <li>Calcula {@code remainder = sum % 11}.</li>
 *   <li>Se {@code remainder == 0} ou {@code remainder == 1}, DV = {@code 0}; caso contrário,
 *       DV = {@code 11 - remainder}.</li>
 * </ol>
 *
 * <h3>cNF determinístico:</h3>
 * <p>Para garantir reprodutibilidade em testes, o {@code cNF} é derivado de
 * {@code (number * 31L + series * 7L) % 100_000_000L}, zero-padded para 8 dígitos.
 * Isso elimina aleatoriedade e permite que o teste valide o DV calculado manualmente.
 *
 * @see <a href="https://www.nfe.fazenda.gov.br/portal/listaConteudo.aspx?tipoConteudo=BMPFMBoln3w=">
 *      Manual de Orientação do Contribuinte — NF-e Layout 4.00</a>
 */
@Service
public class AccessKeyGenerator {

  /** Tamanho esperado do CPF/CNPJ na key (zero-padded). */
  private static final int CPFCNPJ_LENGTH = 14;

  /**
   * Resultado da geração da key de acesso.
   *
   * @param key  44 dígitos completos (inclui cDV)
   * @param cNF    8 dígitos do código numérico usado na composição
   * @param cDV    dígito verificador calculado (0-9)
   */
  public record AccessKeyResult(String key, String cNF, int cDV) {}

  /**
   * Gera a key de acesso de 44 dígitos para uma NF-e model 55.
   *
   * <p>O {@code taxId} pode ter 11 dígitos (CPF) ou 14 dígitos (CNPJ);
   * CPFs são zero-padded à esquerda para completar 14 posições.
   *
   * @param cUF    código da UF do emitente (2 dígitos, ex.: "41")
   * @param aamm   ano/mês de emissão (4 dígitos, ex.: "2612")
   * @param taxId CPF (11 dígitos) ou CNPJ (14 dígitos) do emitente
   * @param series  série da NF-e
   * @param number número da NF-e
   * @return {@link AccessKeyResult} com key completa, cNF e cDV
   */
  public AccessKeyResult generate(String cUF, String aamm, String taxId, int series, long number) {
    String formattedTaxId = zeroPad(taxId.replaceAll("\\D", ""), CPFCNPJ_LENGTH);
    String seriesStr = zeroPad(String.valueOf(series), 3);
    String numberStr = zeroPad(String.valueOf(number), 9);
    String cNF = generateCNF(number, series);

    // Compõe os 43 primeiros dígitos (sem DV)
    String base =
        cUF
            + aamm
            + formattedTaxId
            + "55"
            + seriesStr
            + numberStr
            + "1"   // tpEmis = 1 (normal)
            + cNF;

    int dv = calculateCheckDigit(base);
    String key = base + dv;

    return new AccessKeyResult(key, cNF, dv);
  }

  /**
   * Calcula o dígito verificador de 43 dígitos pela regra do módulo 11.
   *
   * <p>Exemplo de cálculo manual (para teste com key 43 dígitos):
   * <pre>
   *   Dígitos (dir→esq): d43 d42 ... d1
   *   Pesos (ciclico 2..9): p1=2, p2=3, ..., p8=9, p9=2, ...
   *   sum = Σ(di × pi)
   *   remainder = sum % 11
   *   DV = (remainder < 2) ? 0 : (11 - remainder)
   * </pre>
   *
   * @param base 43 dígitos numéricos
   * @return dígito verificador (0–9)
   * @throws IllegalArgumentException se {@code base} não tiver exatamente 43 dígitos numéricos
   */
  public int calculateCheckDigit(String base) {
    if (base == null || base.length() != 43) {
      throw new IllegalArgumentException(
          "Base para calculo de DV deve ter 43 digitos; recebido: "
              + (base != null ? base.length() : "null"));
    }
    int sum = 0;
    int weight = 2;
    for (int i = base.length() - 1; i >= 0; i--) {
      int digit = base.charAt(i) - '0';
      sum += digit * weight;
      weight = (weight == 9) ? 2 : weight + 1;
    }
    int remainder = sum % 11;
    return (remainder < 2) ? 0 : (11 - remainder);
  }

  /**
   * Gera o {@code cNF} de 8 dígitos de forma determinística a partir do número e série.
   *
   * <p>Fórmula: {@code (number * 31 + series * 7) % 100_000_000}, zero-padded para 8 posições.
   * O valor mínimo é {@code 1} (para evitar cNF = "00000000" que pode generate confusão).
   *
   * @param number número da NF-e
   * @param series  série da NF-e
   * @return string de 8 dígitos
   */
  public String generateCNF(long number, int series) {
    long raw = (number * 31L + series * 7L) % 100_000_000L;
    long cNF = Math.max(raw, 1L); // garante mínimo 1
    return zeroPad(String.valueOf(cNF), 8);
  }

  /**
   * Zero-pad à esquerda até o comprimento desejado.
   *
   * @param value  string a ser padded
   * @param length comprimento alvo
   * @return string com zeros à esquerda
   */
  public String zeroPad(String value, int length) {
    if (value == null) {
      value = "";
    }
    if (value.length() >= length) {
      return value.substring(value.length() - length);
    }
    return "0".repeat(length - value.length()) + value;
  }
}
