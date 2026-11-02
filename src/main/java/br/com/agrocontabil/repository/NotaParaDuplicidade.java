package br.com.agrocontabil.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Nota lida para o sinal {@code POSSIVEL_DUPLICIDADE} (issue #276): o documento do destinatário
 * só compara notas do mesmo produtor, no service, e nunca sai dele.
 *
 * @param id                  nota
 * @param numero              número (a frase cita a outra nota pelo número)
 * @param destinatarioCpfCnpj documento do destinatário
 * @param valorTotal          valor total da nota
 */
public record NotaParaDuplicidade(UUID id, Long numero, String destinatarioCpfCnpj,
    BigDecimal valorTotal) {}
