package com.kangban.tianjinmcp;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

@Service
public class MedicalInfoCatalog {

    private final CopyOnWriteArrayList<MedicalPublicRecord> records = new CopyOnWriteArrayList<>();

    public int importRecords(List<MedicalPublicRecord> imported) {
        if (imported == null || imported.isEmpty()) {
            return 0;
        }
        imported.forEach(this::validate);
        for (MedicalPublicRecord record : imported) {
            records.removeIf(existing -> existing.hospitalId().equals(record.hospitalId())
                    && Objects.equals(existing.doctorName(), record.doctorName())
                    && Objects.equals(existing.department(), record.department()));
            records.add(record);
        }
        return imported.size();
    }

    public List<MedicalPublicRecord> searchHospitals(String keyword, String hospitalLevel, String department) {
        return search(record -> matches(record, keyword)
                && matches(record.hospitalLevel(), hospitalLevel)
                && matches(record.department(), department));
    }

    public List<MedicalPublicRecord> searchDoctors(String keyword, String department,
                                                   String hospitalName, String doctorTitle) {
        return search(record -> record.doctorName() != null
                && matches(record.doctorName(), keyword)
                && matches(record.department(), department)
                && matches(record.hospitalName(), hospitalName)
                && matches(record.doctorTitle(), doctorTitle));
    }

    public List<MedicalPublicRecord> searchDepartments(String keyword, String hospitalName) {
        return search(record -> record.department() != null
                && matches(record.department(), keyword)
                && matches(record.hospitalName(), hospitalName));
    }

    public List<MedicalPublicRecord> getHospitalInfo(String hospitalId, String hospitalName) {
        return search(record -> matches(record.hospitalId(), hospitalId)
                || matches(record.hospitalName(), hospitalName));
    }

    public List<MedicalPublicRecord> snapshot() {
        return List.copyOf(records);
    }

    private List<MedicalPublicRecord> search(Predicate<MedicalPublicRecord> predicate) {
        return records.stream()
                .filter(predicate)
                .sorted(Comparator.comparing(MedicalPublicRecord::hospitalName)
                        .thenComparing(record -> record.department() == null ? "" : record.department()))
                .limit(20)
                .toList();
    }

    private boolean matches(MedicalPublicRecord record, String keyword) {
        return matches(record.hospitalName(), keyword)
                || matches(record.address(), keyword)
                || matches(record.department(), keyword);
    }

    private boolean matches(String value, String expected) {
        return expected == null || expected.isBlank()
                || (value != null && value.toLowerCase(Locale.ROOT)
                .contains(expected.trim().toLowerCase(Locale.ROOT)));
    }

    private void validate(MedicalPublicRecord record) {
        if (record == null || record.hospitalId() == null || record.hospitalId().isBlank()
                || record.hospitalName() == null || record.hospitalName().isBlank()
                || record.sourceUrl() == null || record.sourceUrl().isBlank()) {
            throw new IllegalArgumentException("公共医疗目录记录缺少必填字段");
        }
        if (record.hospitalId().length() > 100 || record.hospitalName().length() > 200) {
            throw new IllegalArgumentException("公共医疗目录字段长度超限");
        }
    }
}
