package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercita a persistência do {@link Contador} e do vínculo N:N com o {@link Produtor} (issue #93).
 *
 * <p>O que este teste protege: o vínculo é a <strong>única</strong> autorização que o contador terá
 * sobre os dados de um produtor, e a pergunta de autorização é feita por
 * {@link ContadorRepository#existsByIdAndProdutoresId}. Esse método é derivado do nome — uma
 * renomeação de campo ou uma troca do lado dono do relacionamento o quebra silenciosamente no
 * código, e só uma consulta real contra o banco detecta.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
@DisplayName("Contador + vinculo contador_produtor — persistencia (#93)")
class ContadorVinculoIntegrationTest {

  @Autowired ContadorRepository contadorRepository;
  @Autowired ProdutorRepository produtorRepository;
  @Autowired TestDatabaseCleaner cleaner;

  private Produtor atendido;
  private Produtor naoAtendido;
  private Contador contador;

  @BeforeEach
  void semear() {
    cleaner.limparTudo();

    atendido = produtorRepository.save(
        Produtor.builder().nomeCompleto("Fazenda Atendida").cpfCnpj("98765432100")
            .estado("MT").build());
    naoAtendido = produtorRepository.save(
        Produtor.builder().nomeCompleto("Sitio Sem Contador").cpfCnpj("11122233344")
            .estado("PR").build());

    Contador novo =
        Contador.builder()
            .nomeCompleto("Escritorio Teste")
            .cpfCnpj("11.222.333/0001-95")
            .crc("MT-999999/O")
            .build();
    novo.vincular(atendido);
    contador = contadorRepository.saveAndFlush(novo);
  }

  @Test
  @DisplayName("persiste o contador com id, timestamps e carteira de produtores")
  void persisteContadorComCarteira() {
    assertThat(contador.getId()).isNotNull();
    assertThat(contador.getCreatedAt()).isNotNull();
    assertThat(contador.getProdutores()).extracting(Produtor::getId).containsExactly(
        atendido.getId());
  }

  @Test
  @DisplayName("existsByIdAndProdutoresId responde true para produtor vinculado")
  void vinculadoExiste() {
    assertThat(contadorRepository.existsByIdAndProdutoresId(contador.getId(), atendido.getId()))
        .isTrue();
  }

  @Test
  @DisplayName("existsByIdAndProdutoresId responde false para produtor sem vinculo")
  void naoVinculadoNaoExiste() {
    assertThat(contadorRepository.existsByIdAndProdutoresId(contador.getId(), naoAtendido.getId()))
        .isFalse();
  }

  @Test
  @DisplayName("existsByIdAndProdutoresId responde false para contador inexistente")
  void contadorInexistenteNaoExiste() {
    assertThat(contadorRepository.existsByIdAndProdutoresId(UUID.randomUUID(), atendido.getId()))
        .isFalse();
  }

  @Test
  @DisplayName("vincular e idempotente — o mesmo produtor nao duplica a carteira")
  void vincularEhIdempotente() {
    contador.vincular(atendido);
    contadorRepository.saveAndFlush(contador);

    assertThat(contadorRepository.findById(contador.getId()))
        .get()
        .satisfies(recarregado -> assertThat(recarregado.getProdutores()).hasSize(1));
  }

  @Test
  @DisplayName("um contador atende varios produtores (N:N)")
  void atendeVariosProdutores() {
    contador.vincular(naoAtendido);
    contadorRepository.saveAndFlush(contador);

    assertThat(contadorRepository.existsByIdAndProdutoresId(contador.getId(), atendido.getId()))
        .isTrue();
    assertThat(contadorRepository.existsByIdAndProdutoresId(contador.getId(), naoAtendido.getId()))
        .isTrue();
  }

  @Test
  @DisplayName("um produtor pode ser atendido por mais de um contador (N:N)")
  void produtorAtendidoPorVariosContadores() {
    Contador segundo =
        Contador.builder().nomeCompleto("Segundo Escritorio").cpfCnpj("44.555.666/0001-77").build();
    segundo.vincular(atendido);
    Contador salvo = contadorRepository.saveAndFlush(segundo);

    assertThat(contadorRepository.existsByIdAndProdutoresId(contador.getId(), atendido.getId()))
        .isTrue();
    assertThat(contadorRepository.existsByIdAndProdutoresId(salvo.getId(), atendido.getId()))
        .isTrue();
  }
}
