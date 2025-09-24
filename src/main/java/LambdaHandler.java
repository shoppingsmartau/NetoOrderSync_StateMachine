import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AWS Lambda handler for synchronizing order tracking from Dropshipzone to Neto.
 * Triggered by a ScheduledEvent (e.g., CloudWatch Event/EventBridge).
 */
public class LambdaHandler implements RequestHandler<ScheduledEvent, Void> {

    private final HttpClient httpClient;
    private final Map<String, String> dropshipzoneShippingMethodMap;

    // Environment variable keys
    private static final String NETOAPI_USERNAME_ENV = "NETOAPI_USERNAME";
    private static final String NETOAPI_KEY_ENV = "NETOAPI_KEY";
    private static final String DROPSHIPZONE_EMAIL_ENV = "DROPSHIPZONE_EMAIL";
    private static final String DROPSHIPZONE_PASSWORD_ENV = "DROPSHIPZONE_PASSWORD";
    private static final String DROPSHIPZONE_SHIPPING_METHOD_MAP_ENV = "DROPSHIPZONE_SHIPPING_METHOD_MAP";

    public LambdaHandler() {
        // Initialize HttpClient once per Lambda instance (warm start optimization)
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(NetoAPIClient.CONNECT_TIMEOUT_MS))
                .build();

        // Initialize shipping method map from environment variable
        this.dropshipzoneShippingMethodMap = new HashMap<>();
        String mapString = System.getenv(DROPSHIPZONE_SHIPPING_METHOD_MAP_ENV);
        if (mapString != null && !mapString.isEmpty()) {
            String[] pairs = mapString.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":");
                if (keyValue.length == 2) {
                    dropshipzoneShippingMethodMap.put(keyValue[0].trim(), keyValue[1].trim());
                } else {
                    System.err.println("Warning: Invalid format in " + DROPSHIPZONE_SHIPPING_METHOD_MAP_ENV + " entry: " + pair);
                }
            }
        }
        System.out.println("Dropshipzone Shipping Method Map loaded: " + dropshipzoneShippingMethodMap);
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Lambda function invoked by CloudWatch Event at: " + event.getTime());

        // Log environment variables for debugging (remove in production if sensitive)
        context.getLogger().log(NETOAPI_USERNAME_ENV + ": " + System.getenv(NETOAPI_USERNAME_ENV));
        context.getLogger().log(DROPSHIPZONE_EMAIL_ENV + ": " + System.getenv(DROPSHIPZONE_EMAIL_ENV));
        context.getLogger().log(DROPSHIPZONE_SHIPPING_METHOD_MAP_ENV + ": " + System.getenv(DROPSHIPZONE_SHIPPING_METHOD_MAP_ENV));


        // --- 1. Fetch Neto Orders ---
        context.getLogger().log("\n--- Fetching Neto Orders ---");
        JSONArray netoOrders = null;
        try {
            JSONObject netoFilterPayload = new JSONObject();
            JSONObject netoFilter = new JSONObject();
            JSONArray netoOrderStatus = new JSONArray();
            netoOrderStatus.put("Pack"); // Filter by OrderStatus: "Pack"
            netoFilter.put("OrderStatus", netoOrderStatus);
            netoFilter.put("WarehouseID", "2"); // Filter by WarehouseID: "2"

            JSONArray netoOutputSelector = new JSONArray();
            netoOutputSelector.put("OrderID");
            netoOutputSelector.put("ShippingOption");
            netoOutputSelector.put("OrderStatus");
            netoOutputSelector.put("OrderLine"); // Ensure OrderLine is requested to potentially get SKU
            netoOutputSelector.put("OrderLine.SKU"); // Request SKU specifically
            netoOutputSelector.put("OrderLine.WarehouseID");
            netoOutputSelector.put("OrderLine.ShippingMethod");
            netoOutputSelector.put("OrderLine.ShippingTracking");
            netoFilter.put("OutputSelector", netoOutputSelector);

            netoFilterPayload.put("Filter", netoFilter);

            context.getLogger().log("Attempting to fetch Neto orders with payload: " + netoFilterPayload.toString(2));
            netoOrders = NetoAPIClient.getOrders(httpClient, netoFilterPayload);

            if (netoOrders != null && netoOrders.length() > 0) {
                context.getLogger().log("Total Neto orders fetched: " + netoOrders.length());
            } else {
                context.getLogger().log("No Neto orders found matching the criteria.");
            }
        } catch (Exception e) {
            context.getLogger().log("Error during Neto API fetch: " + e.getMessage());
            e.printStackTrace();
        }

        // --- 2. Fetch Dropshipzone Orders ---
        context.getLogger().log("\n--- Fetching Dropshipzone Orders ---");
        JSONArray dropshipzoneOrders = null;
        try {
            String dropshipzoneToken = DropshipzoneOrdersAPIClient.authenticate(httpClient);
            if (dropshipzoneToken != null) {
                context.getLogger().log("Dropshipzone authentication successful. Token acquired.");

                LocalDate endDate = LocalDate.now();
                LocalDate startDate = endDate.minusDays(6); // Fetch orders from last 6 days (Dropshipzone limit)
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                String dzStartDate = startDate.format(formatter);
                String dzEndDate = endDate.format(formatter);

                context.getLogger().log("Dropshipzone Date Filter: Start Date = " + dzStartDate + ", End Date = " + dzEndDate);

                dropshipzoneOrders = DropshipzoneOrdersAPIClient.getOrders(
                    httpClient, dropshipzoneToken, null, dzStartDate, dzEndDate); // No order_ids filter here

                if (dropshipzoneOrders != null && dropshipzoneOrders.length() > 0) {
                    context.getLogger().log("Total Dropshipzone orders fetched: " + dropshipzoneOrders.length());
                } else {
                    context.getLogger().log("No Dropshipzone orders found matching the criteria.");
                }
            } else {
                context.getLogger().log("Dropshipzone authentication failed. Skipping order fetch.");
            }
        } catch (Exception e) {
            context.getLogger().log("Error during Dropshipzone API fetch: " + e.getMessage());
            e.printStackTrace();
        }

        // --- 3. Match Neto OrderIDs to Dropshipzone Serial Numbers and Update Neto ---
        context.getLogger().log("\n--- Matching Neto OrderIDs to Dropshipzone Serial Numbers and Updating Neto ---");
        if (netoOrders != null && dropshipzoneOrders != null) {
            Map<String, JSONObject> dropshipzoneOrdersBySerialNumber = new HashMap<>();
            for (int i = 0; i < dropshipzoneOrders.length(); i++) {
                JSONObject dzOrder = dropshipzoneOrders.getJSONObject(i);
                String serialNumber = dzOrder.optString("serial_number", null);
                if (serialNumber != null && !serialNumber.isEmpty()) {
                    String cleanedDzSerialNumber = serialNumber.replaceAll("-[a-zA-Z0-9]$", "");
                    dropshipzoneOrdersBySerialNumber.put(cleanedDzSerialNumber, dzOrder);
                }
            }
            context.getLogger().log("Indexed " + dropshipzoneOrdersBySerialNumber.size() + " Dropshipzone orders by CLEANED serial number.");

            int updatedOrdersCount = 0;
            for (int i = 0; i < netoOrders.length(); i++) {
                JSONObject netoOrder = netoOrders.getJSONObject(i);
                String netoOrderId = netoOrder.optString("OrderID", null);

                if (netoOrderId != null && !netoOrderId.isEmpty()) {
                    JSONObject matchingDzOrder = dropshipzoneOrdersBySerialNumber.get(netoOrderId);

                    if (matchingDzOrder != null) {
                        context.getLogger().log("Match found: Neto OrderID " + netoOrderId + " with Dropshipzone Serial Number " + matchingDzOrder.optString("serial_number"));

                        String dzTrackNumber = "N/A";
                        String dzShipmentTitle = "N/A";
                        String dzShipmentCreateAt = "N/A";

                        JSONArray shipments = matchingDzOrder.optJSONArray("shipment");
                        if (shipments != null && shipments.length() > 0) {
                            JSONObject firstShipment = shipments.getJSONObject(0);
                            dzTrackNumber = firstShipment.optString("track_number", "N/A");
                            dzShipmentTitle = firstShipment.optString("title", "N/A");
                            dzShipmentCreateAt = firstShipment.optString("create_at", "N/A");
                        }

                        String netoShippingMethod = dropshipzoneShippingMethodMap.getOrDefault(dzShipmentTitle, dzShipmentTitle);
                        if (!dropshipzoneShippingMethodMap.containsKey(dzShipmentTitle)) {
                            context.getLogger().log(String.format("  No specific mapping found for Dropshipzone Title '%s'. Using original title as Neto Shipping Method.", dzShipmentTitle));
                        }

                        String netoSkuForUpdate = "UNKNOWN_SKU";
                        JSONArray netoOrderLines = netoOrder.optJSONArray("OrderLine");
                        if (netoOrderLines != null && netoOrderLines.length() > 0) {
                            JSONObject firstNetoOrderLine = netoOrderLines.getJSONObject(0);
                            netoSkuForUpdate = firstNetoOrderLine.optString("SKU", netoSkuForUpdate);
                        }

                        if (!"N/A".equals(dzTrackNumber) && !"N/A".equals(netoShippingMethod) && !"N/A".equals(dzShipmentCreateAt)) {
                            context.getLogger().log(String.format("  Attempting to update Neto OrderID %s (SKU: %s) with Tracking: %s, Carrier: %s, Shipped Date: %s",
                                netoOrderId, netoSkuForUpdate, dzTrackNumber, netoShippingMethod, dzShipmentCreateAt));
                            NetoAPIClient.updateOrderTracking(
                                httpClient,
                                netoOrderId,
                                "Dispatched",
                                "tracking",
                                netoSkuForUpdate,
                                netoShippingMethod,
                                dzTrackNumber,
                                dzShipmentCreateAt
                            );
                            updatedOrdersCount++;
                        } else {
                            context.getLogger().log("  Skipping Neto update for OrderID " + netoOrderId + ": Missing tracking details from Dropshipzone.");
                        }
                    } else {
                        context.getLogger().log("No matching Dropshipzone order found for Neto OrderID: " + netoOrderId);
                    }
                }
            }
            context.getLogger().log("Total Neto orders updated: " + updatedOrdersCount);

        } else {
            context.getLogger().log("Cannot perform matching: Either Neto orders or Dropshipzone orders were not fetched successfully.");
        }

        context.getLogger().log("\nLambda execution completed.");
        return null;
    }
}