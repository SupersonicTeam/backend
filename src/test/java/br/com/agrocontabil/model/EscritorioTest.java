package br.com.agrocontabil.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.EmpresarialExigeCnpjException;
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
}
