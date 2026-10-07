// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => "/notifications",
}));
vi.mock("@/lib/api/notification", () => ({
  notificationApi: {
    list: vi.fn(),
    unreadCount: vi.fn(),
    setRead: vi.fn(),
    archive: vi.fn(),
    markAllRead: vi.fn(),
  },
}));

import { NotificationBell } from "@/components/notifications/notification-bell";
import { NotificationsPageContent } from "@/components/notifications/notifications-page-content";
import { ToastProvider } from "@/components/ui/toast";
import { notificationApi, type InboxItem } from "@/lib/api/notification";
import { useAuthStore } from "@/store/useAuthStore";

const item = (overrides: Partial<InboxItem> = {}): InboxItem => ({
  id: "n-1",
  template: "REVIEW_REPLIED",
  title: "Đánh giá của bạn có phản hồi mới",
  body: "Cửa hàng vừa phản hồi đánh giá bạn đã viết.",
  deep_link: `${window.location.origin}/reviews/r-1`,
  created_at: "2026-10-06T11:16:00Z",
  read: false,
  read_at: null,
  ...overrides,
});

function login() {
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-a", roles: ["USER"] }))}.test`);
}

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><ToastProvider>{ui}</ToastProvider></QueryClientProvider>);
}

beforeEach(() => {
  vi.resetAllMocks();
  login();
  vi.mocked(notificationApi.unreadCount).mockResolvedValue({ count: 1, as_of: "2026-10-06T11:16:00Z" });
});
afterEach(cleanup);

describe("NotificationBell", () => {
  it("hiện số chưa đọc trong nhãn truy cập và giới hạn 99+", async () => {
    vi.mocked(notificationApi.unreadCount).mockResolvedValue({ count: 120, as_of: "2026-10-06T11:16:00Z" });
    renderWithClient(<NotificationBell />);
    expect(await screen.findByRole("link", { name: "Thông báo, 120 chưa đọc" })).toBeTruthy();
    expect(screen.getByText("99+")).toBeTruthy();
  });

  it("không có số khi chưa đọc gì hoặc khi API lỗi", async () => {
    vi.mocked(notificationApi.unreadCount).mockRejectedValue(new Error("down"));
    renderWithClient(<NotificationBell />);
    const link = await screen.findByRole("link", { name: "Thông báo" });
    expect(link.getAttribute("href")).toBe("/notifications");
  });

  it("không hiện khi chưa đăng nhập", () => {
    useAuthStore.getState().markAnonymous();
    renderWithClient(<NotificationBell />);
    expect(screen.queryByRole("link", { name: /Thông báo/ })).toBeNull();
    expect(notificationApi.unreadCount).not.toHaveBeenCalled();
  });
});

describe("/notifications", () => {
  it("mở thông báo cùng origin: đánh dấu đã đọc rồi chuyển tới đường dẫn nội bộ", async () => {
    vi.mocked(notificationApi.list).mockResolvedValue({ items: [item()], next_cursor: null });
    vi.mocked(notificationApi.setRead).mockResolvedValue(item({ read: true }));
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);

    fireEvent.click(await screen.findByRole("button", { name: /Đánh giá của bạn có phản hồi mới/ }));

    expect(router.push).toHaveBeenCalledWith("/reviews/r-1");
    await waitFor(() => expect(notificationApi.setRead).toHaveBeenCalledWith("n-1", true));
  });

  it("link tới origin khác không bao giờ trở thành nút điều hướng", async () => {
    vi.mocked(notificationApi.list).mockResolvedValue({
      items: [item({ deep_link: "https://evil.example.com/orders/1" })],
      next_cursor: null,
    });
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);

    await screen.findByText("Đánh giá của bạn có phản hồi mới");
    expect(screen.queryByRole("button", { name: /Đánh giá của bạn có phản hồi mới/ })).toBeNull();
    expect(router.push).not.toHaveBeenCalled();
  });

  it("thông báo đã đọc không gọi lại PATCH khi mở", async () => {
    vi.mocked(notificationApi.list).mockResolvedValue({ items: [item({ read: true })], next_cursor: null });
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);

    fireEvent.click(await screen.findByRole("button", { name: /Đánh giá của bạn có phản hồi mới/ }));

    expect(router.push).toHaveBeenCalledWith("/reviews/r-1");
    expect(notificationApi.setRead).not.toHaveBeenCalled();
  });

  it("đổi trạng thái đọc và ẩn khỏi hộp thư bằng nút có nhãn", async () => {
    vi.mocked(notificationApi.list).mockResolvedValue({ items: [item(), item({ id: "n-2", title: "Đơn hàng đã giao", read: true, deep_link: null })], next_cursor: null });
    vi.mocked(notificationApi.setRead).mockResolvedValue(item({ read: true }));
    vi.mocked(notificationApi.archive).mockResolvedValue(undefined);
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);
    await screen.findByText("Đơn hàng đã giao");

    fireEvent.click(screen.getByRole("button", { name: "Đánh dấu đã đọc" }));
    await waitFor(() => expect(notificationApi.setRead).toHaveBeenCalledWith("n-1", true));

    fireEvent.click(screen.getAllByRole("button", { name: "Ẩn khỏi hộp thư" })[1]);
    await waitFor(() => expect(notificationApi.archive).toHaveBeenCalledWith("n-2"));
    await waitFor(() => expect(screen.queryByText("Đơn hàng đã giao")).toBeNull());
  });

  it("đánh dấu tất cả đã đọc", async () => {
    vi.mocked(notificationApi.list).mockResolvedValue({ items: [item()], next_cursor: null });
    vi.mocked(notificationApi.markAllRead).mockResolvedValue(undefined);
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);
    await screen.findByText("Đánh giá của bạn có phản hồi mới");

    fireEvent.click(screen.getByRole("button", { name: /Đánh dấu tất cả đã đọc/ }));

    await waitFor(() => expect(notificationApi.markAllRead).toHaveBeenCalledTimes(1));
  });

  it("tab chưa đọc gọi unread_only và rỗng thì khen đã đọc hết", async () => {
    vi.mocked(notificationApi.list).mockResolvedValue({ items: [], next_cursor: null });
    renderWithClient(<NotificationsPageContent unreadOnly />);

    expect(await screen.findByText("Bạn đã đọc hết thông báo")).toBeTruthy();
    expect(notificationApi.list).toHaveBeenCalledWith({ limit: 20, cursor: undefined, unread_only: true }, expect.anything());
  });

  it("lỗi tải có nút thử lại và không lộ chi tiết kỹ thuật", async () => {
    vi.mocked(notificationApi.list).mockRejectedValue(new Error("pq: relation does not exist"));
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);
    expect(await screen.findByRole("button", { name: /Thử lại/ })).toBeTruthy();
    expect(screen.queryByText(/relation does not exist/)).toBeNull();
  });
});
