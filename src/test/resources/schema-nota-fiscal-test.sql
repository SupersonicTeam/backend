-- Schema H2-compatible para NotaFiscalRepositoryTest.
-- Usado via @Sql porque o Hibernate create-drop falha no H2 para as entidades
-- existentes que possuem columnDefinition PostgreSQL-especifico (text[], jsonb).

CREATE TABLE IF NOT EXISTS produtores (
    id                  UUID          NOT NULL,
    nome_completo       VARCHAR(255)  NOT NULL,
    cpf_cnpj            VARCHAR(100),
    tipo_produtor       VARCHAR(20),
    regime_tributario   VARCHAR(20),
    porte_empresa       VARCHAR(20),
    culturas            VARCHAR(4096),
    faturamento_anual   NUMERIC(15,2),
    estado              VARCHAR(2),
    email               VARCHAR(512),
    telefone            VARCHAR(100),
    -- Endereco completo + IE do emitente — Onda 2 issue #88 (migration 0021).
    -- Todas nullable: obrigatoriedade e regra de emissao (FiscalRulesValidator).
    endereco_logradouro            VARCHAR(60),
    endereco_numero                VARCHAR(60),
    endereco_complemento           VARCHAR(60),
    endereco_bairro                VARCHAR(60),
    endereco_codigo_municipio_ibge VARCHAR(7),
    endereco_municipio             VARCHAR(60),
    endereco_cep                   VARCHAR(8),
    inscricao_estadual             VARCHAR(14),
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
    -- para o H2 usado nos testes; aqui garantimos as colunas. A dedup de importadas e pela
    -- unique (produtor_id, chave_acesso, origem) do fim da tabela (changeset 0045, #223).
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
    -- Lock otimista — Onda 3 issue #95 (migration 0022)
    version                     BIGINT         NOT NULL DEFAULT 0,
    -- Busca das notas — issue #174 (migration 0037). Mantida pela aplicacao; o indice GIN de
    -- trigrama (pg_trgm + btree_gin) e so do PostgreSQL e nao e replicado aqui.
    busca_normalizada           VARCHAR(600),
    created_at                  TIMESTAMP WITH TIME ZONE,
    updated_at                  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    -- chave_acesso e unica por (produtor, chave, origem) desde o changeset 0045 (issue #223),
    -- que substituiu a unique global da 0002: emitente e destinatario tem a mesma NF-e.
    CONSTRAINT uk_nota_fiscal_produtor_chave_origem UNIQUE (produtor_id, chave_acesso, origem)
);
-- Nota: a unicidade de (produtor, numero, serie, modelo) restrita a origem='PROPRIA' e um
-- INDICE UNICO PARCIAL no PostgreSQL (changeset 0009; a unique total de 0002 foi REMOVIDA la
-- para permitir notas IMPORTADAS de terceiros com numero/serie colidentes). O H2 NAO suporta
-- indice parcial (WHERE em CREATE INDEX), entao essa regra fica so no Liquibase e nao e
-- replicada aqui — decisao consciente do projeto.

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
    -- Tratamento aplicado no calculo — issue #272 (migration 0048)
    percentual_reducao_aplicado  NUMERIC(5,2),
    c_class_trib_aplicado        VARCHAR(6),
    -- Crédito tributário de entrada — Sprint 4 issue Agrocontabil#30 (migration 0011)
    valor_credito_ibs       NUMERIC(15,2),
    valor_credito_cbs       NUMERIC(15,2),
    -- Natureza tributária por operação (DEBITO/CREDITO) — Sprint 4 issue #30 (migration 0012)
    natureza_operacao       VARCHAR(10),
    -- Parcelas estadual/municipal reais de IBS — Sprint 4 issue #31 (migration 0013)
    aliquota_ibs_uf         NUMERIC(7,4),
    aliquota_ibs_mun        NUMERIC(7,4),
    valor_ibs_uf            NUMERIC(15,2),
    valor_ibs_mun           NUMERIC(15,2),
    -- Grupo ICMS do item: CST informado e origem (migration 0052)
    cst_icms                VARCHAR(3),
    origem_icms             VARCHAR(1),
    created_at              TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    -- Espelha o changeset 0002-nota-fiscal-item-unique-numero-item (migration 0002):
    -- um numero_item nao pode se repetir dentro da mesma nota fiscal.
    CONSTRAINT uk_nota_fiscal_item_nota_numero UNIQUE (nota_fiscal_id, numero_item)
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
-- Apuracao mensal de IBS/CBS — Sprint 4 issue Agrocontabil#41 (migration 0014).
-- O perfil de teste desabilita o Liquibase; replicamos a tabela para que os testes de
-- integracao que usam este script via @Sql tenham o schema da apuracao.
-- Manter sincronizado com 0014-apuracao-mensal.xml.
CREATE TABLE IF NOT EXISTS apuracao_mensal (
    id               UUID           NOT NULL,
    produtor_id      UUID           NOT NULL,
    periodo          VARCHAR(7)     NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    debito_ibs       NUMERIC(15,2)  NOT NULL,
    debito_cbs       NUMERIC(15,2)  NOT NULL,
    credito_ibs      NUMERIC(15,2)  NOT NULL,
    credito_cbs      NUMERIC(15,2)  NOT NULL,
    saldo_ibs        NUMERIC(15,2)  NOT NULL,
    saldo_cbs        NUMERIC(15,2)  NOT NULL,
    total_notas      BIGINT         NOT NULL DEFAULT 0,
    data_fechamento  TIMESTAMP WITH TIME ZONE,
    -- Auditoria de reabertura — Sprint 4 issue Agrocontabil#42 (migration 0015).
    reaberto_por     VARCHAR(64),
    data_reabertura  TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_apuracao_produtor_periodo UNIQUE (produtor_id, periodo)
);

-- ============================================================================
-- Guias fiscais do Contador — issue #74 (migration 0018).
-- Manter sincronizado com 0018-guias-fiscais.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS guias_fiscais (
    id               UUID           NOT NULL,
    produtor_id      UUID           NOT NULL,
    tipo_guia        VARCHAR(20)    NOT NULL,
    data_vencimento  DATE           NOT NULL,
    valor            NUMERIC(15,2)  NOT NULL,
    observacoes      VARCHAR(255),
    status           VARCHAR(20)    NOT NULL,
    data_pagamento   TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_guia_fiscal_produtor_id ON guias_fiscais (produtor_id);

-- Contador e vinculo N:N com produtor — Onda 7 issue #93 (migration 0024).
-- Manter sincronizado com 0024-contador-vinculo.xml.
-- Escritorio (migration 0039, issue #228): dono da carteira; o contador e membro com funcao.
CREATE TABLE IF NOT EXISTS escritorio (
    id          UUID                      NOT NULL,
    nome        VARCHAR(255)              NOT NULL,
    cnpj        VARCHAR(14),
    created_at  TIMESTAMP WITH TIME ZONE,
    updated_at  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS contadores (
    id             UUID          NOT NULL,
    nome_completo  VARCHAR(255)  NOT NULL,
    escritorio_id      UUID,
    funcao_escritorio  VARCHAR(20),
    cpf_cnpj       VARCHAR(100),
    crc            VARCHAR(20),
    email          VARCHAR(512),
    telefone       VARCHAR(100),
    created_at     TIMESTAMP WITH TIME ZONE,
    updated_at     TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_contador_cpf_cnpj UNIQUE (cpf_cnpj)
);

-- Carteira do contador — issue #177 (migration 0038, que substitui o contador_produtor da 0024).
-- Manter sincronizado com 0038-carteira-contador.xml. Os indices unicos parciais (um vinculo
-- aberto por par, um convite pendente por par) sao so do PostgreSQL: o H2 nao tem indice parcial,
-- e aqui a regra fica no servico.
CREATE TABLE IF NOT EXISTS carteira_vinculo (
    id            UUID                      NOT NULL,
    escritorio_id   UUID                    NOT NULL,
    responsavel_id  UUID,
    produtor_id   UUID                      NOT NULL,
    situacao      VARCHAR(20)               NOT NULL,
    inicio        TIMESTAMP WITH TIME ZONE  NOT NULL,
    fim           TIMESTAMP WITH TIME ZONE,
    observacoes   VARCHAR(1000),
    created_at    TIMESTAMP WITH TIME ZONE,
    updated_at    TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_carteira_vinculo_escritorio_situacao
    ON carteira_vinculo (escritorio_id, situacao);
CREATE INDEX IF NOT EXISTS idx_carteira_vinculo_responsavel ON carteira_vinculo (responsavel_id);
CREATE INDEX IF NOT EXISTS idx_carteira_vinculo_produtor ON carteira_vinculo (produtor_id);

CREATE TABLE IF NOT EXISTS etiqueta_carteira (
    id                UUID                      NOT NULL,
    escritorio_id     UUID                      NOT NULL,
    nome              VARCHAR(30)               NOT NULL,
    nome_normalizado  VARCHAR(30)               NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

ALTER TABLE etiqueta_carteira ADD CONSTRAINT IF NOT EXISTS uk_etiqueta_carteira_escritorio_nome
    UNIQUE (escritorio_id, nome_normalizado);

CREATE TABLE IF NOT EXISTS carteira_vinculo_etiqueta (
    vinculo_id   UUID  NOT NULL,
    etiqueta_id  UUID  NOT NULL,
    PRIMARY KEY (vinculo_id, etiqueta_id)
);

CREATE INDEX IF NOT EXISTS idx_carteira_vinculo_etiqueta_etiqueta
    ON carteira_vinculo_etiqueta (etiqueta_id);

CREATE TABLE IF NOT EXISTS convite_carteira (
    id             UUID                      NOT NULL,
    escritorio_id  UUID                      NOT NULL,
    convidado_por  UUID,
    email          VARCHAR(512)              NOT NULL,
    situacao       VARCHAR(20)               NOT NULL,
    criado_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    expira_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    respondido_em  TIMESTAMP WITH TIME ZONE,
    produtor_id    UUID,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_convite_carteira_email_situacao
    ON convite_carteira (email, situacao);
CREATE INDEX IF NOT EXISTS idx_convite_carteira_escritorio_criado
    ON convite_carteira (escritorio_id, criado_em);

-- Convite de equipe (migration 0039, issue #228): so o SHA-256 do token, uso unico.
CREATE TABLE IF NOT EXISTS convite_membro (
    id             UUID                      NOT NULL,
    escritorio_id  UUID                      NOT NULL,
    email          VARCHAR(512)              NOT NULL,
    funcao         VARCHAR(20)               NOT NULL,
    token_hash     VARCHAR(64)               NOT NULL,
    criado_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    expira_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    usado_em       TIMESTAMP WITH TIME ZONE,
    convidado_por  UUID,
    PRIMARY KEY (id)
);

ALTER TABLE convite_membro ADD CONSTRAINT IF NOT EXISTS uk_convite_membro_token_hash
    UNIQUE (token_hash);
CREATE INDEX IF NOT EXISTS idx_convite_membro_escritorio_criado
    ON convite_membro (escritorio_id, criado_em);

-- Auditoria das interacoes com a Ceres — Sprint 6 issue #144 (migration 0025).
-- Sem o texto da pergunta nem da resposta. Manter sincronizado com 0025-assistente-interacao.xml.
CREATE TABLE IF NOT EXISTS assistente_interacao (
    id                UUID                      NOT NULL,
    produtor_id       UUID                      NOT NULL,
    rota              VARCHAR(20)               NOT NULL,
    desfecho          VARCHAR(40)               NOT NULL,
    funcoes           TEXT,
    chamadas_funcao   INTEGER                   NOT NULL,
    trechos_citados   TEXT,
    modelo            VARCHAR(120),
    latencia_ms       BIGINT                    NOT NULL,
    tokens_entrada    INTEGER,
    tokens_saida      INTEGER,
    tamanho_pergunta  INTEGER                   NOT NULL,
    criado_em         TIMESTAMP WITH TIME ZONE  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_assistente_interacao_produtor_criado
    ON assistente_interacao (produtor_id, criado_em);

-- Marca de tokens estimados — Sprint 7 issue #166 (migration 0029). Idempotente: nos testes com
-- create-drop o Hibernate ja cria a coluna a partir da entidade.
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS
    tokens_estimados BOOLEAN DEFAULT FALSE NOT NULL;

-- Desfecho por parte do MISTO — Sprint 7 (migration 0030). Idempotente, como o bloco acima.
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS desfecho_dados VARCHAR(40);
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS desfecho_legislacao VARCHAR(40);
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS pergunta_lei_reescrita BOOLEAN;

-- Camada que decidiu a rota — Ceres 2.0 issue #265 (migration 0050). Idempotente, como acima.
ALTER TABLE assistente_interacao ADD COLUMN IF NOT EXISTS camada_classificacao VARCHAR(20);

-- Cota semanal de creditos da Ceres — Sprint 7 issue #166 (migration 0029). Manter sincronizado
-- com 0029-assistente-uso-semanal.xml.
CREATE TABLE IF NOT EXISTS assistente_uso_semanal (
    titular_tipo         VARCHAR(20)               NOT NULL,
    titular_id           UUID                      NOT NULL,
    semana_inicio        DATE                      NOT NULL,
    creditos_reservados  BIGINT                    DEFAULT 0 NOT NULL,
    creditos_consumidos  BIGINT                    DEFAULT 0 NOT NULL,
    tokens_entrada       BIGINT                    DEFAULT 0 NOT NULL,
    tokens_saida         BIGINT                    DEFAULT 0 NOT NULL,
    perguntas            INTEGER                   DEFAULT 0 NOT NULL,
    atualizado_em        TIMESTAMP WITH TIME ZONE  NOT NULL,
    CONSTRAINT pk_assistente_uso_semanal PRIMARY KEY (titular_tipo, titular_id, semana_inicio),
    CONSTRAINT ck_assistente_uso_nao_negativo CHECK (creditos_reservados >= 0
        AND creditos_consumidos >= 0 AND tokens_entrada >= 0 AND tokens_saida >= 0
        AND perguntas >= 0)
);

-- Alertas tributarios — Sprint 7 issue #140 (migration 0028). Manter sincronizado com
-- 0028-alerta-tributario.xml (FK com CASCADE mais abaixo, junto das demais).
CREATE TABLE IF NOT EXISTS alerta_tributario (
    id             UUID                      NOT NULL,
    produtor_id    UUID                      NOT NULL,
    tipo           VARCHAR(30)               NOT NULL,
    referencia_id  UUID                      NOT NULL,
    data_limite    DATE                      NOT NULL,
    descricao      VARCHAR(160)              NOT NULL,
    status         VARCHAR(20)               NOT NULL,
    criado_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    resolvido_em   TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uk_alerta_tributario_referencia UNIQUE (tipo, referencia_id, data_limite)
);

CREATE INDEX IF NOT EXISTS idx_alerta_tributario_produtor
    ON alerta_tributario (produtor_id, status, data_limite);

-- Indice composto (status, periodo) — Sprint 4 issue #57 (migration 0016).
-- Cobre a varredura do refechamento de periodos reabertos (status = ? AND periodo range).
CREATE INDEX IF NOT EXISTS idx_apuracao_status_periodo ON apuracao_mensal (status, periodo);

-- Indices compostos de escalabilidade — Onda 1 issue #102 (migration 0019).
-- A ORDEM das colunas segue o predicado real (igualdades antes do range/ordenacao) e e
-- verificada por SchemaTestIndicesTest. Manter sincronizado com
-- 0019-indices-compostos-escalabilidade.xml.
CREATE INDEX IF NOT EXISTS idx_nota_fiscal_produtor_status_emissao
    ON nota_fiscal (produtor_id, status, data_emissao);
CREATE INDEX IF NOT EXISTS idx_guia_fiscal_status_vencimento
    ON guias_fiscais (status, data_vencimento);
CREATE INDEX IF NOT EXISTS idx_guia_fiscal_produtor_vencimento
    ON guias_fiscais (produtor_id, data_vencimento);

-- ============================================================================
-- Foreign keys — issue #90. Replicam as FKs do Liquibase com as MESMAS acoes ON DELETE,
-- para que RESTRICT/CASCADE/filho orfao se comportem nos testes como em producao.
-- Manter sincronizado com 0002-nota-fiscal.xml, 0014-apuracao-mensal.xml e
-- 0018-guias-fiscais.xml.
--
-- Por que ALTER TABLE e nao dentro dos CREATE TABLE acima: a maioria dos testes
-- @SpringBootTest roda com ddl-auto=create-drop — o Hibernate cria as tabelas (sem FK, pois as
-- entidades usam @ForeignKey(NO_CONSTRAINT)) ANTES deste @Sql, e os CREATE TABLE IF NOT EXISTS
-- viram no-op. O ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS e idempotente e vale nos dois
-- caminhos (@DataJpaTest com ddl-auto=none e @SpringBootTest com create-drop).
--
-- nota_fiscal_validacao NAO recebe FK: o changeset 0006 nao define nenhuma em producao.
-- ============================================================================
ALTER TABLE nota_fiscal ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE RESTRICT;

ALTER TABLE nota_fiscal_item ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_item_nota
    FOREIGN KEY (nota_fiscal_id) REFERENCES nota_fiscal (id) ON DELETE CASCADE;

ALTER TABLE apuracao_mensal ADD CONSTRAINT IF NOT EXISTS fk_apuracao_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id);

ALTER TABLE guias_fiscais ADD CONSTRAINT IF NOT EXISTS fk_guia_fiscal_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id);

-- Carteira (migration 0038): CASCADE em tudo, como era o contador_produtor da 0024. Sem isso,
-- apagar um produtor ou contador semeado num teste falharia por integridade referencial.
ALTER TABLE contadores ADD CONSTRAINT IF NOT EXISTS fk_contador_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE SET NULL;

ALTER TABLE carteira_vinculo ADD CONSTRAINT IF NOT EXISTS fk_carteira_vinculo_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;

ALTER TABLE carteira_vinculo ADD CONSTRAINT IF NOT EXISTS fk_carteira_vinculo_responsavel
    FOREIGN KEY (responsavel_id) REFERENCES contadores (id) ON DELETE SET NULL;

ALTER TABLE carteira_vinculo ADD CONSTRAINT IF NOT EXISTS fk_carteira_vinculo_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

ALTER TABLE etiqueta_carteira ADD CONSTRAINT IF NOT EXISTS fk_etiqueta_carteira_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;

ALTER TABLE carteira_vinculo_etiqueta ADD CONSTRAINT IF NOT EXISTS
    fk_carteira_vinculo_etiqueta_vinculo
    FOREIGN KEY (vinculo_id) REFERENCES carteira_vinculo (id) ON DELETE CASCADE;

ALTER TABLE carteira_vinculo_etiqueta ADD CONSTRAINT IF NOT EXISTS
    fk_carteira_vinculo_etiqueta_etiqueta
    FOREIGN KEY (etiqueta_id) REFERENCES etiqueta_carteira (id) ON DELETE CASCADE;

ALTER TABLE convite_carteira ADD CONSTRAINT IF NOT EXISTS fk_convite_carteira_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;

ALTER TABLE convite_carteira ADD CONSTRAINT IF NOT EXISTS fk_convite_carteira_convidado_por
    FOREIGN KEY (convidado_por) REFERENCES contadores (id) ON DELETE SET NULL;

ALTER TABLE convite_membro ADD CONSTRAINT IF NOT EXISTS fk_convite_membro_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;

ALTER TABLE convite_membro ADD CONSTRAINT IF NOT EXISTS fk_convite_membro_convidado_por
    FOREIGN KEY (convidado_por) REFERENCES contadores (id) ON DELETE SET NULL;

ALTER TABLE convite_carteira ADD CONSTRAINT IF NOT EXISTS fk_convite_carteira_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

-- Auditoria da Ceres: RESTRICT, como nota_fiscal (migration 0025). O TestDatabaseCleaner apaga as
-- interacoes antes dos produtores.
ALTER TABLE assistente_interacao ADD CONSTRAINT IF NOT EXISTS fk_assistente_interacao_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE RESTRICT;

-- Alertas tributarios: CASCADE, como na migration 0028 (alerta e derivado).
ALTER TABLE alerta_tributario ADD CONSTRAINT IF NOT EXISTS fk_alerta_tributario_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

-- Credencial de acesso — Sprint 8 issue #205 (migration 0031). Manter sincronizado com
-- 0031-credencial-usuario.xml. Nos testes com create-drop o Hibernate ja criou a tabela (o CREATE
-- vira no-op), por isso unicidades, FKs e CHECK vem em ALTER idempotente.
CREATE TABLE IF NOT EXISTS credencial_usuario (
    id                     UUID                      NOT NULL,
    email                  VARCHAR(512)              NOT NULL,
    senha_hash             VARCHAR(100)              NOT NULL,
    papel                  VARCHAR(20)               NOT NULL,
    cpf_cnpj               VARCHAR(100)              NOT NULL,
    produtor_id            UUID,
    contador_id            UUID,
    ativo                  BOOLEAN                   DEFAULT TRUE NOT NULL,
    versao_sessao          INTEGER                   DEFAULT 0 NOT NULL,
    falhas_login           INTEGER                   DEFAULT 0 NOT NULL,
    bloqueada_ate          TIMESTAMP WITH TIME ZONE,
    created_at             TIMESTAMP WITH TIME ZONE,
    updated_at             TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS uk_credencial_email UNIQUE (email);

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS uk_credencial_produtor
    UNIQUE (produtor_id);

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS uk_credencial_contador
    UNIQUE (contador_id);

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS uk_credencial_papel_cpf_cnpj
    UNIQUE (papel, cpf_cnpj);

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS fk_credencial_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS fk_credencial_contador
    FOREIGN KEY (contador_id) REFERENCES contadores (id) ON DELETE CASCADE;

ALTER TABLE credencial_usuario ADD CONSTRAINT IF NOT EXISTS ck_credencial_identidade CHECK (
    (papel = 'PRODUTOR' AND produtor_id IS NOT NULL AND contador_id IS NULL)
    OR (papel = 'CONTADOR' AND contador_id IS NOT NULL AND produtor_id IS NULL)
);

-- Sessoes de refresh — Sprint 8 issue #207 (migration 0032). Manter sincronizado com
-- 0032-sessao-refresh.xml. Mesmo cuidado da credencial: unicidade e FK em ALTER idempotente.
CREATE TABLE IF NOT EXISTS sessao_refresh (
    id                   UUID                      NOT NULL,
    credencial_id        UUID                      NOT NULL,
    familia_id           UUID                      NOT NULL,
    token_hash           VARCHAR(64)               NOT NULL,
    versao_sessao        INTEGER                   NOT NULL,
    familia_iniciada_em  TIMESTAMP WITH TIME ZONE  NOT NULL,
    criado_em            TIMESTAMP WITH TIME ZONE  NOT NULL,
    expira_em            TIMESTAMP WITH TIME ZONE  NOT NULL,
    usado_em             TIMESTAMP WITH TIME ZONE,
    revogado_em          TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

ALTER TABLE sessao_refresh ADD CONSTRAINT IF NOT EXISTS uk_sessao_refresh_token_hash
    UNIQUE (token_hash);

ALTER TABLE sessao_refresh ADD CONSTRAINT IF NOT EXISTS fk_sessao_refresh_credencial
    FOREIGN KEY (credencial_id) REFERENCES credencial_usuario (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_sessao_refresh_familia ON sessao_refresh (familia_id);
CREATE INDEX IF NOT EXISTS idx_sessao_refresh_credencial ON sessao_refresh (credencial_id);
CREATE INDEX IF NOT EXISTS idx_sessao_refresh_expira_em ON sessao_refresh (expira_em);

-- ============================================================================
-- Referencia de NCM/CFOP — Sprint 2 issue #20 (migration 0007).
-- O perfil de teste desabilita o Liquibase (liquibase.enabled=false), entao o
-- seed via <insert> do changeset NAO roda nos testes. Replicamos as tabelas e o
-- seed curado aqui para que os validadores @ValidNcm/@ValidCfop tenham dados nos
-- testes de integracao que usam este script via @Sql.
-- Manter sincronizado com 0007-ncm-cfop-referencia.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS ncm_agropecuario (
    codigo                     VARCHAR(8)    NOT NULL,
    descricao                  VARCHAR(255)  NOT NULL,
    -- Produto in natura (LC 214/2025, art. 137) — issue #272 (migration 0048, que tirou o
    -- percentual_reducao_padrao hipotetico do 0012)
    in_natura                  BOOLEAN       NOT NULL DEFAULT FALSE,
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

-- Codigos conferidos contra a nomenclatura oficial (changeset 0046, issue #282); in_natura do
-- changeset 0048 (issue #272); arroz em casca in natura no 0051 (#284).
-- NcmSemeadoExisteNaNomenclaturaTest trava o espelho.
MERGE INTO ncm_agropecuario (codigo, descricao, in_natura) KEY (codigo) VALUES
    ('12019000', 'Soja, mesmo triturada, exceto para semeadura', TRUE),
    ('12011000', 'Soja para semeadura', FALSE),
    ('10059010', 'Milho em grao (exceto para semeadura)', TRUE),
    ('10051000', 'Milho para semeadura', FALSE),
    ('10011900', 'Trigo duro (exceto para semeadura)', TRUE),
    ('10061010', 'Arroz com casca (paddy) para semeadura', FALSE),
    ('10061092', 'Arroz com casca (paddy), sem parboilizacao', TRUE),
    ('10063021', 'Arroz semibranqueado ou branqueado, polido', FALSE),
    ('09011110', 'Cafe nao torrado, nao descafeinado, em grao', FALSE),
    ('17011400', 'Outros acucares de cana, em bruto', FALSE),
    ('52010020', 'Algodao em bruto, nao cardado nem penteado', TRUE),
    ('01022110', 'Bovinos reprodutores de raca pura, prenhes ou com cria', FALSE),
    ('01039100', 'Suinos vivos de peso inferior a 50 kg', FALSE),
    ('01039200', 'Suinos vivos de peso igual ou superior a 50 kg', FALSE),
    ('04072100', 'Ovos frescos de galinha (Gallus domesticus), com casca', FALSE),
    ('04072900', 'Outros ovos frescos de aves, com casca (exceto de galinha)', FALSE),
    ('04012010', 'Leite, teor de gordura entre 1% e 6%, em embalagem ate 1 litro', FALSE),
    ('08043000', 'Abacaxis (ananases) frescos ou secos', FALSE),
    ('07020000', 'Tomates frescos ou refrigerados', FALSE);

-- ============================================================================
-- Tabela oficial de classificacao tributaria do IBS/CBS — issue #272 (migration 0048).
-- Le os MESMOS CSVs que o loadData do Liquibase (db/changelog/data), entao o espelho nao
-- diverge do seed. MERGE: o script roda mais de uma vez no mesmo H2 (DB_CLOSE_DELAY=-1).
-- ============================================================================
CREATE TABLE IF NOT EXISTS classificacao_tributaria (
    c_class_trib            VARCHAR(6)     NOT NULL,
    cst                     VARCHAR(3)     NOT NULL,
    descricao               VARCHAR(1000)  NOT NULL,
    percentual_reducao_ibs  NUMERIC(5,2)   NOT NULL,
    percentual_reducao_cbs  NUMERIC(5,2)   NOT NULL,
    data_inicio             DATE           NOT NULL,
    data_fim                DATE,
    versao_tabela           VARCHAR(40)    NOT NULL,
    CONSTRAINT pk_classificacao_tributaria PRIMARY KEY (c_class_trib, data_inicio)
);

CREATE TABLE IF NOT EXISTS classificacao_tributaria_anexo_item (
    id            INTEGER        NOT NULL,
    c_class_trib  VARCHAR(6)     NOT NULL,
    anexo         INTEGER        NOT NULL,
    descricao     VARCHAR(1500)  NOT NULL,
    condicao      VARCHAR(255),
    excecao       VARCHAR(1000),
    CONSTRAINT pk_classificacao_tributaria_anexo_item PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS classificacao_tributaria_anexo_ncm (
    item_id      INTEGER      NOT NULL,
    ncm          VARCHAR(8)   NOT NULL,
    permissao    VARCHAR(10)  NOT NULL,
    data_inicio  DATE         NOT NULL,
    data_fim     DATE,
    CONSTRAINT pk_classificacao_tributaria_anexo_ncm
        PRIMARY KEY (item_id, ncm, permissao, data_inicio),
    CONSTRAINT fk_anexo_ncm_item FOREIGN KEY (item_id)
        REFERENCES classificacao_tributaria_anexo_item (id),
    CONSTRAINT ck_anexo_ncm_permissao CHECK (permissao IN ('PERMITIDO', 'VEDADO'))
);
CREATE INDEX IF NOT EXISTS idx_classificacao_tributaria_anexo_ncm_ncm
    ON classificacao_tributaria_anexo_ncm (ncm);

MERGE INTO classificacao_tributaria (c_class_trib, cst, descricao, percentual_reducao_ibs,
    percentual_reducao_cbs, data_inicio, data_fim, versao_tabela) KEY (c_class_trib, data_inicio)
    SELECT c_class_trib, cst, descricao, percentual_reducao_ibs, percentual_reducao_cbs,
           data_inicio, data_fim, versao_tabela
      FROM CSVREAD('classpath:db/changelog/data/classificacao-tributaria.csv', NULL,
                   'charset=UTF-8');

MERGE INTO classificacao_tributaria_anexo_item (id, c_class_trib, anexo, descricao, condicao,
    excecao) KEY (id)
    SELECT id, c_class_trib, anexo, descricao, condicao, excecao
      FROM CSVREAD('classpath:db/changelog/data/classificacao-tributaria-anexo-item.csv', NULL,
                   'charset=UTF-8');

MERGE INTO classificacao_tributaria_anexo_ncm (item_id, ncm, permissao, data_inicio, data_fim)
    KEY (item_id, ncm, permissao, data_inicio)
    SELECT item_id, ncm, permissao, data_inicio, data_fim
      FROM CSVREAD('classpath:db/changelog/data/classificacao-tributaria-anexo-ncm.csv', NULL,
                   'charset=UTF-8');

-- ============================================================================
-- Aliquota de IBS por UF — Sprint 4 issue #31 (migration 0013) + vigencia
-- temporal — Onda 2 issue #85 (migration 0019).
-- Parcela estadual + municipal por UF, com data_inicio/data_fim (NULL = aberta)
-- e PK composta (uf, data_inicio). Seed de referencia ano-teste replicado dos
-- changesets 0013 e 0019 (Liquibase desabilitado no perfil de teste).
-- Manter sincronizado com 0013-aliquota-ibs-uf.xml, 0020-aliquota-vigencia.xml e 0047-ibs-2026-estadual.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS aliquota_ibs_uf (
    uf                 VARCHAR(2)    NOT NULL,
    data_inicio        DATE          NOT NULL,
    data_fim           DATE,
    aliquota_estadual  NUMERIC(7,4)  NOT NULL,
    aliquota_municipal NUMERIC(7,4)  NOT NULL,
    CONSTRAINT pk_aliquota_ibs_uf PRIMARY KEY (uf, data_inicio),
    CONSTRAINT ck_aliquota_ibs_uf_vigencia CHECK (data_fim IS NULL OR data_fim >= data_inicio)
);

-- Vigencia de 2026 corrigida pela 0047 (#277): 0,1% estadual e 0% municipal (EC 132/2023,
-- NT 2026.010 item 2, IT 2025.002 v1.70); a divisao 0,05% + 0,05% vale em 2027-2028 (art. 344).
MERGE INTO aliquota_ibs_uf (uf, data_inicio, data_fim, aliquota_estadual, aliquota_municipal)
    KEY (uf, data_inicio) VALUES
    ('AC', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('AL', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('AM', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('AP', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('BA', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('CE', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('DF', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('ES', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('GO', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('MA', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('MG', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('MS', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('MT', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('PA', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('PB', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('PE', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('PI', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('PR', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('RJ', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('RN', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('RO', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('RR', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('RS', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('SC', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('SE', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000), ('SP', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('TO', DATE '2026-01-01', DATE '2026-12-31', 0.0010, 0.0000),
    ('AC', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('AL', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('AM', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('AP', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('BA', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('CE', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('DF', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('ES', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('GO', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('MA', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('MG', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('MS', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('MT', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('PA', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('PB', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('PE', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('PI', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('PR', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('RJ', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('RN', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('RO', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('RR', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('RS', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('SC', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('SE', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005), ('SP', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005),
    ('TO', DATE '2027-01-01', DATE '2028-12-31', 0.0005, 0.0005);

-- ============================================================================
-- Aliquota de CBS por vigencia — Onda 2 issue #85 (migration 0019).
-- Vigencia aberta de 2026 (0,90%, ano-teste). Manter sincronizado com
-- 0019-aliquota-vigencia.xml.
-- ============================================================================
CREATE TABLE IF NOT EXISTS aliquota_cbs (
    data_inicio DATE         NOT NULL,
    data_fim    DATE,
    aliquota    NUMERIC(7,4) NOT NULL,
    CONSTRAINT pk_aliquota_cbs PRIMARY KEY (data_inicio),
    CONSTRAINT ck_aliquota_cbs_vigencia CHECK (data_fim IS NULL OR data_fim >= data_inicio)
);

MERGE INTO aliquota_cbs (data_inicio, data_fim, aliquota) KEY (data_inicio) VALUES
    (DATE '2026-01-01', NULL, 0.0090);

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

-- Redefinicao de senha — Sprint 8 issue #209 (migration 0034). Manter sincronizado com
-- 0034-redefinicao-senha.xml.
CREATE TABLE IF NOT EXISTS redefinicao_senha (
    id             UUID                      NOT NULL,
    credencial_id  UUID                      NOT NULL,
    token_hash     VARCHAR(64)               NOT NULL,
    criado_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    expira_em      TIMESTAMP WITH TIME ZONE  NOT NULL,
    usado_em       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

ALTER TABLE redefinicao_senha ADD CONSTRAINT IF NOT EXISTS uk_redefinicao_senha_token_hash
    UNIQUE (token_hash);

ALTER TABLE redefinicao_senha ADD CONSTRAINT IF NOT EXISTS fk_redefinicao_senha_credencial
    FOREIGN KEY (credencial_id) REFERENCES credencial_usuario (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_redefinicao_senha_credencial ON redefinicao_senha (credencial_id);

-- Registro de auditoria — Sprint 8 issue #149 (migration 0035). Manter sincronizado com
-- 0035-registro-auditoria.xml. Sem FK, como em producao.
CREATE TABLE IF NOT EXISTS registro_auditoria (
    id             UUID                      NOT NULL,
    ocorrido_em    TIMESTAMP WITH TIME ZONE  NOT NULL,
    acao           VARCHAR(40)               NOT NULL,
    recurso        VARCHAR(20)               NOT NULL,
    recurso_id     UUID,
    usuario_id     UUID,
    papel          VARCHAR(20),
    produtor_id    UUID,
    escritorio_id  UUID,
    sucesso        BOOLEAN                   NOT NULL,
    codigo_erro    VARCHAR(60),
    estado_antes   VARCHAR(40),
    estado_depois  VARCHAR(40),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_registro_auditoria_produtor_ocorrido
    ON registro_auditoria (produtor_id, ocorrido_em);
CREATE INDEX IF NOT EXISTS idx_registro_auditoria_ocorrido ON registro_auditoria (ocorrido_em);
CREATE INDEX IF NOT EXISTS idx_registro_auditoria_escritorio
    ON registro_auditoria (escritorio_id, ocorrido_em);

-- Dados pessoais cifrados — Sprint 8 issue #147 (migration 0036): as colunas cpf_cnpj, email e
-- telefone de produtores/contadores e cpf_cnpj/email de credencial_usuario ja nascem alargadas nos
-- CREATE TABLE acima (100 / 512). Manter sincronizado com 0036-dados-pessoais-cifrados.xml.

-- Obrigacoes do calendario — issue #181 (migration 0040). Manter sincronizado com
-- 0040-obrigacao-fiscal.xml, inclusive os 11 modelos (MERGE: idempotente, o script roda por classe).
-- O indice unico parcial (uma versao por modelo e escritorio) e os CHECK sao so do PostgreSQL.
CREATE TABLE IF NOT EXISTS obrigacao_fiscal (
    id                    UUID                      NOT NULL,
    escritorio_id         UUID,
    modelo                BOOLEAN                   DEFAULT FALSE NOT NULL,
    modelo_id             UUID,
    titulo                VARCHAR(120)              NOT NULL,
    descricao             VARCHAR(500),
    periodicidade         VARCHAR(10)               NOT NULL,
    dia                   INTEGER                   NOT NULL,
    mes                   INTEGER,
    ajuste_fim_de_semana  VARCHAR(10)               DEFAULT 'NENHUM' NOT NULL,
    tipo_produtor         VARCHAR(20),
    regimes_tributarios   VARCHAR(60),
    origem                VARCHAR(200),
    ativa                 BOOLEAN                   DEFAULT TRUE NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE,
    updated_at            TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_obrigacao_fiscal_escritorio ON obrigacao_fiscal (escritorio_id);

ALTER TABLE obrigacao_fiscal ADD CONSTRAINT IF NOT EXISTS fk_obrigacao_fiscal_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;

ALTER TABLE obrigacao_fiscal ADD CONSTRAINT IF NOT EXISTS fk_obrigacao_fiscal_modelo
    FOREIGN KEY (modelo_id) REFERENCES obrigacao_fiscal (id) ON DELETE SET NULL;

MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000001', TRUE, 'DAS / PGDAS-D', 'Apuracao e recolhimento mensal do Simples Nacional', 'MENSAL', 20, NULL, 'NENHUM', 'PESSOA_JURIDICA', 'SIMPLES', 'LC 123/2006, art. 21', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000002', TRUE, 'DAS-MEI', 'Recolhimento mensal do MEI', 'MENSAL', 20, NULL, 'NENHUM', NULL, 'MEI', 'LC 123/2006, art. 18-A', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000003', TRUE, 'DASN-SIMEI', 'Declaracao anual do MEI', 'ANUAL', 31, 5, 'NENHUM', NULL, 'MEI', 'Res. CGSN 140/2018', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000004', TRUE, 'DEFIS', 'Declaracao de informacoes socioeconomicas e fiscais do Simples', 'ANUAL', 31, 3, 'NENHUM', 'PESSOA_JURIDICA', 'SIMPLES', 'Res. CGSN 140/2018', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000005', TRUE, 'DCTFWeb', 'Declaracao de debitos e creditos tributarios federais previdenciarios', 'MENSAL', 15, NULL, 'NENHUM', 'PESSOA_JURIDICA', 'PRESUMIDO,REAL', 'IN RFB 2.005/2021', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000006', TRUE, 'EFD-Reinf', 'Escrituracao fiscal digital de retencoes e outras informacoes fiscais', 'MENSAL', 15, NULL, 'NENHUM', 'PESSOA_JURIDICA', 'PRESUMIDO,REAL', 'IN RFB 2.043/2021', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000007', TRUE, 'Funrural (recolhimento)', 'Contribuicao do produtor rural sobre a comercializacao', 'MENSAL', 20, NULL, 'NENHUM', NULL, NULL, 'Lei 8.212/1991, art. 30', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000008', TRUE, 'DIRPF com atividade rural (+ LCDPR)', 'Declaracao do imposto de renda da pessoa fisica e livro caixa digital do produtor rural', 'ANUAL', 31, 5, 'ANTECIPA', 'PESSOA_FISICA', NULL, 'IN anual da RFB; IN RFB 1.848/2018', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000009', TRUE, 'DITR', 'Declaracao do imposto territorial rural (dono de imovel rural)', 'ANUAL', 30, 9, 'ANTECIPA', NULL, NULL, 'Lei 9.393/1996', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000010', TRUE, 'ECD', 'Escrituracao contabil digital (ultimo dia util de junho)', 'ANUAL', 31, 6, 'ANTECIPA', 'PESSOA_JURIDICA', 'PRESUMIDO,REAL', 'IN RFB 2.003/2021', TRUE);
MERGE INTO obrigacao_fiscal (id, modelo, titulo, descricao, periodicidade, dia, mes, ajuste_fim_de_semana, tipo_produtor, regimes_tributarios, origem, ativa) KEY (id) VALUES ('00000000-0000-4000-8181-000000000011', TRUE, 'ECF', 'Escrituracao contabil fiscal (ultimo dia util de julho)', 'ANUAL', 31, 7, 'ANTECIPA', 'PESSOA_JURIDICA', 'PRESUMIDO,REAL', 'IN RFB 2.004/2021', TRUE);

-- Historico de revisao da nota — issue #178 (migration 0041). Manter sincronizado com
-- 0041-nota-fiscal-revisao.xml.
CREATE TABLE IF NOT EXISTS nota_fiscal_revisao (
    id              UUID                      NOT NULL,
    nota_fiscal_id  UUID                      NOT NULL,
    acao            VARCHAR(20)               NOT NULL,
    motivo          VARCHAR(500),
    usuario_id      UUID,
    papel           VARCHAR(20),
    ocorrido_em     TIMESTAMP WITH TIME ZONE  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_nota_fiscal_revisao_nota
    ON nota_fiscal_revisao (nota_fiscal_id, ocorrido_em);

ALTER TABLE nota_fiscal_revisao ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_revisao_nota
    FOREIGN KEY (nota_fiscal_id) REFERENCES nota_fiscal (id) ON DELETE CASCADE;

-- Pendencias e documentos — issue #180 (migration 0042). Manter sincronizado com
-- 0042-pendencia.xml. BYTEA do PostgreSQL vira VARBINARY no H2.
CREATE TABLE IF NOT EXISTS pendencia (
    id               UUID                      NOT NULL,
    escritorio_id    UUID                      NOT NULL,
    produtor_id      UUID                      NOT NULL,
    titulo           VARCHAR(120)              NOT NULL,
    descricao        VARCHAR(1000),
    observacoes      VARCHAR(1000),
    data_limite      DATE,
    tipos_documento  VARCHAR(120),
    status           VARCHAR(20)               NOT NULL,
    motivo           VARCHAR(500),
    criado_por       UUID,
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_pendencia_escritorio_produtor
    ON pendencia (escritorio_id, produtor_id, status);
CREATE INDEX IF NOT EXISTS idx_pendencia_produtor_status ON pendencia (produtor_id, status);

ALTER TABLE pendencia ADD CONSTRAINT IF NOT EXISTS fk_pendencia_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;
ALTER TABLE pendencia ADD CONSTRAINT IF NOT EXISTS fk_pendencia_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;
ALTER TABLE pendencia ADD CONSTRAINT IF NOT EXISTS fk_pendencia_criado_por
    FOREIGN KEY (criado_por) REFERENCES contadores (id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS pendencia_anexo (
    id            UUID                      NOT NULL,
    pendencia_id  UUID                      NOT NULL,
    nome_arquivo  VARCHAR(120)              NOT NULL,
    mime          VARCHAR(40)               NOT NULL,
    tamanho       INTEGER                   NOT NULL,
    sha256        VARCHAR(64)               NOT NULL,
    conteudo      VARBINARY(5242880),
    enviado_por   UUID,
    enviado_em    TIMESTAMP WITH TIME ZONE  NOT NULL,
    -- Antivirus — issue #227 (migration 0043).
    situacao          VARCHAR(20)               DEFAULT 'EM_VERIFICACAO' NOT NULL,
    assinatura_virus  VARCHAR(200),
    verificado_em     TIMESTAMP WITH TIME ZONE,
    libera_pendencia  BOOLEAN                   DEFAULT FALSE NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_pendencia_anexo_pendencia
    ON pendencia_anexo (pendencia_id, enviado_em);

CREATE INDEX IF NOT EXISTS idx_pendencia_anexo_situacao
    ON pendencia_anexo (situacao, verificado_em);

ALTER TABLE pendencia_anexo ADD CONSTRAINT IF NOT EXISTS ck_pendencia_anexo_situacao
    CHECK (situacao IN ('EM_VERIFICACAO', 'LIMPO', 'INFECTADO'));

ALTER TABLE pendencia_anexo ADD CONSTRAINT IF NOT EXISTS ck_pendencia_anexo_conteudo
    CHECK ((situacao = 'INFECTADO' AND conteudo IS NULL)
        OR (situacao <> 'INFECTADO' AND conteudo IS NOT NULL));

ALTER TABLE pendencia_anexo ADD CONSTRAINT IF NOT EXISTS fk_pendencia_anexo_pendencia
    FOREIGN KEY (pendencia_id) REFERENCES pendencia (id) ON DELETE CASCADE;

-- Notificacao por WhatsApp (registro) — issue #182 (migration 0044).
CREATE TABLE IF NOT EXISTS notificacao (
    id                UUID                      NOT NULL,
    produtor_id       UUID                      NOT NULL,
    escritorio_id     UUID,
    disparado_por     UUID,
    papel             VARCHAR(20),
    tipo              VARCHAR(30)               NOT NULL,
    canal             VARCHAR(20)               NOT NULL,
    status            VARCHAR(20)               NOT NULL,
    texto             VARCHAR(4000)             NOT NULL,
    quantidade_itens  INTEGER                   NOT NULL,
    urgentes          INTEGER                   NOT NULL,
    chave_item        VARCHAR(120),
    criado_em         TIMESTAMP WITH TIME ZONE  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_notificacao_produtor_criado ON notificacao (produtor_id, criado_em);

CREATE INDEX IF NOT EXISTS idx_notificacao_criado ON notificacao (criado_em);

ALTER TABLE notificacao ADD CONSTRAINT IF NOT EXISTS uk_notificacao_produtor_chave
    UNIQUE (produtor_id, chave_item);

ALTER TABLE notificacao ADD CONSTRAINT IF NOT EXISTS fk_notificacao_produtor
    FOREIGN KEY (produtor_id) REFERENCES produtores (id) ON DELETE CASCADE;

ALTER TABLE notificacao ADD CONSTRAINT IF NOT EXISTS fk_notificacao_escritorio
    FOREIGN KEY (escritorio_id) REFERENCES escritorio (id) ON DELETE CASCADE;
