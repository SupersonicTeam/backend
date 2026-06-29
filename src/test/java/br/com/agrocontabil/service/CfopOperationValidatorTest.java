package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.IncompatibleCfopException;
import br.com.agrocontabil.model.OperationScope;
import br.com.agrocontabil.model.RuralProducerCfop;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.OperationType;
import br.com.agrocontabil.repository.RuralProducerCfopRepository;
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
class CfopOperationValidatorTest {

  @Mock
  private RuralProducerCfopRepository repository;

  private CfopOperationValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CfopOperationValidator(repository);
  }

  private RuralProducerCfop cfop(String codigo, OperationType tipo, OperationScope scope) {
    return RuralProducerCfop.builder()
        .codigo(codigo).description("desc").operationType(tipo).scope(scope).build();
  }

  private Invoice invoice(String issuerUf, String recipientUf, String cfopItem) {
    Producer producer = Producer.builder().id(UUID.randomUUID()).state(issuerUf).build();
    RecipientAddress end = recipientUf == null ? null
        : RecipientAddress.builder().uf(recipientUf).build();
    InvoiceItem item = InvoiceItem.builder()
        .itemNumber(1).cfop(cfopItem).build();
    Invoice n = Invoice.builder()
        .id(UUID.randomUUID()).producer(producer).recipientAddress(end).build();
    n.addItem(item);
    return n;
  }

  @Test
  @DisplayName("operacao interna com CFOP 5xxx (saida interna) e valida")
  void saidaInternaOk() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", OperationType.SAIDA, OperationScope.INTERNA)));
    assertThatCode(() -> validator.validate(invoice("PR", "PR", "5101"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP interno (5xxx) em venda interestadual lanca CfopIncompativelException")
  void internoEmInterestadual() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", OperationType.SAIDA, OperationScope.INTERNA)));
    assertThatThrownBy(() -> validator.validate(invoice("PR", "SP", "5101")))
        .isInstanceOf(IncompatibleCfopException.class);
  }

  @Test
  @DisplayName("CFOP de entrada (1xxx) numa saida lanca CfopIncompativelException")
  void entradaEmSaida() {
    when(repository.findByCodigo("1101"))
        .thenReturn(Optional.of(cfop("1101", OperationType.ENTRADA, OperationScope.INTERNA)));
    assertThatThrownBy(() -> validator.validate(invoice("PR", "PR", "1101")))
        .isInstanceOf(IncompatibleCfopException.class);
  }

  @Test
  @DisplayName("UF do destinatario ausente: pula checagem de ambito, mas exige saida")
  void ufDestAusentePulaAmbito() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", OperationType.SAIDA, OperationScope.INTERNA)));
    assertThatCode(() -> validator.validate(invoice("PR", null, "5101"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP nao encontrado na referencia e ignorado (existencia ja validada por @ValidCfop)")
  void cfopNaoEncontradoIgnorado() {
    when(repository.findByCodigo("9999")).thenReturn(Optional.empty());
    assertThatCode(() -> validator.validate(invoice("PR", "PR", "9999"))).doesNotThrowAnyException();
  }
}
