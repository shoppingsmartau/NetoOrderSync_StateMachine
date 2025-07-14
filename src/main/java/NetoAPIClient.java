// No package declaration for default package
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONArray; // Added for parsing order arrays
import org.json.JSONObject;

/**
 * A Java client for interacting with the Neto API to fetch orders.
 */
public class NetoAPIClient {

    // Define timeouts for HTTP connections (in milliseconds)
    public static final int CONNECT_TIMEOUT_MS = 5000; // 5 seconds
    public static final int READ_TIMEOUT_MS = 30000;  // Increased to 30 seconds for Neto API calls

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
                    .header("NETOAPI_ACTION", "GetOrder") // *** CHANGED TO GETORDER FOR TESTING ***
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
}
