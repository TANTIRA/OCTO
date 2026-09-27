"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import {
  ConnectionProvider,
  WalletProvider,
  useWallet,
} from "@solana/wallet-adapter-react";
import {
  WalletModalProvider,
  useWalletModal,
} from "@solana/wallet-adapter-react-ui";
import { clusterApiUrl } from "@solana/web3.js";
import { Loader2, Wallet } from "lucide-react";
import { supabase } from "@/lib/supabase";

import "@solana/wallet-adapter-react-ui/styles.css";

/**
 * Sign in with Solana: the wallet signs a SIWS challenge and GoTrue issues the
 * same session an email login would — so the API's JWKS verification and the
 * tenant checks behind it apply unchanged. A first sign-in creates the account
 * (subject to GoTrue's sign-up flag); tenant access is still granted separately.
 * Rendered only client-side — wallets are browser objects.
 */
export default function WalletSignIn({
  onError,
  className,
}: {
  onError: (message: string | null) => void;
  className?: string;
}) {
  // SIWS never talks to the RPC — the endpoint exists only because
  // ConnectionProvider requires one.
  const endpoint = useMemo(
    () => process.env.NEXT_PUBLIC_SOLANA_RPC_URL ?? clusterApiUrl("devnet"),
    [],
  );
  // Standard-compatible wallets (Phantom, Solflare, Backpack, …) announce
  // themselves via wallet-standard; no adapter list to maintain.
  const wallets = useMemo(() => [], []);
  return (
    <ConnectionProvider endpoint={endpoint}>
      <WalletProvider wallets={wallets} autoConnect={false}>
        <WalletModalProvider>
          <WalletSignInButton onError={onError} className={className} />
        </WalletModalProvider>
      </WalletProvider>
    </ConnectionProvider>
  );
}

function WalletSignInButton({
  onError,
  className,
}: {
  onError: (message: string | null) => void;
  className?: string;
}) {
  const { publicKey, signMessage, connected } = useWallet();
  const { visible, setVisible } = useWalletModal();
  const [pending, setPending] = useState(false);
  // One SIWS handshake at a time — the click path and the post-connect effect
  // can both reach signIn for the same press.
  const signing = useRef(false);

  const signIn = async () => {
    if (signing.current) return;
    if (!supabase || !publicKey || !signMessage) {
      setPending(false);
      onError(
        supabase
          ? "This wallet cannot sign messages — pick one that does."
          : null,
      );
      return;
    }
    signing.current = true;
    try {
      const { error } = await supabase.auth.signInWithWeb3({
        chain: "solana",
        statement: "Sign in to OCTO with your Solana wallet.",
        wallet: { publicKey, signMessage },
      });
      if (error) throw error;
      window.location.assign("/app");
    } catch (failure) {
      signing.current = false;
      setPending(false);
      onError(
        failure instanceof Error ? failure.message : "Wallet sign-in failed",
      );
    }
  };

  // Picking a wallet in the modal resolves `connected` — finish the handshake
  // without a second click.
  useEffect(() => {
    if (pending && connected) void signIn();
  }, [pending, connected]);

  // Modal dismissed without a wallet — reset so the button is clickable again.
  useEffect(() => {
    if (!visible && !connected) setPending(false);
  }, [visible, connected]);

  return (
    <button
      type="button"
      disabled={pending || !supabase}
      onClick={() => {
        onError(null);
        if (connected) {
          setPending(true);
          void signIn();
        } else {
          setPending(true);
          setVisible(true);
        }
      }}
      className={className}
    >
      {pending ? (
        <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
      ) : (
        <Wallet aria-hidden="true" className="h-4 w-4" />
      )}
      Solana
    </button>
  );
}
