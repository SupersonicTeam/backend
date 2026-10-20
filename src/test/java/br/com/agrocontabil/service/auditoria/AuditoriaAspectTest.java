package br.com.agrocontabil.service.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ResultadoAuditavel;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.RegistroAuditoria;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("AuditoriaAspect — registro de sucesso e falha sem mudar a operacao (#149)")
class AuditoriaAspectTest {

  private final AuditoriaService auditoria = mock(AuditoriaService.class);
  private final EstadoRecursoLeitor leitor = mock(EstadoRecursoLeitor.class);
  private final UsuarioAutenticadoProvider usuarios = mock(UsuarioAutenticadoProvider.class);
  private final ProceedingJoinPoint chamada = mock(ProceedingJoinPoint.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"),
      ZoneId.of("America/Sao_Paulo"));
  private final AuditoriaAspect aspecto = new AuditoriaAspect(auditoria, leitor, usuarios, clock);

  private final UUID produtorId = UUID.randomUUID();

  record Resultado(Object status, UUID recursoAuditado) implements ResultadoAuditavel {
  }

  @Auditado(acao = AcaoAuditada.NOTA_CRIADA, recurso = RecursoAuditado.NOTA_FISCAL)
  void criar() {
  }

  @Auditado(acao = AcaoAuditada.NOTA_EXCLUIDA, recurso = RecursoAuditado.NOTA_FISCAL)
  void excluir() {
  }

  private Auditado anotacao(String metodo) throws NoSuchMethodException {
    Method m = AuditoriaAspectTest.class.getDeclaredMethod(metodo);
    return m.getAnnotation(Auditado.class);
  }

  @BeforeEach
  void setUp() {
    Produtor produtor = Produtor.builder().id(produtorId).nomeCompleto("Joao").build();
    when(usuarios.tentar()).thenReturn(Optional.of(UsuarioAutenticado.deProdutor(produtor)));
  }

  private RegistroAuditoria registroGravado() {
    ArgumentCaptor<RegistroAuditoria> captor = ArgumentCaptor.forClass(RegistroAuditoria.class);
    verify(auditoria).registrar(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("sucesso: recurso do retorno, estado depois e produtor autenticado")
  void sucessoComRecursoDoRetorno() throws Throwable {
    UUID notaId = UUID.randomUUID();
    Resultado resultado = new Resultado("RASCUNHO", notaId);
    when(chamada.getArgs()).thenReturn(new Object[] {"request"});
    when(chamada.proceed()).thenReturn(resultado);

    assertThat(aspecto.auditar(chamada, anotacao("criar"))).isSameAs(resultado);

    RegistroAuditoria registro = registroGravado();
    assertThat(registro.getRecursoId()).isEqualTo(notaId);
    assertThat(registro.getEstadoDepois()).isEqualTo("RASCUNHO");
    assertThat(registro.getProdutorId()).isEqualTo(produtorId);
    assertThat(registro.getUsuarioId()).isEqualTo(produtorId);
    assertThat(registro.isSucesso()).isTrue();
    assertThat(registro.getOcorridoEm().toInstant()).isEqualTo(clock.instant());
  }

  @Test
  @DisplayName("falha: excecao intacta, codigo do ErrorCode e estado anterior descartado")
  void falhaRelancaComCodigo() throws Throwable {
    UUID notaId = UUID.randomUUID();
    ResourceNotFoundException erro = new ResourceNotFoundException("NotaFiscal", notaId);
    when(chamada.getArgs()).thenReturn(new Object[] {notaId});
    when(leitor.estado(RecursoAuditado.NOTA_FISCAL, notaId)).thenReturn(Optional.of("AUTORIZADA"));
    when(chamada.proceed()).thenThrow(erro);

    assertThatThrownBy(() -> aspecto.auditar(chamada, anotacao("excluir"))).isSameAs(erro);

    RegistroAuditoria registro = registroGravado();
    assertThat(registro.isSucesso()).isFalse();
    assertThat(registro.getCodigoErro()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(registro.getRecursoId()).isEqualTo(notaId);
    assertThat(registro.getEstadoAntes()).isNull();
  }

  @Test
  @DisplayName("excecao fora do dominio vira o nome da classe, nunca a mensagem")
  void excecaoGenericaSoONome() throws Throwable {
    when(chamada.getArgs()).thenReturn(new Object[] {});
    when(chamada.proceed()).thenThrow(new IllegalStateException("cpf 52998224725 no texto"));

    assertThatThrownBy(() -> aspecto.auditar(chamada, anotacao("criar")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(registroGravado().getCodigoErro()).isEqualTo("IllegalStateException");
  }

  @Test
  @DisplayName("falha ao ler o estado anterior nao impede a operacao nem o registro")
  void leituraDoEstadoFalhando() throws Throwable {
    UUID notaId = UUID.randomUUID();
    when(chamada.getArgs()).thenReturn(new Object[] {notaId});
    when(leitor.estado(any(), any())).thenThrow(new IllegalStateException("banco"));
    when(chamada.proceed()).thenReturn(null);

    aspecto.auditar(chamada, anotacao("excluir"));

    RegistroAuditoria registro = registroGravado();
    assertThat(registro.isSucesso()).isTrue();
    assertThat(registro.getEstadoAntes()).isNull();
  }

  @Test
  @DisplayName("lista no retorno (importacao em lote) vira a contagem de itens")
  void listaViraContagem() throws Throwable {
    when(chamada.getArgs()).thenReturn(new Object[] {List.of()});
    when(chamada.proceed()).thenReturn(List.of("a", "b", "c"));

    aspecto.auditar(chamada, anotacao("criar"));

    assertThat(registroGravado().getEstadoDepois()).isEqualTo("itens=3");
  }
}
