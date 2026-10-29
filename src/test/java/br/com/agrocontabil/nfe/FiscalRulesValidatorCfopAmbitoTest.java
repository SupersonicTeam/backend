package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.ProdutorTestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários da regra de compatibilidade CFOP × âmbito da operação (issue #20, RN008).
 *
 * <p>O 1.º dígito do CFOP codifica o âmbito da operação de saída:
 * <ul>
 *   <li>{@code 5xxx} → operação <strong>interna</strong> (mesma UF do emitente)</li>
 *   <li>{@code 6xxx} → operação <strong>interestadual</strong> (UF diferente)</li>
 *   <li>{@code 7xxx} → operação com o <strong>exterior</strong></li>
 * </ul>
 *
 * <p>O {@link FiscalRulesValidator} compara esse dígito com a relação real
 * {@code produtor.estado × destinatarioEndereco.uf} e levanta uma violação (HTTP 422) quando
 * incompatível.
 */
class FiscalRulesValidatorCfopAmbitoTest {

  private final FiscalRulesValidator validator = new FiscalRulesValidator();

  @Test
  @DisplayName("CFOP 5xxx (interno) com UFs iguais deve passar")
  void cfopInternoComUfsIguaisDevePassar() {
    NotaFiscal nota = notaCompleta("PR", "PR", "5101");
    assertThatCode(() -> validator.validar(nota)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP 6xxx (interestadual) com UFs diferentes deve passar")
  void cfopInterestadualComUfsDiferentesDevePassar() {
    NotaFiscal nota = notaCompleta("PR", "SP", "6101");
    assertThatCode(() -> validator.validar(nota)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP 5xxx (interno) com UFs diferentes deve falhar com 422")
  void cfopInternoComUfsDiferentesDeveFalhar() {
    NotaFiscal nota = notaCompleta("PR", "SP", "5101");

    assertThatThrownBy(() -> validator.validar(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(ex -> {
          List<?> errors =
              ((NotaFiscalInvalidaParaEmissaoException) ex).getErrors();
          assertThat(errors).isNotEmpty();
        });
  }

  @Test
  @DisplayName("CFOP 6xxx (interestadual) com UFs iguais deve falhar com 422")
  void cfopInterestadualComUfsIguaisDeveFalhar() {
    NotaFiscal nota = notaCompleta("PR", "PR", "6101");

    assertThatThrownBy(() -> validator.validar(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
  }

  @Test
  @DisplayName("CFOP 7xxx (exterior) com destinatario nacional deve falhar com 422")
  void cfopExteriorComDestinatarioNacionalDeveFalhar() {
    NotaFiscal nota = notaCompleta("PR", "SP", "7101");

    assertThatThrownBy(() -> validator.validar(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
  }

  @Test
  @DisplayName("CFOP 7xxx (exterior) com UF do destinatario 'EX' deve passar")
  void cfopExteriorComUfExteriorDevePassar() {
    NotaFiscal nota = notaCompleta("PR", "EX", "7101");
    assertThatCode(() -> validator.validar(nota)).doesNotThrowAnyException();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private NotaFiscal notaCompleta(String ufEmitente, String ufDestinatario, String cfop) {
    // Emitente completo (issue #88): IE e endereco coerente com a UF do cenario.
    Produtor produtor = Produtor.builder()
        .nomeCompleto("Produtor Teste")
        .cpfCnpj("12345678901")
        .estado(ufEmitente)
        .inscricaoEstadual(ProdutorTestFixtures.INSCRICAO_ESTADUAL)
        .endereco(ProdutorTestFixtures.enderecoParaUf(ufEmitente))
        .build();

    // Destinatario no exterior (UF "EX") usa o codigo de municipio do exterior; nas demais UFs
    // o codigo precisa comecar pelo cUF correspondente (issue #88).
    String codigoMunicipio = "EX".equals(ufDestinatario)
        ? "9999999"
        : UfIbge.codigo(ufDestinatario).orElseThrow() + "00001";
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua de Teste")
        .numero("100")
        .uf(ufDestinatario)
        .municipio("Municipio Teste")
        .codigoMunicipioIbge(codigoMunicipio)
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .numeroItem(1)
        .descricao("Soja")
        .ncm("12019000")
        .cfop(cfop)
        .unidade("KG")
        .quantidade(new BigDecimal("100"))
        .valorUnitario(new BigDecimal("2.50"))
        .valorTotal(new BigDecimal("250.00"))
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .numero(1L)
        .naturezaOperacao("Venda de producao")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Comprador")
        .destinatarioCpfCnpj("98765432000199")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("250.00"))
        .valorTotalNota(new BigDecimal("250.00"))
        .build();
    nota.addItem(item);
    return nota;
  }
}
