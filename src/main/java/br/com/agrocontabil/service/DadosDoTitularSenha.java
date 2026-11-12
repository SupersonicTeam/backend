package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Monta, a partir da credencial, o que a senha nova não pode conter nas trocas de senha (issue
 * #521): o e-mail, o CPF/CNPJ e o telefone do titular e, no contador, o CNPJ e o telefone do
 * escritório de que ele é membro.
 *
 * <p>O escritório é lido no momento da troca, então vale o que está gravado agora, inclusive o que
 * o administrador mudou depois do cadastro. Cada leitura roda na transação curta do próprio
 * repository: nada de conexão presa durante o BCrypt, que vem depois.
 *
 * <p>Nulo-seguro: produtor sem telefone, contador pessoal (sem CNPJ), contador removido da equipe
 * (sem escritório) e identidade já apagada dão só os dados que existem, e a troca segue.
 */
@Component
public class DadosDoTitularSenha {

  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final EscritorioRepository escritorioRepository;

  public DadosDoTitularSenha(ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository, EscritorioRepository escritorioRepository) {
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.escritorioRepository = escritorioRepository;
  }

  /**
   * Dados de contexto do titular da credencial.
   *
   * @param credencial credencial de quem troca a senha
   * @return e-mail, documentos e telefones, sem os ausentes
   */
  public PoliticaSenha.DadosDoTitular de(CredencialUsuario credencial) {
    List<String> documentos = new ArrayList<>();
    List<String> telefones = new ArrayList<>();
    documentos.add(credencial.getCpfCnpj());
    if (credencial.getProdutorId() != null) {
      produtorRepository.findById(credencial.getProdutorId())
          .ifPresent(produtor -> telefones.add(produtor.getTelefone()));
    } else if (credencial.getContadorId() != null) {
      contadorRepository.findById(credencial.getContadorId())
          .ifPresent(contador -> doContador(contador, documentos, telefones));
    }
    return new PoliticaSenha.DadosDoTitular(credencial.getEmail(), documentos, telefones);
  }

  private void doContador(Contador contador, List<String> documentos, List<String> telefones) {
    telefones.add(contador.getTelefone());
    if (contador.getEscritorioId() != null) {
      escritorioRepository.findById(contador.getEscritorioId()).ifPresent(escritorio -> {
        documentos.add(escritorio.getCnpj());
        telefones.add(escritorio.getTelefone());
      });
    }
  }
}
