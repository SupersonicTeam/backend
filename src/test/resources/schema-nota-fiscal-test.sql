-- Schema H2-compatible para NotaFiscalRepositoryTest.
-- Usado via @Sql porque o Hibernate create-drop falha no H2 para as entidades
-- existentes que possuem columnDefinition PostgreSQL-especifico (text[], jsonb).

CREATE TABLE IF NOT EXISTS produtores (
    id                  UUID          NOT NULL,
    nome_completo       VARCHAR(255)  NOT NULL,
    cpf_cnpj            VARCHAR(20),
    tipo_produtor       VARCHAR(20),
    regime_tributario   VARCHAR(20),
    porte_empresa       VARCHAR(20),
    culturas            VARCHAR(4096),
    faturamento_anual   NUMERIC(15,2),
    estado              VARCHAR(2),
    email               VARCHAR(255),
    telefone            VARCHAR(20),
    created_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS nota_fiscal (
    id                          UUID           NOT NULL,
    chave_acesso                VARCHAR(44),
    numero                      BIGINT         NOT NULL,
    serie                       INTEGER        NOT NULL DEFAULT 1,
    modelo                      VARCHAR(2)     NOT NULL DEFAULT '55',
    natureza_operacao           VARCHAR(255)   NOT NULL,
    data_emissao                TIMESTAMP WITH TIME ZONE NOT NULL,
    data_saida                  TIMESTAMP WITH TIME ZONE,
    produtor_id                 UUID           NOT NULL,
    destinatario_nome           VARCHAR(255)   NOT NULL,
    destinatario_cpf_cnpj       VARCHAR(14)    NOT NULL,
    destinatario_ie             VARCHAR(20),
    dest_logradouro             VARCHAR(255),
    dest_numero                 VARCHAR(20),
    dest_municipio              VARCHAR(255),
    dest_codigo_municipio_ibge  VARCHAR(7),
    dest_uf                     VARCHAR(2),
    dest_cep                    VARCHAR(8),
    valor_total_produtos        NUMERIC(15,2)  NOT NULL,
    valor_total_nota            NUMERIC(15,2)  NOT NULL,
    -- IBS/CBS totais — Sprint 2 Sessão 4 (EC 132/2023, LC 214/2025)
    valor_total_ibs             NUMERIC(15,2),
    valor_total_cbs             NUMERIC(15,2),
    status                      VARCHAR(20)    NOT NULL DEFAULT 'RASCUNHO',
    -- Discriminador NFE/NFA — Sprint 2 issue #18 (migration 0008)
    tipo_documento              VARCHAR(10)    NOT NULL DEFAULT 'NFE',
    -- Importacao de XML — Sprint 2 issue #19 (migration 0009).
    -- origem: PROPRIA (emitida pelo produtor) | IMPORTADA (nota de entrada de terceiro).
    -- emitente_*: preenchidos apenas em notas importadas.
    -- Obs.: o indice unico PARCIAL (WHERE origem='PROPRIA') do PostgreSQL nao e portavel
    -- para o H2 usado nos testes; aqui garantimos as colunas. A dedup de importadas por
    -- chave_acesso e coberta pelo unique de chave_acesso ja existente na entidade/migration.
    origem                      VARCHAR(10)    NOT NULL DEFAULT 'PROPRIA',
    emitente_nome               VARCHAR(255),
    emitente_cpf_cnpj           VARCHAR(14),
    -- XML gerado — Sprint 2 Sessão 6
    xml_gerado                  TEXT,
    versao_layout_nfe           VARCHAR(10),
    data_geracao_xml            TIMESTAMP WITH TIME ZONE,
    -- Retorno da SEFAZ — Sprint 3 issue #15
    protocolo_autorizacao       VARCHAR(20),
    data_autorizacao            TIMESTAMP WITH TIME ZONE,
    sefaz_cstat                 VARCHAR(10),
    sefaz_motivo                VARCHAR(255),
    sefaz_ambiente              VARCHAR(20),
    -- Cancelamento da NF-e — Sprint 3 issue #21 (migration 0010)
    data_cancelamento           TIMESTAMP WITH TIME ZONE,
    motivo_cancelamento         VARCHAR(255),
    protocolo_cancelamento      VARCHAR(20),
    -- Crédito tributário de entrada — Sprint 4 issue Agrocontabil#30 (migration 0011)
    valor_total_credito_ibs     NUMERIC(15,2),
    valor_total_credito_cbs     NUMERIC(15,2),
    created_at                  TIMESTAMP WITH TIME ZONE,
    updated_at                  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS nota_fiscal_item (
    id              UUID           NOT NULL,
    nota_fiscal_id  UUID           NOT NULL,
    numero_item     INTEGER        NOT NULL,
    codigo_produto  VARCHAR(60),
    descricao       VARCHAR(255)   NOT NULL,
    ncm             VARCHAR(8)     NOT NULL,
    cfop            VARCHAR(4)     NOT NULL,
    unidade         VARCHAR(6)     NOT NULL,
    quantidade              NUMERIC(15,4)  NOT NULL,
    valor_unitario          NUMERIC(15,4)  NOT NULL,
    valor_total             NUMERIC(15,2)  NOT NULL,
    -- IBS/CBS por item — Sprint 2 Sessão 4 (EC 132/2023, LC 214/2025)
    cst_ibs_cbs             VARCHAR(3),
    c_class_trib            VARCHAR(6),
    base_calculo_ibs_cbs    NUMERIC(15,2),
    aliquota_ibs            NUMERIC(7,4),
    valor_ibs               NUMERIC(15,2),
    aliquota_cbs            NUMERIC(7,4),
    valor_cbs               NUMERIC(15,2),
    percentual_reducao      NUMERIC(5,2)   DEFAULT 0,
    -- Crédito tributário de entrada — Sprint 4 issue Agrocontabil#30 (migration 0011)
    valor_credito_ibs       NUMERIC(15,2),
    valor_credito_cbs       NUMERIC(15,2),
    created_at              TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- Inconsistencias de validacao da NF-e — Sprint 3 issue #22 (migration 0006)
CREATE TABLE IF NOT EXISTS nota_fiscal_validacao (
    id              UUID           NOT NULL,
    nota_fiscal_id  UUID           NOT NULL,
    lote_validacao  UUID           NOT NULL,
    code            VARCHAR(60)    NOT NULL,
    field           VARCHAR(120),
    message         VARCHAR(500)   NOT NULL,
    suggestion      VARCHAR(500),
    rejected_value  VARCHAR(500),
    expected_value  VARCHAR(500),
    validado_em     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================================
-- Referencia de NCM/CFOP — Sprint 2 issue #20 (migration 0007).
-- O perfil de teste desabilita o Liquibase (liquibase.enabled=false), entao o
-- seed via <insert> do changeset NAO roda nos testes. Replicamos as tabelas e o
-- seed curado aqui para que os validadores @ValidNcm/@ValidCfop tenham dados nos
-- testes de integracao que usam este script via @Sql.
-- Manter sincronizado com 0007-ncm-cfop-referencia.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS ncm_agropecuario (
    codigo      VARCHAR(8)    NOT NULL,
    descricao   VARCHAR(255)  NOT NULL,
    PRIMARY KEY (codigo)
);

CREATE TABLE IF NOT EXISTS cfop_referencia (
    codigo                    VARCHAR(4)    NOT NULL,
    descricao                 VARCHAR(255)  NOT NULL,
    tipo_operacao             VARCHAR(20)   NOT NULL,
    ambito                    VARCHAR(20)   NOT NULL,
    aplicavel_produtor_rural  BOOLEAN       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (codigo)
);

MERGE INTO ncm_agropecuario (codigo, descricao) KEY (codigo) VALUES
    ('12010090', 'Soja, mesmo triturada (exceto para semeadura)'),
    ('12010010', 'Soja para semeadura'),
    ('10059010', 'Milho em grao (exceto para semeadura)'),
    ('10051000', 'Milho para semeadura'),
    ('10011900', 'Trigo duro (exceto para semeadura)'),
    ('10063021', 'Arroz semibranqueado ou branqueado, polido'),
    ('09011110', 'Cafe nao torrado, nao descafeinado, em grao'),
    ('17011400', 'Outros acucares de cana, em bruto'),
    ('52010020', 'Algodao em bruto, nao cardado nem penteado'),
    ('01022110', 'Bovinos reprodutores de raca pura, prenhes ou com cria'),
    ('01039000', 'Suinos vivos (exceto reprodutores de raca pura)'),
    ('04070090', 'Outros ovos de aves, com casca, frescos'),
    ('04012010', 'Leite, teor de gordura entre 1% e 6%, em embalagem ate 1 litro'),
    ('08043000', 'Abacaxis (ananases) frescos ou secos'),
    ('07020000', 'Tomates frescos ou refrigerados');

MERGE INTO cfop_referencia (codigo, descricao, tipo_operacao, ambito, aplicavel_produtor_rural) KEY (codigo) VALUES
    ('5101', 'Venda de producao do estabelecimento (operacao interna)', 'SAIDA', 'INTERNO', TRUE),
    ('5102', 'Venda de mercadoria adquirida de terceiros (operacao interna)', 'SAIDA', 'INTERNO', TRUE),
    ('5901', 'Remessa para industrializacao por encomenda (interna)', 'SAIDA', 'INTERNO', TRUE),
    ('5949', 'Outra saida de mercadoria ou prestacao de servico nao especificada (interna)', 'SAIDA', 'INTERNO', TRUE),
    ('6101', 'Venda de producao do estabelecimento (operacao interestadual)', 'SAIDA', 'INTERESTADUAL', TRUE),
    ('6102', 'Venda de mercadoria adquirida de terceiros (operacao interestadual)', 'SAIDA', 'INTERESTADUAL', TRUE),
    ('6949', 'Outra saida de mercadoria ou prestacao de servico nao especificada (interestadual)', 'SAIDA', 'INTERESTADUAL', TRUE),
    ('7101', 'Venda de producao do estabelecimento (exportacao)', 'SAIDA', 'EXTERIOR', TRUE),
    ('1556', 'Compra de material para uso ou consumo (operacao interna)', 'ENTRADA', 'INTERNO', FALSE);
