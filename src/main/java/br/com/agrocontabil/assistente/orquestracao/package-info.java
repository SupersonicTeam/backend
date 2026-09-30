/**
 * Orquestração da Ceres (issue #157): classifica a intenção da pergunta e decide a camada que
 * responde (dados, legislação, as duas ou fora de escopo).
 *
 * <p>Nada aqui é {@code @Transactional}: a chamada ao modelo nunca roda dentro de transação
 * ({@code ArquiteturaAssistenteTest}).
 */
package br.com.agrocontabil.assistente.orquestracao;
