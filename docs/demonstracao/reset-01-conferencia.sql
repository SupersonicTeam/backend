-- =============================================================================================
-- Reset dos dados da demonstração da banca — PASSO 1: conferência (só leitura).
-- docs/demonstracao-banca.md, seção "Reset dos dados da demonstração". Testado contra o
-- PostgreSQL 16 do Testcontainers pelo ResetDemonstracaoPostgresIntegrationTest.
--
-- O que é da demonstração (o SeedDemonstracao / CenarioDemonstracao):
--   * o escritório "Contabilidade Rural Oeste" com o CNPJ do seed (nome e CNPJ do escritório não
--     são cifrados),
--   * os contadores desse escritório,
--   * os produtores da carteira do seed: o nome está na lista do CenarioDemonstracao.CARTEIRA E o
--     produtor tem vínculo (qualquer situação) com esse escritório. CPF/CNPJ e e-mail vão
--     cifrados ao banco (#147), então a identificação é pelo nome + vínculo, nunca só pelo nome,
--   * o que pende deles: notas (itens, validações, revisões), alertas, apurações, guias, pendências
--     (e anexos), notificações, vínculos e etiquetas, convites, obrigações do escritório, uso da
--     Ceres (créditos), interações com a Ceres, credenciais, sessões e links de senha.
--
-- Nunca entram: legislacao_trecho, shedlock, databasechangelog*, tabelas de referência e a
-- auditoria (registro_auditoria não tem FK e sobrevive ao que audita, #149).
-- =============================================================================================
WITH
  esc AS (
    SELECT id FROM escritorio
     WHERE nome = 'Contabilidade Rural Oeste' AND cnpj = '48315276000195'),
  cont AS (SELECT id FROM contadores WHERE escritorio_id IN (SELECT id FROM esc)),
  prod AS (
    SELECT p.id FROM produtores p
     WHERE p.nome_completo IN ('Carlos Eduardo Rossi', 'Fazenda Santa Clara Agropecuaria Ltda',
         'Luiz Carlos Bertoldo', 'Helena Takahashi', 'Agropecuaria Tres Pontes Ltda',
         'Rogerio Antunes Vasconcelos', 'Granja Toledo Ovos e Aves Ltda',
         'Arrozeira Familia Schmitt Ltda', 'Marcos Vieira Lacerda')
       AND EXISTS (SELECT 1 FROM carteira_vinculo v
                    WHERE v.produtor_id = p.id AND v.escritorio_id IN (SELECT id FROM esc))),
  nota AS (SELECT id FROM nota_fiscal WHERE produtor_id IN (SELECT id FROM prod)),
  vinc AS (
    SELECT id FROM carteira_vinculo
     WHERE produtor_id IN (SELECT id FROM prod) OR escritorio_id IN (SELECT id FROM esc)),
  pend AS (
    SELECT id FROM pendencia
     WHERE produtor_id IN (SELECT id FROM prod) OR escritorio_id IN (SELECT id FROM esc)),
  cred AS (
    SELECT id FROM credencial_usuario
     WHERE produtor_id IN (SELECT id FROM prod) OR contador_id IN (SELECT id FROM cont))
SELECT tabela, linhas FROM (
  SELECT 1 AS ordem, 'escritorio' AS tabela, (SELECT COUNT(*) FROM esc) AS linhas
  UNION ALL SELECT 2, 'contadores', (SELECT COUNT(*) FROM cont)
  UNION ALL SELECT 3, 'produtores', (SELECT COUNT(*) FROM prod)
  UNION ALL SELECT 4, 'nota_fiscal', (SELECT COUNT(*) FROM nota)
  UNION ALL SELECT 5, 'nota_fiscal_item',
      (SELECT COUNT(*) FROM nota_fiscal_item WHERE nota_fiscal_id IN (SELECT id FROM nota))
  UNION ALL SELECT 6, 'nota_fiscal_validacao',
      (SELECT COUNT(*) FROM nota_fiscal_validacao WHERE nota_fiscal_id IN (SELECT id FROM nota))
  UNION ALL SELECT 7, 'nota_fiscal_revisao',
      (SELECT COUNT(*) FROM nota_fiscal_revisao WHERE nota_fiscal_id IN (SELECT id FROM nota))
  UNION ALL SELECT 8, 'alerta_tributario',
      (SELECT COUNT(*) FROM alerta_tributario WHERE produtor_id IN (SELECT id FROM prod))
  UNION ALL SELECT 9, 'apuracao_mensal',
      (SELECT COUNT(*) FROM apuracao_mensal WHERE produtor_id IN (SELECT id FROM prod))
  UNION ALL SELECT 10, 'guias_fiscais',
      (SELECT COUNT(*) FROM guias_fiscais WHERE produtor_id IN (SELECT id FROM prod))
  UNION ALL SELECT 11, 'pendencia', (SELECT COUNT(*) FROM pend)
  UNION ALL SELECT 12, 'pendencia_anexo',
      (SELECT COUNT(*) FROM pendencia_anexo WHERE pendencia_id IN (SELECT id FROM pend))
  UNION ALL SELECT 13, 'notificacao',
      (SELECT COUNT(*) FROM notificacao WHERE produtor_id IN (SELECT id FROM prod)
          OR escritorio_id IN (SELECT id FROM esc))
  UNION ALL SELECT 14, 'carteira_vinculo', (SELECT COUNT(*) FROM vinc)
  UNION ALL SELECT 15, 'carteira_vinculo_etiqueta',
      (SELECT COUNT(*) FROM carteira_vinculo_etiqueta WHERE vinculo_id IN (SELECT id FROM vinc))
  UNION ALL SELECT 16, 'etiqueta_carteira',
      (SELECT COUNT(*) FROM etiqueta_carteira WHERE escritorio_id IN (SELECT id FROM esc))
  UNION ALL SELECT 17, 'convite_carteira',
      (SELECT COUNT(*) FROM convite_carteira WHERE escritorio_id IN (SELECT id FROM esc)
          OR produtor_id IN (SELECT id FROM prod))
  UNION ALL SELECT 18, 'convite_membro',
      (SELECT COUNT(*) FROM convite_membro WHERE escritorio_id IN (SELECT id FROM esc))
  UNION ALL SELECT 19, 'obrigacao_fiscal',
      (SELECT COUNT(*) FROM obrigacao_fiscal WHERE escritorio_id IN (SELECT id FROM esc))
  UNION ALL SELECT 20, 'assistente_uso_semanal',
      (SELECT COUNT(*) FROM assistente_uso_semanal WHERE titular_id IN (SELECT id FROM prod)
          OR titular_id IN (SELECT id FROM cont))
  UNION ALL SELECT 21, 'assistente_interacao',
      (SELECT COUNT(*) FROM assistente_interacao WHERE produtor_id IN (SELECT id FROM prod))
  UNION ALL SELECT 22, 'credencial_usuario', (SELECT COUNT(*) FROM cred)
  UNION ALL SELECT 23, 'sessao_refresh',
      (SELECT COUNT(*) FROM sessao_refresh WHERE credencial_id IN (SELECT id FROM cred))
  UNION ALL SELECT 24, 'redefinicao_senha',
      (SELECT COUNT(*) FROM redefinicao_senha WHERE credencial_id IN (SELECT id FROM cred))
) contagem
ORDER BY ordem;
