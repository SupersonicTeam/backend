package br.com.agrocontabil.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Escritórios de teste (issues #519 e #520).
 *
 * <p>Os testes criam o contador pelo {@code CadastroService} com o construtor curto, que dá o
 * escritório pessoal. Equipe e endereço próprio exigem o empresarial: este helper marca o tipo
 * direto no banco, como o changeSet 0062 fez com os escritórios que já tinham equipe, sem mexer no
 * CNPJ (quem precisa dele grava pelo {@code PATCH /api/escritorio}, como antes).
 */
public final class EscritoriosDeTeste {

  private EscritoriosDeTeste() {
  }

  /**
   * Marca como empresarial o escritório do contador.
   *
   * @param jdbc       acesso ao banco de teste
   * @param contadorId contador membro do escritório
   */
  public static void tornarEmpresarial(JdbcTemplate jdbc, UUID contadorId) {
    jdbc.update("UPDATE escritorio SET tipo = 'EMPRESARIAL' WHERE id ="
        + " (SELECT escritorio_id FROM contadores WHERE id = ?)", contadorId);
  }

  /**
   * Marca como verificado o CNPJ do escritório do contador (#520), direto no banco: o endereço
   * próprio só é servido assim. O escritório já tem de ter CNPJ (o CHECK do changeSet 0063).
   * Quem testa a verificação em si usa o {@code VerificacaoCnpjService}.
   *
   * @param jdbc       acesso ao banco de teste
   * @param contadorId contador membro do escritório
   */
  public static void verificarCnpj(JdbcTemplate jdbc, UUID contadorId) {
    jdbc.update("UPDATE escritorio SET cnpj_verificado_em = CURRENT_TIMESTAMP,"
        + " cnpj_verificacao_metodo = 'MANUAL' WHERE id ="
        + " (SELECT escritorio_id FROM contadores WHERE id = ?)", contadorId);
  }

  /**
   * Tira a verificação do CNPJ do escritório do contador (#520).
   *
   * @param jdbc       acesso ao banco de teste
   * @param contadorId contador membro do escritório
   */
  public static void revogarCnpj(JdbcTemplate jdbc, UUID contadorId) {
    jdbc.update("UPDATE escritorio SET cnpj_verificado_em = NULL, cnpj_verificacao_metodo = NULL"
        + " WHERE id = (SELECT escritorio_id FROM contadores WHERE id = ?)", contadorId);
  }
}
