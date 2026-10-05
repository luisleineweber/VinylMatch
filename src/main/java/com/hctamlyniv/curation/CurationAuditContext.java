package com.hctamlyniv.curation;

public record CurationAuditContext(String actor, String reason, String correlationId) {
    public CurationAuditContext {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        if (correlationId == null || correlationId.isBlank()) correlationId = "unknown";
    }
}
