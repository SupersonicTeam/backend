-- =============================================================================================
-- Reset dos dados da demonstração da banca — PASSO 2: apagar (uma transação, filho → pai).
-- Rode só depois do PASSO 1 (reset-01-conferencia.sql) e de conferir as contagens. Rode com
-- psql -v ON_ERROR_STOP=1: qualquer erro desfaz tudo. Depois, suba o backend: o SeedDemonstracao
-- recria tudo pelo pipeline real (é idempotente pela administradora, que aqui some).
-- Testado pelo ResetDemonstracaoPostgresIntegrationTest.
--
-- O que é da demonstração (o SeedDemonstracao e o CenarioDemonstracao):
--   * o escritório "Contabilidade Rural Oeste" com o CNPJ do seed (nome e CNPJ do escritório não
--     são cifrados),
--   * os contadores da equipe do seed (nome na lista do CenarioDemonstracao.EQUIPE) que estão
--     nesse escritório ou sem escritório (membro removido da equipe num ensaio). Contador de fora
--     da equipe que entrou no escritório por convite NÃO é apagado: sai do escritório, como na
--     remoção pela tela (escritorio_id e funcao_escritorio nulos), e a conta dele fica,
--   * os produtores da carteira do seed: nome na lista do CenarioDemonstracao.CARTEIRA E vínculo
--     (qualquer situação) com esse escritório. CPF/CNPJ e e-mail vão cifrados ao banco (#147),
--     então a identificação é pelo nome + vínculo, nunca só pelo nome,
--   * o que pende deles: notas (itens, validações, revisões), alertas, apurações, guias,
--     pendências (e anexos), notificações, vínculos e etiquetas, convites, obrigações do
--     escritório, uso da Ceres (créditos, do produtor e do escritório), interações com a Ceres,
--     credenciais, sessões e links de senha. Vínculo, pendência e notificação de produtor de fora
--     do seed com esse escritório também saem (o escritório deixa de existir), mas o produtor e a
--     conta dele ficam.
--
-- Nunca entram: legislacao_trecho, shedlock, databasechangelog*, tabelas de referência e a
-- auditoria (registro_auditoria não tem FK e sobrevive ao que audita, #149).
-- =============================================================================================
BEGIN;

CREATE TEMP TABLE reset_esc ON COMMIT DROP AS
  SELECT id FROM escritorio
   WHERE nome = 'Contabilidade Rural Oeste' AND cnpj = '48315276000195';
CREATE TEMP TABLE reset_cont ON COMMIT DROP AS
  SELECT id FROM contadores
   WHERE nome_completo IN ('Patricia Moreira Lopes', 'Rafael Augusto Costa', 'Juliana Prado Ferreira')
     AND (escritorio_id IN (SELECT id FROM reset_esc) OR escritorio_id IS NULL);
CREATE TEMP TABLE reset_prod ON COMMIT DROP AS
  SELECT p.id FROM produtores p
   WHERE p.nome_completo IN ('Carlos Eduardo Rossi', 'Fazenda Santa Clara Agropecuaria Ltda',
       'Luiz Carlos Bertoldo', 'Helena Takahashi', 'Agropecuaria Tres Pontes Ltda',
       'Rogerio Antunes Vasconcelos', 'Granja Toledo Ovos e Aves Ltda',
       'Arrozeira Familia Schmitt Ltda', 'Marcos Vieira Lacerda')
     AND EXISTS (SELECT 1 FROM carteira_vinculo v
                  WHERE v.produtor_id = p.id AND v.escritorio_id IN (SELECT id FROM reset_esc));
CREATE TEMP TABLE reset_nota ON COMMIT DROP AS
  SELECT id FROM nota_fiscal WHERE produtor_id IN (SELECT id FROM reset_prod);
CREATE TEMP TABLE reset_vinc ON COMMIT DROP AS
  SELECT id FROM carteira_vinculo
   WHERE produtor_id IN (SELECT id FROM reset_prod) OR escritorio_id IN (SELECT id FROM reset_esc);
CREATE TEMP TABLE reset_pend ON COMMIT DROP AS
  SELECT id FROM pendencia
   WHERE produtor_id IN (SELECT id FROM reset_prod) OR escritorio_id IN (SELECT id FROM reset_esc);
CREATE TEMP TABLE reset_cred ON COMMIT DROP AS
  SELECT id FROM credencial_usuario
   WHERE produtor_id IN (SELECT id FROM reset_prod) OR contador_id IN (SELECT id FROM reset_cont);

DELETE FROM nota_fiscal_item WHERE nota_fiscal_id IN (SELECT id FROM reset_nota);
DELETE FROM nota_fiscal_validacao WHERE nota_fiscal_id IN (SELECT id FROM reset_nota);
DELETE FROM nota_fiscal_revisao WHERE nota_fiscal_id IN (SELECT id FROM reset_nota);
DELETE FROM alerta_tributario WHERE produtor_id IN (SELECT id FROM reset_prod);
DELETE FROM nota_fiscal WHERE id IN (SELECT id FROM reset_nota);
DELETE FROM apuracao_mensal WHERE produtor_id IN (SELECT id FROM reset_prod);
DELETE FROM guias_fiscais WHERE produtor_id IN (SELECT id FROM reset_prod);
DELETE FROM pendencia_anexo WHERE pendencia_id IN (SELECT id FROM reset_pend);
DELETE FROM pendencia WHERE id IN (SELECT id FROM reset_pend);
DELETE FROM notificacao
 WHERE produtor_id IN (SELECT id FROM reset_prod) OR escritorio_id IN (SELECT id FROM reset_esc);
DELETE FROM carteira_vinculo_etiqueta WHERE vinculo_id IN (SELECT id FROM reset_vinc);
DELETE FROM carteira_vinculo WHERE id IN (SELECT id FROM reset_vinc);
DELETE FROM etiqueta_carteira WHERE escritorio_id IN (SELECT id FROM reset_esc);
DELETE FROM convite_carteira
 WHERE escritorio_id IN (SELECT id FROM reset_esc) OR produtor_id IN (SELECT id FROM reset_prod);
DELETE FROM convite_membro WHERE escritorio_id IN (SELECT id FROM reset_esc);
DELETE FROM obrigacao_fiscal WHERE escritorio_id IN (SELECT id FROM reset_esc);
DELETE FROM assistente_uso_semanal
 WHERE titular_id IN (SELECT id FROM reset_prod) OR titular_id IN (SELECT id FROM reset_esc);
DELETE FROM assistente_interacao WHERE produtor_id IN (SELECT id FROM reset_prod);
DELETE FROM sessao_refresh WHERE credencial_id IN (SELECT id FROM reset_cred);
DELETE FROM redefinicao_senha WHERE credencial_id IN (SELECT id FROM reset_cred);
DELETE FROM credencial_usuario WHERE id IN (SELECT id FROM reset_cred);
DELETE FROM contadores WHERE id IN (SELECT id FROM reset_cont);
-- Contador de fora da equipe do seed que entrou no escritório: sai dele, a conta fica.
UPDATE contadores SET escritorio_id = NULL, funcao_escritorio = NULL
 WHERE escritorio_id IN (SELECT id FROM reset_esc);
DELETE FROM escritorio WHERE id IN (SELECT id FROM reset_esc);
DELETE FROM produtores WHERE id IN (SELECT id FROM reset_prod);

COMMIT;
