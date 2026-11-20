import addressCatalog from "@/content/vietnam-addresses-v1.json";

export interface Ward {
  id: string;
  name: string;
}

export interface District {
  id: string;
  name: string;
  wards: Ward[];
}

export interface Province {
  id: string;
  name: string;
  districts: District[];
}

/** Provider API v1 snapshot: legacy three-level addresses (before July 2025).
 * Matches the current User/Checkout/Order contract. Provenance: src/content/README.md.
 * Keep stored codes unchanged; numeric comparison accepts padded and unpadded codes.
 */
export const VIETNAM_ADDRESS_DATA: Province[] = addressCatalog;

export function sameAdministrativeCode(left: string, right: string): boolean {
  return left === right || (/^\d+$/.test(left) && /^\d+$/.test(right) && left.replace(/^0+/, "") === right.replace(/^0+/, ""));
}

export function findProvince(code: string): Province | undefined {
  return VIETNAM_ADDRESS_DATA.find((province) => sameAdministrativeCode(province.id, code));
}

export function findDistrict(
  provinceCode: string,
  districtCode: string
): District | undefined {
  return findProvince(provinceCode)?.districts.find(
    (district) => sameAdministrativeCode(district.id, districtCode)
  );
}

export function findWard(
  provinceCode: string,
  districtCode: string,
  wardCode: string
): Ward | undefined {
  return findDistrict(provinceCode, districtCode)?.wards.find(
    (ward) => sameAdministrativeCode(ward.id, wardCode)
  );
}

/**
 * Địa chỉ đầy đủ để hiển thị. Mã nào không tra được tên thì giữ nguyên mã, để
 * người dùng vẫn thấy dữ liệu thật thay vì một khoảng trống.
 */
export function formatAddressLine(address: {
  address_line: string;
  province_code: string;
  district_code: string;
  ward_code: string;
}): string {
  const ward =
    findWard(address.province_code, address.district_code, address.ward_code)?.name ??
    address.ward_code;
  const district =
    findDistrict(address.province_code, address.district_code)?.name ??
    address.district_code;
  const province = findProvince(address.province_code)?.name ?? address.province_code;

  return [address.address_line, ward, district, province].filter(Boolean).join(", ");
}
