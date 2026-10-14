// qrcode-generator (server/src/vendor/qrcode-generator.ts), bundled by
// vite.config.ts as the module "virtual:qrcode-generator": the part of its
// API that src/ui/qr.ts uses.
declare module "virtual:qrcode-generator" {
  interface QrCode {
    addData(data: string, mode?: "Numeric" | "Alphanumeric" | "Byte" | "Kanji"): void;
    make(): void;
    getModuleCount(): number;
    isDark(row: number, col: number): boolean;
  }
  /** typeNumber 0 picks the smallest version that holds the data. */
  const qrcode: (typeNumber: number, errorCorrectionLevel: "L" | "M" | "Q" | "H") => QrCode;
  export default qrcode;
}
