package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Linha da listagem de notas da carteira do escritório (issue #341): só o que a lista mostra, lido
 * por projeção. Sem os itens e sem o XML (a coluna {@code xml_gerado} é grande e a lista nunca a
 * usa), e sem o documento do produtor nem o do destinatário.
 *
 * @param id                 nota
 * @param produtorId         dono da nota
 * @param produtorNome       nome do produtor (já na mesma consulta: sem N+1)
 * @param numero             número da NF-e
 * @param serie              série
 * @param naturezaOperacao   natureza da operação
 * @param status             situação
 * @param tipoDocumento      NFE ou NFA
 * @param dataEmissao        emissão
 * @param destinatarioNome   nome do destinatário
 * @param origem             PROPRIA (venda) ou IMPORTADA (compra)
 * @param emitenteNome       fornecedor da compra importada (nulo na nota própria); sem o documento
 * @param valorTotalProdutos total dos produtos
 * @param valorTotalNota     total da nota
 * @param valorTotalIbs      IBS da nota
 * @param valorTotalCbs      CBS da nota
 * @param chaveAcesso        chave de acesso (nula antes do XML)
 * @param createdAt          criação
 * @param updatedAt          última atualização
 */
public record NotaDaCarteira(UUID id, UUID produtorId, String produtorNome, Long numero,
    Integer serie, String naturezaOperacao, StatusNotaFiscal status,
    TipoDocumentoFiscal tipoDocumento, OffsetDateTime dataEmissao, String destinatarioNome,
    OrigemNotaFiscal origem, String emitenteNome,
    BigDecimal valorTotalProdutos, BigDecimal valorTotalNota, BigDecimal valorTotalIbs,
    BigDecimal valorTotalCbs, String chaveAcesso, OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
