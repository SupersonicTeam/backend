package br.com.agrocontabil.dto;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.NotaFiscalItem;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NotaFiscalItemRequest — CST e origem do ICMS (Bean Validation e mapeamento)")
class NotaFiscalItemRequestIcmsTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @ParameterizedTest
  @ValueSource(strings = {"40", "41", "50", "51", "90", ""})
  @DisplayName("aceita os CSTs que o leiaute emite sem calculo do ICMS, e o vazio (nao informado)")
  void aceitaCstsSemCalculo(String cst) {
    assertThat(VALIDATOR.validateValue(NotaFiscalItemRequest.class, "cstIcms", cst)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"00", "20", "60", "101", "4", "041", "5 1", " "})
  @DisplayName("recusa CST que exige o calculo do ICMS ou fora do formato (400)")
  void recusaOutros(String cst) {
    assertThat(VALIDATOR.validateValue(NotaFiscalItemRequest.class, "cstIcms", cst))
        .singleElement().satisfies(v -> assertThat(v.getMessage())
            .isEqualTo("cstIcms deve ser 40, 41, 50, 51 ou 90 (o sistema não calcula ICMS)"));
  }

  @Test
  @DisplayName("origem: um digito de 0 a 8; nulo aceito (padrao 0)")
  void origem() {
    assertThat(VALIDATOR.validateValue(NotaFiscalItemRequest.class, "origemIcms", "8")).isEmpty();
    assertThat(VALIDATOR.validateValue(NotaFiscalItemRequest.class, "origemIcms", null)).isEmpty();
    assertThat(VALIDATOR.validateValue(NotaFiscalItemRequest.class, "origemIcms", "9")).hasSize(1);
    assertThat(VALIDATOR.validateValue(NotaFiscalItemRequest.class, "cstIcms", null)).isEmpty();
  }

  @Test
  @DisplayName("o mapper leva CST e origem do request ao item")
  void mapeia() {
    NotaFiscalItemRequest req = new NotaFiscalItemRequest(1, null, "Soja", "12019000", "6101",
        "KG", BigDecimal.ONE, BigDecimal.TEN, null, null, null, "41", "2");

    NotaFiscalItem item = NotaFiscalMapper.toItemEntityList(List.of(req)).get(0);

    assertThat(item.getCstIcms()).isEqualTo("41");
    assertThat(item.getOrigemIcms()).isEqualTo("2");

    NotaFiscalItem vazio = NotaFiscalMapper.toItemEntityList(List.of(new NotaFiscalItemRequest(1,
        null, "Soja", "12019000", "5101", "KG", BigDecimal.ONE, BigDecimal.TEN, null, null, null,
        "", ""))).get(0);
    assertThat(vazio.getCstIcms()).isNull();
    assertThat(vazio.getOrigemIcms()).isNull();
  }
}
