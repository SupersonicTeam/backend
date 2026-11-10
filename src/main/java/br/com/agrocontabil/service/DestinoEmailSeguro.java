package br.com.agrocontabil.service;

import java.util.List;
import java.util.Locale;

/**
 * Destinos que nunca recebem e-mail de verdade (issue #460): os domínios reservados para teste e
 * documentação (RFC 2606 e RFC 6761) e o {@code .local}. As contas do seed da demonstração são
 * {@code @example.com}: mandar para elas só geraria bounce, e bounce acima do limite do provedor
 * suspende a conta.
 */
public final class DestinoEmailSeguro {

  private DestinoEmailSeguro() {
  }

  /**
   * Se o destino é de domínio reservado.
   *
   * @param email       destinatário
   * @param reservados  entradas de {@code app.email.dominios-reservados}: domínio (vale ele e os
   *                    subdomínios) ou sufixo com ponto no início ({@code .test})
   * @return {@code true} se o e-mail não deve sair
   */
  public static boolean reservado(String email, List<String> reservados) {
    if (email == null) {
      return true;
    }
    int arroba = email.lastIndexOf('@');
    String dominio = normalizar(arroba < 0 ? "" : email.substring(arroba + 1));
    if (dominio.isEmpty()) {
      return true;
    }
    for (String entrada : reservados) {
      String reservado = normalizar(entrada);
      if (reservado.isEmpty()) {
        continue;
      }
      String raiz = reservado.startsWith(".") ? reservado.substring(1) : reservado;
      if (dominio.equals(raiz) || dominio.endsWith("." + raiz)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizar(String dominio) {
    String texto = dominio == null ? "" : dominio.strip().toLowerCase(Locale.ROOT);
    while (texto.endsWith(".")) {
      texto = texto.substring(0, texto.length() - 1);
    }
    return texto;
  }
}
