// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AddressForm } from "@/components/profile/AddressForm";
import { findWard, formatAddressLine, VIETNAM_ADDRESS_DATA } from "@/lib/constants/address";
import type { Address } from "@/lib/api/user";

afterEach(cleanup);
function fillContact(name = "Nguyễn An", address = "1 Đường số 2") {
  fireEvent.change(screen.getByRole("textbox", { name: "Người nhận" }), { target: { value: name } });
  fireEvent.change(screen.getByRole("textbox", { name: "Số điện thoại" }), { target: { value: "0912345678" } });
  fireEvent.change(screen.getByRole("textbox", { name: "Địa chỉ cụ thể" }), { target: { value: address } });
}
function chooseOutsideOriginalSamples() {
  const province = VIETNAM_ADDRESS_DATA.find(p => p.id === "89")!;
  const district = province.districts.find(d => d.wards.length > 0)!;
  const ward = district.wards[0];
  fireEvent.change(screen.getByRole("combobox", { name: "Tỉnh/Thành phố" }), { target: { value: province.id } });
  fireEvent.change(screen.getByRole("combobox", { name: "Quận/Huyện" }), { target: { value: district.id } });
  fireEvent.change(screen.getByRole("combobox", { name: "Phường/Xã" }), { target: { value: ward.id } });
  return { province_code: province.id, district_code: district.id, ward_code: ward.id };
}

describe("F03 address coverage", () => {
  it("exposes the full versioned three-level catalog and submits a province outside the three samples", async () => {
    const submit = vi.fn().mockResolvedValue(undefined);
    render(<AddressForm onSubmit={submit} onCancel={vi.fn()} />);
    expect((screen.getByRole("combobox", { name: "Tỉnh/Thành phố" }) as HTMLSelectElement).options.length).toBe(64);
    expect(screen.getByText(/trước tháng 7\/2025/)).toBeTruthy();
    fillContact();
    const codes = chooseOutsideOriginalSamples();
    fireEvent.click(screen.getByRole("button", { name: "Thêm địa chỉ" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith({ ...codes, recipient_name: "Nguyễn An", recipient_phone: "+84912345678", address_line: "1 Đường số 2" }));
  });
  it("accepts the backend maximum of 200/500 Unicode code points", async () => {
    const submit = vi.fn().mockResolvedValue(undefined);
    render(<AddressForm onSubmit={submit} onCancel={vi.fn()} />);
    fillContact("😀".repeat(200), "𠮷".repeat(500));
    chooseOutsideOriginalSamples();
    fireEvent.click(screen.getByRole("button", { name: "Thêm địa chỉ" }));
    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect([...submit.mock.calls[0][0].recipient_name]).toHaveLength(200);
    expect([...submit.mock.calls[0][0].address_line]).toHaveLength(500);
  });
  it("rejects values above the backend limit and invalid administrative codes", async () => {
    const submit = vi.fn();
    render(<AddressForm onSubmit={submit} onCancel={vi.fn()} />);
    fillContact("😀".repeat(201), "x".repeat(501));
    fireEvent.click(screen.getByRole("checkbox", { name: "Nhập mã địa chỉ ngoài danh mục" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Mã tỉnh/thành phố" }), { target: { value: "INVALID!!" } });
    fireEvent.click(screen.getByRole("button", { name: "Thêm địa chỉ" }));
    expect(await screen.findByText("Tối đa 200 ký tự.")).toBeTruthy();
    expect(screen.getByText("Tối đa 500 ký tự.")).toBeTruthy();
    expect(submit).not.toHaveBeenCalled();
  });
  it("preserves a saved address absent from the catalog and allows changing its codes", async () => {
    const submit = vi.fn().mockResolvedValue(undefined);
    const address = { id: "a1", recipient_name: "An", recipient_phone: "+84912345678", address_line: "Địa chỉ ngoài danh mục", province_code: "CUSTOM-P", district_code: "CUSTOM-D", ward_code: "CUSTOM-W" } as Address;
    render(<AddressForm address={address} onSubmit={submit} onCancel={vi.fn()} />);
    expect((screen.getByRole("checkbox", { name: "Nhập mã địa chỉ ngoài danh mục" }) as HTMLInputElement).checked).toBe(true);
    fireEvent.change(screen.getByRole("textbox", { name: "Mã phường/xã" }), { target: { value: "NEW-W" } });
    fireEvent.click(screen.getByRole("button", { name: "Lưu địa chỉ" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ province_code: "CUSTOM-P", district_code: "CUSTOM-D", ward_code: "NEW-W" })));
  });
  it("keeps unpadded saved codes without rewriting them and resets children when the province changes", async () => {
    const submit = vi.fn().mockResolvedValue(undefined);
    const address = { id: "a1", recipient_name: "An", recipient_phone: "+84912345678", address_line: "1 Phúc Xá", province_code: "1", district_code: "1", ward_code: "1" } as Address;
    render(<AddressForm address={address} onSubmit={submit} onCancel={vi.fn()} />);
    expect(findWard("1", "1", "1")?.name).toBeTruthy();
    expect((screen.getByRole("combobox", { name: "Tỉnh/Thành phố" }) as HTMLSelectElement).value).toBe("1");
    fireEvent.click(screen.getByRole("button", { name: "Lưu địa chỉ" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ province_code: "1", district_code: "1", ward_code: "1" })));
    fireEvent.change(screen.getByRole("combobox", { name: "Tỉnh/Thành phố" }), { target: { value: "89" } });
    expect((screen.getByRole("combobox", { name: "Quận/Huyện" }) as HTMLSelectElement).value).toBe("");
    expect((screen.getByRole("combobox", { name: "Phường/Xã" }) as HTMLSelectElement).value).toBe("");
    expect(formatAddressLine(address)).not.toContain(", 1, 1, 1");
  });
});
