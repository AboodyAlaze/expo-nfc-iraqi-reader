import { useCameraPermissions } from 'expo-camera';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Button,
  Image,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import NfcReader, { IdData } from 'expo-nfc-iraqi-reader';

export default function App() {
  const [documentNumber, setDocumentNumber] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [dateOfExpiry, setDateOfExpiry] = useState('');

  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('امسح الـ MRZ أو املأ الحقول يدوياً');
  const [data, setData] = useState<IdData | null>(null);

  const [permission, requestPermission] = useCameraPermissions();

  useEffect(() => {
    const sub = NfcReader.addProgressListener((e) => setStatus(e.message));
    return () => sub.remove();
  }, []);

  const handleScanMrz = async () => {
    if (!permission?.granted) {
      const res = await requestPermission();
      if (!res.granted) {
        setStatus('صلاحية الكاميرا مرفوضة');
        return;
      }
    }

    setStatus('وجّه الكاميرا على أسطر الـ MRZ');
    try {
      const keys = await NfcReader.scanMrz();
      setDocumentNumber(keys.documentNumber);
      setDateOfBirth(keys.dateOfBirth);
      setDateOfExpiry(keys.dateOfExpiry);
      setStatus('تم قراءة الـ MRZ ✔ — هسه قرّب البطاقة للـ NFC');
    } catch (e: any) {
      setStatus(`فشل المسح: ${e?.message ?? e}`);
    }
  };

  const handleScanNfc = async () => {
    setBusy(true);
    setData(null);
    setStatus('قرّب البطاقة من ظهر الجهاز');
    try {
      const result = await NfcReader.scan({
        documentNumber,
        dateOfBirth,
        dateOfExpiry,
      });
      setData(result);
      setStatus('تمت القراءة بنجاح ✔');
    } catch (e: any) {
      setStatus(`فشل: ${e?.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  const handleScanFull = async () => {
    if (!permission?.granted) {
      const res = await requestPermission();
      if (!res.granted) {
        setStatus('صلاحية الكاميرا مرفوضة');
        return;
      }
    }

    setBusy(true);
    setData(null);
    try {
      setStatus('وجّه الكاميرا على أسطر الـ MRZ');
      const result = await NfcReader.scanFull();
      setData(result);
      setStatus('تمت القراءة بنجاح ✔');
    } catch (e: any) {
      setStatus(`فشل: ${e?.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  const ready = documentNumber.length > 0 && dateOfBirth.length === 6 && dateOfExpiry.length === 6;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>قارئ البطاقة الوطنية</Text>

      <View style={styles.button}>
        <Button title="📷 مسح + قراءة (تلقائي)" onPress={handleScanFull} disabled={busy} />
      </View>

      <View style={styles.button}>
        <Button title="📷 مسح الـ MRZ فقط" onPress={handleScanMrz} disabled={busy} />
      </View>

      <Text style={styles.divider}>— أو أدخلها يدوياً —</Text>

      <Field
        label="رقم الوثيقة من الـ MRZ"
        value={documentNumber}
        onChangeText={(t: string) => setDocumentNumber(t.toUpperCase().trim())}
      />
      <Field
        label="تاريخ الميلاد YYMMDD"
        value={dateOfBirth}
        onChangeText={setDateOfBirth}
        keyboardType="number-pad"
        maxLength={6}
      />
      <Field
        label="تاريخ الانتهاء YYMMDD"
        value={dateOfExpiry}
        onChangeText={setDateOfExpiry}
        keyboardType="number-pad"
        maxLength={6}
      />

      <View style={styles.button}>
        <Button title="قراءة NFC" onPress={handleScanNfc} disabled={busy || !ready} />
      </View>

      {busy && <ActivityIndicator style={{ marginVertical: 12 }} />}
      <Text style={styles.status}>{status}</Text>

      {data && (
        <View style={styles.results}>
          {data.faceImageBase64 && (
            <Image
              source={{ uri: `data:image/jpeg;base64,${data.faceImageBase64}` }}
              style={styles.photo}
            />
          )}

          <Text style={styles.section}>البيانات الشخصية</Text>
          <Row label="الاسم الكامل" value={data.fullNameArabic} />
          <Row label="اللقب" value={data.surnameArabic} />
          <Row label="اسم الأم" value={data.motherName} />
          <Row label="اسم الجد" value={data.grandfatherName} />
          <Row label="محل الولادة" value={data.placeOfBirth} />
          <Row label="الاسم (لاتيني)" value={data.givenNames} />
          <Row label="اللقب (لاتيني)" value={data.surname} />
          <Row label="الجنس" value={data.sex} />
          <Row label="تاريخ الميلاد" value={data.dateOfBirth} />
          <Row label="الجنسية" value={data.nationality} />

          <Text style={styles.section}>بيانات البطاقة</Text>
          <Row label="الرقم الوطني" value={data.nationalNumber} />
          <Row label="رقم الوثيقة" value={data.documentNumber} />
          <Row label="الرقم الشخصي" value={data.personalNumber} />
          <Row label="جهة الإصدار" value={data.issuingAuthority} />
          <Row label="تاريخ الإصدار" value={data.dateOfIssue} />
          <Row label="تاريخ الانتهاء" value={data.dateOfExpiry} />

          {data.availableDataGroups?.length > 0 && (
            <>
              <Text style={styles.section}>الملفات على الشريحة</Text>
              <Text style={styles.mono}>
                {data.availableDataGroups.map((g) => `DG${g}`).join('، ')}
              </Text>
            </>
          )}

          <Text style={styles.section}>MRZ الخام</Text>
          <Text style={styles.mono}>{data.rawMrz}</Text>
        </View>
      )}
    </ScrollView>
  );
}

function Field({ label, ...props }: any) {
  return (
    <View style={{ marginBottom: 10 }}>
      <Text style={styles.label}>{label}</Text>
      <TextInput style={styles.input} autoCapitalize="characters" {...props} />
    </View>
  );
}

function Row({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}: </Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff' },
  content: { padding: 20, paddingTop: 60, paddingBottom: 60 },
  title: { fontSize: 22, fontWeight: '700', marginBottom: 20, textAlign: 'right' },
  label: { fontSize: 12, color: '#666', marginBottom: 4, textAlign: 'right' },
  input: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 10,
    fontSize: 16,
  },
  button: { marginTop: 8, marginBottom: 4 },
  divider: {
    textAlign: 'center',
    color: '#999',
    marginVertical: 16,
    fontSize: 13,
  },
  status: { marginTop: 12, textAlign: 'right', color: '#333' },
  results: { marginTop: 20 },
  photo: {
    width: 150,
    height: 190,
    borderRadius: 12,
    alignSelf: 'center',
    marginBottom: 16,
  },
  section: {
    fontSize: 16,
    fontWeight: '700',
    marginTop: 16,
    marginBottom: 6,
    textAlign: 'right',
  },
  row: { flexDirection: 'row-reverse', marginVertical: 2 },
  rowLabel: { fontWeight: '600' },
  rowValue: { flexShrink: 1 },
  mono: { fontFamily: 'monospace', fontSize: 11, textAlign: 'right' },
});
