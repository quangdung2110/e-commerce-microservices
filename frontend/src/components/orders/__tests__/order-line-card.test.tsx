// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { OrderLineCard } from "@/components/orders/order-line-card";
import type { OrderLine } from "@/lib/api/order";

const line: OrderLine = {
  order_line_id: "1",
  product_id: "p-1",
  sku_id: "sku-1",
  quantity: 1,
  unit_price: { amount_minor: 199000, currency: "VND" },
  fulfillment_status: "DELIVERED",
  snapshot_name: "Áo thun cổ tròn Terra",
  snapshot_image_url: null,
  snapshot_attributes: { color: "Đen" },
  delivered_at: "2026-10-05T00:00:00Z",
};

afterEach(cleanup);

describe("OrderLineCard", () => {
  it("có nút đánh giá khi dòng này còn quyền đánh giá", () => {
    render(<OrderLineCard line={line} reviewHref="/reviews/new?eligibility=e-1" />);
    expect(screen.getByRole("link", { name: "Đánh giá sản phẩm" }).getAttribute("href")).toBe("/reviews/new?eligibility=e-1");
  });

  it("không có nút đánh giá khi không có entitlement", () => {
    render(<OrderLineCard line={line} />);
    expect(screen.queryByRole("link", { name: "Đánh giá sản phẩm" })).toBeNull();
  });
});
