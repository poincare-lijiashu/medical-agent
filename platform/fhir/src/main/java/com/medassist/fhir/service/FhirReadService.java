package com.medassist.fhir.service;

import com.medassist.fhir.repo.FhirQueries.EncounterQuery;
import com.medassist.fhir.repo.FhirQueries.MedicationRequestQuery;
import com.medassist.fhir.repo.FhirQueries.ObservationQuery;
import com.medassist.fhir.repo.FhirQueries.PatientQuery;
import com.medassist.fhir.repo.FhirRepository;
import com.medassist.fhir.repo.FhirRepository.Page;
import com.medassist.fhir.repo.FhirRows.CrosswalkRow;
import com.medassist.fhir.repo.FhirRows.EncounterRow;
import com.medassist.fhir.repo.FhirRows.MedicationRequestRow;
import com.medassist.fhir.repo.FhirRows.ObservationRow;
import com.medassist.fhir.repo.FhirRows.PatientRow;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FHIR 读取编排。
 *
 * <p>只做"取数 + 组装"，不做授权（授权在 Web 层）也不做 SQL（在仓储层）。
 * 这样同一套读取逻辑可以直接被未来的 GraphQL/批量导出复用，而不会把权限判断散开。
 */
@Service
public class FhirReadService {

    /** 只有 category=laboratory 有数据；其他类别返回空结果（合法请求，不是错误）。 */
    private static final String LABORATORY = "laboratory";

    private final FhirRepository repository;

    public FhirReadService(FhirRepository repository) {
        this.repository = repository;
    }

    public record PatientWithCrosswalks(PatientRow row, List<CrosswalkRow> crosswalks) {
    }

    public Page<PatientWithCrosswalks> searchPatients(PatientQuery query) {
        Page<PatientRow> page = repository.searchPatients(query);
        Map<UUID, List<CrosswalkRow>> crosswalks = crosswalksOf(page.rows());
        return new Page<>(page.rows().stream()
                .map(row -> new PatientWithCrosswalks(row,
                        crosswalks.getOrDefault(row.id(), List.of())))
                .toList(), page.total());
    }

    public Optional<PatientWithCrosswalks> readPatient(UUID id) {
        return repository.findPatient(id).map(row -> new PatientWithCrosswalks(row,
                repository.findCrosswalks(List.of(id))));
    }

    public Page<EncounterRow> searchEncounters(EncounterQuery query, Collection<UUID> campuses) {
        return repository.searchEncounters(query, campuses);
    }

    public Optional<EncounterRow> readEncounter(UUID id, Collection<UUID> campuses) {
        return repository.findEncounter(id, campuses);
    }

    public Page<ObservationRow> searchObservations(ObservationQuery query,
                                                   Collection<UUID> campuses) {
        if (query.category() != null && !LABORATORY.equals(query.category())) {
            return new Page<>(List.of(), 0);
        }
        return repository.searchObservations(query, campuses);
    }

    public Optional<ObservationRow> readObservation(UUID id, Collection<UUID> campuses) {
        return repository.findObservation(id, campuses);
    }

    public Page<MedicationRequestRow> searchMedicationRequests(MedicationRequestQuery query,
                                                               Collection<UUID> campuses) {
        return repository.searchMedicationRequests(query, campuses);
    }

    public Optional<MedicationRequestRow> readMedicationRequest(UUID id,
                                                                Collection<UUID> campuses) {
        return repository.findMedicationRequest(id, campuses);
    }

    private Map<UUID, List<CrosswalkRow>> crosswalksOf(List<PatientRow> rows) {
        List<UUID> ids = rows.stream().map(PatientRow::id).toList();
        return repository.findCrosswalks(ids).stream()
                .collect(Collectors.groupingBy(CrosswalkRow::patientId));
    }
}
