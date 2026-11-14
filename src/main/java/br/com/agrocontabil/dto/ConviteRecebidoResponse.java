package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoConvite;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite na visão do produtor (issue #177).
 *
 * @param id         convite
 * @param escritorio   nome do escritório que convidou (texto livre, escolhido pelo escritório)
 * @param escritorioVerificado o CNPJ do escritório foi verificado pelo AgroConIA (#520)? O nome
 *     não prova quem convidou: sem a verificação, a tela avisa o produtor
 * @param escritorioCnpj CNPJ do escritório, só dígitos, só quando verificado (o selo o mostra ao
 *     lado do nome); nulo sem verificação
 * @param convidadoPor nome do contador que mandou o convite (#228)
 * @param crc          registro dele no conselho regional
 * @param situacao   situação do convite
 * @param criadoEm   quando foi enviado
 * @param expiraEm   até quando vale
 */
@Schema(description = "Convite recebido pelo produtor de um escritório")
public record ConviteRecebidoResponse(
    @Schema(description = "Id do convite", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Nome do escritório que convidou", example = "Escritório Campo Verde")
    String escritorio,
    @Schema(description = "O CNPJ do escritório foi verificado pelo AgroConIA (#520)? O nome é "
        + "texto livre e não prova quem convidou: sem a verificação, mostre um aviso para o "
        + "produtor confirmar o convite com o contador antes de aceitar", example = "true")
    boolean escritorioVerificado,
    @Schema(description = "CNPJ do escritório, só dígitos, só quando verificado: o selo o mostra "
        + "ao lado do nome. Nulo sem verificação", example = "11444777000161", nullable = true)
    String escritorioCnpj,
    @Schema(description = "Nome do contador que enviou o convite", example = "Ana Souza")
    String convidadoPor,
    @Schema(description = "Registro do contador no conselho regional", example = "MT-12345")
    String crc,
    @Schema(description = "Situação do convite", example = "PENDENTE")
    SituacaoConvite situacao,
    @Schema(description = "Quando o convite foi enviado", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime criadoEm,
    @Schema(description = "Até quando o convite vale", example = "2026-09-15T10:00:00-03:00")
    OffsetDateTime expiraEm) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }
}
