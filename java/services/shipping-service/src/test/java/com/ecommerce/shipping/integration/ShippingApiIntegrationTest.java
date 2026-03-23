package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeResult;
import com.ecommerce.shipping.application.dto.CreateShipmentResult;
import com.ecommerce.shipping.application.dto.TrackingEventsResult;
import com.ecommerce.shipping.application.port.in.CalculateShippingFeeUseCase;
import com.ecommerce.shipping.application.port.in.CancelShipmentUseCase;
import com.ecommerce.shipping.application.port.in.CreateShipmentUseCase;
import com.ecommerce.shipping.application.port.in.GetTrackingEventsUseCase;
import com.ecommerce.shipping.application.port.in.ReceiveWebhookUseCase;
import com.ecommerce.shipping.presentation.controller.ShippingExceptionHandler;
import com.ecommerce.shipping.presentation.controller.ShippingQuoteController;
import com.ecommerce.shipping.presentation.controller.ShippingWebhookController;
import com.ecommerce.shipping.presentation.controller.ShipmentCommandController;
import com.ecommerce.shipping.presentation.controller.ShipmentTrackingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        ShippingQuoteController.class,
        ShipmentCommandController.class,
        ShipmentTrackingController.class,
        ShippingWebhookController.class
})
@Import(ShippingExceptionHandler.class)
class ShippingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CalculateShippingFeeUseCase calculateShippingFeeUseCase;

    @MockBean
    private CreateShipmentUseCase createShipmentUseCase;

    @MockBean
    private CancelShipmentUseCase cancelShipmentUseCase;

    @MockBean
    private GetTrackingEventsUseCase getTrackingEventsUseCase;

    @MockBean
    private ReceiveWebhookUseCase receiveWebhookUseCase;

    @Test
    void shouldReturnOkForCalculateBatch() throws Exception {
        // Theo thiết kế shipping_workflow.md mục 1: POST /v1/shipping/calculate-batch
        when(calculateShippingFeeUseCase.calculateBatch(any())).thenReturn(new CalculateShippingFeeResult(List.of(
                new CalculateShippingFeeResult.QuoteOptions(0, List.of(
                        new CalculateShippingFeeResult.ShippingOption("GHN", "STANDARD", BigDecimal.valueOf(25000), 2)
                ))
        )));

        mockMvc.perform(post("/v1/shipping/calculate-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "quotes": [
                                    {
                                      "fromWarehouseId": 5,
                                      "fromProvince": "HCM",
                                      "toProvince": "HCM",
                                      "weightGrams": 500,
                                      "dimensions": {"length": 20, "width": 15, "height": 10},
                                      "codAmount": 0,
                                      "itemsCount": 2
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnOkForCreateShipment() throws Exception {
        // Theo thiết kế shipping_workflow.md mục 2: POST /v1/shipping/create-shipment
        when(createShipmentUseCase.createShipment(any())).thenReturn(
                new CreateShipmentResult(1L, "GHN12345", "https://label", LocalDate.now().plusDays(2)));

        mockMvc.perform(post("/v1/shipping/create-shipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": 10001,
                                  "parcelId": 2001,
                                  "courierCode": "GHN",
                                  "serviceType": "STANDARD",
                                  "sender": {"warehouseId": 5},
                                  "receiver": {"name": "A"},
                                  "items": [{"name": "Item", "quantity": 1}],
                                  "codAmount": 0,
                                  "totalWeightGrams": 500,
                                  "note": "note"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnOkForTrackingEvents() throws Exception {
        // Theo thiết kế shipping_workflow.md mục 4: GET /v1/shipping/{tracking}/events
        when(getTrackingEventsUseCase.getTrackingEvents(any(), anyInt())).thenReturn(new TrackingEventsResult(
                "GHN12345",
                "GHN",
                "IN_TRANSIT",
                List.of(new TrackingEventsResult.TrackingEventItem("IN_TRANSIT", "Hub", Instant.now()))
        ));

        mockMvc.perform(get("/v1/shipping/GHN12345/events"))
                .andExpect(status().isOk());
    }
}
