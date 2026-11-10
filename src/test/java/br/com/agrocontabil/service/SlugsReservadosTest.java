package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Slugs reservados: o arquivo versionado da #464")
class SlugsReservadosTest {

  private final SlugsReservados reservados = new SlugsReservados();

  @Test
  @DisplayName("tem a lista da secao 4.1, com ceres, e os nomes acrescentados em 2026-11-09")
  void listaDecidida() {
    assertThat(reservados.nomes()).contains(
        // Infraestrutura
        "www", "api", "app", "admin", "administrador", "mail", "email", "smtp", "imap", "pop",
        "webmail", "mx", "ns1", "ns2", "dns", "ftp", "autodiscover", "autoconfig", "cdn",
        "static", "assets", "img", "status", "healthz", "interno",
        // Ambientes
        "dev", "homolog", "staging", "test", "teste", "demo", "demonstracao", "banca",
        // Produto e autenticacao
        "login", "entrar", "cadastro", "conta", "auth", "sso", "oauth", "senha", "seguranca",
        "ajuda", "suporte", "help", "docs", "blog", "contato", "pagamento", "cobranca",
        "billing", "planos", "precos",
        // Marcas e orgaos
        "agroconia", "agrocontabil", "gwnext", "supersonic", "ceres", "sefaz", "receita", "gov",
        "nfe",
        // Decisao do Murillo, 2026-11-09
        "atendimento", "comercial", "vendas", "financeiro", "sac", "ouvidoria", "parceiros", "ia",
        "assistente");
    assertThat(reservados.termosContidos())
        .containsExactlyInAnyOrder("agroconia", "agrocontabil", "gwnext", "sefaz", "receita");
  }

  @Test
  @DisplayName("nome exato e termo contido sao reservados; o slug da banca nao")
  void reservado() {
    for (String slug : List.of("www", "ceres", "assistente", "agroconia-suporte",
        "suporte-gwnext", "minhareceita", "sefazpr")) {
      assertThat(reservados.reservado(slug)).as(slug).isTrue();
    }
    for (String slug : List.of("ruraloeste", "ceresagro", "wwwx", "contabilidade")) {
      assertThat(reservados.reservado(slug)).as(slug).isFalse();
    }
    assertThat(reservados.reservado(null)).isFalse();
  }

  @Test
  @DisplayName("linha fora da forma no arquivo derruba a leitura (nunca libera em silencio)")
  void arquivoInvalido() {
    assertThatThrownBy(() -> new SlugsReservados("escritorio/slugs-reservados-invalido.txt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Api");
    assertThatThrownBy(() -> new SlugsReservados("escritorio/nao-existe.txt"))
        .isInstanceOf(RuntimeException.class);
  }
}
