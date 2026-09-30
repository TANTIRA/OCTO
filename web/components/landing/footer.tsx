"use client";

import { animate, onScroll, splitText, stagger, utils } from "animejs";
import Image from "next/image";
import wordmark from "@/public/mesta-wordmark-white.png";
import { EASE, useAnime } from "./motion";

const COLUMNS = [
  {
    title: "Platform",
    links: [
      ["Book of record", "#platform"],
      ["Ontology", "#ontology"],
      ["Security", "#security"],
    ],
  },
  {
    title: "Build",
    links: [
      ["API reference", "https://api-octo.mesta.click"],
      ["Status", "https://admin-octo.mesta.click"],
      ["GitHub", "https://github.com/TANTIRA"],
    ],
  },
  {
    title: "Company",
    links: [
      ["FAQ", "#faq"],
      ["Contact", "#contact"],
      ["Log in", "/login"],
    ],
  },
];

export function Footer() {
  const root = useAnime<HTMLElement>((scope, reduce) => {
    if (reduce) return;
    const giant = (scope.root as HTMLElement).querySelector<HTMLElement>(".giant")!;
    const { chars } = splitText(giant, { chars: { wrap: "clip" } });
    utils.set(giant, { opacity: 1 });
    animate(chars, {
      translateY: ["105%", "0%"],
      duration: 1400,
      delay: stagger(70),
      ease: EASE,
      autoplay: onScroll({ target: giant, enter: "95% start" }),
    });
  });

  return (
    <footer ref={root} className="overflow-hidden bg-black px-4 pt-20 text-white sm:px-6 lg:px-8">
      <div className="mx-auto max-w-[1320px] border-t border-white/10 pt-14">
        <div className="grid gap-12 md:grid-cols-[1.4fr_repeat(3,1fr)]">
          <div>
            <Image src={wordmark} alt="Mesta" width={99} className="h-7 w-auto" />
            <p className="mt-5 max-w-xs text-sm leading-relaxed text-white/50">
              One database, one system, one process — for private markets.
            </p>
          </div>
          {COLUMNS.map((col) => (
            <nav key={col.title} aria-label={col.title}>
              <h3 className="text-xs font-medium text-white/40">{col.title}</h3>
              <ul className="mt-4 space-y-3">
                {col.links.map(([text, href]) => (
                  <li key={text}>
                    <a
                      href={href}
                      {...(href.startsWith("http") && { target: "_blank", rel: "noopener noreferrer" })}
                      className="text-sm text-white/80 transition-colors hover:text-white"
                    >
                      {text}
                    </a>
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </div>

        <p
          data-anim
          aria-hidden
          className="giant mt-20 select-none font-display text-[27vw] font-semibold leading-[0.8] tracking-[-0.07em] lg:text-[22rem]"
        >
          OCTO
        </p>

        <div className="flex flex-col gap-2 border-t border-white/10 py-6 text-xs text-white/40 sm:flex-row sm:justify-between">
          <span suppressHydrationWarning>© {new Date().getFullYear()} Mesta. All rights reserved.</span>
          <span>Private beta</span>
        </div>
      </div>
    </footer>
  );
}
