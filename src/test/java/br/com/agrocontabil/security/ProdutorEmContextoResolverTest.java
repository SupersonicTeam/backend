package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.NaoAutenticadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes do produtor-em-contexto por papel (issue #93).
 *
 * <p>Usa o {@code SecurityContextHolder} real (via {@link AutenticacaoTestSupport}) com um
 * {@link UsuarioAutenticadoProvider} de verdade: o que se quer provar é a decisão de acesso para
 * cada papel, e mockar o provider testaria o mock.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProdutorEmContextoResolver — acesso por papel (#93)")
class ProdutorEmContextoResolverTest {

  @Mock private ProdutorRepository produtorRepository;
  @Mock private VinculoCarteiraRepository vinculoRepository;

  private ProdutorEmContextoResolver resolver;
  private Produtor produtor;
  private Contador contador;

  @BeforeEach
  void setUp() {
    AutenticacaoTestSupport.limpar();
    resolver =
        new ProdutorEmContextoResolver(
            new UsuarioAutenticadoProvider(), produtorRepository, vinculoRepository);
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Fazenda Boa Vista").build();
    contador = Contador.builder().id(UUID.randomUUID()).nomeCompleto("Escritorio").build();
  }

  @AfterEach
  void tearDown() {
    AutenticacaoTestSupport.limpar();
  }

  // -------------------------------------------------------------------------
  // Papel PRODUTOR
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("produtor acessa a si mesmo sem consultar vinculo")
  void produtorAcessaASiMesmo() {
    AutenticacaoTestSupport.autenticarComo(produtor);

    assertThatCode(() -> resolver.exigirAcesso(produtor.getId())).doesNotThrowAnyException();

    verifyNoInteractions(vinculoRepository);
  }

  @Test
  @DisplayName("produtor pedindo outro produtor recebe 404, nunca 403")
  void produtorNaoAcessaOutro() {
    AutenticacaoTestSupport.autenticarComo(produtor);
    UUID outro = UUID.randomUUID();

    // 404 de proposito: 403 confirmaria que aquele produtor existe.
    assertThatThrownBy(() -> resolver.exigirAcesso(outro))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Papel CONTADOR
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("contador acessa produtor da carteira")
  void contadorAcessaProdutorVinculado() {
    AutenticacaoTestSupport.autenticarComo(contador);
    when(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), produtor.getId(), SituacaoVinculo.ATIVO))
        .thenReturn(true);

    assertThatCode(() -> resolver.exigirAcesso(produtor.getId())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("contador sem vinculo com o produtor recebe 404")
  void contadorNaoAcessaProdutorSemVinculo() {
    AutenticacaoTestSupport.autenticarComo(contador);
    when(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), produtor.getId(), SituacaoVinculo.ATIVO))
        .thenReturn(false);

    assertThatThrownBy(() -> resolver.exigirAcesso(produtor.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // resolver(): entidade gerenciada
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("resolver() devolve a instancia do repositorio, nao a do principal (detached)")
  void resolverDevolveInstanciaGerenciada() {
    AutenticacaoTestSupport.autenticarComo(produtor);
    Produtor gerenciado =
        Produtor.builder().id(produtor.getId()).nomeCompleto("Fazenda Boa Vista").build();
    when(produtorRepository.findById(produtor.getId())).thenReturn(Optional.of(gerenciado));

    assertThat(resolver.resolver(produtor.getId())).isSameAs(gerenciado);
  }

  @Test
  @DisplayName("resolver() lanca 404 quando o produtor autorizado nao existe mais no banco")
  void resolverLancaNotFoundQuandoProdutorSumiu() {
    AutenticacaoTestSupport.autenticarComo(produtor);
    when(produtorRepository.findById(produtor.getId())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolver(produtor.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("resolver() nao consulta o produtor quando o acesso ja foi negado")
  void resolverNaoConsultaProdutorQuandoNegado() {
    AutenticacaoTestSupport.autenticarComo(contador);
    when(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), produtor.getId(), SituacaoVinculo.ATIVO))
        .thenReturn(false);

    assertThatThrownBy(() -> resolver.resolver(produtor.getId()))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(produtorRepository, never()).findById(produtor.getId());
  }

  // -------------------------------------------------------------------------
  // Sem identidade
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("sem identidade no contexto responde 401, antes de qualquer consulta")
  void semIdentidadeResponde401() {
    assertThatThrownBy(() -> resolver.exigirAcesso(produtor.getId()))
        .isInstanceOf(NaoAutenticadoException.class);

    verifyNoInteractions(produtorRepository, vinculoRepository);
  }
}
