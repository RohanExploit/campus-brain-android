"use client";

import { useEffect, useRef } from "react";
import { SendIcon, AlertIcon } from "@/components/icons";
import { MicIcon, StopIcon } from "./icons";
import { useSpeechInput } from "./useSpeechInput";
import s from "./mobile.module.css";

/**
 * Thumb-reachable input, pinned to the bottom of the viewport and padded past
 * the gesture bar. Grows to a few lines then scrolls internally, so the thread
 * above it never gets squeezed off-screen.
 */
export default function Composer({
  value,
  onChange,
  onSubmit,
  onStop,
  busy,
}: {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  onStop: () => void;
  busy: boolean;
}) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const speech = useSpeechInput(onChange);

  // Auto-size to content up to the CSS max-height, then let it scroll.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [value]);

  const canSend = value.trim().length > 0 && !busy;

  return (
    <div className={s.composer}>
      <div className={s.composerRow}>
        {speech.supported && (
          <button
            type="button"
            className={`${s.iconBtn} ${speech.listening ? s.micLive : ""}`}
            onClick={() => (speech.listening ? speech.stop() : speech.start())}
            aria-label={speech.listening ? "Stop listening" : "Speak your question"}
            aria-pressed={speech.listening}
          >
            <MicIcon size={20} />
          </button>
        )}

        <div className={s.fieldWrap}>
          <textarea
            ref={ref}
            className={s.field}
            rows={1}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={(e) => {
              // Enter sends on a hardware keyboard; the on-screen keyboard's
              // return key inserts a newline via shift-less "enterKeyHint".
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                if (canSend) onSubmit();
              }
            }}
            placeholder="Ask about students, fees, policy…"
            enterKeyHint="send"
            inputMode="text"
            aria-label="Your question"
          />
          <button
            type="button"
            className={`${s.send} ${busy ? s.sendStop : ""}`}
            onClick={busy ? onStop : onSubmit}
            disabled={!busy && !canSend}
            aria-label={busy ? "Stop this query" : "Send question"}
          >
            {busy ? <StopIcon size={16} /> : <SendIcon size={16} />}
          </button>
        </div>
      </div>

      {/* No pretending: the mic is the browser recogniser, and the offline
          Indic path is future work, so both are said out loud. */}
      {speech.error ? (
        <div className={`${s.composerNote} ${s.composerNoteLive}`}>
          <AlertIcon size={12} /> {speech.error}
        </div>
      ) : speech.listening ? (
        <div className={`${s.composerNote} ${s.composerNoteLive}`}>
          Listening — browser speech, English. Speak your question.
        </div>
      ) : speech.supported ? (
        <div className={s.composerNote}>
          Mic uses the browser recogniser (English). On-device Marathi &amp; Hindi
          speech — the offline path — lands in the 30-hour build.
        </div>
      ) : (
        <div className={s.composerNote}>
          Voice input needs Chrome. On-device Marathi &amp; Hindi speech lands in the
          30-hour build.
        </div>
      )}
    </div>
  );
}
