package dev.jamjet.agent.audit;

/**
 * An audit entry failed verification. {@link #index()} is the entry's position in the list
 * that was checked; {@link #reason()} says why.
 */
public final class AuditChainException extends Exception {

    /** Why an entry failed. {@link #wire()} is the name the Python verifier uses. */
    public enum Reason {
        /** No {@code entry_hash}: the runtime never sealed it. */
        UNSEALED("unsealed"),
        /** No {@code signature}. */
        UNSIGNED("unsigned"),
        /** {@code prev_hash} is not the previous entry's hash, or the set is not one chain. */
        BROKEN_LINK("broken_link"),
        /** The fields do not hash to {@code entry_hash}: the entry was changed. */
        HASH_MISMATCH("hash_mismatch"),
        /** Not a valid Ed25519 signature, by this key, in the runtime's exact form. */
        BAD_SIGNATURE("bad_signature"),
        /** Signed under another key id. */
        WRONG_KEY("wrong_key"),
        /** A field the Rust runtime would not read (wrong JSON type, non-canonical UUID). */
        MALFORMED("malformed");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final int index;
    private final Reason reason;

    public AuditChainException(int index, Reason reason, String detail) {
        super("audit entry " + index + " failed: " + reason.wire()
                + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        this.index = index;
        this.reason = reason;
    }

    public int index() {
        return index;
    }

    public Reason reason() {
        return reason;
    }
}
