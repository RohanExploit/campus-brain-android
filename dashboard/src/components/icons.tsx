/**
 * Drawn icon set — single consistent stroke system (24px grid, 1.75 stroke,
 * currentColor), replacing the Unicode glyphs the dashboard used as icons.
 * Import { Icon } and reference by name, or use a named export directly.
 */
import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Svg({ size = 16, children, ...rest }: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const SearchIcon = (p: IconProps) => (
  <Svg {...p}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.2-3.2" /></Svg>
);
export const LayersIcon = (p: IconProps) => (
  <Svg {...p}><path d="M12 3 4 7l8 4 8-4-8-4Z" /><path d="m4 12 8 4 8-4" /><path d="m4 17 8 4 8-4" /></Svg>
);
export const FlagIcon = (p: IconProps) => (
  <Svg {...p}><path d="M5 21V4" /><path d="M5 4h11l-2 3.5L16 11H5" /></Svg>
);
export const ActivityIcon = (p: IconProps) => (
  <Svg {...p}><path d="M3 12h3.5l2.5-7 4 14 2.5-7H21" /></Svg>
);
export const LibraryIcon = (p: IconProps) => (
  <Svg {...p}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 9h18" /><path d="M8 4v5" /><path d="M13 13h5" /><path d="M13 16.5h3" /></Svg>
);
export const UploadIcon = (p: IconProps) => (
  <Svg {...p}><path d="M12 15V4" /><path d="m7.5 8.5 4.5-4.5 4.5 4.5" /><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></Svg>
);
export const ShieldCheckIcon = (p: IconProps) => (
  <Svg {...p}><path d="M12 3 5 6v5c0 4.2 2.9 7.6 7 9 4.1-1.4 7-4.8 7-9V6l-7-3Z" /><path d="m9.2 11.8 2 2 3.6-3.8" /></Svg>
);
export const SendIcon = (p: IconProps) => (
  <Svg {...p}><path d="M22 3 11 14" /><path d="M22 3 15 21l-4-7-7-4 18-7Z" /></Svg>
);
export const RefreshIcon = (p: IconProps) => (
  <Svg {...p}><path d="M20 11a8 8 0 0 0-14-4.5L4 8" /><path d="M4 4v4h4" /><path d="M4 13a8 8 0 0 0 14 4.5L20 16" /><path d="M20 20v-4h-4" /></Svg>
);
export const AlertIcon = (p: IconProps) => (
  <Svg {...p}><path d="M12 4 2.5 20h19L12 4Z" /><path d="M12 10v4" /><path d="M12 17.5v.01" /></Svg>
);
export const CheckIcon = (p: IconProps) => (
  <Svg {...p}><path d="m4.5 12.5 4.5 4.5 10.5-11" /></Svg>
);
export const CloseIcon = (p: IconProps) => (
  <Svg {...p}><path d="M6 6 18 18" /><path d="M18 6 6 18" /></Svg>
);
export const DatabaseIcon = (p: IconProps) => (
  <Svg {...p}><ellipse cx="12" cy="5.5" rx="7" ry="3" /><path d="M5 5.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /><path d="M5 11.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /></Svg>
);
export const ChipIcon = (p: IconProps) => (
  <Svg {...p}><rect x="7" y="7" width="10" height="10" rx="1.5" /><path d="M10 3v3M14 3v3M10 18v3M14 18v3M3 10h3M3 14h3M18 10h3M18 14h3" /></Svg>
);
export const ClockIcon = (p: IconProps) => (
  <Svg {...p}><circle cx="12" cy="12" r="8" /><path d="M12 8v4l2.5 1.5" /></Svg>
);
export const SparkIcon = (p: IconProps) => (
  <Svg {...p}><path d="M12 3v18M3 12h18" opacity="0" /><path d="M12 2.5c.6 4.6 2.4 6.4 7 7-4.6.6-6.4 2.4-7 7-.6-4.6-2.4-6.4-7-7 4.6-.6 6.4-2.4 7-7Z" /></Svg>
);
export const DocIcon = (p: IconProps) => (
  <Svg {...p}><path d="M6 3h8l4 4v14H6V3Z" /><path d="M14 3v4h4" /><path d="M9 13h6M9 16.5h6" /></Svg>
);

export type IconName =
  | "search" | "layers" | "flag" | "activity" | "library" | "upload"
  | "shield" | "send" | "refresh" | "alert" | "check" | "close"
  | "database" | "chip" | "clock" | "spark" | "doc";

const MAP: Record<IconName, (p: IconProps) => React.ReactElement> = {
  search: SearchIcon, layers: LayersIcon, flag: FlagIcon, activity: ActivityIcon,
  library: LibraryIcon, upload: UploadIcon, shield: ShieldCheckIcon, send: SendIcon,
  refresh: RefreshIcon, alert: AlertIcon, check: CheckIcon, close: CloseIcon,
  database: DatabaseIcon, chip: ChipIcon, clock: ClockIcon, spark: SparkIcon, doc: DocIcon,
};

export function Icon({ name, ...rest }: IconProps & { name: IconName }) {
  const C = MAP[name];
  return <C {...rest} />;
}
