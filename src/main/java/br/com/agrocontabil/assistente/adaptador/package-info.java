/**
 * Adaptadores das portas da Ceres (issue #141): implementam as interfaces de
 * {@code assistente.porta} chamando os services do backend — nunca repositories direto, porque é
 * nos services que vivem o anti-IDOR e a regra fiscal. Extrair a Ceres como serviço separado é
 * trocar estes adaptadores por clientes HTTP.
 */
package br.com.agrocontabil.assistente.adaptador;
