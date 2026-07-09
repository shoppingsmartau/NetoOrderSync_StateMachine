import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONArray;
import org.json.JSONObject;

public class NetoAPIClient {
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 30000;
    private static final String NETO_API_URL = "https://www.shoppingsmart.com.au/do/WS/NetoAPI";

    public static JSONArray getOrders(HttpClient httpClient, JSONObject filterPayload) {
        String netoUsername = System.getenv("NETOAPI_USERNAME");
        String netoKey = System.getenv("NETOAPI_KEY");

        if (netoUsername == null || netoKey == null || netoUsername.isEmpty() || netoKey.isEmpty()) {
            System.err.println("Error: Neto credentials not set.");
            return null;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(NETO_API_URL))
                    .header("NETOAPI_ACTION", "GetOrder")
                    .header("NETOAPI_USERNAME", netoUsername)
                    .header("NETOAPI_KEY", netoKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(filterPayload.toString()))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Neto API Order Fetch Response Code: " + response.statusCode());
            System.out.println("Neto API Order Fetch Raw Response: " + response.body());

            if (response.statusCode() != 200) {
                return null;
            }

            JSONObject json = new JSONObject(response.body());
            String ack = json.optString("Ack", "Error");

            if (!ack.equalsIgnoreCase("Success")) {
                System.err.println("Neto Error: " + json.toString());
                return null;
            }

            return json.optJSONArray("Order");

        } catch (IOException | InterruptedException e) {
            System.err.println("Error calling Neto API: " + e.getMessage());
            return null;
        }
    }

    public static void updateOrderTracking(
            HttpClient httpClient,
            String orderId,
            String orderStatus,
            String sendOrderEmail,
            String sku,
            String shippingMethod,
            String trackingNumber,
            String dateShipped
    ) {

        String netoUsername = System.getenv("NETOAPI_USERNAME");
        String netoKey = System.getenv("NETOAPI_KEY");

        if (netoUsername == null || netoKey == null) {
            System.err.println("Error: Neto credentials not set.");
            return;
        }

        try {
            JSONObject trackingDetails = new JSONObject()
                    .put("ShippingMethod", shippingMethod)
                    .put("TrackingNumber", trackingNumber)
                    .put("DateShipped", dateShipped);

            JSONObject orderLineItem = new JSONObject();
            if (sku != null && !sku.isEmpty()) {
                orderLineItem.put("SKU", sku);
            }
            orderLineItem.put("TrackingDetails", trackingDetails);

            JSONArray orderLines = new JSONArray();
            orderLines.put(orderLineItem);

            JSONObject order = new JSONObject()
                    .put("OrderID", orderId)
                    .put("OrderStatus", orderStatus)
                    .put("WarehouseID", "5")        // ⭐ REQUIRED FOR STATUS CHANGE
                    .put("SendOrderEmail", sendOrderEmail)
                    .put("OrderLine", orderLines);

            JSONObject payload = new JSONObject().put("Order", order);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(NETO_API_URL))
                    .header("NETOAPI_ACTION", "UpdateOrder")
                    .header("NETOAPI_USERNAME", netoUsername)
                    .header("NETOAPI_KEY", netoKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("===== NETO UPDATE RESPONSE =====");
            System.out.println(response.body());

        } catch (IOException | InterruptedException e) {
            System.err.println("Error updating Neto order: " + e.getMessage());
        }
    }
}
