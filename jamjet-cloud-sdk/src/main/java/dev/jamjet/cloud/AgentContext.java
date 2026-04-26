package dev.jamjet.cloud;

/**
 * ThreadLocal-backed current-agent context. Functional equivalent of the
 * Python SDK's ContextVar approach. Keeps the thread-confinement model so
 * virtual threads work without surprises.
 *
 * <p>Future migration to ScopedValue (JDK 25 preview) is a one-line swap.
 */
final class AgentContext {

    private static final ThreadLocal<Agent> CURRENT = new ThreadLocal<>();
    private static volatile Agent defaultAgent;

    private AgentContext() {
    }

    static Agent current() {
        Agent t = CURRENT.get();
        return t != null ? t : defaultAgent;
    }

    static void set(Agent agent) {
        if (agent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(agent);
        }
    }

    static void setDefault(Agent agent) {
        defaultAgent = agent;
    }
}
