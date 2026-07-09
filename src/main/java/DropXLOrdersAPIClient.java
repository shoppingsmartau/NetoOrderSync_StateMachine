import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import org.json.JSONArray;

import com.amazonaws.services.lambda.runtime.Context;

public class DropXLOrdersAPIClient {

    private static final String BASE_URL = System.getenv("DROPXL_BASE_URL");
    private static final String ORDERS_URL = BASE_URL + "api_customer/orders";

    public static JSONArray getOrders(HttpClient httpClient, String email, String token,
                                      String startDate, String endDate, Context context)
            throws IOException, InterruptedException {

        String auth = Base64.getEncoder().encodeToString((email + ":" + token).getBytes());

        StringBuilder url = new StringBuilder(ORDERS_URL);
        url.append("?submitted_at_gteq=").append(URLEncoder.encode(startDate, StandardCharsets.UTF_8));
        url.append("&submitted_at_lteq=").append(URLEncoder.encode(endDate, StandardCharsets.UTF_8));

        context.getLogger().log("DropXL Request URL: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url.toString()))
                .header("Authorization", "Basic " + auth)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .GET()
                .timeout(Duration.ofMillis(30000))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        context.getLogger().log("DropXL Response Code: " + response.statusCode());
        context.getLogger().log("DropXL Raw Response: " + response.body());

        if (response.statusCode() != 200) {
            return new JSONArray();
        }

        return new JSONArray(response.body());
    }
}
