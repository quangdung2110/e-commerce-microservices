export const PROVINCES = [
  { id: "1", name: "Hà Nội" },
  { id: "79", name: "Hồ Chí Minh" },
  { id: "48", name: "Đà Nẵng" },
];

export const DISTRICTS: Record<string, { id: string; name: string }[]> = {
  "1": [
    { id: "1", name: "Quận Ba Đình" },
    { id: "2", name: "Quận Hoàn Kiếm" },
    { id: "3", name: "Quận Tây Hồ" },
  ],
  "79": [
    { id: "760", name: "Quận 1" },
    { id: "761", name: "Quận 12" },
    { id: "764", name: "Quận Gò Vấp" },
  ],
  "48": [
    { id: "490", name: "Quận Liên Chiểu" },
    { id: "491", name: "Quận Thanh Khê" },
    { id: "492", name: "Quận Hải Châu" },
  ],
};

export const WARDS: Record<string, { id: string; name: string }[]> = {
  "1": [
    { id: "1", name: "Phường Phúc Xá" },
    { id: "4", name: "Phường Trúc Bạch" },
  ],
  "2": [
    { id: "7", name: "Phường Đồng Xuân" },
    { id: "10", name: "Phường Hàng Mã" },
  ],
  "3": [
    { id: "13", name: "Phường Phú Thượng" },
    { id: "16", name: "Phường Nhật Tân" },
  ],
  "760": [
    { id: "26734", name: "Phường Tân Định" },
    { id: "26737", name: "Phường Đa Kao" },
  ],
  "761": [
    { id: "26740", name: "Phường Thạnh Xuân" },
    { id: "26743", name: "Phường Thạnh Lộc" },
  ],
  "764": [
    { id: "26746", name: "Phường 15" },
    { id: "26749", name: "Phường 13" },
  ],
  "490": [
    { id: "20194", name: "Phường Hòa Hiệp Bắc" },
    { id: "20197", name: "Phường Hòa Hiệp Nam" },
  ],
  "491": [
    { id: "20200", name: "Phường Thanh Khê Tây" },
    { id: "20203", name: "Phường Thanh Khê Đông" },
  ],
  "492": [
    { id: "20206", name: "Phường Hải Châu I" },
    { id: "20209", name: "Phường Hải Châu II" },
  ],
};
