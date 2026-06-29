package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.CfopIncompativelException;
import br.com.agrocontabil.model.AmbitoOperacao;
import br.com.agrocontabil.model.CfopProdutorRural;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoOperacao;
import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CfopOperacaoValidator — compatibilidade CFOP x operacao")
class CfopOperacaoValidatorTest {

  @Mock
  private CfopProdutorRuralRepository repository;

  private CfopOperacaoValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CfopOperacaoValidator(repository);
  }

  private CfopProdutorRural cfop(String codigo, TipoOperacao tipo, AmbitoOperacao ambito) {
    return CfopProdutorRural.builder()
        .codigo(codigo).descricao("desc").tipoOperacao(tipo).ambito(ambito).build();
  }

  private NotaFiscal nota(String ufEmit, String ufDest, String cfopItem) {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).estado(ufEmit).build();
    EnderecoDestinatario end = ufDest == null ? null
        : EnderecoDestinatario.builder().uf(ufDest).build();
    NotaFiscalItem item = NotaFiscalItem.builder()
        .numeroItem(1).cfop(cfopItem).build();
    NotaFiscal n = NotaFiscal.builder()
        .id(UUID.randomUUID()).produtor(produtor).destinatarioEndereco(end).build();
    n.addItem(item);
    return n;
  }

  @Test
  @DisplayName("operacao interna com CFOP 5xxx (saida interna) e valida")
  void saidaInternaOk() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", TipoOperacao.SAIDA, AmbitoOperacao.INTERNA)));
    assertThatCode(() -> validator.validar(nota("PR", "PR", "5101"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP interno (5xxx) em venda interestadual lanca CfopIncompativelException")
  void internoEmInterestadual() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", TipoOperacao.SAIDA, AmbitoOperacao.INTERNA)));
    assertThatThrownBy(() -> validator.validar(nota("PR", "SP", "5101")))
        .isInstanceOf(CfopIncompativelException.class);
  }

  @Test
  @DisplayName("CFOP de entrada (1xxx) numa saida lanca CfopIncompativelException")
  void entradaEmSaida() {
    when(repository.findByCodigo("1101"))
        .thenReturn(Optional.of(cfop("1101", TipoOperacao.ENTRADA, AmbitoOperacao.INTERNA)));
    assertThatThrownBy(() -> validator.validar(nota("PR", "PR", "1101")))
        .isInstanceOf(CfopIncompativelException.class);
  }

  @Test
  @DisplayName("UF do destinatario ausente: pula checagem de ambito, mas exige saida")
  void ufDestAusentePulaAmbito() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", TipoOperacao.SAIDA, AmbitoOperacao.INTERNA)));
    assertThatCode(() -> validator.validar(nota("PR", null, "5101"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP nao encontrado na referencia e ignorado (existencia ja validada por @ValidCfop)")
  void cfopNaoEncontradoIgnorado() {
    when(repository.findByCodigo("9999")).thenReturn(Optional.empty());
    assertThatCode(() -> validator.validar(nota("PR", "PR", "9999"))).doesNotThrowAnyException();
  }
}
