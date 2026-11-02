package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sinais de nota possivelmente emitida errada (issue #276): o que a validação fiscal não pega
 * porque a nota é válida no formato, mas talvez errada no propósito ou no conteúdo. Regra de
 * domínio, determinística, fora do módulo da Ceres — que a lê pela porta e pode ser reaproveitada
 * na emissão ou na revisão, se a equipe decidir.
 *
 * <p>Só olha as notas do próprio produtor (anti-IDOR: nota de outro produtor = nenhum sinal, como
 * nota inexistente) e só as notas que ele emitiu ({@link OrigemNotaFiscal#PROPRIA}): a nota de
 * compra foi o fornecedor quem emitiu.
 *
 * <p><strong>{@link CodigoSinalNota#PRODUTO_PARECE_SERVICO}:</strong> item com CFOP genérico
 * ({@code 5949}/{@code 6949}, "outra saída não especificada") <em>ou</em> NCM ausente ou fora da
 * referência, <em>e</em> descrição com um termo de serviço ({@code sinais-nota/termos-servico.txt},
 * palavra ou expressão inteira, sem acento nem caixa). As expressões de
 * {@code sinais-nota/excecoes-servico.txt} ("para plantio") saem da descrição antes: "semente de
 * soja para plantio" é produto. As listas passam pela revisão do avaliador externo (#275) e do
 * contador (#284, perguntas 13 e 14).
 */
@Service
public class SinaisNotaConferirService {

  /** CFOPs de "outra saída não especificada", que aceitam qualquer coisa. */
  static final Set<String> CFOPS_GENERICOS = Set.of("5949", "6949");

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

  private final NotaFiscalRepository notaFiscalRepository;
  private final NcmReferenciaRepository ncmReferenciaRepository;
  private final Clock clock;
  private final Pattern termosServico;
  private final List<String> excecoes;

  /** Constrói o serviço com as listas versionadas em {@code sinais-nota/}. */
  @Autowired
  public SinaisNotaConferirService(NotaFiscalRepository notaFiscalRepository,
      NcmReferenciaRepository ncmReferenciaRepository, Clock clock) {
    this(notaFiscalRepository, ncmReferenciaRepository, clock,
        lista("sinais-nota/termos-servico.txt"), lista("sinais-nota/excecoes-servico.txt"));
  }

  SinaisNotaConferirService(NotaFiscalRepository notaFiscalRepository,
      NcmReferenciaRepository ncmReferenciaRepository, Clock clock, List<String> termos,
      List<String> excecoes) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.ncmReferenciaRepository = ncmReferenciaRepository;
    this.clock = clock;
    this.termosServico = Pattern.compile("\\b(" + termos.stream()
        .map(t -> Pattern.quote(NormalizacaoBusca.normalizar(t)))
        .collect(Collectors.joining("|")) + ")\\b");
    this.excecoes = excecoes.stream().map(NormalizacaoBusca::normalizar).toList();
  }

  /**
   * Sinais da nota, do primeiro item ao último.
   *
   * @param produtorId produtor da sessão
   * @param notaId     nota
   * @return sinais; vazia sem sinal, para nota de compra ou de outro produtor
   */
  @Transactional(readOnly = true)
  public List<SinalNota> sinais(UUID produtorId, UUID notaId) {
    return notaFiscalRepository.findByIdAndProdutorIdComGrafoDeEmissao(notaId, produtorId)
        .filter(n -> n.getOrigem() == OrigemNotaFiscal.PROPRIA)
        .map(this::sinais)
        .orElse(List.of());
  }

  private List<SinalNota> sinais(NotaFiscal nota) {
    LocalDate prazo = prazoCancelamento(nota);
    List<SinalNota> sinais = new ArrayList<>();
    nota.getItens().stream()
        .sorted(Comparator.comparing(NotaFiscalItem::getNumeroItem,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .filter(this::pareceServico)
        .forEach(item -> sinais.add(new SinalNota(CodigoSinalNota.PRODUTO_PARECE_SERVICO,
            item.getNumeroItem(), item.getDescricao(), item.getCfop(), item.getNcm(), prazo)));
    return List.copyOf(sinais);
  }

  /** Item com CFOP genérico ou NCM fora da referência, e descrição com termo de serviço. */
  boolean pareceServico(NotaFiscalItem item) {
    boolean generico = item.getCfop() != null && CFOPS_GENERICOS.contains(item.getCfop().strip());
    boolean semNcmConhecido = item.getNcm() == null || item.getNcm().isBlank()
        || !ncmReferenciaRepository.existsByCodigo(item.getNcm().strip());
    if (!generico && !semNcmConhecido) {
      return false;
    }
    return temTermoDeServico(item.getDescricao());
  }

  /** Termo de serviço na descrição, depois de tirar as exceções ("para plantio"). */
  boolean temTermoDeServico(String descricao) {
    String texto = " " + NormalizacaoBusca.normalizar(descricao)
        .replaceAll("[^a-z0-9]+", " ") + " ";
    for (String excecao : excecoes) {
      texto = texto.replace(" " + excecao + " ", " ");
    }
    return termosServico.matcher(texto).find();
  }

  /** Último dia para cancelar (RN006), só com a nota autorizada e ainda no prazo. */
  private LocalDate prazoCancelamento(NotaFiscal nota) {
    if (nota.getStatus() != StatusNotaFiscal.AUTORIZADA || nota.getDataEmissao() == null) {
      return null;
    }
    OffsetDateTime limite = nota.getDataEmissao().plusDays(SefazService.PRAZO_CANCELAMENTO_DIAS);
    return OffsetDateTime.now(clock).isAfter(limite)
        ? null : limite.atZoneSameInstant(FUSO).toLocalDate();
  }

  private static List<String> lista(String recurso) {
    try (InputStream in = new ClassPathResource(recurso).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
          .map(String::strip)
          .filter(l -> !l.isEmpty() && !l.startsWith("#"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Lista de sinais ausente: " + recurso, e);
    }
  }
}
