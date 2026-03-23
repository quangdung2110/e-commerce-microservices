package com.ecommerce.shipping.domain.adapter;

// Theo thiết kế shipping_workers.md mục 2: parse/verify webhook theo provider.
public interface ShippingWebhookAdapter {

    String providerCode();
}
