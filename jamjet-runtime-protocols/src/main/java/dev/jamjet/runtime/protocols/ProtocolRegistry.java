package dev.jamjet.runtime.protocols;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping protocol names to {@link ProtocolAdapter} instances.
 *
 * <p>Registrations are last-writer-wins: registering a new adapter under an
 * already-registered protocol name replaces the previous entry (after logging a
 * warning).</p>
 */
public final class ProtocolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProtocolRegistry.class);

    private final ConcurrentHashMap<String, ProtocolAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * Registers the given adapter under its {@link ProtocolAdapter#protocol()} name.
     *
     * @param name    protocol name key (must match {@code adapter.protocol()})
     * @param adapter the adapter to register
     * @throws IllegalArgumentException if {@code name} is null or blank
     * @throws NullPointerException     if {@code adapter} is null
     */
    public void register(String name, ProtocolAdapter adapter) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Protocol name must not be null or blank");
        }
        if (adapter == null) {
            throw new NullPointerException("adapter must not be null");
        }
        ProtocolAdapter previous = adapters.put(name, adapter);
        if (previous != null) {
            log.warn("Replacing existing ProtocolAdapter for protocol '{}': {} -> {}",
                    name, previous.getClass().getSimpleName(), adapter.getClass().getSimpleName());
        } else {
            log.debug("Registered ProtocolAdapter for protocol '{}'", name);
        }
    }

    /**
     * Looks up the adapter registered under the given protocol name.
     *
     * @param protocol the protocol name to look up
     * @return the adapter, or {@link Optional#empty()} if none is registered
     */
    public Optional<ProtocolAdapter> get(String protocol) {
        return Optional.ofNullable(adapters.get(protocol));
    }

    /**
     * Returns an immutable snapshot of the currently registered protocol names.
     *
     * @return set of protocol names
     */
    public Set<String> registeredProtocols() {
        return Set.copyOf(adapters.keySet());
    }
}
