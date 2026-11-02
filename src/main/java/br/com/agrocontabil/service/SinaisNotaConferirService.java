package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ItemParaSinal;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import br.com.agrocontabil.service.SinalNota.Momento;
import br.com.agrocontabil.service.SinalNota.Motivo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
 * <p>Só olha as notas do próprio produtor (anti-IDOR: os itens são lidos pelo produtor; de outro
 * = nenhum) e só as que ele emitiu ({@link OrigemNotaFiscal#PROPRIA}): a nota de compra foi o
 * fornecedor quem emitiu. Nota em trânsito, pendente na SEFAZ ou cancelada não recebe sinal: não
 * há o que o produtor faça com ela agora.
 *
 * <p><strong>{@link CodigoSinalNota#PRODUTO_PARECE_SERVICO}:</strong> descrição do item com um
 * termo de serviço ({@code sinais-nota/termos-servico.txt}, palavra ou expressão inteira, sem
 * acento nem caixa, depois de tirar as expressões de {@code sinais-nota/excecoes-servico.txt},
 * como "para plantio") <em>e</em> CFOP genérico ({@code 5949}/{@code 6949}, "outra saída não
 * especificada") ou NCM ausente ou fora da referência. Este último só alcança nota antiga, de antes
 * de o código sair da referência (#282): a API e a emissão já barram NCM desconhecido. As listas
 * passam pela revisão do avaliador externo (#275) e do contador (#284, perguntas 13 e 14).
 */
@Service
public class SinaisNotaConferirService {

  /** CFOPs de "outra saída não especificada", que aceitam qualquer coisa. */
  static final Set<String> CFOPS_GENERICOS = Set.of("5949", "6949");

  /** Situações sem sinal: em trânsito, aguardando a SEFAZ ou já cancelada. */
  static final Set<StatusNotaFiscal> SEM_SINAL = EnumSet.of(StatusNotaFiscal.ENVIANDO,
      StatusNotaFiscal.PENDENTE_SEFAZ, StatusNotaFiscal.CANCELANDO, StatusNotaFiscal.CANCELADA);

  private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

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
    List<String> normalizados = termos.stream().map(SinaisNotaConferirService::texto)
        .filter(t -> !t.isEmpty()).distinct().toList();
    if (normalizados.isEmpty()) {
      // Lista vazia viraria \b()\b, que casa qualquer descricao: todo item 5949 seria "servico".
      throw new IllegalStateException("Lista de termos de servico vazia (sinais-nota)");
    }
    this.termosServico = Pattern.compile("\\b(" + normalizados.stream().map(Pattern::quote)
        .collect(Collectors.joining("|")) + ")\\b");
    this.excecoes = excecoes.stream().map(SinaisNotaConferirService::texto)
        .filter(t -> !t.isEmpty()).toList();
  }

  /**
   * Sinais da nota, do primeiro item ao último.
   *
   * @param produtorId produtor da sessão
   * @param nota       nota já lida pelo chamador (origem, situação e emissão)
   * @return sinais; vazia sem sinal, para nota de compra, sem sinal pela situação ou de outro
   *     produtor
   */
  @Transactional(readOnly = true)
  public List<SinalNota> sinais(UUID produtorId, NotaParaSinal nota) {
    if (nota.origem() != OrigemNotaFiscal.PROPRIA || SEM_SINAL.contains(nota.status())) {
      return List.of();
    }
    Momento momento = momento(nota);
    OffsetDateTime prazo = momento == Momento.AUTORIZADA_NO_PRAZO ? limite(nota) : null;
    List<SinalNota> sinais = new ArrayList<>();
    for (ItemParaSinal item : notaFiscalRepository.findItensParaSinais(nota.id(), produtorId)) {
      motivoServico(item).ifPresent(motivo -> sinais.add(new SinalNota(
          CodigoSinalNota.PRODUTO_PARECE_SERVICO, motivo, momento, item.numeroItem(),
          item.descricao(), item.cfop(), prazo)));
    }
    return List.copyOf(sinais);
  }

  /**
   * Por que o item parece serviço; vazio se não parece. O termo é conferido primeiro (sem
   * consulta) e a referência de NCM só quando o CFOP não é genérico.
   */
  Optional<Motivo> motivoServico(ItemParaSinal item) {
    if (!temTermoDeServico(item.descricao())) {
      return Optional.empty();
    }
    if (item.cfop() != null && CFOPS_GENERICOS.contains(item.cfop().strip())) {
      return Optional.of(Motivo.CFOP_GENERICO);
    }
    boolean foraDaReferencia = item.ncm() == null || item.ncm().isBlank()
        || !ncmReferenciaRepository.existsByCodigo(item.ncm().strip());
    return foraDaReferencia ? Optional.of(Motivo.NCM_FORA_DA_REFERENCIA) : Optional.empty();
  }

  /** Termo de serviço na descrição, depois de tirar as exceções ("para plantio"). */
  boolean temTermoDeServico(String descricao) {
    String normalizada = " " + texto(descricao) + " ";
    for (String excecao : excecoes) {
      normalizada = normalizada.replace(" " + excecao + " ", " ");
    }
    return termosServico.matcher(normalizada).find();
  }

  private Momento momento(NotaParaSinal nota) {
    if (nota.status() != StatusNotaFiscal.AUTORIZADA) {
      return Momento.ANTES_DE_EMITIR;
    }
    OffsetDateTime limite = limite(nota);
    return limite != null && !OffsetDateTime.now(clock).isAfter(limite)
        ? Momento.AUTORIZADA_NO_PRAZO : Momento.AUTORIZADA_FORA_DO_PRAZO;
  }

  /** O mesmo limite do {@code SefazService.cancelar}: a emissão mais 30 dias, com a hora. */
  private static OffsetDateTime limite(NotaParaSinal nota) {
    return nota.dataEmissao() == null ? null
        : nota.dataEmissao().plusDays(SefazService.PRAZO_CANCELAMENTO_DIAS);
  }

  /** Normaliza descrição e listas do mesmo jeito: sem acento, minúsculas, pontuação = espaço. */
  private static String texto(String valor) {
    return NAO_ALFANUMERICO.matcher(NormalizacaoBusca.normalizar(valor)).replaceAll(" ").strip();
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

  /**
   * O que os sinais precisam da nota, já lido por quem chama (a consulta da Ceres tem tudo isso
   * na projeção da #256).
   *
   * @param id          nota
   * @param origem      própria ou importada
   * @param status      situação
   * @param dataEmissao emissão
   */
  public record NotaParaSinal(UUID id, OrigemNotaFiscal origem, StatusNotaFiscal status,
      OffsetDateTime dataEmissao) {}
}
