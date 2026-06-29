package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.model.InfAdic;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotaFiscalNfeMapper — diferenciacao NFA no infCpl")
class NotaFiscalNfeMapperNfaTest {

  private NotaFiscalNfeMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new NotaFiscalNfeMapper(new ChaveAcessoGenerator());
  }

  @Test
  @DisplayName("infCpl contem marcador de NOTA FISCAL AVULSA quando tipoDocumento = NFA")
  void infCplContemMarcadorNfa() {
    NFe nfe = mapper.toNfe(buildNota(TipoDocumento.NFA));

    InfAdic infAdic = nfe.getInfNFe().getInfAdic();
    assertThat(infAdic.getInfCpl()).contains("NOTA FISCAL AVULSA");
  }

  @Test
  @DisplayName("infCpl NAO contem marcador de NFA quando tipoDocumento = NFE")
  void infCplSemMarcadorParaNfe() {
    NFe nfe = mapper.toNfe(buildNota(TipoDocumento.NFE));

    InfAdic infAdic = nfe.getInfNFe().getInfAdic();
    assertThat(infAdic.getInfCpl()).doesNotContain("NOTA FISCAL AVULSA");
  }

  private NotaFiscal buildNota(TipoDocumento tipo) {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("52998224725")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao")
        .ncm("12010090")
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .build();

    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores")
        .numero("100")
        .municipio("Curitiba")
        .codigoMunicipioIbge("4106902")
        .uf("PR")
        .cep("80010000")
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .tipoDocumento(tipo)
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();

    nota.addItem(item);
    return nota;
  }
}
