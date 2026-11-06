package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Filtros opcionais de {@code GET /api/notas} (issue #174), combináveis entre si e com a
 * paginação. Ausente = não filtra.
 *
 * <p>Nenhum filtro escolhe o produtor: o recorte do produtor autenticado vem sempre antes.
 *
 * @param status        um ou mais status; repetido é OU ({@code ?status=A&status=B})
 * @param tipoDocumento {@code NFE} ou {@code NFA}
 * @param emitidaDe     emissão a partir deste dia (inclusivo, em {@code America/Sao_Paulo})
 * @param emitidaAte    emissão até este dia inteiro (inclusivo, em {@code America/Sao_Paulo})
 * @param busca         número, destinatário, natureza da operação, CPF/CNPJ do destinatário
 *                      (11 ou 14 dígitos, com ou sem máscara) e descrição ou NCM dos itens,
 *                      parcial e sem diferenciar maiúscula nem acento; texto com menos de 3
 *                      caracteres é ignorado, exceto número (prefixo do número da nota)
 */
public record FiltroNotasRequest(
    @Schema(description = "Um ou mais status (repetível; OU entre eles)")
    List<StatusNotaFiscal> status,

    @Schema(description = "NFE ou NFA")
    TipoDocumentoFiscal tipoDocumento,

    @Schema(description = "Emissão a partir deste dia, inclusivo (YYYY-MM-DD)",
        example = "2026-09-01")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    LocalDate emitidaDe,

    @Schema(description = "Emissão até este dia inteiro, inclusivo (YYYY-MM-DD)",
        example = "2026-09-30")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    LocalDate emitidaAte,

    @Schema(description = "Número, destinatário, natureza, CPF/CNPJ do destinatário ou produto "
        + "(descrição ou NCM do item) (3 a 100 caracteres; número da nota também por prefixo)",
        example = "soja")
    @Size(max = 100)
    String busca) {

  /** Sem nenhum filtro. */
  public static FiltroNotasRequest vazio() {
    return new FiltroNotasRequest(null, null, null, null, null);
  }
}
