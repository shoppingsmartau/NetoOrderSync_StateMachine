// No package declaration for default package
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * LambdaHandler for processing scheduled events. It now fetches order data from Neto
 * using the specified filter and logs the retrieved orders.
 * All product update logic and S3 interactions have been removed.
 */
public class LambdaHandler implements RequestHandler<ScheduledEvent, Void> {

    // Thread pool is no longer strictly needed for parallel updates if only fetching,
    // but keeping it if you plan to process orders in parallel later.
    private static final int THREAD_POOL_SIZE = 5; // Adjust as needed for processing fetched orders
    private final ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    private HttpClient httpClient; // Shared HttpClient instance for external APIs

    // Define timeouts for HTTP connections (in milliseconds) for the shared HttpClient
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000; // 5 seconds
    private static final int HTTP_READ_TIMEOUT_MS = 15000;  // 15 seconds

    public LambdaHandler() {
        System.out.println("LambdaHandler constructor invoked. Initializing HttpClient.");

        // --- Shared HttpClient Initialization for external APIs ---
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(HTTP_CONNECT_TIMEOUT_MS))
                .build();
        System.out.println("Shared HttpClient initialized for external APIs.");
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Lambda function invoked by CloudWatch Event at: " + event.getTime());
        context.getLogger().log("Event received: " + event.toString());

        try {
            // --- Construct the Neto API Filter Payload ---
            JSONObject filterPayload = new JSONObject();
            JSONObject filter = new JSONObject();
            JSONArray orderStatus = new JSONArray();
            orderStatus.put("New");
            orderStatus.put("Pick");
            orderStatus.put("Pack");
            filter.put("OrderStatus", orderStatus);

            JSONArray outputSelector = new JSONArray();
            outputSelector.put("ShippingOption");
            outputSelector.put("DeliveryInstruction");
            outputSelector.put("Username");
            outputSelector.put("Email");
            outputSelector.put("ShipAddress");
            outputSelector.put("BillAddress");
            outputSelector.put("CustomerRef1");
            outputSelector.put("CustomerRef2");
            outputSelector.put("CustomerRef3");
            outputSelector.put("CustomerRef4");
            outputSelector.put("SalesChannel");
            outputSelector.put("GrandTotal");
            outputSelector.put("ShippingTotal");
            outputSelector.put("ShippingDiscount");
            outputSelector.put("OrderType");
            outputSelector.put("OrderStatus");
            outputSelector.put("OrderPayment");
            outputSelector.put("OrderPayment.PaymentType");
            outputSelector.put("OrderPayment.DatePaid");
            outputSelector.put("DatePlaced");
            outputSelector.put("DateRequired");
            outputSelector.put("DateInvoiced");
            outputSelector.put("DatePaid");
            outputSelector.put("OrderLine");
            outputSelector.put("OrderLine.ProductName");
            outputSelector.put("OrderLine.PickQuantity");
            outputSelector.put("OrderLine.BackorderQuantity");
            outputSelector.put("OrderLine.UnitPrice");
            outputSelector.put("OrderLine.WarehouseID");
            outputSelector.put("OrderLine.WarehouseName");
            outputSelector.put("OrderLine.WarehouseReference");
            outputSelector.put("OrderLine.Quantity");
            outputSelector.put("OrderLine.PercentDiscount");
            outputSelector.put("OrderLine.ProductDiscount");
            outputSelector.put("OrderLine.CostPrice");
            outputSelector.put("OrderLine.ShippingMethod");
            outputSelector.put("OrderLine.ShippingTracking");
            outputSelector.put("ShippingSignature");
            outputSelector.put("eBay.eBayUsername");
            outputSelector.put("eBay.eBayStoreName");
            outputSelector.put("OrderLine.eBay.eBayTransactionID");
            outputSelector.put("OrderLine.eBay.eBayAuctionID");
            outputSelector.put("OrderLine.eBay.ListingType");
            outputSelector.put("OrderLine.eBay.DateCreated");
            outputSelector.put("OrderLine.eBay.DatePaid");
            filter.put("OutputSelector", outputSelector);

            JSONObject updateResults = new JSONObject();
            updateResults.put("ExportStatus", "Exported");
            filter.put("UpdateResults", updateResults);

            filterPayload.put("Filter", filter);

            context.getLogger().log("Neto Order Fetch Payload: " + filterPayload.toString(2)); // Log with indent for readability

            // --- Fetch Orders from Neto ---
            JSONArray fetchedOrders = NetoAPIClient.getOrders(httpClient, filterPayload);

            if (fetchedOrders != null && fetchedOrders.length() > 0) {
                context.getLogger().log("Successfully fetched " + fetchedOrders.length() + " orders.");
                // --- Process Fetched Orders in Parallel (Example) ---
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (int i = 0; i < fetchedOrders.length(); i++) {
                    JSONObject order = fetchedOrders.getJSONObject(i);
                    final int orderIndex = i; // For use in lambda
                    futures.add(CompletableFuture.runAsync(() -> {
                        context.getLogger().log("Processing Order " + (orderIndex + 1) + ": " + order.optString("OrderID", "N/A") + " - Status: " + order.optString("OrderStatus", "N/A"));
                        // --- YOUR ORDER PROCESSING LOGIC HERE ---
                        // Example: You can extract specific fields and perform actions.
                        // String orderId = order.optString("OrderID");
                        // String customerEmail = order.optString("Email");
                        // JSONArray orderLines = order.optJSONArray("OrderLine");
                        // if (orderLines != null) {
                        //     for (int j = 0; j < orderLines.length(); j++) {
                        //         JSONObject line = orderLines.getJSONObject(j);
                        //         context.getLogger().log("  - Line Item: " + line.optString("ProductName") + ", Qty: " + line.optInt("Quantity"));
                        //     }
                        // }
                    }, executorService));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                context.getLogger().log("All fetched orders processed.");

            } else {
                context.getLogger().log("No orders fetched or an error occurred during fetch.");
            }

        } catch (Exception e) {
            context.getLogger().log("An unhandled error occurred during Lambda execution:");
            e.printStackTrace();
            throw new RuntimeException("Lambda execution failed: " + e.getMessage(), e);
        } finally {
            // Shutdown the executor service gracefully
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    context.getLogger().log("Executor service did not terminate gracefully within 5 seconds.");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                context.getLogger().log("Executor service termination interrupted.");
            }
        }
        return null;
    }
}
