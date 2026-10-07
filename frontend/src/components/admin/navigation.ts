import {
  Boxes,
  ClipboardList,
  FolderTree,
  LayoutDashboard,
  Megaphone,
  PackageSearch,
  Settings,
  ShieldCheck,
  Tags,
  Warehouse,
  type LucideIcon,
} from "lucide-react";

export interface AdminNavItem {
  label: string;
  href: string;
  icon: LucideIcon;
  enabled: boolean;
}

export interface AdminNavGroup {
  label?: string;
  items: AdminNavItem[];
}

/** Mục của bước chưa hoàn thành giữ trong cấu hình nhưng không render. */
export const ADMIN_NAV: AdminNavGroup[] = [
  {
    items: [
      { label: "Tổng quan", href: "/admin", icon: LayoutDashboard, enabled: true },
    ],
  },
  {
    label: "Catalog",
    items: [
      { label: "Sản phẩm", href: "/admin/products", icon: PackageSearch, enabled: true },
      { label: "Danh mục", href: "/admin/categories", icon: FolderTree, enabled: true },
      { label: "Thuộc tính", href: "/admin/attributes", icon: Tags, enabled: true },
    ],
  },
  {
    label: "Kho",
    items: [
      { label: "Tra cứu", href: "/admin/inventory", icon: Warehouse, enabled: true },
      { label: "Nhập hàng", href: "/admin/inventory/receipts/new", icon: Boxes, enabled: true },
    ],
  },
  {
    label: "Vận hành",
    items: [
      { label: "Đơn hàng", href: "/admin/orders", icon: ClipboardList, enabled: true },
      { label: "Khuyến mãi", href: "/admin/promotions", icon: Megaphone, enabled: true },
      { label: "Kiểm duyệt", href: "/admin/reviews", icon: ShieldCheck, enabled: false },
      { label: "Hệ thống", href: "/admin/system", icon: Settings, enabled: false },
    ],
  },
];

export const ENABLED_ADMIN_NAV = ADMIN_NAV.map((group) => ({
  ...group,
  items: group.items.filter((item) => item.enabled),
})).filter((group) => group.items.length > 0);

export const ADMIN_BREADCRUMB_LABELS: Record<string, string> = {
  "/admin": "Tổng quan",
  "/admin/products": "Sản phẩm",
  "/admin/products/new": "Tạo sản phẩm",
  "/admin/categories": "Danh mục",
  "/admin/attributes": "Thuộc tính",
  "/admin/inventory": "Kho hàng",
  "/admin/inventory/warehouses/new": "Tạo kho",
  "/admin/inventory/receipts/new": "Nhập hàng",
  "/admin/inventory/adjustments/new": "Điều chỉnh tồn",
  "/admin/orders": "Đơn hàng",
  "/admin/promotions": "Khuyến mãi",
  "/admin/promotions/new": "Tạo campaign",
};

export function adminBreadcrumbLabel(pathname: string): string {
  if (ADMIN_BREADCRUMB_LABELS[pathname]) return ADMIN_BREADCRUMB_LABELS[pathname];
  if (pathname.startsWith("/admin/products/")) return "Chi tiết sản phẩm";
  if (pathname.startsWith("/admin/categories/")) return "Chi tiết danh mục";
  if (pathname.startsWith("/admin/attributes/")) return "Chi tiết thuộc tính";
  if (pathname.startsWith("/admin/inventory/warehouses/")) return "Chi tiết kho";
  if (pathname.startsWith("/admin/inventory/stock/")) return "Vị trí tồn";
  if (pathname.startsWith("/admin/inventory/movements/")) return "Movement tồn kho";
  if (pathname.startsWith("/admin/inventory/commands/")) return "Kết quả lệnh";
  if (pathname.startsWith("/admin/inventory/reservations/")) return "Phân bổ đơn hàng";
  if (pathname.startsWith("/admin/orders/")) return "Chi tiết đơn hàng";
  if (pathname.startsWith("/admin/promotions/")) {
    const [, , , , section, leaf] = pathname.split("/");
    if (section === "vouchers") return leaf === "new" ? "Thêm voucher" : "Chi tiết voucher";
    if (section === "markdowns") return leaf === "new" ? "Thêm giảm giá sản phẩm" : "Giảm giá sản phẩm";
    return "Chi tiết campaign";
  }
  return "Quản trị";
}
