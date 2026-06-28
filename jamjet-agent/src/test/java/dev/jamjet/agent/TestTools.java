package dev.jamjet.agent;

/**
 * Tool-holder fixtures shared by the agent tests. The two tools mirror the Python
 * golden fixture used to generate {@code golden/agent_loop_ir.python.json}:
 * {@code web_search(query: str) -> str} and {@code add_numbers(a: int, b: int) -> str}.
 */
public final class TestTools {

    private TestTools() {}

    /** One @Tool method: {@code web_search(query)}. */
    public static final class WebSearchTool {
        @Tool(name = "web_search", description = "Search the web for a query.")
        public String webSearch(String query) {
            return "results for " + query;
        }
    }

    /** One @Tool method: {@code add_numbers(a, b)}. */
    public static final class MathTool {
        @Tool(name = "add_numbers", description = "Add two integers.")
        public String addNumbers(int a, int b) {
            return String.valueOf(a + b);
        }
    }

    /** Two @Tool methods in one holder, plus a non-tool method, for ordering tests. */
    public static final class MultiTool {
        @Tool(description = "Z tool.")
        public String zebra(String s) {
            return s;
        }

        @Tool(description = "A tool.")
        public boolean alpha(boolean flag) {
            return flag;
        }

        public String notATool(String ignored) {
            return ignored;
        }
    }
}
