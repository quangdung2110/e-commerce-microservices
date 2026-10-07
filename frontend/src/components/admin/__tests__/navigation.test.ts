import { describe, expect, it } from "vitest";

import {
  ENABLED_ADMIN_NAV,
  adminBreadcrumbLabel,
} from "@/components/admin/navigation";

describe("điều hướng admin catalog", () => {
  it("chỉ hiện route đã hoàn thành", () => {
    expect(ENABLED_ADMIN_NAV.flatMap((group) => group.items.map((item) => item.href))).toEqual([
      "/admin",
      "/admin/products",
      "/admin/categories",
      "/admin/attributes",
      "/admin/inventory",
      "/admin/inventory/receipts/new",
      "/admin/orders",
      "/admin/promotions",
    ]);
  });

  it("đặt breadcrumb cho route động", () => {
    expect(adminBreadcrumbLabel("/admin/products/0199")).toBe("Chi tiết sản phẩm");
    expect(adminBreadcrumbLabel("/admin/categories/0199")).toBe("Chi tiết danh mục");
    expect(adminBreadcrumbLabel("/admin/attributes/0199")).toBe("Chi tiết thuộc tính");
    expect(adminBreadcrumbLabel("/admin/inventory/warehouses/wh-1")).toBe("Chi tiết kho");
    expect(adminBreadcrumbLabel("/admin/inventory/stock/wh-1/sku-1")).toBe("Vị trí tồn");
    expect(adminBreadcrumbLabel("/admin/inventory/movements/move-1")).toBe("Movement tồn kho");
    expect(adminBreadcrumbLabel("/admin/inventory/commands/cmd-1")).toBe("Kết quả lệnh");
    expect(adminBreadcrumbLabel("/admin/inventory/reservations/123")).toBe("Phân bổ đơn hàng");
    expect(adminBreadcrumbLabel("/admin/orders/123")).toBe("Chi tiết đơn hàng");
    expect(adminBreadcrumbLabel("/admin/promotions/new")).toBe("Tạo campaign");
    expect(adminBreadcrumbLabel("/admin/promotions/3")).toBe("Chi tiết campaign");
    expect(adminBreadcrumbLabel("/admin/promotions/3/vouchers/new")).toBe("Thêm voucher");
    expect(adminBreadcrumbLabel("/admin/promotions/3/vouchers/7")).toBe("Chi tiết voucher");
    expect(adminBreadcrumbLabel("/admin/promotions/3/markdowns/new")).toBe("Thêm giảm giá sản phẩm");
    expect(adminBreadcrumbLabel("/admin/promotions/3/markdowns/5")).toBe("Giảm giá sản phẩm");
  });
});
