package dev.jamjet.example.cloud;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class LookupTool {
    @Tool(description = "Look up the shipping status of an order by its id.")
    public String orderStatus(@ToolParam(description = "The order id, e.g. A-100") String orderId) {
        return "Order " + orderId + " is shipped.";
    }
}
