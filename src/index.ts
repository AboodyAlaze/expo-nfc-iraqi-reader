import ExpoNfcIraqiReaderModule from './ExpoNfcIraqiReaderModule';
import type {
  IdData,
  MrzKeys,
  ScanOptions,
  ScanProgressEvent,
  ScanStage,
} from './ExpoNfcIraqiReader.types';

export type { IdData, MrzKeys, ScanOptions, ScanProgressEvent, ScanStage };

/** الاشتراك اللي ترجعه دوال الاستماع — استدعِ remove() لإلغائه */
export type Subscription = { remove: () => void };

/** هل الجهاز يدعم NFC وهو مفعّل؟ */
export function isAvailable(): boolean {
  return ExpoNfcIraqiReaderModule.isAvailable();
}

/** هل صلاحية الكاميرا ممنوحة؟ */
export function hasCameraPermission(): boolean {
  return ExpoNfcIraqiReaderModule.hasCameraPermission();
}

/**
 * يبدأ جلسة القراءة ويرجع بيانات البطاقة.
 * القيم الثلاث تُقرأ من الـ MRZ المطبوع على ظهر البطاقة.
 */
export async function scan(options: ScanOptions): Promise<IdData> {
  return ExpoNfcIraqiReaderModule.scan(options);
}

/** يفتح الكاميرا ويقرأ الـ MRZ من ظهر البطاقة */
export async function scanMrz(): Promise<MrzKeys> {
  return ExpoNfcIraqiReaderModule.scanMrz();
}

/** يمسح الـ MRZ بالكاميرا ثم يقرأ الشريحة مباشرة */
export async function scanFull(): Promise<IdData> {
  const keys = await ExpoNfcIraqiReaderModule.scanMrz();
  return ExpoNfcIraqiReaderModule.scan(keys);
}

/** إلغاء الجلسة الجارية */
export function cancel(): void {
  ExpoNfcIraqiReaderModule.cancel();
}

/** الاشتراك بتحديثات التقدّم أثناء القراءة */
export function addProgressListener(listener: (event: ScanProgressEvent) => void): Subscription {
  return ExpoNfcIraqiReaderModule.addListener('onScanProgress', listener);
}

export default {
  isAvailable,
  hasCameraPermission,
  scan,
  scanMrz,
  scanFull,
  cancel,
  addProgressListener,
};
