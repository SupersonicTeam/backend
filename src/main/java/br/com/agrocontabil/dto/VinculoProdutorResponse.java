package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.EncerradoPor;
import br.com.agrocontabil.model.SituacaoVinculo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Escritório que atende o produtor, na visão do produtor (issue #177). Sem as observações nem as
 * etiquetas do escritório.
 *
 * @param id          vínculo
 * @param escritorioId escritório (issue #228)
 * @param escritorio  nome do escritório
 * @param responsavel nome do contador responsável pelo produtor no escritório
 * @param crc         registro do responsável no conselho regional
 * @param situacao   situação do vínculo
 * @param inicio     início
 * @param fim        fim (só encerrado)
 * @param encerradoPor quem encerrou; nulo se o vínculo não está encerrado ou foi encerrado antes
 *     do registro (#402)
 * @param motivoEncerramento motivo informado, se houver
 */
@Schema(description = "Escritório que atende o produtor, na visão do produtor")
public record VinculoProdutorResponse(
    @Schema(description = "Id do vínculo", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Id do escritório", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID escritorioId,
    @Schema(description = "Nome do escritório", example = "Escritório Campo Verde")
    String escritorio,
    @Schema(description = "Nome do contador responsável pelo produtor", example = "Ana Souza")
    String responsavel,
    @Schema(description = "Registro do responsável no conselho regional", example = "MT-12345")
    String crc,
    @Schema(description = "Situação do vínculo", example = "ATIVO")
    SituacaoVinculo situacao,
    @Schema(description = "Início do vínculo", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime inicio,
    @Schema(description = "Fim do vínculo; só quando encerrado")
    OffsetDateTime fim,
    @Schema(description = "Quem encerrou: PRODUTOR ou ESCRITORIO. Nulo quando o vínculo não está "
        + "encerrado ou quando foi encerrado antes do registro (\"não registrado\": não "
        + "presumir que foi o produtor)", example = "ESCRITORIO", nullable = true)
    EncerradoPor encerradoPor,
    @Schema(description = "Motivo do encerramento, texto livre; nulo se não informado ou não "
        + "registrado", example = "Mudança de escritório", nullable = true)
    String motivoEncerramento) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }
}
