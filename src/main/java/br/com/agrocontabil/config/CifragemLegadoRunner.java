package br.com.agrocontabil.config;

import br.com.agrocontabil.security.CifraDeterministica;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cifra, na subida, os dados pessoais gravados em claro antes da issue #147.
 *
 * <p>O Liquibase não tem a chave (e não deve ter), então a migração do conteúdo é da aplicação:
 * para cada coluna cifrada, lê em lotes as linhas cujo valor não tem o prefixo {@code v1:} e grava
 * a versão cifrada.
 *
 * <p><strong>Antes do servidor web.</strong> Roda em {@link SmartInitializingSingleton} — depois de
 * todo singleton (o Liquibase já aplicou o schema), mas antes de o Tomcat aceitar conexões e de os
 * jobs começarem. Enquanto houver linha em claro, as buscas (que cifram o parâmetro) não a acham: um
 * cadastro com o CPF ou e-mail de uma conta legada passaria, e o login dela falharia. Por isso a
 * janela não pode estar aberta para requisições (achado do review de segurança; como
 * {@code ApplicationRunner} ela ficava).
 *
 * <p><strong>Idempotente e seguro entre réplicas.</strong> A atualização só vale se a linha ainda
 * tiver o valor lido ({@code WHERE id = ? AND coluna = ?}), e a cifra é determinística: duas
 * réplicas subindo juntas gravam o mesmo valor, e uma subida depois da migração não acha nada.
 *
 * <p><strong>Erro numa linha não derruba a subida.</strong> A linha fica em claro, o id vai ao log
 * em {@code ERROR} (sem o valor) e a migração segue; a próxima subida tenta de novo. Abortar faria
 * cada reinício falhar igual até alguém mexer no banco à mão.
 *
 * <p>Desligável por {@code app.seguranca.cripto.migrar-legado} (desligada nos testes, onde o
 * schema H2 nasce depois do contexto e o teste chama {@link #cifrarLegado()} direto).
 */
@Component
public class CifragemLegadoRunner implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(CifragemLegadoRunner.class);

  /** Tabela → colunas cifradas. Mesma lista dos {@code @Convert} das entidades. */
  static final Map<String, List<String>> COLUNAS_CIFRADAS = Map.of(
      "produtores", List.of("cpf_cnpj", "email", "telefone"),
      "contadores", List.of("cpf_cnpj", "email", "telefone"),
      "credencial_usuario", List.of("cpf_cnpj", "email"),
      "convite_carteira", List.of("email"),
      "convite_membro", List.of("email"));

  private static final int LOTE = 500;

  private final JdbcTemplate jdbc;
  private final CifraDeterministica cifra;
  private final boolean migrarNaSubida;

  public CifragemLegadoRunner(JdbcTemplate jdbc, CifraDeterministica cifra,
      CriptografiaProperties propriedades) {
    this.jdbc = jdbc;
    this.cifra = cifra;
    this.migrarNaSubida = propriedades.migrarLegado();
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (migrarNaSubida) {
      cifrarLegado();
    }
  }

  /**
   * Cifra todo valor legado das colunas cifradas.
   *
   * @return quantidade de valores cifrados
   */
  public int cifrarLegado() {
    int total = 0;
    for (Map.Entry<String, List<String>> tabela : COLUNAS_CIFRADAS.entrySet()) {
      for (String coluna : tabela.getValue()) {
        int cifrados = cifrarColuna(tabela.getKey(), coluna);
        if (cifrados > 0) {
          log.info("Cifragem do legado (#147): {}.{} — {} valor(es) cifrado(s)", tabela.getKey(),
              coluna, cifrados);
        }
        total += cifrados;
      }
    }
    return total;
  }

  private int cifrarColuna(String tabela, String coluna) {
    // Nomes vem da constante acima, nunca de entrada: a concatenacao e segura.
    String selecao = "SELECT id, " + coluna + " AS valor FROM " + tabela
        + " WHERE " + coluna + " IS NOT NULL AND " + coluna + " NOT LIKE '"
        + CifraDeterministica.PREFIXO + "%' LIMIT " + LOTE;
    String atualizacao = "UPDATE " + tabela + " SET " + coluna + " = ? WHERE id = ? AND "
        + coluna + " = ?";
    int cifrados = 0;
    int cifradosNoLote;
    List<Map<String, Object>> lote;
    do {
      lote = jdbc.queryForList(selecao);
      cifradosNoLote = 0;
      for (Map<String, Object> linha : lote) {
        cifradosNoLote += cifrarLinha(atualizacao, tabela, coluna, linha);
      }
      cifrados += cifradosNoLote;
      // Lote cheio de falhas voltaria igual: sem progresso, para (a proxima subida tenta de novo).
    } while (lote.size() == LOTE && cifradosNoLote > 0);
    return cifrados;
  }

  private int cifrarLinha(String atualizacao, String tabela, String coluna,
      Map<String, Object> linha) {
    String valor = (String) linha.get("valor");
    try {
      return jdbc.update(atualizacao, cifra.cifrar(valor), linha.get("id"), valor);
    } catch (RuntimeException e) {
      // Sem o valor e sem a excecao do banco, que traria a linha recusada (com o dado pessoal).
      log.error("Cifragem do legado (#147): {}.{} id={} ficou em claro ({}); resolver e reiniciar",
          tabela, coluna, linha.get("id"), e.getClass().getSimpleName());
      return 0;
    }
  }
}
