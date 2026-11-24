package br.com.agrocontabil.controller;

import br.com.agrocontabil.dto.TermosUsoResponse;
import br.com.agrocontabil.service.TermosUsoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Termos de uso (issue #498). O controller só delega. */
@RestController
@ConditionalOnWebApplication
@Tag(name = "Termos de uso", description = "Texto dos termos de uso do AgroConIA")
public class TermosUsoController {

  private final TermosUsoService termosService;

  public TermosUsoController(TermosUsoService termosService) {
    this.termosService = termosService;
  }

  /**
   * Termos de uso vigentes.
   *
   * @return versão, controlador, contato e seções do texto
   */
  @GetMapping("/api/termos-de-uso")
  @SecurityRequirements
  @Operation(summary = "Termos de uso do AgroConIA",
      description = "Publico: o titular le antes de se cadastrar. Versao (a mesma gravada no "
          + "aceite do cadastro), controlador, contato e as secoes do texto, em texto puro.")
  public ResponseEntity<TermosUsoResponse> termos() {
    return ResponseEntity.ok(termosService.termos());
  }
}
