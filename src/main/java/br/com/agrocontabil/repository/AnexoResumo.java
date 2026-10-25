package br.com.agrocontabil.repository;

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
 * @param enviadoEm   quando
 */
public record AnexoResumo(UUID id, String nomeArquivo, String mime, int tamanho, String sha256,
    OffsetDateTime enviadoEm) {
}
