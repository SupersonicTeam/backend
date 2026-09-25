package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.service.ChaveAcessoGenerator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link ChaveAcessoGenerator}.
 *
 * <h3>Exemplo de cálculo manual do DV (módulo 11):</h3>
 * <p>Chave base (43 dígitos):
 * {@code 4126120000000000055001000000001100000003}... (exemplo construído abaixo)
 *
 * <p>Para o exemplo do teste principal:
 * <ul>
 *   <li>cUF = "41" (Paraná)</li>
 *   <li>AAMM = "2612" (dezembro 2026)</li>
 *   <li>CPF = "12345678901" → zero-padded 14 = "00012345678901"</li>
 *   <li>mod = "55"</li>
 *   <li>serie = "001"</li>
 *   <li>nNF = "000000001"</li>
 *   <li>tpEmis = "1"</li>
 *   <li>cNF = (1 * 31 + 1 * 7) % 100_000_000 = 38 → "00000038"</li>
 * </ul>
 * Base 43 dígitos: "41" + "2612" + "00012345678901" + "55" + "001" + "000000001" + "1" + "00000038"
 * = "41261200012345678901550010000000011000000038"
 *
 * <p>Cálculo do DV (pesos 2..9 ciclicamente, da direita para esquerda):
 * <pre>
 *  Pos  Digit  Peso  Produto
 *   1     8     2      16
 *   2     3     3       9
 *   3     0     4       0
 *   4     0     5       0
 *   5     0     6       0
 *   6     0     7       0
 *   7     0     8       0
 *   8     0     9       0
 *   9     1     2       2  (peso volta para 2)
 *  10     1     3       3
 *  11     0     4       0
 *  12     0     5       0
 *  13     0     6       0
 *  14     0     7       0
 *  15     0     8       0
 *  16     0     9       0
 *  17     0     2       0
 *  18     0     3       0
 *  19     0     4       0
 *  20     0     5       0
 *  21     5     6      30
 *  22     5     7      35
 *  23     1     8       8
 *  24     0     9       0
 *  25     9     2      18
 *  26     8     3      24
 *  27     7     4      28
 *  28     6     5      30
 *  29     5     6      30
 *  30     4     7      28
 *  31     3     8      24
 *  32     2     9      18
 *  33     1     2       2
 *  34     0     3       0
 *  35     0     4       0
 *  36     2     5      10
 *  37     1     6       6
 *  38     6     7      42
 *  39     2     8      16
 *  40     1     9       9
 *  41     4     2       8
 *  suma total = 16+9+2+3+30+35+8+18+24+28+30+30+28+24+18+2+10+6+42+16+9+8 = 396
 *  resto = 396 % 11 = 396 - 36*11 = 396 - 396 = 0
 *  DV = 0 (resto < 2 → DV = 0)
 * </pre>
 * (O cálculo exato depende dos 43 dígitos; o teste usa asserção programática,
 *  não hard-coded, para confirmar que o DV é auto-consistente.)
 */
@DisplayName("ChaveAcessoGenerator")
class ChaveAcessoGeneratorTest {

  private ChaveAcessoGenerator generator;

  @BeforeEach
  void setUp() {
    generator = new ChaveAcessoGenerator();
  }

  @Test
  @DisplayName("deve gerar chave com exatamente 44 digitos numericos")
  void deveGerarChaveCom44Digitos() {
    ChaveAcessoGenerator.ChaveResult result =
        generator.gerar("41", "2612", "00012345678901", 1, 1L);

    assertThat(result.chave()).hasSize(44);
    assertThat(result.chave()).matches("\\d{44}");
  }

  @Test
  @DisplayName("deve zero-paddar CPF de 11 digitos para 14 na chave")
  void deveZeroPaddarCpfNaChave() {
    // CPF 12345678901 (11 dígitos) deve aparecer como 00012345678901 na chave
    ChaveAcessoGenerator.ChaveResult result =
        generator.gerar("41", "2612", "12345678901", 1, 1L);

    String chave = result.chave();
    assertThat(chave).hasSize(44);
    // Posições 7..20 (0-indexed 6..19) = CNPJ/CPF (14 dígitos)
    String cpfNaChave = chave.substring(6, 20);
    assertThat(cpfNaChave).isEqualTo("00012345678901");
  }

  @Test
  @DisplayName("deve preservar CNPJ de 14 digitos sem modificacao")
  void devePreservarCnpjNaChave() {
    ChaveAcessoGenerator.ChaveResult result =
        generator.gerar("35", "2606", "12345678000195", 1, 100L);

    String cnpjNaChave = result.chave().substring(6, 20);
    assertThat(cnpjNaChave).isEqualTo("12345678000195");
  }

  @Test
  @DisplayName("deve calcular DV correto pelo modulo 11 — DV auto-consistente")
  void deveTerDvAutoConsistente() {
    ChaveAcessoGenerator.ChaveResult result =
        generator.gerar("41", "2612", "00012345678901", 1, 1L);

    String chave = result.chave();
    // Os 43 primeiros dígitos são a base
    String base = chave.substring(0, 43);
    int dvEsperado = generator.calcularDv(base);
    int dvNaChave = Character.getNumericValue(chave.charAt(43));

    assertThat(dvNaChave).isEqualTo(dvEsperado);
    assertThat(result.cDV()).isEqualTo(dvEsperado);
  }

  @Test
  @DisplayName("DV deve ser 0 quando resto do modulo 11 for 0 ou 1")
  void dvDeveSerZeroParaResto0ou1() {
    // Cria uma base de 43 zeros → soma = 0, resto = 0, DV = 0
    String baseZeros = "0".repeat(43);
    int dv = generator.calcularDv(baseZeros);
    assertThat(dv).isEqualTo(0);
  }

  @Test
  @DisplayName("cNF tem 8 digitos e o DV da chave continua correto")
  void cnfTemOitoDigitosEDvCorreto() {
    for (int i = 0; i < 50; i++) {
      ChaveAcessoGenerator.ChaveResult result =
          generator.gerar("41", "2612", "12345678000195", 1, 1L);

      assertThat(result.cNF()).matches("\\d{8}");
      assertThat(result.chave().substring(35, 43)).isEqualTo(result.cNF());
      assertThat(result.cDV()).isEqualTo(generator.calcularDv(result.chave().substring(0, 43)));
    }
  }

  @Test
  @DisplayName("cNF e imprevisivel: a mesma nota gerada de novo recebe outro cNF (issue #129)")
  void cnfImprevisivel() {
    // Antes o cNF era derivado de numero e serie: a mesma nota gerava sempre a mesma chave, e
    // qualquer um que conhecesse o emitente deduzia a chave de acesso das notas dele.
    Set<String> sorteados = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      sorteados.add(generator.gerar("41", "2612", "12345678000195", 1, 1L).cNF());
    }
    // 20 sorteios em 100 milhoes: repetir todos seria impossivel na pratica.
    assertThat(sorteados).hasSizeGreaterThan(1);
  }

  @Test
  @DisplayName("cNF nunca e igual ao nNF, digitos repetidos ou sequencia trivial (rejeicao 897)")
  void cnfEvitaFormasRecusadasPelaSefaz() {
    // Fonte roteirizada: os quatro primeiros sorteios caem nas formas que a SEFAZ recusa.
    ChaveAcessoGenerator roteirizado = new ChaveAcessoGenerator(
        sorteios(123, 11111111, 12345678, 1234567, 48151623));

    ChaveAcessoGenerator.ChaveResult result =
        roteirizado.gerar("41", "2612", "12345678000195", 1, 123L);

    // 00000123 = nNF; 11111111 = digitos iguais; 12345678 e 01234567 = sequencias triviais.
    assertThat(result.cNF()).isEqualTo("48151623");
  }

  /** Fonte de aleatoriedade que devolve os valores na ordem dada. */
  private static RandomGenerator sorteios(int... valores) {
    Iterator<Integer> proximo = Arrays.stream(valores).iterator();
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        throw new UnsupportedOperationException("so nextInt(bound) e usado");
      }

      @Override
      public int nextInt(int bound) {
        return proximo.next();
      }
    };
  }

  @Test
  @DisplayName("composicao da chave deve ter os campos nas posicoes corretas")
  void composicaoCorretaDosSegmentos() {
    // cUF=41 AAMM=2612 CNPJ=12345678000195 mod=55 serie=001 nNF=000000001 tpEmis=1 cNF=? cDV=?
    ChaveAcessoGenerator.ChaveResult result =
        generator.gerar("41", "2612", "12345678000195", 1, 1L);

    String chave = result.chave();
    assertThat(chave.substring(0, 2)).isEqualTo("41");   // cUF
    assertThat(chave.substring(2, 6)).isEqualTo("2612"); // AAMM
    assertThat(chave.substring(6, 20)).isEqualTo("12345678000195"); // CNPJ
    assertThat(chave.substring(20, 22)).isEqualTo("55"); // mod
    assertThat(chave.substring(22, 25)).isEqualTo("001"); // serie
    assertThat(chave.substring(25, 34)).isEqualTo("000000001"); // nNF
    assertThat(chave.substring(34, 35)).isEqualTo("1"); // tpEmis
    assertThat(chave.substring(35, 43)).isEqualTo(result.cNF()); // cNF
    assertThat(chave.substring(43)).isEqualTo(String.valueOf(result.cDV())); // cDV
  }

  @Test
  @DisplayName("calcularDv deve rejeitar base com tamanho diferente de 43")
  void deveLancarExcecaoParaBaseComTamanhoErrado() {
    assertThatThrownBy(() -> generator.calcularDv("123"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("43 digitos");
  }

  @Test
  @DisplayName("zeroPad deve adicionar zeros a esquerda")
  void zeroPadDeveAdicionarZeros() {
    assertThat(generator.zeroPad("5", 3)).isEqualTo("005");
    assertThat(generator.zeroPad("123", 3)).isEqualTo("123");
    // O caso "valor maior que o limite" deixou de truncar na issue #88 — ver zeroPadLancaEmOverflow.
  }

  // -------------------------------------------------------------------------
  // Overflow de nNF / serie (issue #88)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("zeroPad deve lancar quando o valor excede o tamanho, em vez de truncar (#88)")
  void zeroPadLancaEmOverflow() {
    // Antes da #88 isto devolvia "234567890" silenciosamente: o nNF embutido na chave divergia
    // do nNF do XML, produzindo uma chave inconsistente aceita localmente e rejeitada na SEFAZ.
    assertThatThrownBy(() -> generator.zeroPad("1234567890", 9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("9");
  }

  @Test
  @DisplayName("gerar com nNF acima de 999.999.999 lanca em vez de truncar (#88)")
  void gerarComNumeroAcimaDoLimiteLanca() {
    assertThatThrownBy(() -> generator.gerar("41", "2612", "00012345678901", 1, 1_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("nNF e serie no limite produzem chave consistente com o informado (#88)")
  void chaveComNnfESerieNoLimite() {
    ChaveAcessoGenerator.ChaveResult result =
        generator.gerar("41", "2612", "00012345678901", 999, 999_999_999L);

    String chave = result.chave();
    assertThat(chave).hasSize(44);
    // Posicoes do layout: modelo [20,22), serie [22,25), nNF [25,34).
    assertThat(chave.substring(20, 22)).isEqualTo("55");
    assertThat(chave.substring(22, 25)).isEqualTo("999");
    assertThat(chave.substring(25, 34)).isEqualTo("999999999");
  }
}
