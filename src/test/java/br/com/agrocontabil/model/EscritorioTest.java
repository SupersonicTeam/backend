package br.com.agrocontabil.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.EmpresarialExigeCnpjException;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariante do {@link Escritorio} (issue #519): o empresarial tem CNPJ, conferido ao gravar só
 * quando o tipo ou o CNPJ muda, como a do {@link Produtor} (#298).
 */
@DisplayName("Escritorio: empresarial exige CNPJ (#519)")
class EscritorioTest {

  @Test
  @DisplayName("ao criar: empresarial sem CNPJ e recusado; pessoal sem CNPJ e empresarial com, nao")
  void aoCriar() {
    assertThatThrownBy(() -> Escritorio.builder().nome("X").tipo(TipoEscritorio.EMPRESARIAL)
        .build().onCreate()).isInstanceOf(EmpresarialExigeCnpjException.class);
    assertThatThrownBy(() -> Escritorio.builder().nome("X").tipo(TipoEscritorio.EMPRESARIAL)
        .cnpj(" ").build().onCreate()).isInstanceOf(EmpresarialExigeCnpjException.class);
    assertThatCode(() -> Escritorio.builder().nome("X").build().onCreate())
        .doesNotThrowAnyException();
    assertThatCode(() -> Escritorio.builder().nome("X").tipo(TipoEscritorio.EMPRESARIAL)
        .cnpj("11444777000161").build().onCreate()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("empresarial antigo sem CNPJ (changeSet 0062) continua valendo ate mexerem no CNPJ")
  void legadoSoQuandoMuda() {
    Escritorio legado = Escritorio.builder().nome("Equipe antiga")
        .tipo(TipoEscritorio.EMPRESARIAL).build();
    legado.onLoad();

    legado.setNome("Equipe antiga Ltda");
    assertThatCode(legado::onUpdate).doesNotThrowAnyException();

    legado.setCnpj("11444777000161");
    assertThatCode(legado::onUpdate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("segundo flush na mesma transacao compara com o ultimo estado gravado, nao com o lido")
  void segundoFlushComparaComOGravado() throws Exception {
    // O JPA chama a mesma renovacao depois de ler, de inserir e de atualizar.
    var renovacao = Escritorio.class.getDeclaredMethod("onLoad");
    assertThat(renovacao.isAnnotationPresent(PostLoad.class)
        && renovacao.isAnnotationPresent(PostPersist.class)
        && renovacao.isAnnotationPresent(PostUpdate.class)).isTrue();

    Escritorio legado = Escritorio.builder().nome("Equipe antiga")
        .tipo(TipoEscritorio.EMPRESARIAL).build();
    legado.onLoad();
    legado.setCnpj("11444777000161");
    legado.onUpdate();
    // @PostUpdate: o CNPJ gravado passa a ser a base.
    legado.onLoad();

    legado.setCnpj(null);
    assertThatThrownBy(legado::onUpdate).isInstanceOf(EmpresarialExigeCnpjException.class);
  }

  @Test
  @DisplayName("so o administrador do empresarial nao troca de equipe pelo convite (#519)")
  void administradorNaoTrocaDeEquipe() {
    assertThat(Escritorio.administradorNaoTrocaDeEquipe(TipoEscritorio.EMPRESARIAL,
        FuncaoEscritorio.ADMINISTRADOR)).isTrue();
    assertThat(Escritorio.administradorNaoTrocaDeEquipe(TipoEscritorio.PESSOAL,
        FuncaoEscritorio.ADMINISTRADOR)).isFalse();
    assertThat(Escritorio.administradorNaoTrocaDeEquipe(TipoEscritorio.EMPRESARIAL,
        FuncaoEscritorio.CONTADOR)).isFalse();
    assertThat(Escritorio.administradorNaoTrocaDeEquipe(TipoEscritorio.EMPRESARIAL,
        FuncaoEscritorio.ASSISTENTE)).isFalse();
    assertThat(Escritorio.administradorNaoTrocaDeEquipe(null, FuncaoEscritorio.ADMINISTRADOR))
        .isFalse();
  }

  @Test
  @DisplayName("ao atualizar: apagar o CNPJ do empresarial ou tornar empresarial sem CNPJ e recusado")
  void aoAtualizar() {
    Escritorio empresarial = Escritorio.builder().nome("X").tipo(TipoEscritorio.EMPRESARIAL)
        .cnpj("11444777000161").build();
    empresarial.onLoad();
    empresarial.setCnpj(null);
    assertThatThrownBy(empresarial::onUpdate).isInstanceOf(EmpresarialExigeCnpjException.class);

    Escritorio pessoal = Escritorio.builder().nome("X").build();
    pessoal.onLoad();
    pessoal.setTipo(TipoEscritorio.EMPRESARIAL);
    assertThatThrownBy(pessoal::onUpdate).isInstanceOf(EmpresarialExigeCnpjException.class);
  }

  @Test
  @DisplayName("verificacao do CNPJ (#520): trocar ou apagar o CNPJ a limpa; nome, telefone e tipo nao")
  void trocarCnpjLimpaAVerificacao() {
    OffsetDateTime quando = OffsetDateTime.parse("2026-11-11T10:00:00-03:00");
    Escritorio escritorio = Escritorio.builder().nome("Rural Oeste")
        .tipo(TipoEscritorio.EMPRESARIAL).cnpj("11444777000161").build();
    escritorio.onLoad();
    escritorio.verificarCnpj(MetodoVerificacaoCnpj.MANUAL, quando);
    assertThat(escritorio.cnpjVerificado()).isTrue();

    escritorio.setNome("Rural Oeste Ltda");
    escritorio.setTelefone("4533330000");
    escritorio.onUpdate();
    escritorio.onLoad();
    assertThat(escritorio.getCnpjVerificadoEm()).isEqualTo(quando);
    assertThat(escritorio.getCnpjVerificacaoMetodo()).isEqualTo(MetodoVerificacaoCnpj.MANUAL);

    escritorio.setCnpj("11222333000181");
    escritorio.onUpdate();
    escritorio.onLoad();
    assertThat(escritorio.cnpjVerificado()).isFalse();
    assertThat(escritorio.getCnpjVerificacaoMetodo()).isNull();

    Escritorio pessoal = Escritorio.builder().nome("Joao").cnpj("11444777000161").build();
    pessoal.onLoad();
    pessoal.verificarCnpj(MetodoVerificacaoCnpj.MANUAL, quando);
    pessoal.setCnpj(null);
    pessoal.onUpdate();
    assertThat(pessoal.cnpjVerificado()).isFalse();
  }

  @Test
  @DisplayName("verificacao do CNPJ (#520): so com CNPJ; revogar limpa as duas colunas")
  void verificarERevogar() {
    OffsetDateTime quando = OffsetDateTime.parse("2026-11-11T10:00:00-03:00");
    assertThatThrownBy(() -> Escritorio.builder().nome("Joao").build()
        .verificarCnpj(MetodoVerificacaoCnpj.MANUAL, quando))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> Escritorio.builder().nome("Joao").cnpj(" ").build()
        .verificarCnpj(MetodoVerificacaoCnpj.MANUAL, quando))
        .isInstanceOf(IllegalStateException.class);

    Escritorio escritorio = Escritorio.builder().nome("Rural Oeste").cnpj("11444777000161")
        .build();
    escritorio.verificarCnpj(MetodoVerificacaoCnpj.DEMONSTRACAO, quando);
    assertThat(escritorio.getCnpjVerificacaoMetodo())
        .isEqualTo(MetodoVerificacaoCnpj.DEMONSTRACAO);
    escritorio.revogarVerificacaoCnpj();
    assertThat(escritorio.getCnpjVerificadoEm()).isNull();
    assertThat(escritorio.getCnpjVerificacaoMetodo()).isNull();
  }
}
