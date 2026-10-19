package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LimiteTaxaAutenticacaoFilter — chave do cliente (#208)")
class LimiteTaxaAutenticacaoFilterTest {

  @Test
  @DisplayName("IPv4 inteiro; IPv6 agrupado pelo /64 (o atacante nao troca de endereco a vontade)")
  void chaveCliente() {
    assertThat(LimiteTaxaAutenticacaoFilter.chaveCliente("203.0.113.7")).isEqualTo("203.0.113.7");
    String a = LimiteTaxaAutenticacaoFilter.chaveCliente("2001:db8:1234:5678::1");
    String b = LimiteTaxaAutenticacaoFilter.chaveCliente("2001:db8:1234:5678:ffff:ffff:ffff:ffff");
    String outraRede = LimiteTaxaAutenticacaoFilter.chaveCliente("2001:db8:1234:5679::1");
    assertThat(a).isEqualTo(b).isEqualTo("20010db812345678::/64");
    assertThat(outraRede).isNotEqualTo(a);
    assertThat(LimiteTaxaAutenticacaoFilter.chaveCliente(null)).isNull();
  }
}
