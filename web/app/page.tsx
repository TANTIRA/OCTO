import Navigation2 from "@/components/blocks/navigation-2";
import Hero15 from "@/components/blocks/hero-15";
import HowItWorks5 from "@/components/blocks/how-it-works-5";
import Features4 from "@/components/blocks/features-4";
import Faq2 from "@/components/blocks/faq-2";
import Cta2 from "@/components/blocks/cta-2";
import Contact10 from "@/components/blocks/contact-10";
import Footer12 from "@/components/blocks/footer-12";

/**
 * Mesta-Asset landing page
 *
 * Composed with the React Bits Landing Builder.
 *
 * The wrapper below sets `--rb-section-min-h: 0px`, which lets content
 * sections take their natural height instead of each filling the viewport.
 * Remove it and every section reverts to full-screen, which is the correct
 * behaviour when a block is used on its own.
 */
export default function Page() {
  return (
    <main
      className="w-full"
      style={{ "--rb-section-min-h": "0px" } as React.CSSProperties}
    >
      <Navigation2 />
      <Hero15 />
      <HowItWorks5 />
      <Features4 />
      <Faq2 />
      <Cta2 />
      <Contact10 />
      <Footer12 />
    </main>
  );
}
