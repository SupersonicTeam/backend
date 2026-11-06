package br.com.agrocontabil.controller;

import br.com.agrocontabil.dto.FiltroNotasCarteiraRequest;
import br.com.agrocontabil.dto.NotaCarteiraResponse;
import br.com.agrocontabil.dto.ResumoNotasCarteiraResponse;
import br.com.agrocontabil.service.CarteiraNotasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notas de <strong>toda a carteira ativa</strong> do escritório, sem escolher o cliente (issue
 * #341). Só leitura; qualquer membro do escritório (produtor 403, contador sem escritório 403
 * {@code SEM_ESCRITORIO}). A visão por cliente continua em
 * {@link CarteiraNotasController}.
 */
@RestController
@RequestMapping("/api/contador/notas")
@Tag(name = "Carteira do contador")
public class CarteiraTodasNotasController {

  private final CarteiraNotasService carteiraNotasService;

  public CarteiraTodasNotasController(CarteiraNotasService carteiraNotasService) {
    this.carteiraNotasService = carteiraNotasService;
  }

  /** Notas da carteira ativa, com os filtros de {@code GET /api/notas} mais cliente e responsável. */
  @GetMapping
  @Operation(summary = "Notas de toda a carteira ativa do escritorio",
      description = "Mesmos filtros e ordenacao de GET /api/notas, mais produtorId (fora da "
          + "carteira, 404) e responsavel (eu, nenhum ou id do membro). So clientes com vinculo "
          + "ATIVO. Cada item traz produtorId e produtorNome, sem itens nem documento. Corpo em "
          + "array e total em X-Total-Count.")
  public ResponseEntity<List<NotaCarteiraResponse>> listar(
      @Valid @ParameterObject FiltroNotasCarteiraRequest filtro,
      @ParameterObject Pageable pageable) {
    Page<NotaCarteiraResponse> pagina = carteiraNotasService.listarDaCarteira(filtro, pageable);
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }

  /** Contagem por caixa de triagem, com os mesmos filtros da lista. */
  @GetMapping("/resumo")
  @Operation(summary = "Contagem das notas da carteira por caixa de triagem",
      description = "Revisao, travadas, rascunhos, devolvidas, autorizadas, canceladas e todas, "
          + "numa consulta so. Aceita os filtros da lista (inclusive busca), menos status.")
  public ResumoNotasCarteiraResponse resumo(
      @Valid @ParameterObject FiltroNotasCarteiraRequest filtro) {
    return carteiraNotasService.resumoDaCarteira(filtro);
  }
}
