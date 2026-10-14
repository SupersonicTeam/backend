package br.com.agrocontabil.model;

/** Situação de um {@link AlertaTributario} (issue #140). */
public enum StatusAlertaTributario {
  /** Vale até a data-limite, enquanto o recurso ainda estiver na situação que gerou o alerta. */
  ATIVO,
  /** Prazo passou ou o motivo deixou de existir (guia paga, nota cancelada, apuração fechada). */
  RESOLVIDO
}
