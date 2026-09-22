package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.CfopReferenciaRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.ImportacaoNotaFiscalService;
import br.com.agrocontabil.service.NotaFiscalService;
import br.com.agrocontabil.service.NotaFiscalValidacaoService;
import br.com.agrocontabil.service.SefazService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de validação de entrada do {@code POST /api/notas} (Onda 2, issue #88).
 *
 * <p>Cobre os limites do layout da NF-e que antes passavam silenciosamente: {@code numero} acima
 * de 999.999.999 e {@code serie} acima de 999 eram truncados pelo {@code zeroPad}, produzindo uma
 * chave de acesso divergente do XML; e o código IBGE do município do destinatário não era
 * confrontado com a UF informada.
 *
 * <p>Todos respondem <strong>400</strong> com o envelope {@code errors[]}, antes de qualquer
 * regra de negócio. A nota para o frontend está na descrição do PR.
 */
@WebMvcTest(controllers = NotaFiscalController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class NotaFiscalControllerValidationTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private NotaFiscalService notaFiscalService;
  @MockBean private SefazService sefazService;
  @MockBean private NotaFiscalValidacaoService validacaoService;
  @MockBean private ImportacaoNotaFiscalService importacaoService;
  @MockBean private ProdutorRepository produtorRepository;
  @MockBean private NcmReferenciaRepository ncmReferenciaRepository;
  @MockBean private CfopReferenciaRepository cfopReferenciaRepository;

  @Test
  @DisplayName("numero acima de 999.999.999 responde 400 com errors[]")
  void numeroAcimaDoLimite() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("1000000000", "1", enderecoJson("5103403", "MT"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'numero')]").exists());
  }

  @Test
  @DisplayName("serie acima de 999 responde 400 com errors[]")
  void serieAcimaDoLimite() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("1", "1000", enderecoJson("5103403", "MT"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'serie')]").exists());
  }

  @Test
  @DisplayName("codigo IBGE do destinatario de outra UF responde 400 com errors[]")
  void ibgeDestinatarioIncoerenteComUf() throws Exception {
    // Municipio de Sao Paulo capital (3550308) declarado como MT.
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("1", "1", enderecoJson("3550308", "MT"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(
            "$.errors[?(@.field =~ /.*codigoMunicipioIbge.*/)]").exists());
  }

  @Test
  @DisplayName("codigo IBGE omitido continua aceito (rascunho sem endereco completo)")
  void ibgeDestinatarioOmitidoAceito() throws Exception {
    when(notaFiscalService.create(any())).thenReturn(respostaMinima());

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("1", "1", "{\"uf\": \"MT\", \"municipio\": \"Cuiaba\"}")))
        .andExpect(status().isCreated());
  }

  // ---------------------------------------------------------------------------------------

  private static String enderecoJson(String codigoIbge, String uf) {
    return """
        {"municipio": "Cuiaba", "codigoMunicipioIbge": "%s", "uf": "%s"}
        """.formatted(codigoIbge, uf);
  }

  private static String payload(String numero, String serie, String enderecoJson) {
    return """
        {
          "numero": %s,
          "serie": %s,
          "naturezaOperacao": "Venda de producao",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "52998224725",
          "destinatarioEndereco": %s,
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Soja em graos",
              "ncm": "12010090",
              "cfop": "6101",
              "unidade": "KG",
              "quantidade": 100,
              "valorUnitario": 2.50
            }
          ]
        }
        """.formatted(numero, serie, enderecoJson);
  }

  private static NotaFiscalResponse respostaMinima() {
    return new NotaFiscalResponse(
        UUID.randomUUID(),
        null,
        1L,
        1,
        "55",
        "Venda de producao",
        StatusNotaFiscal.RASCUNHO,
        TipoDocumentoFiscal.NFE,
        OffsetDateTime.now(),
        null,
        UUID.randomUUID(),
        "Comprador",
        "52998224725",
        null,
        null,
        new BigDecimal("250.00"),
        new BigDecimal("250.00"),
        new BigDecimal("0.25"),
        new BigDecimal("2.25"),
        List.of(),
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }
}
