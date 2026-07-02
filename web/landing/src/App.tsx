import { Nav } from "@/components/site/Nav";
import { Hero } from "@/components/site/Hero";
import { Architecture } from "@/components/site/Architecture";
import { ProofPacket } from "@/components/site/ProofPacket";
import { Showcase } from "@/components/site/Showcase";
import { Waitlist } from "@/components/site/Waitlist";
import { Footer } from "@/components/site/Footer";
import { useReveal } from "@/lib/reveal";

export function App() {
  useReveal();
  return (
    <main className="min-h-screen bg-background text-foreground selection:bg-primary selection:text-primary-foreground">
      <Nav />
      <Hero />
      <Architecture />
      <ProofPacket />
      <Showcase />
      <Waitlist />
      <Footer />
    </main>
  );
}
