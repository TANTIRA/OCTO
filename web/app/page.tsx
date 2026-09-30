import { Contact } from "@/components/landing/contact";
import { Faq } from "@/components/landing/faq";
import { Footer } from "@/components/landing/footer";
import { Hero } from "@/components/landing/hero";
import { Nav } from "@/components/landing/nav";
import { OctoCore } from "@/components/landing/octo-core";
import { Ontology } from "@/components/landing/ontology";
import { Platform } from "@/components/landing/platform";
import { Security } from "@/components/landing/security";
import { Stats } from "@/components/landing/stats";

export default function Page() {
  return (
    <div className="landing bg-black font-display">
      <Nav />
      <main>
        <Hero />
        <Stats />
        <Platform />
        <Ontology />
        <Security />
        <Faq />
        <Contact />
      </main>
      <Footer />
      <OctoCore />
    </div>
  );
}
