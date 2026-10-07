import { Header } from "@/components/layout/header";
import { Footer } from "@/components/layout/footer";

/**
 * Khung của phần storefront: header + footer. Nhóm `(auth)` cố tình nằm ngoài
 * layout này vì các màn hình đăng nhập chiếm trọn viewport và không có chrome.
 */
export default function SiteLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-svh flex-col">
      <Header />
      <div className="flex-1">{children}</div>
      <Footer />
    </div>
  );
}
