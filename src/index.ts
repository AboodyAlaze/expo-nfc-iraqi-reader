import ExpoNfcIraqiReaderModule from './ExpoNfcIraqiReaderModule';
import type { IdData, ScanOptions, ScanProgressEvent, ScanStage } from './ExpoNfcIraqiReader.types';

export type { IdData, ScanOptions, ScanProgressEvent, ScanStage };

/** الاشتراك اللي ترجعه دوال الاستماع — استدعِ remove() لإلغائه */
export type Subscription = { remove: () => void };

/** هل الجهاز يدعم NFC وهو مفعّل؟ */
export function isAvailable(): boolean {
  return ExpoNfcIraqiReaderModule.isAvailable();
}

/**
 * يبدأ جلسة القراءة ويرجع بيانات البطاقة.
 * القيم الثلاث تُقرأ من الـ MRZ المطبوع على ظهر البطاقة.
 */
export async function scan(options: ScanOptions): Promise<IdData> {
  return ExpoNfcIraqiReaderModule.scan(options);
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
  scan,
  cancel,
  addProgressListener,
};
