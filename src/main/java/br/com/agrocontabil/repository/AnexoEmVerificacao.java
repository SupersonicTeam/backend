package br.com.agrocontabil.repository;

import java.util.UUID;

/**
 * Anexo à espera do antivírus, com o arquivo (issue #227): o que o job precisa para verificar e
 * auditar, sem carregar a entidade.
 *
 * @param id          anexo
 * @param pendenciaId pendência
 * @param enviadoPor  produtor que anexou
 * @param conteudo    bytes
 */
public record AnexoEmVerificacao(UUID id, UUID pendenciaId, UUID enviadoPor, byte[] conteudo) {
}
