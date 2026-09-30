package br.com.agrocontabil.assistente.rag;

/**
 * Resultado de uma ingestão de norma (issue #156).
 *
 * @param dispositivos    dispositivos encontrados no texto
 * @param novos           trechos inseridos (primeira carga ou nova redação)
 * @param inalterados     trechos que já estavam na base com o mesmo texto (idempotência)
 * @param vigenciasFechadas redações anteriores encerradas por uma nova redação
 */
public record ResumoIngestao(int dispositivos, int novos, int inalterados, int vigenciasFechadas) {}
