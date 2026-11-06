package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Filtros de {@code GET /api/contador/notas} e de {@code GET /api/contador/notas/resumo} (issue
 * #341): os de {@code GET /api/notas} (#174) mais {@code produtorId} e {@code responsavel}, como na
 * carteira. Ausente = não filtra. Nenhum filtro alarga o escopo: a carteira ativa do escritório
 * vem sempre antes.
 *
 * @param status        um ou mais status; repetido é OU. Ignorado no {@code /resumo}, que já
 *                      separa a contagem por grupo de status
 * @param tipoDocumento {@code NFE} ou {@code NFA}
 * @param emitidaDe     emissão a partir deste dia (inclusivo, em {@code America/Sao_Paulo})
 * @param emitidaAte    emissão até este dia inteiro (inclusivo, em {@code America/Sao_Paulo})
 * @param busca         a mesma de {@code GET /api/notas}
 * @param produtorId    só as notas deste cliente; fora da carteira ativa, 404
 * @param responsavel   {@code eu}, {@code nenhum} ou o id de um membro do escritório
 */
@Schema(description = "Filtros das notas da carteira do escritório")
public record FiltroNotasCarteiraRequest(
    @Schema(description = "Um ou mais status (repetível; OU entre eles). Ignorado no /resumo")
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
    String busca,

    @Schema(description = "Só as notas deste cliente da carteira (fora dela, 404)")
    UUID produtorId,

    @Schema(description = "Responsável pelo cliente: eu, nenhum ou o id de um membro",
        example = "eu")
    String responsavel) {

  /** Os filtros de nota (os de {@code GET /api/notas}), sem o recorte da carteira. */
  public FiltroNotasRequest comoFiltroDeNotas() {
    return new FiltroNotasRequest(status, tipoDocumento, emitidaDe, emitidaAte, busca);
  }

  /** Os mesmos filtros sem o status: o {@code /resumo} conta por grupo de status. */
  public FiltroNotasRequest comoFiltroDeNotasSemStatus() {
    return new FiltroNotasRequest(null, tipoDocumento, emitidaDe, emitidaAte, busca);
  }
}
