package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.SituacaoAnexo;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Metadados de um anexo de pendência, sem o arquivo (issue #180).
 *
 * @param id          anexo
 * @param nomeArquivo nome sanitizado
 * @param mime        MIME conferido pelos bytes
 * @param tamanho     bytes
 * @param sha256      hash do conteúdo
 * @param situacao    antivírus (#227): {@code LIMPO} pode ser baixado; {@code EM_VERIFICACAO}
 *                    ainda não
 * @param enviadoEm   quando
 */
public record AnexoResumo(UUID id, String nomeArquivo, String mime, int tamanho, String sha256,
    SituacaoAnexo situacao, OffsetDateTime enviadoEm) {
}
