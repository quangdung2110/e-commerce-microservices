import type { Metadata } from "next";

import { PreferencesForm } from "@/components/profile/PreferencesForm";

export const metadata: Metadata = {
  title: "Tuỳ chọn thông báo",
  description: "Chọn cách TechNova liên lạc với bạn.",
};

export default function PreferencesPage() {
  return <PreferencesForm />;
}
