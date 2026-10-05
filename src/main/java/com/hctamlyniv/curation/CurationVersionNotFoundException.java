package com.hctamlyniv.curation;

public final class CurationVersionNotFoundException extends RuntimeException {
    public CurationVersionNotFoundException(long version) {
        super("Curation version not found: " + version);
    }
}
