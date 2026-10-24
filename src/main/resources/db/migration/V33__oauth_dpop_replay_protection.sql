CREATE TABLE oauth_dpop_used_proofs (
    proof_hash VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_oauth_dpop_used_proofs_expiry ON oauth_dpop_used_proofs(expires_at);
