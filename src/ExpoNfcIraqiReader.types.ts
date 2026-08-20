export type ScanOptions = {
  /** رقم الوثيقة من الـ MRZ — 9 خانات */
  documentNumber: string;
  /** تاريخ الميلاد بصيغة YYMMDD */
  dateOfBirth: string;
  /** تاريخ الانتهاء بصيغة YYMMDD */
  dateOfExpiry: string;
};

export type IdData = {
  // من DG1 (الـ MRZ)
  documentNumber: string;
  nationalNumber: string;
  surname: string;
  givenNames: string;
  nationality: string;
  dateOfBirth: string;
  sex: string;
  dateOfExpiry: string;
  rawMrz: string;

  // من DG11 — البيانات العربية
  fullNameArabic: string;
  surnameArabic: string;
  motherName: string;
  grandfatherName: string;
  placeOfBirth: string;
  personalNumber: string;

  // من DG12
  issuingAuthority: string;
  dateOfIssue: string;

  /** الصورة كـ base64 بدون بادئة data: */
  faceImageBase64?: string | null;
  /** JPEG أو JPEG2000 */
  faceFormat?: string;

  /** أرقام الـ Data Groups الموجودة على الشريحة */
  availableDataGroups: number[];
};

export type ScanStage = 'connecting' | 'authenticating' | 'reading' | 'photo';

export type ScanProgressEvent = {
  stage: ScanStage;
  message: string;
};

export type ExpoNfcIraqiReaderModuleEvents = {
  onScanProgress: (event: ScanProgressEvent) => void;
};
