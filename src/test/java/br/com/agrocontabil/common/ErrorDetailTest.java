package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ErrorDetailTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void shouldOmitNullOptionalFieldsInJson() throws Exception {
    ErrorDetail detail = ErrorDetail.of(ErrorCode.VALIDATION_ERROR, "itens[0].ncm",
        "NCM deve ter 8 digitos");

    String json = mapper.writeValueAsString(detail);

    assertThat(json).contains("\"code\":\"VALIDATION_ERROR\"");
    assertThat(json).contains("\"field\":\"itens[0].ncm\"");
    assertThat(json).contains("\"message\":\"NCM deve ter 8 digitos\"");
    assertThat(json).doesNotContain("suggestion");
    assertThat(json).doesNotContain("rejectedValue");
    assertThat(json).doesNotContain("expectedValue");
  }

  @Test
  void shouldSerializeAllFieldsWhenPresent() throws Exception {
    ErrorDetail detail = new ErrorDetail("CFOP_INVALIDO", "itens[0].cfop",
        "CFOP incompativel com a UF de destino",
        "Verifique se a operacao e estadual ou interestadual", "5102", "6102");

    String json = mapper.writeValueAsString(detail);

    assertThat(json).contains("\"suggestion\":");
    assertThat(json).contains("\"rejectedValue\":\"5102\"");
    assertThat(json).contains("\"expectedValue\":\"6102\"");
  }
}
