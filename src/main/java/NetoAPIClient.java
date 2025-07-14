// No package declaration for default package
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONArray; // Added for parsing order arrays
import org.json.JSONObject;

/**
 * A Java client for interacting with the Neto API to fetch orders and update order tracking.
 */
public class NetoAPIClient {

    // Define timeouts for HTTP connections (in milliseconds)
    public static final int CONNECT_TIMEOUT_MS = 5000; // 5 seconds
    public static final int READ_TIMEOUT_MS = 30000;  // 30 seconds for Neto API calls

    // The Neto API URL as specified by the user
    private static final String NETO_API_URL = "https://www.shoppingsmart.com.au/do/WS/NetoAPI";

    /**
     * Fetches orders from Neto based on the provided filter and output selectors.
     * Credentials (NETOAPI_USERNAME, NETOAPI_KEY) are retrieved from environment variables.
     * The API action used is "GetOrder" for this test.
     *
     * @param httpClient The shared HttpClient instance to use for the request.
     * @param filterPayload The JSONObject containing the "Filter" and "OutputSelector" for the request.
     * @return A JSONArray of orders if successful, otherwise null.
     */
    public static JSONArray getOrders(HttpClient httpClient, JSONObject filterPayload) {
        String netoUsername = System.getenv("NETOAPI_USERNAME");
        String netoKey = System.getenv("NETOAPI_KEY");

        if (netoUsername == null || netoKey == null || netoUsername.isEmpty() || netoKey.isEmpty()) {
            System.err.println("Error: Neto credentials (NETOAPI_USERNAME, NETOAPI_KEY) not set as environment variables. Skipping Neto order fetch.");
            return null;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(NETO_API_URL))
                    .header("NETOAPI_ACTION", "GetOrder") // Using GetOrder for fetching
                    .header("NETOAPI_USERNAME", netoUsername)
                    .header("NETOAPI_KEY", netoKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(filterPayload.toString()))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS)) // Using the increased timeout
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int responseCode = response.statusCode();
            String rawResponse = response.body();

            System.out.println("Neto API Order Fetch Response Code: " + responseCode);
            System.out.println("Neto API Order Fetch Raw Response: " + rawResponse);

            if (responseCode != 200) {
                System.err.println("Error fetching orders from Neto. Status: " + responseCode);
                System.err.println("Error Details: " + rawResponse);
                return null;
            }

            JSONObject netoResponseJson = new JSONObject(rawResponse);
            String ack = netoResponseJson.optString("Ack", "Error");

            if ("Success".equalsIgnoreCase(ack)) {
                JSONArray orders = netoResponseJson.optJSONArray("Order");
                if (orders != null) {
                    System.out.println("Successfully fetched " + orders.length() + " orders from Neto.");
                    return orders;
                } else {
                    System.out.println("Neto response was successful but contained no 'Order' array.");
                    return new JSONArray(); // Return empty array if no orders
                }
            } else {
                System.err.println("Neto API returned non-success Acknowledgment: " + ack);
                JSONArray errors = netoResponseJson.optJSONArray("Errors");
                if (errors != null) {
                    System.err.println("Neto Errors: " + errors.toString());
                }
                return null;
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Error calling Neto API to fetch orders: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Updates order tracking details in Neto.
     *
     * @param httpClient The shared HttpClient instance.
     * @param orderId The OrderID of the Neto order to update.
     * @param orderStatus The new OrderStatus (e.g., "Dispatched").
     * @param sendOrderEmail Whether to send a tracking email (e.g., "tracking").
     * @param sku The SKU of the order line item to update tracking for.
     * @param shippingMethod The shipping method (e.g., "TEAM GLOBAL EXPRESS").
     * @param trackingNumber The tracking number.
     * @param dateShipped The date and time the order was shipped (YYYY-MM-DD HH:MM:SS).
     */
    public static void updateOrderTracking(HttpClient httpClient, String orderId, String orderStatus,
                                           String sendOrderEmail, String sku, String shippingMethod,
                                           String trackingNumber, String dateShipped) {
        String netoUsername = System.getenv("NETOAPI_USERNAME");
        String netoKey = System.getenv("NETOAPI_KEY");

        if (netoUsername == null || netoKey == null || netoUsername.isEmpty() || netoKey.isEmpty()) {
            System.err.println("Error: Neto credentials not set. Cannot update Neto order tracking for OrderID: " + orderId);
            return;
        }

        JSONObject trackingDetails = new JSONObject()
                .put("ShippingMethod", shippingMethod)
                .put("TrackingNumber", trackingNumber)
                .put("DateShipped", dateShipped);

        JSONObject orderLineItem = new JSONObject();
        if (sku != null && !sku.isEmpty()) {
            orderLineItem.put("SKU", sku); // Include SKU in the OrderLine
        }
        orderLineItem.put("TrackingDetails", trackingDetails);

        JSONArray orderLines = new JSONArray();
        orderLines.put(orderLineItem);


        JSONObject order = new JSONObject()
                .put("OrderID", orderId)
                .put("OrderStatus", orderStatus)
                .put("SendOrderEmail", sendOrderEmail)
                .put("OrderLine", orderLines); // Include OrderLine with TrackingDetails

        JSONObject payload = new JSONObject()
                .put("Order", order);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(NETO_API_URL))
                    .header("NETOAPI_ACTION", "UpdateOrder") // Action for updating order
                    .header("NETOAPI_USERNAME", netoUsername)
                    .header("NETOAPI_KEY", netoKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int responseCode = response.statusCode();
            String rawResponse = response.body();

            System.out.println(String.format("Neto API Order Tracking Update for OrderID %s (Code: %d): %s",
                orderId, responseCode, rawResponse));

            JSONObject netoResponseJson = new JSONObject(rawResponse);
            String ack = netoResponseJson.optString("Ack", "Error");

            if ("Success".equalsIgnoreCase(ack)) {
                System.out.println("Successfully updated Neto order tracking for OrderID: " + orderId);
            } else {
                System.err.println("Neto API returned non-success Acknowledgment for OrderID " + orderId + ": " + ack);
                JSONArray errors = netoResponseJson.optJSONArray("Errors");
                if (errors != null) {
                    System.err.println("Neto Errors for OrderID " + orderId + ": " + errors.toString());
                }
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Error calling Neto API to update order tracking for OrderID " + orderId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
