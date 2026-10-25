package com.vayunmathur.health.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.domain.FhirRecords
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.uuid.Uuid

fun MedicalViewModel.saveAllergyDraft() {
    val draft = allergyDraftState.value
    if (draft.displayName.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getAllergy(it) }
        val detached = repository.detachIfForeign(
            MedicalKind.Allergy,
            PersonalHealthRecords.allergyResourceType,
            existing?.dataSourceId,
            existing?.fhirResourceId,
        )
        val edited = (existing ?: AllergyEntry(
            id = Uuid.random().toString(),
            displayName = "",
            recordedAt = Instant.now(),
        )).copy(
            rxcui = draft.rxcui,
            displayName = draft.displayName.trim(),
            category = draft.category,
            criticality = draft.criticality,
            reaction = draft.reaction.blankToNull(),
            onsetAt = draft.onsetOn?.toInstant(),
            note = draft.note.blankToNull(),
        )
        val entry = if (detached) edited.copy(fhirResourceId = null, dataSourceId = null) else edited
        repository.upsertAllergy(entry)
        repository.markPending(MedicalKind.Allergy, entry.id)
        mirrorAllergy(entry.id)
    }
}

fun MedicalViewModel.deleteAllergy(entry: AllergyEntry) {
    viewModelScope.launch {
        repository.deleteAllergy(entry)
        repository.forgetRow(
            MedicalKind.Allergy,
            entry.id,
            PersonalHealthRecords.allergyResourceType,
            entry.dataSourceId,
            entry.fhirResourceId,
        )
    }
}

internal suspend fun MedicalViewModel.mirrorAllergy(id: String) {
    val entry = repository.getAllergy(id) ?: return repository.clearPending(MedicalKind.Allergy, id)
    val dataSourceId = repository.mirrorTarget(MedicalKind.Allergy, id, entry.dataSourceId) ?: return
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.allergyIntoleranceJson(entry),
    ) ?: return
    repository.upsertAllergy(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
    repository.clearPending(MedicalKind.Allergy, id)
}

fun MedicalViewModel.saveConditionDraft() {
    val draft = conditionDraftState.value
    if (draft.displayName.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getCondition(it) }
        val detached = repository.detachIfForeign(
            MedicalKind.Condition,
            PersonalHealthRecords.conditionResourceType,
            existing?.dataSourceId,
            existing?.fhirResourceId,
        )
        val edited = (existing ?: ConditionEntry(
            id = Uuid.random().toString(),
            displayName = "",
            onsetAt = Instant.now(),
        )).copy(
            icd10Code = draft.icd10Code,
            displayName = draft.displayName.trim(),
            status = draft.status,
            onsetAt = draft.onsetOn.toInstant(),
            resolvedAt = draft.resolvedOn?.toInstant(),
            note = draft.note.blankToNull(),
        )
        val entry = if (detached) edited.copy(fhirResourceId = null, dataSourceId = null) else edited
        repository.upsertCondition(entry)
        repository.markPending(MedicalKind.Condition, entry.id)
        mirrorCondition(entry.id)
    }
}

fun MedicalViewModel.deleteCondition(entry: ConditionEntry) {
    viewModelScope.launch {
        repository.deleteCondition(entry)
        repository.forgetRow(
            MedicalKind.Condition,
            entry.id,
            PersonalHealthRecords.conditionResourceType,
            entry.dataSourceId,
            entry.fhirResourceId,
        )
    }
}

internal suspend fun MedicalViewModel.mirrorCondition(id: String) {
    val entry = repository.getCondition(id) ?: return repository.clearPending(MedicalKind.Condition, id)
    val dataSourceId = repository.mirrorTarget(MedicalKind.Condition, id, entry.dataSourceId) ?: return
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.conditionJson(entry),
    ) ?: return
    repository.upsertCondition(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
    repository.clearPending(MedicalKind.Condition, id)
}

fun MedicalViewModel.saveLabDraft() {
    val draft = labDraftState.value
    if (draft.displayName.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getLabResult(it) }
        val detached = repository.detachIfForeign(
            MedicalKind.LabResult,
            PersonalHealthRecords.observationResourceType,
            existing?.dataSourceId,
            existing?.fhirResourceId,
        )
        // A result is either a number or a word. Anything that does not parse is kept verbatim
        // as text rather than being coerced to zero, because "positive" and "trace" are real
        // results and silently turning one into 0.0 would be a clinically wrong record.
        val numeric = draft.value.trim().replace(',', '.').toDoubleOrNull()
        val edited = (existing ?: LabResultEntry(
            id = Uuid.random().toString(),
            displayName = "",
            takenAt = Instant.now(),
        )).copy(
            loincCode = draft.loincCode,
            displayName = draft.displayName.trim(),
            value = numeric,
            valueText = if (numeric == null) draft.value.blankToNull() else null,
            unit = draft.unit.blankToNull(),
            referenceLow = draft.referenceLow.trim().toDoubleOrNull(),
            referenceHigh = draft.referenceHigh.trim().toDoubleOrNull(),
            takenAt = draft.takenOn.toInstant(),
            note = draft.note.blankToNull(),
        )
        val entry = if (detached) edited.copy(fhirResourceId = null, dataSourceId = null) else edited
        repository.upsertLabResult(entry)
        repository.markPending(MedicalKind.LabResult, entry.id)
        mirrorLabResult(entry.id)
    }
}

fun MedicalViewModel.deleteLabResult(entry: LabResultEntry) {
    viewModelScope.launch {
        repository.deleteLabResult(entry)
        repository.forgetRow(
            MedicalKind.LabResult,
            entry.id,
            PersonalHealthRecords.observationResourceType,
            entry.dataSourceId,
            entry.fhirResourceId,
        )
    }
}

internal suspend fun MedicalViewModel.mirrorLabResult(id: String) {
    val entry = repository.getLabResult(id) ?: return repository.clearPending(MedicalKind.LabResult, id)
    val dataSourceId = repository.mirrorTarget(MedicalKind.LabResult, id, entry.dataSourceId) ?: return
    val written = PersonalHealthRecords.upsert(
        dataSourceId,
        FhirRecords.labResultJson(entry),
    ) ?: return
    repository.upsertLabResult(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
    repository.clearPending(MedicalKind.LabResult, id)
}
