package com.hctamlyniv.curation;

public final class CurationVersionConflictException extends RuntimeException {
    private final long expectedVersion;
    private final long actualVersion;

    public CurationVersionConflictException(long expectedVersion, long actualVersion) {
        super("Curation version conflict: expected " + expectedVersion + " but was " + actualVersion);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long expectedVersion() { return expectedVersion; }
    public long actualVersion() { return actualVersion; }
}
