// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider, type InfiniteData } from "@tanstack/react-query";

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
import { notificationApi, type InboxItem, type InboxPage } from "@/lib/api/notification";
import { notificationKeys } from "@/hooks/use-notifications";
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

function renderWithClient(ui: React.ReactElement, client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  const wrap = (content: React.ReactElement) => <QueryClientProvider client={client}><ToastProvider>{content}</ToastProvider></QueryClientProvider>;
  const result = render(wrap(ui));
  return { ...result, client, rerender: (content: React.ReactElement) => result.rerender(wrap(content)) };
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

  it("bỏ thông báo cuối khỏi tab chưa đọc ngay cả khi tải lại chưa hoàn tất", async () => {
    vi.mocked(notificationApi.list)
      .mockResolvedValueOnce({ items: [item()], next_cursor: null })
      .mockImplementation(() => new Promise(() => {}));
    vi.mocked(notificationApi.setRead).mockResolvedValue(item({ read: true }));
    renderWithClient(<NotificationsPageContent unreadOnly />);
    fireEvent.click(await screen.findByRole("button", { name: "Đánh dấu đã đọc" }));

    expect(await screen.findByText("Bạn đã đọc hết thông báo")).toBeTruthy();
    expect(screen.queryByText("Đánh giá của bạn có phản hồi mới")).toBeNull();
    await waitFor(() => expect(notificationApi.list).toHaveBeenCalledTimes(2));
  });

  it("bỏ thông báo đã đọc trên trang tiếp theo nhưng giữ nó trong tab tất cả", async () => {
    const first = item({ id: "n-2", title: "Thông báo trang đầu" });
    const cache: InfiniteData<InboxPage, string | undefined> = {
      pages: [{ items: [first], next_cursor: "page-2" }, { items: [item()], next_cursor: null }],
      pageParams: [undefined, "page-2"],
    };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    client.setQueryData(notificationKeys.list("buyer-a", true), cache);
    client.setQueryData(notificationKeys.list("buyer-a", false), cache);
    vi.mocked(notificationApi.list).mockImplementation(() => new Promise(() => {}));
    vi.mocked(notificationApi.setRead).mockResolvedValue(item({ read: true }));
    renderWithClient(<NotificationsPageContent unreadOnly />, client);
    fireEvent.click(screen.getAllByRole("button", { name: "Đánh dấu đã đọc" })[1]);

    await waitFor(() => expect(screen.queryByText("Đánh giá của bạn có phản hồi mới")).toBeNull());
    expect(screen.getByText("Thông báo trang đầu")).toBeTruthy();
    const unread = client.getQueryData<InfiniteData<InboxPage>>(notificationKeys.list("buyer-a", true));
    const all = client.getQueryData<InfiniteData<InboxPage>>(notificationKeys.list("buyer-a", false));
    expect(unread?.pages.map((page) => page.items.map((entry) => entry.id))).toEqual([["n-2"], []]);
    expect(unread?.pageParams).toEqual([undefined, "page-2"]);
    expect(all?.pages[1].items[0]).toMatchObject({ id: "n-1", read: true });
  });

  it("đánh dấu chưa đọc ở tab tất cả làm mới tab chưa đọc đã được cache", async () => {
    let current = item({ read: true });
    vi.mocked(notificationApi.list).mockImplementation(async (params) => ({
      items: params?.unread_only && current.read ? [] : [current], next_cursor: null,
    }));
    vi.mocked(notificationApi.setRead).mockImplementation(async () => {
      current = item();
      return current;
    });
    const view = renderWithClient(<NotificationsPageContent unreadOnly />);
    await screen.findByText("Bạn đã đọc hết thông báo");
    view.rerender(<NotificationsPageContent unreadOnly={false} />);
    fireEvent.click(await screen.findByRole("button", { name: "Đánh dấu chưa đọc" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Đánh dấu đã đọc" }).hasAttribute("disabled")).toBe(false));
    view.rerender(<NotificationsPageContent unreadOnly />);

    expect(await screen.findByText("Đánh giá của bạn có phản hồi mới")).toBeTruthy();
    expect(screen.queryByText("Bạn đã đọc hết thông báo")).toBeNull();
  });

  it("lỗi tải có nút thử lại và không lộ chi tiết kỹ thuật", async () => {
    vi.mocked(notificationApi.list).mockRejectedValue(new Error("pq: relation does not exist"));
    renderWithClient(<NotificationsPageContent unreadOnly={false} />);
    expect(await screen.findByRole("button", { name: /Thử lại/ })).toBeTruthy();
    expect(screen.queryByText(/relation does not exist/)).toBeNull();
  });
});
