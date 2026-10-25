package br.com.agrocontabil.controller;

import br.com.agrocontabil.dto.ConviteRecebidoResponse;
import br.com.agrocontabil.dto.VinculoProdutorResponse;
import br.com.agrocontabil.service.CarteiraService;
import br.com.agrocontabil.service.ConviteCarteiraService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O lado do produtor na carteira (issue #177): convites recebidos e escritórios que o atendem.
 * Só produtor (contador 403); convite ou vínculo de outro é 404.
 */
@RestController
@Tag(name = "Convites e escritorios do produtor",
    description = "Aceite do convite do escritorio e fim do vinculo (issue #177)")
public class ConviteController {

  private final ConviteCarteiraService conviteService;
  private final CarteiraService carteiraService;

  public ConviteController(ConviteCarteiraService conviteService,
      CarteiraService carteiraService) {
    this.conviteService = conviteService;
    this.carteiraService = carteiraService;
  }

  /** Convites pendentes para o e-mail do produtor. */
  @GetMapping("/api/convites")
  @Operation(summary = "Convites pendentes para o e-mail da conta")
  public ResponseEntity<List<ConviteRecebidoResponse>> convites() {
    List<ConviteRecebidoResponse> convites = conviteService.listarRecebidos();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(convites.size()))
        .body(convites);
  }

  /** Aceita o convite. */
  @PostMapping("/api/convites/{conviteId}/aceitar")
  @Operation(summary = "Aceita o convite: o escritorio passa a ver os seus dados")
  public ConviteRecebidoResponse aceitar(@PathVariable UUID conviteId) {
    return conviteService.aceitar(conviteId);
  }

  /** Recusa o convite. */
  @PostMapping("/api/convites/{conviteId}/recusar")
  @Operation(summary = "Recusa o convite")
  public ConviteRecebidoResponse recusar(@PathVariable UUID conviteId) {
    return conviteService.recusar(conviteId);
  }

  /** Escritórios que atendem o produtor. */
  @GetMapping("/api/vinculos")
  @Operation(summary = "Escritorios que atendem a conta (ativos e suspensos)")
  public ResponseEntity<List<VinculoProdutorResponse>> vinculos() {
    List<VinculoProdutorResponse> vinculos = carteiraService.listarDoProdutor();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(vinculos.size()))
        .body(vinculos);
  }

  /** O produtor encerra o vínculo com um escritório. */
  @PostMapping("/api/vinculos/{vinculoId}/encerrar")
  @Operation(summary = "Encerra o vinculo com o escritorio: o acesso dele acaba na hora")
  public VinculoProdutorResponse encerrar(@PathVariable UUID vinculoId) {
    return carteiraService.encerrarPeloProdutor(vinculoId);
  }
}
