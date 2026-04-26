package dev.jamjet.cloud;

/**
 * A named agent identity. Equal-by-name within a project.
 *
 * <p>Use {@link #enter()} for try-with-resources scoping; the SDK tags every
 * span created inside the resource with this agent.
 *
 * <pre>{@code
 *   Agent researcher = JamjetCloud.agent("research-bot",
 *       "https://acme.example.com/agents/research", "Retrieves sources");
 *   try (Agent.Scope ignored = researcher.enter()) {
 *       // chatClient.prompt(...)  ← tagged research-bot
 *   }
 * }</pre>
 */
public final class Agent {

    private final String name;
    private final String cardUri;
    private final String description;

    Agent(String name, String cardUri, String description) {
        this.name = name;
        this.cardUri = cardUri;
        this.description = description;
    }

    public String name() { return name; }
    public String cardUri() { return cardUri; }
    public String description() { return description; }

    /**
     * Enter this agent's scope. Returns a Scope which restores the previous
     * agent when closed. Safe across virtual threads (uses a ScopedValue
     * internally — falls back to ThreadLocal on JDK 21 where ScopedValue is
     * preview).
     */
    public Scope enter() {
        Agent previous = AgentContext.current();
        AgentContext.set(this);
        return new Scope(previous);
    }

    /** Try-with-resources handle for {@link #enter()}. */
    public static final class Scope implements AutoCloseable {
        private final Agent previous;
        Scope(Agent previous) {
            this.previous = previous;
        }
        @Override
        public void close() {
            AgentContext.set(previous);
        }
    }
}
