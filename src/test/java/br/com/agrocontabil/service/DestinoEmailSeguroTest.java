package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DestinoEmailSeguroTest {

  private static final List<String> RESERVADOS = List.of("example.com", "example.net",
      "example.org", ".example", ".test", ".invalid", ".local", ".localhost");

  @ParameterizedTest(name = "reservado: [{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {
      "produtor@example.com",
      "Contador@EXAMPLE.COM",
      "x@sub.example.org",
      "x@example.net.",
      "x@agroconia.test",
      "x@test",
      "x@algo.invalid",
      "x@agroconia.local",
      "x@maquina.localhost",
      "x@teste.example",
      "sem-arroba",
      "x@"})
  void reservado(String email) {
    assertThat(DestinoEmailSeguro.reservado(email, RESERVADOS)).isTrue();
  }

  @ParameterizedTest(name = "enviado: {0}")
  @ValueSource(strings = {
      "produtor@exemplo.com.br",
      "x@example.com.br",
      "x@myexample.com",
      "x@notexample.org",
      "x@testes.com.br",
      "x@local.com.br",
      "contato@gwnext.com.br"})
  void enviado(String email) {
    assertThat(DestinoEmailSeguro.reservado(email, RESERVADOS)).isFalse();
  }
}
