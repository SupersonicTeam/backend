-- =============================================================================================
-- Reset dos dados da demonstração da banca — PASSO 2: apagar (uma transação, filho → pai).
-- Rode só depois do PASSO 1 (reset-01-conferencia.sql) e de conferir as contagens. Mesma
-- identificação do passo 1, ver o cabeçalho dele. Depois, reinicie o backend: o SeedDemonstracao
-- recria tudo pelo pipeline real (é idempotente pela administradora, que aqui some).
--
-- Nunca toca em legislacao_trecho, shedlock, databasechangelog*, tabelas de referência nem na
-- auditoria (registro_auditoria). Testado pelo ResetDemonstracaoPostgresIntegrationTest.
-- =============================================================================================
BEGIN;

CREATE TEMP TABLE reset_esc ON COMMIT DROP AS
  SELECT id FROM escritorio
   WHERE nome = 'Contabilidade Rural Oeste' AND cnpj = '48315276000195';
CREATE TEMP TABLE reset_cont ON COMMIT DROP AS
  SELECT id FROM contadores WHERE escritorio_id IN (SELECT id FROM reset_esc);
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
 WHERE titular_id IN (SELECT id FROM reset_prod) OR titular_id IN (SELECT id FROM reset_cont);
DELETE FROM assistente_interacao WHERE produtor_id IN (SELECT id FROM reset_prod);
DELETE FROM sessao_refresh WHERE credencial_id IN (SELECT id FROM reset_cred);
DELETE FROM redefinicao_senha WHERE credencial_id IN (SELECT id FROM reset_cred);
DELETE FROM credencial_usuario WHERE id IN (SELECT id FROM reset_cred);
DELETE FROM contadores WHERE id IN (SELECT id FROM reset_cont);
DELETE FROM escritorio WHERE id IN (SELECT id FROM reset_esc);
DELETE FROM produtores WHERE id IN (SELECT id FROM reset_prod);

COMMIT;
