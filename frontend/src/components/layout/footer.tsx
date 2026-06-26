export function Footer() {
  return (
    <footer className="w-full bg-black border-t border-white/[0.08] mt-auto relative overflow-hidden">
      {/* Subtle bottom glow */}
      <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-[60%] h-[1px] bg-gradient-to-r from-transparent via-forest-bright/30 to-transparent shadow-[0_0_20px_rgba(40,200,64,0.3)]"></div>
      
      <div className="container mx-auto px-4 py-16 relative z-10">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-12">
          {/* Brand */}
          <div className="space-y-4">
            <div className="flex items-center gap-2 group cursor-default">
              <div className="h-8 w-8 rounded-[5px] bg-[#0c0c0f] border border-white/10 flex items-center justify-center shadow-[0_0_15px_rgba(255,255,255,0.05)] group-hover:border-forest/50 transition-all duration-300">
                <span className="text-lg font-black text-white group-hover:text-forest-bright transition-colors duration-300">T</span>
              </div>
              <span className="text-xl font-black tracking-tighter text-white/90">
                TechNova
              </span>
            </div>
            <p className="text-sm text-white/40 leading-relaxed max-w-xs">
              Trải nghiệm mua sắm thiết bị công nghệ cao cấp, chính hãng với dịch vụ chăm sóc khách hàng tận tâm nhất.
            </p>
          </div>

          {/* Links */}
          <div>
            <h4 className="font-bold mb-4 text-white/90 font-mono text-sm tracking-wide uppercase">Về chúng tôi</h4>
            <ul className="space-y-3">
              {["Giới thiệu", "Tuyển dụng", "Chính sách bảo mật", "Điều khoản sử dụng"].map((item) => (
                <li key={item}>
                  <a href="#" className="text-sm text-white/40 hover:text-forest-bright transition-colors duration-300 flex items-center gap-2 before:content-['›'] before:text-forest-bright/0 hover:before:text-forest-bright before:transition-colors">
                    {item}
                  </a>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h4 className="font-bold mb-4 text-white/90 font-mono text-sm tracking-wide uppercase">Hỗ trợ khách hàng</h4>
            <ul className="space-y-3">
              {["Trung tâm trợ giúp", "Chính sách đổi trả", "Chính sách bảo hành", "Hướng dẫn mua hàng"].map((item) => (
                <li key={item}>
                  <a href="#" className="text-sm text-white/40 hover:text-forest-bright transition-colors duration-300 flex items-center gap-2 before:content-['›'] before:text-forest-bright/0 hover:before:text-forest-bright before:transition-colors">
                    {item}
                  </a>
                </li>
              ))}
            </ul>
          </div>

          {/* Newsletter */}
          <div>
            <h4 className="font-bold mb-4 text-white/90 font-mono text-sm tracking-wide uppercase">Đăng ký nhận tin</h4>
            <p className="text-sm text-white/40 mb-4">
              Nhận thông tin ưu đãi mới nhất và cập nhật công nghệ.
            </p>
            <div className="flex gap-2">
              <input 
                type="email" 
                placeholder="Email của bạn..." 
                className="flex-1 rounded-full bg-white/[0.04] border border-white/10 px-4 py-2 text-sm text-white placeholder:text-white/20 focus:bg-white/[0.08] focus:border-forest-bright focus:ring-1 focus:ring-forest-bright outline-none transition-all duration-300 font-mono"
              />
              <button className="px-6 py-2 bg-white text-black rounded-full text-sm font-bold hover:bg-forest-bright hover:shadow-[0_0_15px_rgba(40,200,64,0.3)] transition-all duration-300 active:scale-95">
                Gửi
              </button>
            </div>
          </div>
        </div>
        
        <div className="mt-16 pt-8 border-t border-white/[0.06] flex flex-col md:flex-row items-center justify-between gap-4 text-xs font-mono text-white/30">
          <p>© 2026 TechNova. Designed for Developers.</p>
          <div className="flex items-center gap-6">
             <span className="hover:text-white transition-colors cursor-pointer">Facebook</span>
             <span className="hover:text-white transition-colors cursor-pointer">Instagram</span>
             <span className="hover:text-white transition-colors cursor-pointer">X.com</span>
          </div>
        </div>
      </div>
    </footer>
  );
}
