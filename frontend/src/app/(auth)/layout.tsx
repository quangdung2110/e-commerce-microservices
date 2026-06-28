import Image from 'next/image';

export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen w-full flex bg-background text-foreground overflow-hidden">
      {/* Left Column: Form */}
      <div className="w-full lg:w-1/2 flex items-center justify-center p-8 sm:p-12 lg:p-24 relative z-10">
        <div className="absolute inset-0 bg-background/50 backdrop-blur-3xl -z-10 lg:hidden"></div>
        <div className="w-full max-w-md space-y-8 relative">
          <div className="absolute -top-32 -left-32 w-64 h-64 bg-primary/20 rounded-full blur-[100px] -z-10"></div>
          {children}
        </div>
      </div>

      {/* Right Column: Premium Visual */}
      <div className="hidden lg:flex w-1/2 relative bg-black items-center justify-center overflow-hidden">
        {/* Abstract Dark Space with Glowing Accents */}
        <div className="absolute inset-0 w-full h-full">
          <Image
            src="/auth_background.png"
            alt="Premium Space Background"
            fill
            className="object-cover opacity-80 mix-blend-screen"
            priority
          />
        </div>
        
        {/* Overlays for depth */}
        <div className="absolute inset-0 bg-gradient-to-r from-background via-transparent to-transparent z-10"></div>
        <div className="absolute inset-0 bg-gradient-to-t from-background/80 via-transparent to-transparent z-10"></div>
        
        {/* Dynamic decorative elements */}
        <div className="relative z-20 max-w-lg p-12 text-center animate-in fade-in zoom-in duration-1000 slide-in-from-bottom-8">
          <h2 className="text-4xl font-bold tracking-tight mb-4 text-glow text-white">
            E-Commerce Reimagined
          </h2>
          <p className="text-muted-foreground text-lg leading-relaxed">
            Experience the next generation of online shopping. Blazing fast, highly secure, and visually stunning.
          </p>
        </div>
      </div>
    </div>
  );
}
