package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistência do {@link Contador} e do vínculo da carteira (issues #93 e #177).
 *
 * <p>O que este teste protege: o vínculo {@code ATIVO} é a <strong>única</strong> autorização que
 * o contador tem sobre os dados de um produtor, e a pergunta é feita por
 * {@link VinculoCarteiraRepository#existsByContadorIdAndProdutorIdAndSituacao}, método derivado do
 * nome — uma renomeação de campo o quebra silenciosamente, e só uma consulta real detecta.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
@DisplayName("Contador + vinculo da carteira — persistencia (#93, #177)")
class ContadorVinculoIntegrationTest {

  @Autowired ContadorRepository contadorRepository;
  @Autowired ProdutorRepository produtorRepository;
  @Autowired VinculoCarteiraRepository vinculoRepository;
  @Autowired NotaFiscalRepository notaRepository;
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
    contador = contadorRepository.saveAndFlush(Contador.builder()
        .nomeCompleto("Escritorio Teste")
        .cpfCnpj("11.222.333/0001-95")
        .crc("MT-999999/O")
        .build());
    vincular(contador, atendido, SituacaoVinculo.ATIVO);
  }

  private VinculoCarteira vincular(Contador c, Produtor p, SituacaoVinculo situacao) {
    return vinculoRepository.saveAndFlush(VinculoCarteira.builder().contadorId(c.getId())
        .produtorId(p.getId()).situacao(situacao).inicio(OffsetDateTime.now()).build());
  }

  @Test
  @DisplayName("vinculo ATIVO autoriza; sem vinculo, nao")
  void ativoAutoriza() {
    assertThat(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), atendido.getId(), SituacaoVinculo.ATIVO)).isTrue();
    assertThat(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), naoAtendido.getId(), SituacaoVinculo.ATIVO)).isFalse();
    assertThat(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        UUID.randomUUID(), atendido.getId(), SituacaoVinculo.ATIVO)).isFalse();
  }

  @Test
  @DisplayName("vinculo suspenso nao autoriza, mas e o vinculo aberto do par")
  void suspensoNaoAutoriza() {
    vincular(contador, naoAtendido, SituacaoVinculo.SUSPENSO);

    assertThat(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), naoAtendido.getId(), SituacaoVinculo.ATIVO)).isFalse();
    assertThat(vinculoRepository.findAbertoForUpdate(contador.getId(), naoAtendido.getId()))
        .get().extracting(VinculoCarteira::getSituacao).isEqualTo(SituacaoVinculo.SUSPENSO);
  }

  @Test
  @DisplayName("encerrado fica como historico e nao e vinculo aberto")
  void encerradoEhHistorico() {
    vincular(contador, naoAtendido, SituacaoVinculo.ENCERRADO);

    assertThat(vinculoRepository.findAbertoForUpdate(contador.getId(), naoAtendido.getId()))
        .isEmpty();
    assertThat(vinculoRepository.findIdsDoContador(contador.getId())).hasSize(2);
  }

  @Test
  @DisplayName("um produtor pode ser atendido por mais de um escritorio")
  void produtorAtendidoPorVariosContadores() {
    Contador segundo = contadorRepository.saveAndFlush(
        Contador.builder().nomeCompleto("Segundo Escritorio").cpfCnpj("44.555.666/0001-77")
            .build());
    vincular(segundo, atendido, SituacaoVinculo.ATIVO);

    assertThat(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        contador.getId(), atendido.getId(), SituacaoVinculo.ATIVO)).isTrue();
    assertThat(vinculoRepository.existsByContadorIdAndProdutorIdAndSituacao(
        segundo.getId(), atendido.getId(), SituacaoVinculo.ATIVO)).isTrue();
  }

  @Test
  @DisplayName("contagem de atencao agrega so a carteira ativa do escritorio")
  void contagemSoDaCarteiraAtiva() {
    notaRepository.saveAndFlush(nota(atendido));
    notaRepository.saveAndFlush(nota(atendido));
    notaRepository.saveAndFlush(nota(naoAtendido));

    assertThat(vinculoRepository.contarNotasDaCarteira(
        contador.getId(), StatusNotaFiscal.COM_INCONSISTENCIAS))
        .containsExactly(new ContagemPorProdutor(atendido.getId(), 2));
  }

  private static long numero = 1;

  private static NotaFiscal nota(Produtor produtor) {
    return NotaFiscal.builder()
        .numero(numero++).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .status(StatusNotaFiscal.COM_INCONSISTENCIAS)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua A").numero("1").municipio("Cuiaba")
            .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
  }
}
