package br.com.agrocontabil.service;

import org.springframework.stereotype.Service;

/**
 * Gerador da chave de acesso de 44 dígitos da NF-e modelo 55.
 *
 * <p>Composição da chave conforme Manual de Orientação do Contribuinte (MOC) da NF-e,
 * Layout 4.00, Tabela de Composição da Chave de Acesso:
 *
 * <pre>
 *  Campo    | Tamanho | Descrição
 * ----------+---------+------------------------------------------------------------
 *  cUF      |    2    | Código da UF do emitente (IBGE)
 *  AAMM     |    4    | Ano/Mês de emissão (ex.: 2612 = dezembro de 2026)
 *  CNPJ/CPF |   14    | CNPJ (14 dígitos) ou CPF zero-padded à esquerda até 14 dígitos
 *  mod      |    2    | Modelo: 55 (NF-e)
 *  serie    |    3    | Série (zero-padded)
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
 *   <li>Calcula {@code resto = soma % 11}.</li>
 *   <li>Se {@code resto == 0} ou {@code resto == 1}, DV = {@code 0}; caso contrário,
 *       DV = {@code 11 - resto}.</li>
 * </ol>
 *
 * <h3>cNF determinístico:</h3>
 * <p>Para garantir reprodutibilidade em testes, o {@code cNF} é derivado de
 * {@code (numero * 31L + serie * 7L) % 100_000_000L}, zero-padded para 8 dígitos.
 * Isso elimina aleatoriedade e permite que o teste valide o DV calculado manualmente.
 *
 * @see <a href="https://www.nfe.fazenda.gov.br/portal/listaConteudo.aspx?tipoConteudo=BMPFMBoln3w=">
 *      Manual de Orientação do Contribuinte — NF-e Layout 4.00</a>
 */
@Service
public class ChaveAcessoGenerator {

  /** Tamanho esperado do CPF/CNPJ na chave (zero-padded). */
  private static final int CPFCNPJ_LENGTH = 14;

  /**
   * Resultado da geração da chave de acesso.
   *
   * @param chave  44 dígitos completos (inclui cDV)
   * @param cNF    8 dígitos do código numérico usado na composição
   * @param cDV    dígito verificador calculado (0-9)
   */
  public record ChaveResult(String chave, String cNF, int cDV) {}

  /**
   * Gera a chave de acesso de 44 dígitos para uma NF-e modelo 55.
   *
   * <p>O {@code cpfCnpj} pode ter 11 dígitos (CPF) ou 14 dígitos (CNPJ);
   * CPFs são zero-padded à esquerda para completar 14 posições.
   *
   * @param cUF    código da UF do emitente (2 dígitos, ex.: "41")
   * @param aamm   ano/mês de emissão (4 dígitos, ex.: "2612")
   * @param cpfCnpj CPF (11 dígitos) ou CNPJ (14 dígitos) do emitente
   * @param serie  série da NF-e
   * @param numero número da NF-e
   * @return {@link ChaveResult} com chave completa, cNF e cDV
   */
  public ChaveResult gerar(String cUF, String aamm, String cpfCnpj, int serie, long numero) {
    String cnpjCpfFormatado = zeroPad(cpfCnpj.replaceAll("\\D", ""), CPFCNPJ_LENGTH);
    String serieStr = zeroPad(String.valueOf(serie), 3);
    String numeroStr = zeroPad(String.valueOf(numero), 9);
    String cNF = gerarCNF(numero, serie);

    // Compõe os 43 primeiros dígitos (sem DV)
    String base =
        cUF
            + aamm
            + cnpjCpfFormatado
            + "55"
            + serieStr
            + numeroStr
            + "1"   // tpEmis = 1 (normal)
            + cNF;

    int dv = calcularDv(base);
    String chave = base + dv;

    return new ChaveResult(chave, cNF, dv);
  }

  /**
   * Calcula o dígito verificador de 43 dígitos pela regra do módulo 11.
   *
   * <p>Exemplo de cálculo manual (para teste com chave 43 dígitos):
   * <pre>
   *   Dígitos (dir→esq): d43 d42 ... d1
   *   Pesos (ciclico 2..9): p1=2, p2=3, ..., p8=9, p9=2, ...
   *   soma = Σ(di × pi)
   *   resto = soma % 11
   *   DV = (resto < 2) ? 0 : (11 - resto)
   * </pre>
   *
   * @param base 43 dígitos numéricos
   * @return dígito verificador (0–9)
   * @throws IllegalArgumentException se {@code base} não tiver exatamente 43 dígitos numéricos
   */
  public int calcularDv(String base) {
    if (base == null || base.length() != 43) {
      throw new IllegalArgumentException(
          "Base para calculo de DV deve ter 43 digitos; recebido: "
              + (base != null ? base.length() : "null"));
    }
    int soma = 0;
    int peso = 2;
    for (int i = base.length() - 1; i >= 0; i--) {
      int digito = base.charAt(i) - '0';
      soma += digito * peso;
      peso = (peso == 9) ? 2 : peso + 1;
    }
    int resto = soma % 11;
    return (resto < 2) ? 0 : (11 - resto);
  }

  /**
   * Gera o {@code cNF} de 8 dígitos de forma determinística a partir do número e série.
   *
   * <p>Fórmula: {@code (numero * 31 + serie * 7) % 100_000_000}, zero-padded para 8 posições.
   * O valor mínimo é {@code 1} (para evitar cNF = "00000000" que pode gerar confusão).
   *
   * @param numero número da NF-e
   * @param serie  série da NF-e
   * @return string de 8 dígitos
   */
  public String gerarCNF(long numero, int serie) {
    long raw = (numero * 31L + serie * 7L) % 100_000_000L;
    long cNF = Math.max(raw, 1L); // garante mínimo 1
    return zeroPad(String.valueOf(cNF), 8);
  }

  /**
   * Zero-pad à esquerda até o comprimento desejado.
   *
   * @param value  string a ser padded
   * @param length comprimento alvo
   * @return string com zeros à esquerda
   * @throws IllegalArgumentException se {@code value} for maior que {@code length} — truncar
   *     produziria uma chave de acesso inconsistente com o XML (issue #88)
   */
  public String zeroPad(String value, int length) {
    if (value == null) {
      value = "";
    }
    if (value.length() > length) {
      // Truncar aqui (comportamento anterior a issue #88) fazia o nNF embutido na chave divergir
      // do nNF do XML: chave aceita localmente e rejeitada pela SEFAZ. A mensagem contem o valor
      // e nunca deve ser logada nem devolvida ao cliente (pode ser CPF/CNPJ — LGPD).
      throw new IllegalArgumentException(
          String.format("Valor com %d digitos excede o limite de %d para o campo da chave de"
              + " acesso", value.length(), length));
    }
    if (value.length() == length) {
      return value;
    }
    return "0".repeat(length - value.length()) + value;
  }
}
