CREATE TABLE rag_documents (
 id VARCHAR(36) PRIMARY KEY,
 file_name VARCHAR(255) NOT NULL,
 format VARCHAR(8) NOT NULL,
 size_bytes BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL,
 chunk_count INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL,
 error_message VARCHAR(512),
 cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE,
 deletion_requested BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_rag_documents_created ON rag_documents(created_at);
CREATE TABLE rag_chunks (
 id VARCHAR(36) PRIMARY KEY,
 document_id VARCHAR(36) NOT NULL,
 chunk_index INT NOT NULL,
 text MEDIUMTEXT NOT NULL,
 page_start INT,
 page_end INT,
 CONSTRAINT fk_rag_chunks_document FOREIGN KEY (document_id) REFERENCES rag_documents(id) ON DELETE CASCADE,
 CONSTRAINT uq_rag_chunks_index UNIQUE(document_id, chunk_index)
);
CREATE TABLE rag_settings (setting_key VARCHAR(64) PRIMARY KEY, setting_value VARCHAR(512) NOT NULL);
