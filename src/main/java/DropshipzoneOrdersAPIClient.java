// No package declaration for default package
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * A Java client for interacting with the Dropshipzone Orders API.
 */
public class DropshipzoneOrdersAPIClient {

    private static final String AUTH_URL = "https://api.dropshipzone.com.au/auth";
    private static final String ORDERS_BASE_URL = "https://api.dropshipzone.com.au/orders";

    public static final int CONNECT_TIMEOUT_MS = 5000; // 5 seconds
    public static final int READ_TIMEOUT_MS = 30000;  // 30 seconds for API calls

    /**
     * Authenticates with the Dropshipzone API using predefined credentials.
     * Credentials are retrieved from environment variables.
     *
     * @param httpClient The shared HttpClient instance to use for the request.
     * @return The JWT token string if authentication is successful, otherwise null.
     * @throws IOException If an I/O error occurs during the HTTP request.
     * @throws InterruptedException If the operation is interrupted.
     */
    public static String authenticate(HttpClient httpClient) throws IOException, InterruptedException {
        String email = System.getenv("DROPSHIPZONE_EMAIL");
        String password = System.getenv("DROPSHIPZONE_PASSWORD");

        if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
            System.err.println("Error: Dropshipzone credentials (DROPSHIPZONE_EMAIL, DROPSHIPZONE_PASSWORD) not set as environment variables.");
            return null;
        }

        JSONObject jsonInput = new JSONObject()
                .put("email", email)
                .put("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(AUTH_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonInput.toString()))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int responseCode = response.statusCode();
        if (responseCode != 200) {
            System.err.println("Dropshipzone Authentication failed with response code: " + responseCode);
            System.err.println("Dropshipzone Authentication Error Details: " + response.body());
            return null;
        }

        JSONObject obj;
        try {
            obj = new JSONObject(response.body());
        } catch (org.json.JSONException jsonE) {
            System.err.println("ERROR: Failed to parse JSON for Dropshipzone token extraction.");
            System.err.println("Raw JSON content that failed parsing: " + response.body());
            jsonE.printStackTrace();
            return null;
        }
        return obj.optString("token", null);
    }

    /**
     * Fetches orders from the Dropshipzone Orders API using the GET method with specific filters.
     *
     * @param httpClient The shared HttpClient instance.
     * @param token The JWT token for authentication.
     * @param orderIds A list of specific order IDs to filter by. Can be null or empty.
     * @param startDate The start date for filtering orders (YYYY-MM-DD). Can be null or empty.
     * @param endDate The end date for filtering orders (YYYY-MM-DD). Can be null or empty.
     * @return A JSONArray of orders if successful, otherwise null.
     * @throws IOException If an I/O error occurs during the HTTP request.
     * @throws InterruptedException If the operation is interrupted.
     */
    public static JSONArray getOrders(HttpClient httpClient, String token,
                                      List<String> orderIds, String startDate, String endDate)
            throws IOException, InterruptedException {
        if (token == null || token.isEmpty()) {
            System.err.println("Error: Dropshipzone API token is missing. Cannot fetch orders.");
            return null;
        }

        StringBuilder queryParams = new StringBuilder();
        queryParams.append("?"); // Start query parameters

        boolean firstParam = true; // Helper to manage '&'

        // Add order_ids filter
        if (orderIds != null && !orderIds.isEmpty()) {
            if (!firstParam) queryParams.append("&");
            queryParams.append("order_ids=").append(URLEncoder.encode(String.join(",", orderIds), StandardCharsets.UTF_8.toString()));
            firstParam = false;
        }

        // Add start_date filter
        if (startDate != null && !startDate.isEmpty()) {
            if (!firstParam) queryParams.append("&");
            queryParams.append("start_date=").append(URLEncoder.encode(startDate, StandardCharsets.UTF_8.toString()));
            firstParam = false;
        }

        // Add end_date filter
        if (endDate != null && !endDate.isEmpty()) {
            if (!firstParam) queryParams.append("&");
            queryParams.append("end_date=").append(URLEncoder.encode(endDate, StandardCharsets.UTF_8.toString()));
            firstParam = false;
        }

        String requestUrl = ORDERS_BASE_URL + queryParams.toString();
        System.out.println("Dropshipzone Orders API Request URL: " + requestUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(requestUrl))
                .header("Authorization", "jwt " + token) // Use JWT token for authorization
                .GET() // Explicitly using GET method
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int responseCode = response.statusCode();
        String rawResponse = response.body();

        System.out.println("Dropshipzone Orders API Response Code: " + responseCode);
        System.out.println("Dropshipzone Orders API Raw Response: " + rawResponse);

        if (responseCode != 200) {
            System.err.println("Error fetching orders from Dropshipzone. Status: " + responseCode);
            System.err.println("Error Details: " + rawResponse);
            // Attempt to parse error message from array format
            try {
                JSONArray errorArray = new JSONArray(rawResponse);
                if (errorArray.length() > 0) {
                    JSONObject errorObj = errorArray.getJSONObject(0);
                    System.err.println("Dropshipzone API Error Message: " + errorObj.optString("errmsg", "Unknown error"));
                }
            } catch (org.json.JSONException jsonE) {
                System.err.println("Could not parse Dropshipzone error response as JSON Array: " + jsonE.getMessage());
            }
            return null;
        }

        // --- Robust JSON Parsing for Dropshipzone Response ---
        // First, try to parse as a JSONArray (for success or some error formats)
        // Then, if it's not an array, try as a JSONObject (for other success formats)
        try {
            JSONArray orders = new JSONArray(rawResponse);
            // If it's an array, check if it's an error message or actual data
            if (orders.length() > 0 && orders.getJSONObject(0).has("status") && orders.getJSONObject(0).optInt("status") == -1) {
                JSONObject errorObj = orders.getJSONObject(0);
                System.err.println("Dropshipzone API returned an error array: " + errorObj.optString("errmsg", "Unknown error"));
                return null;
            }
            // If it's an array but not an error, assume it's the orders data directly
            System.out.println("Successfully fetched " + orders.length() + " orders from Dropshipzone (as direct array).");
            return orders;

        } catch (org.json.JSONException e_array) {
            // If it's not a JSONArray, try parsing as a JSONObject
            try {
                JSONObject dropshipzoneResponseJson = new JSONObject(rawResponse);
                // Dropshipzone API might return a 'data' array or 'orders' array within an object
                JSONArray orders = dropshipzoneResponseJson.optJSONArray("data");
                if (orders == null) {
                    orders = dropshipzoneResponseJson.optJSONArray("orders"); // Common alternative
                }
                if (orders != null) {
                    System.out.println("Successfully fetched " + orders.length() + " orders from Dropshipzone (as object with 'data'/'orders').");
                    return orders;
                } else {
                    System.err.println("Dropshipzone response was successful but contained no 'data' or 'orders' array in object format.");
                    return new JSONArray(); // Return empty array if no orders found in expected structure
                }
            } catch (org.json.JSONException e_object) {
                System.err.println("ERROR: Failed to parse Dropshipzone Orders API response JSON as either Array or Object.");
                System.err.println("Raw JSON content that failed parsing: " + rawResponse);
                e_object.printStackTrace();
                return null;
            }
        }
    }
}
