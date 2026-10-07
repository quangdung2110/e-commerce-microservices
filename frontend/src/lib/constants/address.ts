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

// Giả lập dữ liệu Tỉnh/Thành phố, Quận/Huyện, Phường/Xã
export const VIETNAM_ADDRESS_DATA: Province[] = [
  {
    id: "79",
    name: "Thành phố Hồ Chí Minh",
    districts: [
      {
        id: "760",
        name: "Quận 1",
        wards: [
          { id: "26734", name: "Phường Bến Nghé" },
          { id: "26737", name: "Phường Bến Thành" },
          { id: "26740", name: "Phường Đa Kao" },
        ],
      },
      {
        id: "761",
        name: "Quận 12",
        wards: [
          { id: "26767", name: "Phường Thạnh Xuân" },
          { id: "26770", name: "Phường Thạnh Lộc" },
        ],
      },
      {
        id: "764",
        name: "Quận Gò Vấp",
        wards: [
          { id: "26875", name: "Phường 1" },
          { id: "26881", name: "Phường 3" },
          { id: "26884", name: "Phường 4" },
        ],
      },
    ],
  },
  {
    id: "01",
    name: "Thành phố Hà Nội",
    districts: [
      {
        id: "001",
        name: "Quận Ba Đình",
        wards: [
          { id: "00001", name: "Phường Phúc Xá" },
          { id: "00004", name: "Phường Trúc Bạch" },
        ],
      },
      {
        id: "002",
        name: "Quận Hoàn Kiếm",
        wards: [
          { id: "00037", name: "Phường Phúc Tân" },
          { id: "00040", name: "Phường Đồng Xuân" },
        ],
      },
    ],
  },
  {
    id: "48",
    name: "Thành phố Đà Nẵng",
    districts: [
      {
        id: "490",
        name: "Quận Liên Chiểu",
        wards: [
          { id: "20194", name: "Phường Hòa Hiệp Bắc" },
          { id: "20195", name: "Phường Hòa Hiệp Nam" },
        ],
      },
      {
        id: "492",
        name: "Quận Hải Châu",
        wards: [
          { id: "20215", name: "Phường Thanh Bình" },
          { id: "20218", name: "Phường Thuận Phước" },
        ],
      },
    ],
  },
];

/**
 * Tra tên hành chính từ mã mà User Service lưu.
 *
 * Backend chỉ kiểm tra *định dạng* mã (`^[A-Za-z0-9_-]{1,20}$`), không đối
 * chiếu xem phường có thuộc quận hay không — đó là việc của một master-data
 * contract chưa tồn tại. Vì vậy bảng dưới đây là nguồn duy nhất để đổi mã thành
 * tên khi hiển thị, và nó là dữ liệu mẫu, chưa đầy đủ 63 tỉnh thành.
 */
export function findProvince(code: string): Province | undefined {
  return VIETNAM_ADDRESS_DATA.find((province) => province.id === code);
}

export function findDistrict(
  provinceCode: string,
  districtCode: string
): District | undefined {
  return findProvince(provinceCode)?.districts.find(
    (district) => district.id === districtCode
  );
}

export function findWard(
  provinceCode: string,
  districtCode: string,
  wardCode: string
): Ward | undefined {
  return findDistrict(provinceCode, districtCode)?.wards.find(
    (ward) => ward.id === wardCode
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
