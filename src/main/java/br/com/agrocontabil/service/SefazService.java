package br.com.agrocontabil.service;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.exception.NotaFiscalNaoEnviavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de transmissão de notas fiscais para a SEFAZ (issue #15 — RF012).
 *
 * <p>Orquestra o envio do XML assinado ao webservice da SEFAZ (via {@link SefazClient},
 * que aplica retry em falha de comunicação) e persiste o resultado na nota: status
 * resultante, código/motivo retornados, protocolo e data de autorização.
 *
 * <p>Regras:
 * <ul>
 *   <li>Ownership anti-IDOR: busca via {@code findByIdAndProdutorId} — 404 se a nota não
 *       pertencer ao produtor autenticado.</li>
 *   <li>Pré-condição: somente notas em {@link StatusNotaFiscal#XML_GERADO} podem ser
 *       enviadas; caso contrário, {@link NotaFiscalNaoEnviavelException} (HTTP 409).</li>
 *   <li>Mapeamento do retorno: AUTORIZADA → {@code AUTORIZADA} (com protocolo e data);
 *       REJEITADA/DENEGADA → {@code REJEITADA}; PENDENTE → {@code PENDENTE_SEFAZ}.</li>
 *   <li>Rejeição do fisco não é erro: é resultado válido persistido com cStat e motivo.</li>
 * </ul>
 *
 * <p><strong>Sprint 3:</strong> o transporte ativo é o mock determinístico
 * ({@link br.com.agrocontabil.sefaz.MockSefazTransport}). O envio SOAP+mTLS real à SEFAZ
 * com certificado ICP-Brasil é débito da Sprint 7 — basta implementar
 * {@link br.com.agrocontabil.sefaz.SefazTransport} sem alterar este serviço.
 */
@Service
@Transactional
public class SefazService {

  private static final Logger log = LoggerFactory.getLogger(SefazService.class);

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final SefazClient sefazClient;
  private final SefazProperties sefazProperties;

  /** Constrói o serviço com as dependências de persistência, transporte e configuração. */
  public SefazService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      SefazClient sefazClient,
      SefazProperties sefazProperties) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.sefazClient = sefazClient;
    this.sefazProperties = sefazProperties;
  }

  /**
   * Envia a NF-e indicada à SEFAZ e persiste o retorno na nota.
   *
   * @param notaId identificador da nota fiscal
   * @return DTO com o status resultante e os dados do retorno do fisco
   * @throws ResourceNotFoundException       se a nota não existir ou pertencer a outro produtor
   * @throws NotaFiscalNaoEnviavelException  se o status não for XML_GERADO (HTTP 409)
   * @throws br.com.agrocontabil.exception.SefazComunicacaoException
   *         se a comunicação com a SEFAZ falhar após esgotar as tentativas (HTTP 502)
   */
  public SefazEnvioResponse enviar(UUID notaId) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(notaId, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    if (nota.getStatus() != StatusNotaFiscal.XML_GERADO) {
      throw new NotaFiscalNaoEnviavelException(notaId, nota.getStatus());
    }

    SefazAmbiente ambiente = sefazProperties.getAmbiente();
    SefazRetorno retorno =
        sefazClient.enviar(nota.getXmlGerado(), nota.getChaveAcesso(), ambiente);

    aplicarRetorno(nota, retorno, ambiente);
    NotaFiscal salva = notaFiscalRepository.save(nota);

    // Log de auditoria do envio — nunca inclui dados sensíveis (CPF/CNPJ) nem o XML.
    log.info(
        "NF-e enviada a SEFAZ: notaId={} chave={} ambiente={} cStat={} situacao={} protocolo={}",
        salva.getId(),
        salva.getChaveAcesso(),
        ambiente,
        retorno.cStat(),
        retorno.situacao(),
        retorno.protocolo());

    return toResponse(salva);
  }

  /**
   * Aplica o retorno do fisco à entidade: status, código/motivo, ambiente e, se autorizada,
   * protocolo e data de autorização.
   */
  private void aplicarRetorno(NotaFiscal nota, SefazRetorno retorno, SefazAmbiente ambiente) {
    nota.setStatus(mapStatus(retorno.situacao()));
    nota.setSefazCstat(retorno.cStat());
    nota.setSefazMotivo(retorno.xMotivo());
    nota.setSefazAmbiente(ambiente);
    if (retorno.situacao() == SituacaoSefaz.AUTORIZADA) {
      nota.setProtocoloAutorizacao(retorno.protocolo());
      nota.setDataAutorizacao(retorno.dataAutorizacao());
    }
  }

  /** Mapeia a situação retornada pela SEFAZ para o status de domínio da nota. */
  private StatusNotaFiscal mapStatus(SituacaoSefaz situacao) {
    return switch (situacao) {
      case AUTORIZADA -> StatusNotaFiscal.AUTORIZADA;
      case REJEITADA, DENEGADA -> StatusNotaFiscal.REJEITADA;
      case PENDENTE -> StatusNotaFiscal.PENDENTE_SEFAZ;
    };
  }

  /** Converte a entidade já atualizada no DTO de resposta do envio. */
  private SefazEnvioResponse toResponse(NotaFiscal nota) {
    return new SefazEnvioResponse(
        nota.getChaveAcesso(),
        nota.getStatus(),
        nota.getSefazCstat(),
        nota.getSefazMotivo(),
        nota.getProtocoloAutorizacao(),
        nota.getDataAutorizacao());
  }
}
