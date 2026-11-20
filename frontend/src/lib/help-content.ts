/** Public guidance follows the implemented flows; business contact/warranty terms are not invented. */
export type HelpArticle = {
  slug: string;
  title: string;
  description: string;
  sections: { title: string; paragraphs?: string[]; steps?: string[] }[];
  action: { href: string; label: string };
};

export const helpArticles: HelpArticle[] = [
  {
    slug: "about", title: "Về TechNova",
    description: "Công nghệ cho cuộc sống mỗi ngày, với thông tin rõ ràng ở từng bước mua sắm.",
    sections: [
      { title: "Chọn điều phù hợp với bạn", paragraphs: ["TechNova giúp bạn khám phá sản phẩm công nghệ, chọn phân loại, xem thông số và đọc đánh giá từ người mua. Bạn có thể tìm theo danh mục, khoảng giá, tình trạng hàng hoặc ưu đãi đang áp dụng."] },
      { title: "Biết rõ trước khi đặt hàng", paragraphs: ["Giỏ hàng và bước đặt hàng hiển thị sản phẩm, số lượng, giảm giá và tổng thanh toán. Giá và tình trạng hàng được kiểm tra lại trước khi xác nhận đơn; thông tin trên trang sản phẩm không phải là cam kết giữ hàng."] },
      { title: "Theo dõi sau khi mua", paragraphs: ["Đơn hàng của tôi tập hợp trạng thái thanh toán, giao hàng và yêu cầu trả hàng. Các thao tác khả dụng được hiển thị theo tình trạng thực tế của từng đơn."] },
    ],
    action: { href: "/search?in_stock=false", label: "Khám phá sản phẩm" },
  },
  {
    slug: "contact", title: "Liên hệ và hỗ trợ",
    description: "Tìm đúng thông tin cho đơn hàng và chuẩn bị nội dung cần hỗ trợ.",
    sections: [
      { title: "Bạn đang cần hỗ trợ một đơn hàng?", steps: ["Đăng nhập tài khoản đã đặt đơn và mở Đơn hàng của tôi.", "Chọn đơn cần kiểm tra để xem trạng thái thanh toán, giao hàng hoặc trả hàng mới nhất.", "Nếu yêu cầu đang được xử lý, theo dõi trên cùng đơn hàng; không tạo thêm đơn hoặc gửi lại yêu cầu khi chưa biết kết quả."] },
      { title: "Thông tin nên chuẩn bị", paragraphs: ["Ghi lại mã đơn hàng, tên sản phẩm, phân loại, thời điểm phát sinh và mô tả vấn đề. Không cung cấp mật khẩu, mã xác thực hoặc đầy đủ thông tin thẻ thanh toán."] },
      { title: "Kênh liên hệ trực tiếp", paragraphs: ["Email và số điện thoại hỗ trợ chính thức chưa được công bố trên cửa hàng. Trang này chưa tiếp nhận tin nhắn; vui lòng không gửi thông tin đến các địa chỉ tự suy đoán. Bạn vẫn có thể xem hướng dẫn và thực hiện các thao tác được hỗ trợ trong chi tiết đơn hàng."] },
    ],
    action: { href: "/orders", label: "Xem đơn hàng của tôi" },
  },
  {
    slug: "returns", title: "Trả hàng và hoàn tiền",
    description: "Kiểm tra điều kiện, chọn đúng sản phẩm và theo dõi yêu cầu trong đơn hàng của bạn.",
    sections: [
      { title: "Khi nào có thể yêu cầu trả hàng?", paragraphs: ["Bạn có thể gửi yêu cầu khi đơn đã hoàn thành và tất cả sản phẩm trong đơn đã được xác nhận giao. Nút Yêu cầu trả hàng chỉ xuất hiện khi đơn đáp ứng điều kiện.", "Mỗi đơn chỉ có một lần gửi yêu cầu trả hàng được hệ thống tiếp nhận. Hãy chọn đủ các sản phẩm cần trả trước khi xác nhận; quyền này không tự mở lại nếu quá trình xử lý gặp sự cố."] },
      { title: "Cách gửi yêu cầu", steps: ["Mở Đơn hàng của tôi và chọn đơn đã hoàn thành.", "Chọn Yêu cầu trả hàng, đánh dấu các sản phẩm cần trả và chọn lý do.", "Với mỗi mục đã chọn, yêu cầu áp dụng cho toàn bộ số lượng của mục đó. Kiểm tra kỹ danh sách rồi gửi một lần.", "Theo dõi tiến trình trả hàng và hoàn tiền trong chi tiết đơn."] },
      { title: "Hoàn tiền được xử lý thế nào?", paragraphs: ["Yêu cầu trả hàng được xử lý sau khi có kết quả tiếp nhận và kiểm tra hàng. Số tiền dựa trên giá thực trả của các mục đã chọn, sau phân bổ giảm giá; không mặc định bằng giá niêm yết.", "Việc gửi yêu cầu không có nghĩa tiền đã được hoàn ngay. Nếu cần kiểm tra thêm, trạng thái trên đơn sẽ hướng dẫn bạn theo dõi; không gửi yêu cầu mới để thay thế yêu cầu đang xử lý."] },
      { title: "Nếu muốn hủy đơn", paragraphs: ["Bạn có thể yêu cầu hủy khi đơn đang chờ thanh toán và nút Hủy đơn còn khả dụng. Với đơn đã thanh toán, thao tác hủy này không áp dụng; hãy theo dõi các lựa chọn được hiển thị trong chi tiết đơn."] },
    ],
    action: { href: "/orders", label: "Kiểm tra đơn hàng" },
  },
  {
    slug: "warranty", title: "Thông tin bảo hành",
    description: "Kiểm tra điều kiện của đúng sản phẩm trước khi mua hoặc yêu cầu xử lý.",
    sections: [
      { title: "Điều kiện theo từng sản phẩm", paragraphs: ["Cửa hàng chưa công bố một thời hạn bảo hành chung cho toàn bộ sản phẩm. Không mặc định mọi sản phẩm đều có cùng thời hạn, phạm vi hoặc hình thức bảo hành.", "Hãy kiểm tra mô tả, thông số và giấy tờ đi kèm của đúng sản phẩm, phân loại đã mua. Nếu chưa có điều kiện bảo hành được công bố, bạn cần xác nhận thông tin trước khi dựa vào quyền lợi đó để quyết định mua."] },
      { title: "Chuẩn bị khi cần kiểm tra", steps: ["Lưu mã đơn hàng và thông tin sản phẩm đã mua.", "Chuẩn bị số sê-ri nếu có, mô tả lỗi và hình ảnh thể hiện tình trạng sản phẩm.", "Đối chiếu điều kiện của nhà sản xuất hoặc thông tin đi kèm sản phẩm; không gửi mật khẩu hay dữ liệu cá nhân trong thiết bị."] },
      { title: "Trả hàng và bảo hành", paragraphs: ["Trả hàng và hoàn tiền là một quy trình riêng trong Đơn hàng của tôi. Việc một đơn được phép gửi yêu cầu trả hàng không đồng nghĩa sản phẩm đã được chấp thuận bảo hành hoặc đổi mới."] },
    ],
    action: { href: "/help/returns", label: "Xem hướng dẫn trả hàng" },
  },
  {
    slug: "shipping", title: "Vận chuyển và nhận hàng",
    description: "Kiểm tra địa chỉ, chi phí và tình trạng giao hàng của đơn.",
    sections: [
      { title: "Địa chỉ nhận hàng", paragraphs: ["Chọn một địa chỉ trong sổ địa chỉ tại bước đặt hàng. Kiểm tra tên người nhận, số điện thoại và địa chỉ đầy đủ trước khi xác nhận. Bạn có thể thêm hoặc cập nhật địa chỉ trong tài khoản trước khi đặt đơn."] },
      { title: "Phí vận chuyển", paragraphs: ["Cửa hàng hiện không tính phí vận chuyển khi đặt hàng. Hãy kiểm tra tổng thanh toán hiển thị trước khi xác nhận đơn."] },
      { title: "Theo dõi giao hàng", paragraphs: ["Mở chi tiết đơn hàng để xem trạng thái của các sản phẩm. Thanh toán thành công không đồng nghĩa đơn đã được giao; đơn hoàn thành khi toàn bộ sản phẩm đã được xác nhận giao.", "Cửa hàng chưa công bố thời gian giao cố định hoặc cam kết giao trong ngày. Không suy ra ngày nhận chỉ từ thời điểm thanh toán."] },
      { title: "Khi nhận hàng", steps: ["Đối chiếu sản phẩm, phân loại và số lượng với đơn đã đặt.", "Giữ lại thông tin đơn và ghi nhận tình trạng sản phẩm nếu có vấn đề.", "Sau khi đơn hoàn thành, kiểm tra mục trả hàng nếu cần gửi yêu cầu và đơn đáp ứng điều kiện."] },
    ],
    action: { href: "/profile/addresses", label: "Kiểm tra địa chỉ nhận hàng" },
  },
  {
    slug: "terms", title: "Điều khoản sử dụng",
    description: "Những thông tin cần biết khi dùng tài khoản và đặt hàng tại TechNova.",
    sections: [
      { title: "Tài khoản của bạn", paragraphs: ["Sử dụng thông tin chính xác khi đăng ký và cung cấp địa chỉ giao hàng. Giữ bí mật mật khẩu, mã xác thực; kiểm tra các phiên đăng nhập và cập nhật bảo mật trong tài khoản khi cần."] },
      { title: "Giá và xác nhận đơn", paragraphs: ["Giá trên danh sách hoặc trang sản phẩm có thể thay đổi. Giá, ưu đãi và tình trạng hàng được kiểm tra lại ở bước đặt hàng. Chỉ tiếp tục khi bạn đã xem đúng phân loại, số lượng, địa chỉ và tổng thanh toán.", "Bảng tổng tiền tại bước đặt hàng có thời hạn hiệu lực và chưa giữ hàng. Nếu hết hạn hoặc nội dung đơn thay đổi, hãy cập nhật giá trước khi xác nhận lại."] },
      { title: "Thanh toán và yêu cầu sau mua", paragraphs: ["Thanh toán thẻ được thực hiện qua Stripe. Nếu kết quả chưa rõ, xem lại trạng thái của cùng đơn trước khi thử thanh toán lại hoặc đặt một đơn mới.", "Hủy đơn, trả hàng và hoàn tiền phụ thuộc trạng thái đơn và điều kiện được mô tả tại các trang hướng dẫn tương ứng. Hãy kiểm tra các lựa chọn hiện có trong chi tiết đơn."] },
      { title: "Nội dung đánh giá", paragraphs: ["Đánh giá nên phản ánh trải nghiệm mua hàng, không chứa thông tin nhạy cảm hoặc nội dung gây hại. Nội dung có thể được ẩn hoặc gỡ qua quy trình kiểm duyệt; theo dõi trạng thái trong mục đánh giá của bạn."] },
    ],
    action: { href: "/help/returns", label: "Xem điều kiện trả hàng" },
  },
  {
    slug: "privacy", title: "Thông tin về quyền riêng tư",
    description: "Dữ liệu phục vụ tài khoản, đơn hàng và các lựa chọn bạn có thể quản lý.",
    sections: [
      { title: "Dữ liệu phục vụ mua sắm", paragraphs: ["Thông tin tài khoản, hồ sơ, địa chỉ người nhận, giỏ hàng và đơn hàng được dùng để đăng nhập, xử lý giao dịch và hiển thị tiến trình mua sắm. Nội dung đánh giá bạn gửi có thể được hiển thị cùng tên tác giả trên trang sản phẩm sau khi được duyệt."] },
      { title: "Thanh toán và thông báo", paragraphs: ["Thông tin thẻ được nhập qua biểu mẫu thanh toán của Stripe. Không đưa số thẻ, mật khẩu hoặc mã xác thực vào đánh giá hay mô tả vấn đề.", "Bạn có thể quản lý thông báo email, trong ứng dụng và tiếp thị tại Tùy chọn thông báo. Thông báo bảo mật vẫn được gửi khi cần, kể cả khi bạn tắt các kênh thông thường."] },
      { title: "Quản lý tài khoản và dữ liệu", paragraphs: ["Bạn có thể xem và cập nhật hồ sơ, sổ địa chỉ, kiểm tra các phiên đăng nhập và gửi yêu cầu xóa tài khoản trong phần bảo mật. Hãy đọc thông báo xác nhận và thời điểm xử lý hiển thị trước khi gửi yêu cầu.", "Yêu cầu xóa tài khoản không đồng nghĩa mọi dữ liệu giao dịch được xóa ngay lập tức. Trạng thái và thời điểm xử lý được thông báo trong luồng xóa tài khoản."] },
    ],
    action: { href: "/profile/security", label: "Quản lý bảo mật tài khoản" },
  },
  {
    slug: "cookies", title: "Cookie và dữ liệu trình duyệt",
    description: "Cách cửa hàng ghi nhớ phiên đăng nhập và lựa chọn giao diện.",
    sections: [
      { title: "Cookie đăng nhập", paragraphs: ["Cookie phiên được dùng để khôi phục đăng nhập khi bạn tải lại trang. Nếu chặn hoặc xóa cookie, bạn có thể cần đăng nhập lại để xem giỏ hàng, đơn hàng và tài khoản."] },
      { title: "Các lựa chọn được ghi nhớ", paragraphs: ["Trình duyệt lưu lựa chọn giao diện sáng/tối và gợi ý email đăng nhập. Gợi ý email giúp điền lại biểu mẫu, không phải là bằng chứng xác thực. Access token của tài khoản được giữ trong bộ nhớ ứng dụng, không lưu trong localStorage."] },
      { title: "Bạn có thể quản lý thế nào?", paragraphs: ["Dùng cài đặt của trình duyệt để xem hoặc xóa dữ liệu của cửa hàng. Trên thiết bị dùng chung, hãy đăng xuất khi sử dụng xong. Xóa dữ liệu trình duyệt không xóa tài khoản hoặc lịch sử đơn hàng trên hệ thống."] },
    ],
    action: { href: "/help/privacy", label: "Tìm hiểu về quyền riêng tư" },
  },
];

export const footerColumns = [
  { title: "Về TechNova", links: [{ label: "Giới thiệu", href: "/help/about" }, { label: "Liên hệ", href: "/help/contact" }] },
  { title: "Hỗ trợ khách hàng", links: [{ label: "Trung tâm trợ giúp", href: "/help" }, { label: "Trả hàng và hoàn tiền", href: "/help/returns" }, { label: "Bảo hành", href: "/help/warranty" }, { label: "Vận chuyển", href: "/help/shipping" }] },
  { title: "Chính sách", links: [{ label: "Điều khoản sử dụng", href: "/help/terms" }, { label: "Quyền riêng tư", href: "/help/privacy" }, { label: "Cookie và dữ liệu trình duyệt", href: "/help/cookies" }] },
];

export function findHelpArticle(slug: string) {
  return helpArticles.find((article) => article.slug === slug);
}
