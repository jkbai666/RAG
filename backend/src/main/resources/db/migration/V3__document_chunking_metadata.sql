ALTER TABLE rag_documents
 ADD COLUMN chunking_strategy VARCHAR(16) DEFAULT 'LEGACY';
ALTER TABLE rag_documents
 ADD COLUMN chunking_fallback_reason VARCHAR(128) DEFAULT NULL;
