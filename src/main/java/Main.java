// No package declaration for default package
import java.net.http.HttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate; // Added for date manipulation
import java.time.format.DateTimeFormatter; // Added for date formatting
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap; // For efficient lookup of Dropshipzone orders by serial number
import java.util.Arrays; // For Arrays.asList

public class Main {

    public static void main(String[] args) {
        System.out.println("Starting local test of API clients...");

        // --- IMPORTANT: API Credentials must be set as actual Environment Variables ---
        // For local testing, set these in your terminal before running:
        // export NETOAPI_USERNAME="YOUR_NETO_USERNAME"
        // export NETOAPI_KEY="YOUR_NETO_KEY"
        // export DROPSHIPZONE_EMAIL="YOUR_DROPSHIPZONE_EMAIL"
        // export DROPSHIPZONE_PASSWORD="YOUR_DROPSHIPZONE_PASSWORD"
        // export DROPSHIPZONE_SHIPPING_METHOD_MAP="TEAM GLOBAL EXPRESS:TollIpecP&S,OTHER_DZ_TITLE:OTHER_NETO_METHOD"

        // Initialize HttpClient with the same timeouts as in client classes
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(NetoAPIClient.CONNECT_TIMEOUT_MS)) // Using NetoAPIClient's connect timeout
                .build();

        // --- Parse Dropshipzone Shipping Method Map from Environment Variable ---
        Map<String, String> dropshipzoneShippingMethodMap = new HashMap<>();
        String mapString = System.getenv("DROPSHIPZONE_SHIPPING_METHOD_MAP");
        if (mapString != null && !mapString.isEmpty()) {
            String[] pairs = mapString.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":");
                if (keyValue.length == 2) {
                    dropshipzoneShippingMethodMap.put(keyValue[0].trim(), keyValue[1].trim());
                } else {
                    System.err.println("Warning: Invalid format in DROPSHIPZONE_SHIPPING_METHOD_MAP entry: " + pair);
                }
            }
        }
        System.out.println("Dropshipzone Shipping Method Map loaded: " + dropshipzoneShippingMethodMap);


        // --- 1. Test Neto API Orders Fetch ---
        System.out.println("\n--- Testing NetoAPIClient.getOrders ---");
        JSONArray netoOrders = null; // Initialize to null
        try {
            // Construct the Neto API Filter Payload
            JSONObject netoFilterPayload = new JSONObject();
            JSONObject netoFilter = new JSONObject();
            JSONArray netoOrderStatus = new JSONArray();
            netoOrderStatus.put("Pack"); // Filter by OrderStatus: "Pack"
            netoFilter.put("OrderStatus", netoOrderStatus);

            // Filter by WarehouseID: "2"
            netoFilter.put("WarehouseID", "2");

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

            System.out.println("Attempting to fetch Neto orders with payload: " + netoFilterPayload.toString(2));

            netoOrders = NetoAPIClient.getOrders(httpClient, netoFilterPayload);

            if (netoOrders != null) {
                System.out.println("\n--- Fetched Neto Orders ---");
                if (netoOrders.length() > 0) {
                    System.out.println("Total Neto orders fetched: " + netoOrders.length());
                    System.out.println(netoOrders.toString(2)); // Print with indent for readability
                } else {
                    System.out.println("No Neto orders found matching the criteria.");
                }
            } else {
                System.err.println("\nFailed to fetch Neto orders. Check logs above for errors.");
            }
        } catch (Exception e) {
            System.err.println("Error during Neto API call: " + e.getMessage());
            e.printStackTrace();
        }


        // --- 2. Test Dropshipzone Orders API Fetch ---
        System.out.println("\n--- Testing DropshipzoneOrdersAPIClient.getOrders (Broad Date Range) ---");
        JSONArray dropshipzoneOrders = null; // Initialize to null
        try {
            // Corrected call: use DropshipzoneOrdersAPIClient for authentication
            String dropshipzoneToken = DropshipzoneOrdersAPIClient.authenticate(httpClient);
            if (dropshipzoneToken != null) {
                System.out.println("Dropshipzone authentication successful. Token acquired.");

                // Dynamically calculate start and end dates (broad range to capture potential matches)
                LocalDate endDate = LocalDate.now();
                LocalDate startDate = endDate.minusDays(14); // Fetch orders from last 14 days (Dropshipzone limit)

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

                String dzStartDate = startDate.format(formatter);
                String dzEndDate = endDate.format(formatter);

                // Do NOT filter by specific order_ids here. We will filter in memory.
                List<String> dzOrderIds = null; // Set to null to fetch a broader range of orders

                System.out.println("Dropshipzone Date Filter: Start Date = " + dzStartDate + ", End Date = " + dzEndDate);
                System.out.println("Dropshipzone Order ID Filter: None (fetching by date range)");


                dropshipzoneOrders = DropshipzoneOrdersAPIClient.getOrders(
                    httpClient, dropshipzoneToken, dzOrderIds, dzStartDate, dzEndDate);

                if (dropshipzoneOrders != null) {
                    System.out.println("\n--- Fetched Dropshipzone Orders ---");
                    if (dropshipzoneOrders.length() > 0) {
                        System.out.println("Total Dropshipzone orders fetched: " + dropshipzoneOrders.length());
                        System.out.println(dropshipzoneOrders.toString(2)); // Print with indent for readability

                        // --- Extract and Print Shipment and Dispatch Info (for all fetched DZ orders) ---
                        System.out.println("\n--- Dropshipzone Order Details (Shipment & Dispatch - All Fetched) ---");
                        for (int i = 0; i < dropshipzoneOrders.length(); i++) {
                            JSONObject dzOrder = dropshipzoneOrders.getJSONObject(i);
                            String dzOrderId = dzOrder.optString("order_id", "N/A");
                            String dispatchTime = dzOrder.optString("dispatch_time", "N/A");
                            JSONArray shipments = dzOrder.optJSONArray("shipment");

                            System.out.println("Dropshipzone Order ID: " + dzOrderId);
                            System.out.println("  Dispatch Time: " + dispatchTime);

                            if (shipments != null && shipments.length() > 0) {
                                for (int j = 0; j < shipments.length(); j++) {
                                    JSONObject shipment = shipments.getJSONObject(j);
                                    String trackNumber = shipment.optString("track_number", "N/A");
                                    String title = shipment.optString("title", "N/A");
                                    String createdAt = shipment.optString("create_at", "N/A");
                                    System.out.println("    Shipment " + (j + 1) + ":");
                                    System.out.println("      Track Number: " + trackNumber);
                                    System.out.println("      Title: " + title);
                                    System.out.println("      Create At: " + createdAt);
                                }
                            } else {
                                System.out.println("    No shipment details found for this order.");
                            }
                            System.out.println("---"); // Separator for clarity
                        }
                    } else {
                        System.out.println("No Dropshipzone orders found matching the criteria.");
                    }
                } else {
                    System.err.println("\nFailed to fetch Dropshipzone orders. Check logs above for errors.");
                }
            } else {
                System.err.println("Dropshipzone authentication failed. Skipping order fetch.");
            }
        } catch (Exception e) {
            System.err.println("Error during Dropshipzone API call: " + e.getMessage());
            e.printStackTrace();
        }

        // --- 3. Match Neto OrderIDs to Dropshipzone Serial Numbers and Update Neto ---
        System.out.println("\n--- Matching Neto OrderIDs to Dropshipzone Serial Numbers and Updating Neto ---");
        if (netoOrders != null && dropshipzoneOrders != null) {
            Map<String, JSONObject> dropshipzoneOrdersBySerialNumber = new HashMap<>();
            for (int i = 0; i < dropshipzoneOrders.length(); i++) {
                JSONObject dzOrder = dropshipzoneOrders.getJSONObject(i);
                String serialNumber = dzOrder.optString("serial_number", null); // Assuming "serial_number" is the field name
                if (serialNumber != null && !serialNumber.isEmpty()) {
                    // Remove "-[any_character]" suffix from Dropshipzone serial number for matching
                    String cleanedDzSerialNumber = serialNumber.replaceAll("-[a-zA-Z0-9]$", "");
                    dropshipzoneOrdersBySerialNumber.put(cleanedDzSerialNumber, dzOrder);
                }
            }
            System.out.println("Indexed " + dropshipzoneOrdersBySerialNumber.size() + " Dropshipzone orders by CLEANED serial number (removed -[char]).");


            List<String> matchedOrderPairs = new ArrayList<>();
            for (int i = 0; i < netoOrders.length(); i++) {
                JSONObject netoOrder = netoOrders.getJSONObject(i);
                String netoOrderId = netoOrder.optString("OrderID", null);

                if (netoOrderId != null && !netoOrderId.isEmpty()) {
                    JSONObject matchingDzOrder = dropshipzoneOrdersBySerialNumber.get(netoOrderId);

                    if (matchingDzOrder != null) {
                        // Extract additional details from the matched Dropshipzone order
                        String dzDispatchTime = matchingDzOrder.optString("dispatch_time", "N/A");
                        String dzTrackNumber = "N/A";
                        String dzShipmentTitle = "N/A";
                        String dzShipmentCreateAt = "N/A"; // This will be the DateShipped for Neto

                        JSONArray shipments = matchingDzOrder.optJSONArray("shipment");
                        if (shipments != null && shipments.length() > 0) {
                            JSONObject firstShipment = shipments.getJSONObject(0); // Get details from the first shipment
                            dzTrackNumber = firstShipment.optString("track_number", "N/A");
                            dzShipmentTitle = firstShipment.optString("title", "N/A");
                            dzShipmentCreateAt = firstShipment.optString("create_at", "N/A");
                        }

                        // --- Determine Neto Shipping Method based on Dropshipzone Title using the map ---
                        String netoShippingMethod = dzShipmentTitle; // Default to DZ title
                        if (dropshipzoneShippingMethodMap.containsKey(dzShipmentTitle)) {
                            netoShippingMethod = dropshipzoneShippingMethodMap.get(dzShipmentTitle);
                            System.out.println(String.format("  Mapped Dropshipzone Title '%s' to Neto Shipping Method '%s'", dzShipmentTitle, netoShippingMethod));
                        } else {
                            System.out.println(String.format("  No mapping found for Dropshipzone Title '%s'. Using original title as Neto Shipping Method.", dzShipmentTitle));
                        }


                        // --- Determine SKU for Neto Update ---
                        String netoSkuForUpdate = "UNKNOWN_SKU"; // Default if not found
                        JSONArray netoOrderLines = netoOrder.optJSONArray("OrderLine");
                        if (netoOrderLines != null && netoOrderLines.length() > 0) {
                            JSONObject firstNetoOrderLine = netoOrderLines.getJSONObject(0);
                            netoSkuForUpdate = firstNetoOrderLine.optString("SKU", netoSkuForUpdate);
                        }


                        // --- Call NetoAPIClient to update tracking ---
                        // Ensure dzTrackNumber, netoShippingMethod, and dzShipmentCreateAt are valid before updating
                        if (!"N/A".equals(dzTrackNumber) && !"N/A".equals(netoShippingMethod) && !"N/A".equals(dzShipmentCreateAt)) {
                            System.out.println(String.format("  Attempting to update Neto OrderID %s (SKU: %s) with Tracking: %s, Carrier: %s, Shipped Date: %s",
                                netoOrderId, netoSkuForUpdate, dzTrackNumber, netoShippingMethod, dzShipmentCreateAt));
                            NetoAPIClient.updateOrderTracking(
                                httpClient,
                                netoOrderId,
                                "Dispatched", // Set Neto OrderStatus to Dispatched
                                "tracking",   // Send tracking email
                                netoSkuForUpdate, // Pass the determined SKU
                                netoShippingMethod, // Use the determined shipping method
                                dzTrackNumber,
                                dzShipmentCreateAt
                            );
                        } else {
                            System.out.println("  Skipping Neto update for OrderID " + netoOrderId + ": Missing tracking details from Dropshipzone.");
                        }

                        matchedOrderPairs.add(String.format(
                            "Neto OrderID: %s matched with Dropshipzone Serial Number: %s (Dropshipzone Order ID: %s)\n" +
                            "  Track Number: %s, Original DZ Title: %s, Neto Shipping Method: %s, Created At: %s\n" +
                            "  Dispatch Time: %s",
                            netoOrderId,
                            matchingDzOrder.optString("serial_number"),
                            matchingDzOrder.optString("order_id"),
                            dzTrackNumber,
                            dzShipmentTitle, // Original DZ title
                            netoShippingMethod, // The method used for Neto
                            dzShipmentCreateAt,
                            dzDispatchTime
                        ));
                    } else {
                        System.out.println("No matching Dropshipzone order found for Neto OrderID: " + netoOrderId);
                    }
                }
            }

            if (!matchedOrderPairs.isEmpty()) {
                System.out.println("\n--- Matched Order Pairs (Neto OrderID -> Dropshipzone Serial Number) ---");
                for (String pair : matchedOrderPairs) {
                    System.out.println(pair);
                }
            } else {
                System.out.println("No matches found between Neto OrderIDs and Dropshipzone serial numbers.");
            }

        } else {
            System.out.println("Cannot perform matching: Either Neto orders or Dropshipzone orders were not fetched successfully.");
        }

        System.out.println("\nLocal test completed.");
    }
}
