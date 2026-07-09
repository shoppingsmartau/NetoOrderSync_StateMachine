import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public class LambdaHandler implements RequestHandler<Object, String> {

    private final HttpClient httpClient;
    private final Map<String, String> dropxlShippingMethodMap;

    public LambdaHandler() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(NetoAPIClient.CONNECT_TIMEOUT_MS))
                .build();

        this.dropxlShippingMethodMap = new HashMap<>();

        String mapString = System.getenv("DROPXL_SHIPPING_METHOD_MAP");
        if (mapString != null && !mapString.isEmpty()) {
            String[] pairs = mapString.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":");
                if (keyValue.length == 2) {
                    dropxlShippingMethodMap.put(keyValue[0].trim(), keyValue[1].trim());
                } else {
                    System.err.println("Warning: Invalid DROPXL_SHIPPING_METHOD_MAP entry: " + pair);
                }
            }
        }

        System.out.println("DropXL Shipping Method Map loaded: " + dropxlShippingMethodMap);
    }

    @Override
    public String handleRequest(Object input, Context context) {

        HttpClient httpClient = this.httpClient;

        // --- Fetch Neto Pack Orders ---
        JSONObject netoFilterPayload = new JSONObject();
        JSONObject netoFilter = new JSONObject();

        JSONArray status = new JSONArray();
        status.put("Pack");
        netoFilter.put("OrderStatus", status);

        JSONArray output = new JSONArray();
        output.put("OrderID");
        output.put("OrderStatus");
        output.put("OrderLine");
        output.put("OrderLine.SKU");

        netoFilter.put("OutputSelector", output);
        netoFilterPayload.put("Filter", netoFilter);

        JSONArray netoOrders = NetoAPIClient.getOrders(httpClient, netoFilterPayload);

        if (netoOrders == null) {
            context.getLogger().log("Neto returned null or error.");
            return "Neto error";
        }

        context.getLogger().log("Total Neto orders fetched: " + netoOrders.length());

        // --- Fetch DropXL Orders ---
        String email = System.getenv("DROPXL_EMAIL");
        String token = System.getenv("DROPXL_TOKEN");

        String startDate = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_DATE);
        String endDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        JSONArray dropxlOrders;
        try {
            dropxlOrders = DropXLOrdersAPIClient.getOrders(httpClient, email, token, startDate, endDate, context);
        } catch (Exception e) {
            context.getLogger().log("DropXL error: " + e.getMessage());
            return "DropXL error";
        }

        context.getLogger().log("Total DropXL orders fetched: " + dropxlOrders.length());

        // --- Match Neto OrderID ↔ DropXL customer_order_reference ---
        int updatedCount = 0;

        for (int i = 0; i < netoOrders.length(); i++) {

            JSONObject netoOrder = netoOrders.getJSONObject(i);
            String netoOrderId = netoOrder.optString("OrderID");

            // Extract SKU
            String sku = "";
            JSONArray lines = netoOrder.optJSONArray("OrderLine");
            if (lines != null && lines.length() > 0) {
                sku = lines.getJSONObject(0).optString("SKU", "");
            }

            // Search for matching DropXL order
            for (int j = 0; j < dropxlOrders.length(); j++) {

                JSONObject dropOrder = dropxlOrders.getJSONObject(j).getJSONObject("order");
                String dropRef = dropOrder.optString("customer_order_reference");

                if (dropRef.equals(netoOrderId)) {

                    String tracking = dropOrder.optString("shipping_tracking");
                    String dropxlCarrierName = dropOrder.optString("shipping_option_name");
                    String sentDate = dropOrder.optString("sent_date");

                    // --- Apply Carrier Name → Neto Shipping Method mapping ---
                    String netoShippingMethod =
                            dropxlShippingMethodMap.getOrDefault(dropxlCarrierName, dropxlCarrierName);

                    if (!dropxlShippingMethodMap.containsKey(dropxlCarrierName)) {
                        context.getLogger().log("No mapping found for DropXL carrier '" +
                                dropxlCarrierName + "'. Using original carrier name.");
                    }

                    // --- Update Neto ---
                    NetoAPIClient.updateOrderTracking(
                            httpClient,
                            netoOrderId,
                            "Dispatched",
                            "tracking",
                            sku,
                            netoShippingMethod,
                            tracking,
                            sentDate
                    );

                    updatedCount++;
                }
            }
        }

        context.getLogger().log("Total Neto orders updated: " + updatedCount);

        return "OK";
    }
}
