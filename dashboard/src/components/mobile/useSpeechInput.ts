"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";

/**
 * Browser SpeechRecognition wrapper for the phone client.
 *
 * Honest scope: this is the *browser's* recogniser (Chrome on Android routes it
 * through Google's speech service). It is here so a judge can talk to the demo,
 * not as a claim about the offline stack. The on-device Marathi/Hindi recogniser
 * — the one that would keep the zero-egress promise the rest of the system keeps
 * — is not built; the UI labels it as such rather than pretending.
 *
 * Where the API is absent (Firefox, older WebViews) `supported` is false and the
 * caller hides the mic entirely: text input keeps working, nothing errors.
 */

// SpeechRecognition is not in lib.dom.d.ts, so declare the slice we use rather
// than reaching for `any`.
interface SpeechAlternative {
  transcript: string;
}
interface SpeechResult {
  readonly length: number;
  isFinal: boolean;
  [index: number]: SpeechAlternative;
}
interface SpeechResultList {
  readonly length: number;
  [index: number]: SpeechResult;
}
interface SpeechRecognitionEventLike extends Event {
  resultIndex: number;
  results: SpeechResultList;
}
interface SpeechRecognitionErrorEventLike extends Event {
  error: string;
}
interface SpeechRecognitionLike extends EventTarget {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  start(): void;
  stop(): void;
  abort(): void;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onerror: ((e: SpeechRecognitionErrorEventLike) => void) | null;
  onend: (() => void) | null;
}
type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

function getCtor(): SpeechRecognitionCtor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionCtor;
    webkitSpeechRecognition?: SpeechRecognitionCtor;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

// Feature detection as an external store: the server cannot know whether the API
// exists, so it reports false and the client corrects it on hydration without an
// effect-driven second render.
const NEVER_CHANGES = () => () => {};
const clientHasSpeech = () => getCtor() !== null;
const serverHasSpeech = () => false;

export interface SpeechInput {
  /** API present in this browser. False → caller must hide the mic. */
  supported: boolean;
  listening: boolean;
  /** Last recogniser error, already turned into something a human can read. */
  error: string | null;
  start: () => void;
  stop: () => void;
}

export function useSpeechInput(onTranscript: (text: string) => void): SpeechInput {
  const supported = useSyncExternalStore(NEVER_CHANGES, clientHasSpeech, serverHasSpeech);
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recRef = useRef<SpeechRecognitionLike | null>(null);

  useEffect(
    () => () => {
      recRef.current?.abort();
      recRef.current = null;
    },
    []
  );

  const stop = useCallback(() => {
    recRef.current?.stop();
    setListening(false);
  }, []);

  const start = useCallback(() => {
    const Ctor = getCtor();
    if (!Ctor) return;
    recRef.current?.abort();

    const rec = new Ctor();
    // en-IN, not en-US: the demo is spoken in Indian English.
    rec.lang = "en-IN";
    rec.continuous = false;
    rec.interimResults = true;
    rec.maxAlternatives = 1;

    rec.onresult = (e) => {
      let text = "";
      for (let i = 0; i < e.results.length; i++) {
        text += e.results[i][0].transcript;
      }
      onTranscript(text.trim());
    };
    rec.onerror = (e) => {
      setListening(false);
      setError(
        e.error === "not-allowed" || e.error === "service-not-allowed"
          ? "Microphone permission denied."
          : e.error === "no-speech"
            ? "Didn't catch that — try again."
            : `Speech input failed (${e.error}).`
      );
    };
    rec.onend = () => setListening(false);

    recRef.current = rec;
    setError(null);
    try {
      rec.start();
      setListening(true);
    } catch {
      setListening(false);
      setError("Could not start the microphone.");
    }
  }, [onTranscript]);

  return { supported, listening, error, start, stop };
}
