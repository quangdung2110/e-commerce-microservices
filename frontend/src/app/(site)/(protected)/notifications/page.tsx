import { NotificationsPageContent } from "@/components/notifications/notifications-page-content";

export const metadata = {
  title: "Thông báo - TechNova",
  description: "Hộp thư thông báo về đơn hàng, đánh giá và tài khoản",
};

export default async function NotificationsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  return <NotificationsPageContent unreadOnly={params.filter === "unread"} />;
}
